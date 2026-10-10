package com.github.nrfr.manager

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import org.json.JSONArray
import org.json.JSONObject

// Used only while the instrumentation has delegated WRITE_APN_SETTINGS and phone permissions.
@SuppressLint("MissingPermission")
@RequiresApi(29)
internal class ApnOverrideAccess(
    private val context: Context,
    private val info: SubscriptionInfo,
    private val simKey: String,
    private val originalNumeric: String,
    private val originalCarrierId: Int?,
    private val restore: () -> Unit,
    private val set: (String) -> Unit,
    private val matchesSim: () -> Boolean,
) : ApnOverrideCoordinator.Backend {
    private val resolver = context.contentResolver
    private val prefs = context.getSharedPreferences("apn_override_recovery", Context.MODE_PRIVATE)
    private val telephony = context.getSystemService(TelephonyManager::class.java)
        .createForSubscriptionId(info.subscriptionId)
    private val carriers = "content://telephony/carriers".toUri()
    private val simApns = "content://telephony/carriers/sim_apn_list/${info.subscriptionId}".toUri()
    private val dataApns = "content://telephony/carriers/sim_apn_list/filtered/subId/${info.subscriptionId}".toUri()
    private val preferredUri = "content://telephony/carriers/preferapn/subId/${info.subscriptionId}".toUri()

    override fun checkAccess() {
        assertPresent()
        check(context.checkSelfPermission(Manifest.permission.WRITE_APN_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
            "此系統未提供 APN 存取權限，尚未覆寫 SIM 代碼"
        }
        rows(carriers, "_id = ?", arrayOf("-1"))
        preferred()
    }

    override fun journal(): ApnJournal? = prefs.getString(simKey, null)?.let { text ->
        val json = JSONObject(text)
        check(json.getInt("version") == 1) { "APN 回復備份版本不相容，請先還原設定" }
        val items = json.getJSONArray("aliases")
        ApnJournal(json.getString("numeric"), json.optionalLong("preferred"), (0 until items.length()).map { i ->
            val item = items.getJSONObject(i)
            ApnAlias(item.getLong("source"), item.getString("name"), item.getString("fingerprint"), item.optionalLong("id"))
        })
    }

    override fun save(journal: ApnJournal?) {
        val edit = prefs.edit()
        if (journal == null) edit.remove(simKey) else edit.putString(simKey, JSONObject().apply {
            put("version", 1)
            put("numeric", journal.numeric)
            put("preferred", journal.preferredSourceId ?: JSONObject.NULL)
            put("aliases", JSONArray().apply {
                journal.aliases.forEach { alias -> put(JSONObject().apply {
                    put("source", alias.sourceId)
                    put("name", alias.name)
                    put("fingerprint", alias.fingerprint)
                    put("id", alias.id ?: JSONObject.NULL)
                }) }
            })
        }.toString())
        check(edit.commit()) { "無法保存 APN 回復備份，尚未確認套用" }
    }

    override fun preferred(): Long? = rows(preferredUri).firstOrNull()?.id

    override fun setPreferred(id: Long?) {
        assertPresent()
        providerCall("無法保存所選 SIM 的 APN 選取值") {
            check(resolver.update(preferredUri, ContentValues().apply { put("apn_id", id ?: -1L) }, null, null) == 1)
        }
    }

    override fun restoreIdentity() {
        assertPresent()
        restore()
        awaitNumeric(originalNumeric)
    }

    override fun setIdentity(numeric: String) {
        assertPresent()
        set(numeric)
        awaitNumeric(numeric)
    }

    override fun originalProfiles(): List<ApnProfile> {
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        var stable = 0
        var previous: Pair<Set<Long>, Int>? = null
        do {
            assertPresent()
            check(telephony.simOperator == originalNumeric) { "原 SIM 代碼尚未回復，未讀取其他業者的 APN" }
            val profiles = rows(simApns)
            val key = profiles.map { it.id }.toSet() to telephony.simSpecificCarrierId
            val dataIds = rows(dataApns).map { it.id }.toSet()
            check(dataIds.containsAll(key.first)) { "APN 受到裝置管理政策限制，尚未覆寫代碼" }
            stable = if (key == previous) stable + 1 else 0
            val carrierMatches = originalCarrierId == null || originalCarrierId < 0 || key.second == originalCarrierId
            if (stable >= 3 && profiles.isNotEmpty() && carrierMatches) return profiles
            previous = key
            Thread.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("無法取得穩定的原 SIM APN 清單，請確認 SIM 與 APN 已就緒")
    }

    override fun assertSimBinding() {
        assertPresent()
        check(matchesSim()) { "系統不支援這張 SIM 的 APN 綁定，未覆寫代碼" }
    }

    override fun existingAliases(numeric: String): List<ApnProfile> = rows(carriers,
        "numeric = ? AND mvno_type = ? AND mvno_match_data = ?",
        arrayOf(numeric, "iccid", info.iccId))

    override fun insert(profile: ApnProfile): ApnProfile {
        assertPresent()
        val uri = providerCall("系統拒絕建立 APN 相容項目") {
            resolver.insert(carriers, profile.fields.toValues()) ?: error("insert failed")
        }
        return rows(carriers, "_id = ?", arrayOf(ContentUris.parseId(uri).toString())).singleOrNull()
            ?: error("無法核對新增的 APN 相容項目")
    }

    override fun delete(profile: ApnProfile) {
        assertPresent()
        // Guard the complete payload in the delete itself to protect concurrent external edits.
        val clauses = mutableListOf("_id = ?")
        val args = mutableListOf(profile.id.toString())
        for ((column, value) in profile.fields) {
            if (column in setOf("_id", "current", "edited")) continue
            check(column.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*"))) { "APN 欄位不相容，未刪除項目" }
            if (value == null) clauses += "$column IS NULL" else {
                clauses += "$column = ?"
                args += value
            }
        }
        providerCall("APN 相容項目未能安全清理，請重新執行還原設定") {
            check(resolver.delete(carriers, clauses.joinToString(" AND "), args.toTypedArray()) == 1)
        }
    }

    override fun verify(numeric: String, requiredIds: Set<Long>, preferred: Long?) {
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        var stable = 0
        do {
            assertPresent()
            val visibleIds = rows(simApns).map { it.id }.toSet()
            val dataIds = rows(dataApns).map { it.id }.toSet()
            val actualPreferred = preferred()
            val matches = telephony.simOperator == numeric && visibleIds.isNotEmpty() && dataIds.isNotEmpty() &&
                visibleIds.containsAll(requiredIds) && dataIds.containsAll(requiredIds) &&
                (preferred == null || (actualPreferred == preferred && preferred in visibleIds && preferred in dataIds))
            stable = if (matches) stable + 1 else 0
            if (stable >= 3) return
            Thread.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("APN 清單或選取值回讀不一致，尚未確認套用")
    }

    private fun awaitNumeric(numeric: String) {
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        do {
            assertPresent()
            if (telephony.simOperator == numeric) return
            Thread.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("SIM 代碼回讀不一致，尚未確認套用")
    }

    private fun assertPresent() {
        val subscriptions = context.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty()
        val active = subscriptions.firstOrNull { it.subscriptionId == info.subscriptionId && it.simSlotIndex == info.simSlotIndex }
        check(active?.iccId == info.iccId) { "SIM 已移除或更換，已停止 APN 與代碼寫入" }
        check(subscriptions.none { it.subscriptionId != info.subscriptionId && it.iccId.startsWith(info.iccId) }) {
            "無法獨立辨識所選 SIM 的 APN 綁定，已停止寫入"
        }
    }

    private fun rows(uri: Uri, selection: String? = null, args: Array<String>? = null): List<ApnProfile> =
        providerCall("無法存取所選 SIM 的 APN，系統可能限制 APN 權限") {
            val cursor = resolver.query(uri, null, selection, args, null) ?: error("null APN cursor")
            cursor.use {
                val result = mutableListOf<ApnProfile>()
                val id = it.getColumnIndexOrThrow("_id")
                while (it.moveToNext()) {
                    result += ApnProfile(it.getLong(id), it.columnNames.mapIndexed { index, name ->
                        name to if (it.isNull(index)) null else it.getString(index)
                    }.toMap())
                }
                result
            }
        }

    private fun Map<String, String?>.toValues() = ContentValues().also { values ->
        for ((key, value) in this) if (value == null) values.putNull(key) else values.put(key, value)
    }
    private fun JSONObject.optionalLong(key: String) = if (isNull(key)) null else getLong(key)
    private inline fun <T> providerCall(message: String, call: () -> T): T = try { call() } catch (_: Exception) {
        // Provider exceptions can echo SQL parameters, including APN passwords and ICCIDs.
        throw IllegalStateException(message)
    }
}
