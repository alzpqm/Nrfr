package com.github.nrfr.manager

import android.annotation.TargetApi
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.telephony.SubscriptionInfo
import android.telephony.TelephonyManager
import org.json.JSONObject
import java.security.MessageDigest

/** A SIM-specific test matching rule. Never replaces a system rule or changes the real IMSI. */
@TargetApi(29)
internal object SamsungCarrierRule {
    private val all = Uri.parse("content://carrier_id/all")
    private const val PREFS = "samsung_sim_test_carrier_rules"
    private fun key(info: SubscriptionInfo): String = MessageDigest.getInstance("SHA-256")
        .digest(checkNotNull(info.iccId?.takeIf { it.isNotEmpty() }).toByteArray())
        .joinToString("") { "%02x".format(it) }

    fun hasBackup(context: Context, info: SubscriptionInfo): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(key(info))

    fun prepare(context: Context, info: SubscriptionInfo, numeric: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val simKey = key(info)
        val existing = prefs.getString(simKey, null)
        if (existing != null) {
            check(JSONObject(existing).getString("numeric") == numeric) { "請先還原，再修改另一個 MNC" }
            return
        }
        val phone = context.getSystemService(TelephonyManager::class.java).createForSubscriptionId(info.subscriptionId)
        val carrier = phone.simCarrierId
        check(carrier >= 0 && phone.simSpecificCarrierId == carrier) { "此 SIM 的電信識別關係尚未支援安全覆寫" }
        val iccid = checkNotNull(info.iccId?.takeIf { it.matches(Regex("[0-9A-Fa-f]{10,}")) }) {
            "無法確認 SIM 專用電信識別條件"
        }
        val selection = "mccmnc = ? AND iccid_prefix = ?"
        val args = arrayOf(numeric, iccid)
        context.contentResolver.query(all, arrayOf("_id"), selection, args, null)?.use {
            check(it.count == 0) { "已有相同 SIM 的電信識別規則，未改寫原規則" }
        } ?: error("電信識別規則查詢未回傳資料")
        val name = phone.simCarrierIdName?.toString().orEmpty()
        val backup = JSONObject().put("numeric", numeric).put("iccid", iccid).put("carrier", carrier)
        check(NightzukuRecoveryStore.put(context, PREFS, simKey, backup.toString())) { "電信識別規則備份失敗" }
        val values = ContentValues().apply {
            put("mccmnc", numeric); put("iccid_prefix", iccid); put("carrier_id", carrier)
            put("carrier_name", name); put("parent_carrier_id", -1)
        }
        try {
            val inserted = context.contentResolver.insert(all, values) ?: error("SIM 專用電信識別規則未新增")
            val id = checkNotNull(inserted.lastPathSegment?.toLongOrNull())
            backup.put("id", id)
            check(NightzukuRecoveryStore.put(context, PREFS, simKey, backup.toString())) { "電信識別規則回復紀錄失敗" }
        } catch (failure: Exception) {
            try { restore(context, info) } catch (rollback: Exception) { failure.addSuppressed(rollback) }
            throw failure
        }
    }

    fun restore(context: Context, info: SubscriptionInfo) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val simKey = key(info)
        val saved = prefs.getString(simKey, null) ?: return
        val backup = JSONObject(saved)
        var selection = "mccmnc = ? AND iccid_prefix = ? AND carrier_id = ?"
        val args = mutableListOf(backup.getString("numeric"), backup.getString("iccid"), backup.getInt("carrier").toString())
        if (backup.has("id")) { selection += " AND _id = ?"; args += backup.getLong("id").toString() }
        val cursor = context.contentResolver.query(all, arrayOf("_id"), selection, args.toTypedArray(), null)
            ?: error("電信識別規則還原查詢失敗")
        val count = cursor.use { it.count }
        check(count <= 1) { "電信識別規則不唯一，已保留備份" }
        if (count == 1) check(context.contentResolver.delete(all, selection, args.toTypedArray()) == 1) {
            "SIM 專用電信識別規則還原未確認"
        }
        context.contentResolver.query(all, arrayOf("_id"), selection, args.toTypedArray(), null)?.use {
            check(it.count == 0) { "SIM 專用電信識別規則仍存在" }
        } ?: error("電信識別規則還原回讀失敗")
        check(NightzukuRecoveryStore.remove(context, PREFS, simKey)) { "電信識別規則已還原，備份清理失敗" }
    }
}
