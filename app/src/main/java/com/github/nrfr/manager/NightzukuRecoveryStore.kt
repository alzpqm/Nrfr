package com.github.nrfr.manager

import android.content.Context
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch

/** Package/certificate-scoped recovery metadata outside the app's clear-data directory. */
internal object NightzukuRecoveryStore {
    val names = setOf("carrier_identity_originals", "samsung_apn_carrier_binding", "samsung_sim_test_carrier_rules")
    private val lock = Any()
    private var document: JSONObject? = null
    private var namespace = ""
    @Volatile private var service: IRecoveryStore? = null
    private var connection: ServiceConnection? = null

    fun enabled(): Boolean = Build.VERSION.SDK_INT >= 29 && SamsungApnCurrentOperator.isRequired()

    private fun service(context: Context): IRecoveryStore {
        service?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        val ready = CountDownLatch(1)
        val callback = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = IRecoveryStore.Stub.asInterface(binder)
                ready.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) { service = null }
        }
        connection = callback
        Shizuku.bindUserService(Shizuku.UserServiceArgs(ComponentName(context, RecoveryUserService::class.java))
            .daemon(false).version(1).processNameSuffix("recovery_store"), callback)
        check(ready.await(8, TimeUnit.SECONDS)) { "Nightzuku 還原服務啟動逾時" }
        return checkNotNull(service)
    }

    private fun hash(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value)
        .joinToString("") { "%02x".format(it) }

    private fun scope(context: Context): Pair<String, String> {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName,
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
        @Suppress("DEPRECATION")
        val signatures = if (Build.VERSION.SDK_INT >= 28) checkNotNull(info.signingInfo).apkContentsSigners else info.signatures
        val certificate = hash(checkNotNull(signatures).single().toByteArray())
        return hash("${context.packageName}:$certificate".toByteArray()) to certificate
    }

    /** Bounded byte-array exchange through Nightzuku's shell UserService. */
    private fun transport(context: Context, operation: String, bytes: ByteArray = byteArrayOf()): ByteArray {
        check(Shizuku.getUid() == 2000) { "此還原儲存測試需要 Nightzuku ADB 模式" }
        val remote = service(context)
        check(remote.uid() == 2000) { "Nightzuku 還原服務不是 shell 身分" }
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "nrfr-recovery-io").apply { isDaemon = true } }
        val result = executor.submit<ByteArray> {
            remote.exchange(namespace, operation, bytes).also { check(it.size <= 262144) }
        }
        return try { result.get(10, TimeUnit.SECONDS) }
        finally { result.cancel(true); executor.shutdownNow() }
    }

    fun prepare(context: Context) = synchronized(lock) {
        document = null
        if (!enabled()) return@synchronized
        val (id, certificate) = scope(context)
        namespace = id
        val bytes = transport(context, "read")
        val saved = if (bytes.isEmpty()) JSONObject().put("schema", 1).put("package", context.packageName)
            .put("certificate", certificate).put("records", JSONObject()) else JSONObject(bytes.toString(Charsets.UTF_8))
        check(saved.getInt("schema") == 1 && saved.getString("package") == context.packageName &&
            saved.getString("certificate") == certificate) { "Nightzuku 還原檔與現裝版本不符" }
        val records = saved.getJSONObject("records")
        records.keys().forEach { name -> check(name in names) }
        if (bytes.isEmpty()) {
            // One-time migration from a build without durable storage. Thereafter the
            // shell record wins, including an empty record after completed restoration.
            names.forEach { name ->
                val entries = JSONObject()
                context.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (key, value) ->
                    check(key.matches(Regex("[a-f0-9]{64}")) && value is String)
                    JSONObject(value)
                    entries.put(key, value)
                }
                records.put(name, entries)
            }
            val initial = saved.toString().toByteArray()
            check(transport(context, "write", initial).contentEquals(initial)) { "Nightzuku 還原儲存初始化未確認" }
        }
        document = saved
    }

    fun restoreLocal(context: Context, simKeys: Set<String>) = synchronized(lock) {
        val records = document?.getJSONObject("records") ?: return@synchronized
        names.forEach { name ->
            val remote = records.optJSONObject(name) ?: JSONObject()
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val durable = remote.keys().asSequence().associateWith { key ->
                check(key.matches(Regex("[a-f0-9]{64}")))
                remote.getString(key).also { JSONObject(it) }
            }
            val local = prefs.all.mapValues { (_, value) -> check(value is String); value }
            val expected = RecoveryRecordPolicy.reconcile(local, durable, simKeys)
            if (local != expected) {
                val editor = prefs.edit().clear()
                expected.forEach { (key, value) -> editor.putString(key, value) }
                check(editor.commit() && prefs.all == expected) { "Nightzuku 還原紀錄回灌未確認" }
            }
        }
    }

    fun put(context: Context, name: String, key: String, value: String): Boolean = synchronized(lock) {
        if (enabled()) update(context, name, key, value)
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().putString(key, value).commit()
    }

    fun remove(context: Context, name: String, key: String): Boolean = synchronized(lock) {
        if (enabled()) update(context, name, key, null)
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().remove(key).commit()
    }

    private fun update(context: Context, name: String, key: String, value: String?) {
        check(name in names && key.matches(Regex("[a-f0-9]{64}")))
        val next = JSONObject(checkNotNull(document) { "還原儲存尚未就緒" }.toString())
        val records = next.getJSONObject("records")
        val entries = records.optJSONObject(name) ?: JSONObject().also { records.put(name, it) }
        if (value == null) entries.remove(key) else { JSONObject(value); entries.put(key, value) }
        // Retain an empty, identifier-free document after the last removal. It prevents
        // a crash before local removal from resurrecting a completed backup on restart.
        val bytes = next.toString().toByteArray()
        check(transport(context, "write", bytes).contentEquals(bytes)) { "Nightzuku 還原紀錄回讀不一致" }
        document = next
    }

    /** Diagnostic proof uses only empty records and removes its scratch file afterwards. */
    fun probe(context: Context): Boolean = synchronized(lock) {
        val (id, certificate) = scope(context)
        val oldNamespace = namespace
        namespace = hash("$id:probe".toByteArray())
        val saved = JSONObject().put("schema", 1).put("package", context.packageName)
            .put("certificate", certificate).put("records", JSONObject())
        try {
        val bytes = saved.toString().toByteArray()
        val written = try { transport(context, "write", bytes) } catch (failure: Exception) {
            throw IllegalStateException("Recovery write: ${failure.message}", failure)
        }
        check(written.contentEquals(bytes)) { "Recovery write echo mismatch (${written.size}/${bytes.size})" }
        val read = transport(context, "read")
        check(read.contentEquals(bytes)) { "Recovery read echo mismatch (${read.size}/${bytes.size})" }
        transport(context, "delete")
        true
        } finally { namespace = oldNamespace }
    }
}
