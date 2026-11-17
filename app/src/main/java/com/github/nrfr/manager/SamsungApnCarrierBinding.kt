package com.github.nrfr.manager

import android.annotation.TargetApi
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Bind existing APNs to the real carrier; their numeric and connection parameters never change. */
@TargetApi(29)
internal object SamsungApnCarrierBinding {
    private const val PREFS = "samsung_apn_carrier_binding"
    private const val ROOT = "content://telephony/carriers"
    // Verified against this Samsung provider's CARRIERS_UNIQUE_FIELDS_DEFAULTS.
    private val uniqueColumns = listOf("numeric", "mcc", "mnc", "apn", "proxy", "port", "mmsproxy",
        "mmsport", "mmsc", "carrier_enabled", "bearer", "mvno_type", "mvno_match_data", "profile_id",
        "protocol", "roaming_protocol", "user_editable", "owned_by", "apn_set_id", "carrier_id",
        "infrastructure_bitmask", "esim_bootstrap_provisioning")
    private data class Row(val id: Long, val carrier: Int, val edited: Int, val otherCurrent: Int)

    private fun key(info: SubscriptionInfo): String = MessageDigest.getInstance("SHA-256")
        .digest(checkNotNull(info.iccId?.takeIf { it.isNotEmpty() }).toByteArray())
        .joinToString("") { "%02x".format(it) }

    fun hasBackup(context: Context, info: SubscriptionInfo): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(key(info))

    private fun ids(context: Context, uri: String, selection: String? = null,
        args: Array<String>? = null): Set<Long> {
        val cursor = context.contentResolver.query(Uri.parse(uri), arrayOf("_id"), selection, args, null)
            ?: error("APN 查詢未回傳資料")
        return cursor.use { buildSet { while (it.moveToNext()) add(it.getLong(0)) } }
    }

    private fun settingsIds(context: Context, info: SubscriptionInfo): Set<Long> =
        ids(context, "$ROOT/sim_apn_list/${info.subscriptionId}",
            "NOT (type='ia' AND (apn=\"\" OR apn IS NULL)) AND ${if (info.simSlotIndex == 0) "current" else "current1"} = 1")

    fun filteredIds(context: Context, info: SubscriptionInfo): Set<Long> =
        ids(context, "$ROOT/sim_apn_list/filtered/subId/${info.subscriptionId}")

    fun preferredIds(context: Context, info: SubscriptionInfo): Set<Long> =
        ids(context, "$ROOT/preferapn/subId/${info.subscriptionId}")

    private fun checkNoConflict(context: Context, id: Long, destinationCarrier: Int) {
        val cursor = context.contentResolver.query(Uri.parse("$ROOT/$id"), uniqueColumns.toTypedArray(), null, null, null)
            ?: error("APN 唯一鍵查詢失敗")
        val values = cursor.use {
            check(it.moveToFirst()) { "原 APN 項目已不存在" }
            uniqueColumns.mapIndexed { index, column ->
                if (column == "carrier_id") destinationCarrier.toString() else it.getString(index)
            }
        }
        val selection = mutableListOf("_id <> ?")
        val args = mutableListOf(id.toString())
        uniqueColumns.forEachIndexed { index, column ->
            val value = values[index]
            if (value == null) selection += "$column IS NULL"
            else { selection += "$column = ?"; args += value }
        }
        check(ids(context, ROOT, selection.joinToString(" AND "), args.toTypedArray()).isEmpty()) {
            "APN 關聯存在唯一鍵衝突，未執行此列更新；備份已保留"
        }
    }

    fun prepare(context: Context, info: SubscriptionInfo) {
        check(info.simSlotIndex in 0..1) { "不支援此 SIM 卡槽的 APN 關聯" }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val simKey = key(info)
        if (prefs.contains(simKey)) return
        val telephony = context.getSystemService(TelephonyManager::class.java)
            .createForSubscriptionId(info.subscriptionId)
        val carrier = telephony.simCarrierId
        check(carrier >= 0) { "無法確認 SIM carrier ID，代碼尚未修改" }
        val original = info.mccString.orEmpty() + info.mncString.orEmpty()
        check(telephony.simOperator == original) { "請先還原原 SIM 代碼，再建立 APN 關聯" }
        val otherFlag = if (info.simSlotIndex == 0) "current1" else "current"
        val cursor = context.contentResolver.query(Uri.parse(ROOT),
            arrayOf("_id", "carrier_id", "edited", otherFlag),
            "numeric = ? AND edited NOT IN (2,3,5,6)", arrayOf(original), null)
            ?: error("無法讀取原 APN 項目")
        val rows = cursor.use {
            buildList { while (it.moveToNext()) add(Row(it.getLong(0), it.getInt(1), it.getInt(2), it.getInt(3))) }
        }
        check(rows.isNotEmpty() && rows.all { it.carrier == -1 || it.carrier == carrier }) {
            "原 APN 的電信業者關聯無法安全確認"
        }
        check(rows.map { it.carrier }.toSet().size == 1) {
            "原 APN 的 carrier ID 混合，無法排除關聯衝突"
        }
        val rowIds = rows.map { it.id }.toSet()
        val filtered = ids(context, "$ROOT/sim_apn_list/filtered/subId/${info.subscriptionId}")
        val settings = settingsIds(context, info)
        val preferred = ids(context, "$ROOT/preferapn/subId/${info.subscriptionId}")
        check(filtered.isNotEmpty() && rowIds.containsAll(filtered) && settings.isNotEmpty() &&
            preferred.size == 1 && filtered.containsAll(preferred)) { "原 APN 與選取狀態無法確認" }
        // A one-to-one carrier-id replacement preserves uniqueness among these rows. Refuse
        // pre-existing destination-carrier rows, so a conflict cannot merge/delete another row.
        check((ids(context, ROOT, "carrier_id = ?", arrayOf(carrier.toString())) - rowIds).isEmpty()) {
            "已有其他 carrier ID APN 項目，未執行關聯修改"
        }
        val subscriptions = context.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty()
        subscriptions.filter { it.subscriptionId != info.subscriptionId }.forEach { other ->
            check(rows.none { it.otherCurrent == 1 } &&
                ids(context, "$ROOT/sim_apn_list/filtered/subId/${other.subscriptionId}").intersect(rowIds).isEmpty()) {
                "原 APN 為雙卡共用，未執行關聯修改"
            }
        }
        val savedRows = JSONArray()
        rows.forEach { savedRows.put(JSONObject().put("id", it.id).put("carrier", it.carrier).put("edited", it.edited)) }
        val backup = JSONObject().put("carrier", carrier).put("rows", savedRows)
            .put("filtered", JSONArray(filtered.toList())).put("settings", JSONArray(settings.toList()))
            .put("preferred", JSONArray(preferred.toList()))
        check(prefs.edit().putString(simKey, backup.toString()).commit()) { "原 APN 關聯備份失敗" }
        try {
            rows.filter { it.carrier != carrier }.forEach { row ->
                checkNoConflict(context, row.id, carrier)
                val values = ContentValues().apply { put("carrier_id", carrier); put("edited", row.edited) }
                check(context.contentResolver.update(Uri.parse("$ROOT/${row.id}"), values, null, null) == 1) {
                    "APN 關聯更新未確認"
                }
            }
        } catch (failure: Exception) {
            try { restore(context, info) } catch (rollback: Exception) { failure.addSuppressed(rollback) }
            throw failure
        }
    }

    fun verify(context: Context, info: SubscriptionInfo) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(info), null)
            ?: error("APN 關聯備份不存在")
        val backup = JSONObject(saved)
        fun expected(name: String): Set<Long> = backup.getJSONArray(name).let { values ->
            (0 until values.length()).map { values.getLong(it) }.toSet()
        }
        check(ids(context, "$ROOT/sim_apn_list/filtered/subId/${info.subscriptionId}") == expected("filtered")) {
            "修改後的數據 APN 與原項目不一致，已停止修改"
        }
        check(settingsIds(context, info) == expected("settings")) { "修改後 APN 顯示項目與原清單不一致" }
        check(ids(context, "$ROOT/preferapn/subId/${info.subscriptionId}") == expected("preferred")) {
            "修改後 APN 選取與原值不一致"
        }
    }

    fun restore(context: Context, info: SubscriptionInfo) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val simKey = key(info)
        val saved = prefs.getString(simKey, null) ?: return
        val backup = JSONObject(saved)
        val rows = backup.getJSONArray("rows")
        var missingRows = false
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val uri = Uri.parse("$ROOT/${row.getLong("id")}")
            val cursor = context.contentResolver.query(uri, arrayOf("carrier_id", "edited"), null, null, null)
                ?: error("APN 還原查詢未回傳資料")
            val state = cursor.use {
                if (!it.moveToFirst()) null else it.getInt(0) to it.getInt(1)
            }
            if (state == null) {
                missingRows = true
                continue // Never recreate a deleted APN or silently call it restored.
            }
            if (state.first == row.getInt("carrier")) continue
            check(state.first == backup.getInt("carrier")) { "APN 關聯已被其他操作變更，備份已保留" }
            checkNoConflict(context, row.getLong("id"), row.getInt("carrier"))
            val values = ContentValues().apply { put("carrier_id", row.getInt("carrier")); put("edited", state.second) }
            check(context.contentResolver.update(uri, values, null, null) == 1) { "原 APN 關聯還原未確認" }
            context.contentResolver.query(uri, arrayOf("carrier_id"), null, null, null)?.use {
                check(it.moveToFirst() && it.getInt(0) == row.getInt("carrier")) { "原 APN 關聯回讀不一致" }
            } ?: error("原 APN 關聯回讀失敗")
        }
        check(!missingRows) { "部分原 APN 已被刪除；現存項目關聯已還原，備份已保留" }
        check(prefs.edit().remove(simKey).commit()) { "APN 已還原，關聯備份清理失敗" }
    }
}
