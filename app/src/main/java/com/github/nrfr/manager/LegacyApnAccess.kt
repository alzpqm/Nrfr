package com.github.nrfr.manager

import android.Manifest
import android.annotation.SuppressLint
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
import org.json.JSONObject

// Optional cleanup of 1.1.3 rows, after the original identity has already been restored and verified.
@SuppressLint("MissingPermission")
@RequiresApi(29)
internal class LegacyApnAccess(
    private val context: Context,
    private val info: SubscriptionInfo,
    private val simKey: String,
    private val originalNumeric: String,
) : LegacyApnCleanupCoordinator.Backend {
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
            "此系統未提供 APN 清理權限"
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

    override fun clearJournal() {
        check(prefs.edit().remove(simKey).commit()) { "無法清理 APN 回復紀錄" }
    }

    override fun preferred(): Long? = rows(preferredUri).firstOrNull()?.id

    override fun setPreferred(id: Long) {
        assertPresent()
        providerCall("無法保存所選 SIM 的 APN 選取值") {
            check(resolver.update(preferredUri, ContentValues().apply { put("apn_id", id) }, null, null) == 1)
        }
    }

    override fun originalProfiles(): List<ApnProfile> {
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        var stable = 0
        var previous: Set<Long>? = null
        do {
            assertPresent()
            check(telephony.simOperator == originalNumeric) { "原 SIM 代碼尚未回復，未讀取其他業者的 APN" }
            val dataIds = rows(dataApns).map { it.id }.toSet()
            val profiles = rows(simApns).filter { it.id in dataIds }
            val key = profiles.map { it.id }.toSet()
            stable = if (key == previous) stable + 1 else 0
            if (stable >= 3 && profiles.isNotEmpty()) return profiles
            previous = key
            Thread.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("無法取得穩定的原 SIM APN 清單，請確認 SIM 與 APN 已就緒")
    }

    override fun existingAliases(numeric: String): List<ApnProfile> = rows(carriers,
        "numeric = ? AND mvno_type = ? AND mvno_match_data = ?",
        arrayOf(numeric, "iccid", info.iccId))

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

    override fun verify(numeric: String, preferred: Long?) {
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        var stable = 0
        do {
            assertPresent()
            val visibleIds = rows(simApns).map { it.id }.toSet()
            val dataIds = rows(dataApns).map { it.id }.toSet()
            val actualPreferred = preferred()
            val matches = telephony.simOperator == numeric && visibleIds.isNotEmpty() && dataIds.isNotEmpty() &&
                (preferred == null || (actualPreferred == preferred && preferred in visibleIds && preferred in dataIds))
            stable = if (matches) stable + 1 else 0
            if (stable >= 3) return
            Thread.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("APN 清單或選取值回讀不一致，尚未確認套用")
    }

    private fun assertPresent() {
        check(info.iccId?.matches(Regex("[0-9A-Fa-f]{18,32}")) == true) { "無法辨識所選 SIM，未清理 APN" }
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

    private fun JSONObject.optionalLong(key: String) = if (isNull(key)) null else getLong(key)
    private inline fun <T> providerCall(message: String, call: () -> T): T = try { call() } catch (_: Exception) {
        // Provider exceptions can echo SQL parameters, including APN passwords and ICCIDs.
        throw IllegalStateException(message)
    }
}
