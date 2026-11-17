package com.github.nrfr

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.github.nrfr.manager.PrivilegedCarrierConfigRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Shell-only diagnostic transport of this package's private recovery records.
 * Never included in the normal release, and never imports APN rows or a signing key. */
class ResearchRecoveryActivity : ComponentActivity() {
    private val names = listOf("carrier_identity_originals", "samsung_apn_carrier_binding",
        "samsung_sim_test_carrier_rules")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val operation = intent.getStringExtra("operation")
        val label = intent.getStringExtra("label").orEmpty()
        if (operation !in listOf("backup", "import") || !label.matches(Regex("[a-zA-Z0-9_-]{1,40}"))) {
            finish(); return
        }
        lifecycleScope.launch {
            val directory = checkNotNull(getExternalFilesDir("recovery"))
            val report = JSONObject().put("operation", operation).put("capturedAt", System.currentTimeMillis())
            try {
                val reply = PrivilegedCarrierConfigRunner.run(this@ResearchRecoveryActivity,
                    PrivilegedCarrierConfigRunner.Request.ReadSimCards)
                @Suppress("DEPRECATION")
                val cards = reply.getParcelableArrayList<Bundle>(PrivilegedCarrierConfigRunner.RESULT_SIM_CARDS).orEmpty()
                val simKeys = cards.map { checkNotNull(it.getString("research_sim_key")) }.toSet()
                check(simKeys.isNotEmpty() && simKeys.all { it.matches(Regex("[a-f0-9]{64}")) })
                val packageInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val certificate = checkNotNull(packageInfo.signingInfo).apkContentsSigners.single().toByteArray()
                val fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate)
                    .joinToString("") { "%02x".format(it) }
                val file = File(directory, "$label.json")
                if (operation == "backup") {
                    val records = JSONObject()
                    names.forEach { name ->
                        val values = getSharedPreferences(name, Context.MODE_PRIVATE).all
                        check(values.values.all { it is String } && simKeys.containsAll(values.keys))
                        records.put(name, JSONObject(values))
                    }
                    check(names.any { records.getJSONObject(it).length() > 0 }) { "沒有待還原的研究紀錄" }
                    file.writeText(JSONObject().put("schema", 1).put("package", packageName)
                        .put("certificate", fingerprint).put("records", records).toString())
                    report.put("records", names.sumOf { records.getJSONObject(it).length() })
                } else {
                    val backup = JSONObject(file.readText())
                    check(backup.getInt("schema") == 1 && backup.getString("package") == packageName &&
                        backup.getString("certificate") == fingerprint) { "回復檔與現裝測試版不符" }
                    val records = backup.getJSONObject("records")
                    check(records.keys().asSequence().toSet() == names.toSet())
                    val parsed = names.associateWith { name ->
                        val objectValues = records.getJSONObject(name)
                        objectValues.keys().asSequence().associateWith { key ->
                            check(key in simKeys) { "回復檔不屬於目前的 SIM" }
                            objectValues.getString(key).also { JSONObject(it) }
                        }
                    }
                    // Validate every file first. Idempotent import is allowed; a different
                    // active experiment must never be overwritten by an older backup.
                    parsed.forEach { (name, values) ->
                        val current = getSharedPreferences(name, Context.MODE_PRIVATE).all
                        check(current.isEmpty() || current == values) { "已有不同的還原紀錄，未覆寫" }
                    }
                    parsed.forEach { (name, values) ->
                        val prefs = getSharedPreferences(name, Context.MODE_PRIVATE)
                        val editor = prefs.edit()
                        values.forEach { (key, value) -> editor.putString(key, value) }
                        check(editor.commit() && prefs.all == values) { "回復紀錄回讀未確認" }
                    }
                    report.put("records", parsed.values.sumOf { it.size })
                }
                report.put("success", true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report.put("success", false).put("errorClass", failure.javaClass.simpleName)
                    .put("error", failure.message)
            }
            File(directory, "$label-$operation-result.json").writeText(report.toString())
            finish()
        }
    }
}
