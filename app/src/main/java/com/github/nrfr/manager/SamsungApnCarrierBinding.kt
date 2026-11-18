package com.github.nrfr.manager

import android.annotation.TargetApi
import android.annotation.SuppressLint
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

    fun hasAnyBackup(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.isNotEmpty()

    private fun ids(context: Context, uri: String, selection: String? = null,
        args: Array<String>? = null): Set<Long> {
        val cursor = context.contentResolver.query(Uri.parse(uri), arrayOf("_id"), selection, args, null)
            ?: error("APN 查詢未回傳資料")
        return cursor.use { buildSet { while (it.moveToNext()) add(it.getLong(0)) } }
    }

    private fun settingsIds(context: Context, info: SubscriptionInfo): Set<Long> =
        ids(context, "$ROOT/sim_apn_list/${info.subscriptionId}",
            "NOT (type='ia' AND (apn=\"\" OR apn IS NULL)) AND ${if (info.simSlotIndex == 0) "current" else "current1"} = 1")

    private fun readRow(context: Context, id: Long): Map<String, String?> {
        val cursor = context.contentResolver.query(Uri.parse("$ROOT/$id"), null, null, null, null)
            ?: error("APN 項目查詢未回傳資料")
        return cursor.use {
            check(it.moveToFirst()) { "APN 項目已不存在" }
            it.columnNames.mapIndexed { index, column -> column to it.getString(index) }.toMap()
        }
    }

    private val matchIgnored = setOf("mvno_type", "mvno_match_data", "edited", "current", "current1")

    private fun contentFingerprint(row: Map<String, String?>,
        ignored: Set<String> = setOf("carrier_id", "edited", "current", "current1")): String {
        val canonical = JSONArray()
        row.toSortedMap().filterKeys { it !in ignored }.forEach { (column, value) ->
            canonical.put(JSONArray().put(column).put(value ?: JSONObject.NULL))
        }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    fun filteredIds(context: Context, info: SubscriptionInfo): Set<Long> =
        ids(context, "$ROOT/sim_apn_list/filtered/subId/${info.subscriptionId}")

    fun preferredIds(context: Context, info: SubscriptionInfo): Set<Long> =
        ids(context, "$ROOT/preferapn/subId/${info.subscriptionId}")

    private fun checkNoConflict(context: Context, id: Long, destinationCarrier: Int, numeric: String? = null,
        matching: Pair<String?, String?>? = null) {
        val cursor = context.contentResolver.query(Uri.parse("$ROOT/$id"), uniqueColumns.toTypedArray(), null, null, null)
            ?: error("APN 唯一鍵查詢失敗")
        val values = cursor.use {
            check(it.moveToFirst()) { "原 APN 項目已不存在" }
            uniqueColumns.mapIndexed { index, column ->
                when (column) {
                    "carrier_id" -> destinationCarrier.toString()
                    "numeric" -> numeric ?: it.getString(index)
                    "mcc" -> numeric?.take(3) ?: it.getString(index)
                    "mnc" -> numeric?.drop(3) ?: it.getString(index)
                    "mvno_type" -> if (matching != null) matching.first else it.getString(index)
                    "mvno_match_data" -> if (matching != null) matching.second else it.getString(index)
                    else -> it.getString(index)
                }
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

    private fun matchesIccid(phone: TelephonyManager, numeric: String, pattern: String): Boolean {
        // This device's provider maps ICCID to 3 and calls this same phone-service API.
        val method = TelephonyManager::class.java.getMethod("matchesCurrentSimOperator",
            String::class.java, Int::class.javaPrimitiveType, String::class.java)
        return method.invoke(phone, numeric, 3, pattern) as Boolean
    }

    private fun nullable(entry: JSONObject, name: String): String? =
        if (entry.isNull(name)) null else entry.getString(name)

    /** A target-numeric MVNO can exclude every real APN; a target MNO can also join them.
     * Retain these rows, but put their matching metadata in the nonmatching bucket while
     * this SIM override is active. The durable journal precedes every item update. */
    @SuppressLint("MissingPermission")
    private fun competition(context: Context, info: SubscriptionInfo, testNumeric: String,
        original: String, phone: TelephonyManager): JSONArray {
        val candidates = ids(context, ROOT, "numeric = ? AND edited NOT IN (2,3,5,6)", arrayOf(testNumeric))
        val subscriptions = context.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty()
        subscriptions.filter { it.subscriptionId != info.subscriptionId }.forEach { other ->
            val otherPhone = context.getSystemService(TelephonyManager::class.java).createForSubscriptionId(other.subscriptionId)
            check(otherPhone.simOperator != testNumeric &&
                candidates.intersect(filteredIds(context, other) + settingsIds(context, other) + preferredIds(context, other)).isEmpty()) {
                "目標代碼的 APN 正由另一張 SIM 使用，未修改匹配條件"
            }
        }
        val entries = JSONArray()
        candidates.sorted().forEach { id ->
            val before = readRow(context, id)
            val marker = ApnMatchingMetadata.marker(key(info), id)
            check(!matchesIccid(phone, original, marker)) { "無法隔離目標 APN 的匹配條件" }
            val carrier = checkNotNull(before["carrier_id"]).toInt()
            checkNoConflict(context, id, carrier, matching = "iccid" to marker)
            entries.put(JSONObject().put("id", id).put("mvnoType", before["mvno_type"] ?: JSONObject.NULL)
                .put("mvnoData", before["mvno_match_data"] ?: JSONObject.NULL)
                .put("edited", before["edited"] ?: JSONObject.NULL).put("marker", marker)
                .put("fingerprint", contentFingerprint(before, matchIgnored)))
        }
        return entries
    }

    private fun applyCompetition(context: Context, entries: JSONArray) {
        for (index in 0 until entries.length()) {
            val entry = entries.getJSONObject(index)
            val id = entry.getLong("id")
            val before = readRow(context, id)
            check(before["mvno_type"] == nullable(entry, "mvnoType") &&
                before["mvno_match_data"] == nullable(entry, "mvnoData") &&
                contentFingerprint(before, matchIgnored) == entry.getString("fingerprint")) {
                "目標 APN 已被編輯，匹配條件尚未修改"
            }
            checkNoConflict(context, id, checkNotNull(before["carrier_id"]).toInt(),
                matching = "iccid" to entry.getString("marker"))
            val values = ContentValues().apply {
                put("mvno_type", "iccid"); put("mvno_match_data", entry.getString("marker"))
                put("edited", before["edited"])
            }
            check(context.contentResolver.update(Uri.parse("$ROOT/$id"), values, null, null) == 1) {
                "目標 APN 匹配條件更新未確認"
            }
            val after = readRow(context, id)
            check(after == before + mapOf("mvno_type" to "iccid", "mvno_match_data" to entry.getString("marker"))) {
                "目標 APN 其他欄位回讀不一致，備份已保留"
            }
        }
    }

    private fun restoreCompetition(context: Context, entries: JSONArray) {
        for (index in 0 until entries.length()) {
            val entry = entries.getJSONObject(index)
            val id = entry.getLong("id")
            if (ids(context, ROOT, "_id = ?", arrayOf(id.toString())).isEmpty()) continue
            val before = readRow(context, id)
            val originalType = nullable(entry, "mvnoType")
            val originalData = nullable(entry, "mvnoData")
            val restore = ApnMatchingMetadata.restore(before["mvno_type"], before["mvno_match_data"],
                originalType, originalData, entry.getString("marker"))
            if (!restore) continue // Journal written, but this row was never changed.
            // Recompute the reverse unique key using the user's current connection fields.
            checkNoConflict(context, id, checkNotNull(before["carrier_id"]).toInt(),
                matching = originalType to originalData)
            val edited = ApnMatchingMetadata.restoredEdited(before["edited"], nullable(entry, "edited"),
                contentFingerprint(before, matchIgnored) == entry.getString("fingerprint"))
            val values = ContentValues().apply {
                put("mvno_type", originalType); put("mvno_match_data", originalData); put("edited", edited)
            }
            check(context.contentResolver.update(Uri.parse("$ROOT/$id"), values, null, null) == 1) {
                "目標 APN 原匹配條件還原未確認"
            }
            check(readRow(context, id) == before + mapOf("mvno_type" to originalType,
                "mvno_match_data" to originalData, "edited" to edited)) {
                "目標 APN 原匹配條件回讀不一致，備份已保留"
            }
        }
    }

    // Called only within the instrumentation's scoped READ_PHONE_STATE delegation.
    @SuppressLint("MissingPermission")
    fun prepare(context: Context, info: SubscriptionInfo, testNumeric: String) {
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
        rows.forEach { savedRows.put(JSONObject().put("id", it.id).put("carrier", it.carrier).put("edited", it.edited)
            .put("contentFingerprint", contentFingerprint(readRow(context, it.id)))) }
        val backup = JSONObject().put("carrier", carrier).put("rows", savedRows)
            .put("originalNumeric", original).put("testNumeric", testNumeric)
            .put("preexisting", JSONArray(ids(context, ROOT, "numeric = ?", arrayOf(testNumeric)).toList()))
            .put("filtered", JSONArray(filtered.toList())).put("settings", JSONArray(settings.toList()))
            .put("preferred", JSONArray(preferred.toList()))
            .put("competition", competition(context, info, testNumeric, original, telephony))
        if (context.packageName.endsWith(".s25")) {
            ApnResearchSnapshot.capture(context, subscriptions, "before-binding", testNumeric)
        }
        check(NightzukuRecoveryStore.put(context, PREFS, simKey, backup.toString())) { "原 APN 關聯備份失敗" }
        try {
            applyCompetition(context, backup.getJSONArray("competition"))
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

    /** Samsung's editor seeds numeric from the overridden SIM property. Keep the user's
     * new APN on the actual connection network, without copying an APN or selecting it. */
    @SuppressLint("MissingPermission")
    fun reconcileNewApns(context: Context, info: SubscriptionInfo): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val simKey = key(info)
        val backup = prefs.getString(simKey, null)?.let(::JSONObject) ?: return 0
        if (!backup.has("testNumeric")) return 0 // Older backup: restore before migrating.
        val original = backup.getString("originalNumeric")
        val testNumeric = backup.getString("testNumeric")
        val phone = context.getSystemService(TelephonyManager::class.java).createForSubscriptionId(info.subscriptionId)
        if (phone.simOperator != testNumeric || !SamsungCarrierRule.hasBackup(context, info)) return 0
        check(phone.simCarrierId == backup.getInt("carrier")) { "SIM 電信識別已改變，APN 維護已停止" }
        val rows = backup.getJSONArray("rows")
        val excluded = backup.getJSONArray("preexisting").let { values ->
            (0 until values.length()).map { values.getLong(it) }.toMutableSet()
        }
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            if (!row.optBoolean("created")) excluded += row.getLong("id")
        }
        val cursor = context.contentResolver.query(Uri.parse(ROOT),
            arrayOf("_id", "carrier_id", "edited"),
            "numeric = ? AND edited = 1 AND sub_id = ? AND sim_slot = ?",
            arrayOf(testNumeric, info.subscriptionId.toString(), info.simSlotIndex.toString()), null)
            ?: error("新增 APN 查詢未回傳資料")
        val candidates = cursor.use { buildList {
            while (it.moveToNext()) if (it.getLong(0) !in excluded)
                add(Row(it.getLong(0), it.getInt(1), it.getInt(2), 0))
        } }
        var changed = 0
        for (row in candidates) {
            val before = readRow(context, row.id)
            check(before["numeric"] == testNumeric && before["edited"] == "1" &&
                before["sub_id"] == info.subscriptionId.toString() &&
                before["sim_slot"] == info.simSlotIndex.toString() && before["carrier_id"] == row.carrier.toString()) {
                "新增 APN 已變更，未執行此次維護"
            }
            check(row.carrier == -1 || row.carrier == backup.getInt("carrier")) {
                "新增 APN 的電信關聯不符，未修改此項目"
            }
            val otherFlag = if (info.simSlotIndex == 0) "current1" else "current"
            val uri = Uri.parse("$ROOT/${row.id}")
            context.contentResolver.query(uri, arrayOf(otherFlag), null, null, null)?.use {
                check(it.moveToFirst() && it.getInt(0) != 1) { "新增 APN 為雙卡共用，未修改此項目" }
            } ?: error("新增 APN 卡槽查詢失敗")
            var entry = (0 until rows.length()).map { rows.getJSONObject(it) }
                .firstOrNull { it.getLong("id") == row.id }
            if (entry == null) {
                entry = JSONObject().put("id", row.id).put("carrier", row.carrier)
                    .put("edited", row.edited).put("created", true).put("createdNumeric", testNumeric)
                rows.put(entry)
                check(NightzukuRecoveryStore.put(context, PREFS, simKey, backup.toString())) { "新增 APN 關聯備份失敗" }
            }
            checkNoConflict(context, row.id, backup.getInt("carrier"), original)
            check(readRow(context, row.id) == before) { "新增 APN 正在編輯，請完成後再試" }
            if (context.packageName.endsWith(".s25")) {
                ApnResearchSnapshot.capture(context, listOf(info), "before-new-apn-normalize")
            }
            val values = ContentValues().apply {
                put("numeric", original); put("mcc", original.take(3)); put("mnc", original.drop(3))
                put("carrier_id", backup.getInt("carrier")); put("edited", row.edited)
                put(if (info.simSlotIndex == 0) "current" else "current1", 1)
            }
            // Samsung rejects any extra selection on an item URI. Keep this item update;
            // the collection URI uses REPLACE. Preflight/readback cannot lock out external writers.
            check(context.contentResolver.update(uri, values, null, null) == 1) {
                "新增 APN 已變更，未確認此次維護"
            }
            val after = readRow(context, row.id)
            check(after["numeric"] == original && after["mcc"] == original.take(3) &&
                after["mnc"] == original.drop(3) && after["carrier_id"] == backup.getInt("carrier").toString() &&
                after[if (info.simSlotIndex == 0) "current" else "current1"] == "1") {
                "新增 APN 維護回讀不一致"
            }
            val modified = setOf("numeric", "mcc", "mnc", "carrier_id",
                if (info.simSlotIndex == 0) "current" else "current1")
            check(before.filterKeys { it !in modified } == after.filterKeys { it !in modified }) {
                "新增 APN 其他欄位有變更，請核對後再試；備份已保留"
            }
            entry.put("normalized", true)
            check(NightzukuRecoveryStore.put(context, PREFS, simKey, backup.toString())) { "新增 APN 維護紀錄失敗" }
            changed++
        }
        return changed
    }

    fun verify(context: Context, info: SubscriptionInfo) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(info), null)
            ?: error("APN 關聯備份不存在")
        val backup = JSONObject(saved)
        val phone = context.getSystemService(TelephonyManager::class.java).createForSubscriptionId(info.subscriptionId)
        backup.optJSONArray("competition")?.let { entries ->
            for (index in 0 until entries.length()) {
                check(!matchesIccid(phone, backup.getString("testNumeric"), entries.getJSONObject(index).getString("marker"))) {
                    "目標 APN 匹配隔離未生效，已停止修改"
                }
            }
        }
        fun expected(name: String): Set<Long> = backup.getJSONArray(name).let { values ->
            (0 until values.length()).map { values.getLong(it) }.toSet()
        }
        if (ids(context, "$ROOT/sim_apn_list/filtered/subId/${info.subscriptionId}") != expected("filtered")) {
            if (context.packageName.endsWith(".s25")) {
                runCatching { ApnResearchSnapshot.capture(context, listOf(info), "binding-failed") }
            }
            error("修改後的數據 APN 與原項目不一致，已停止修改")
        }
        check(settingsIds(context, info) == expected("settings")) { "修改後 APN 顯示項目與原清單不一致" }
        check(ids(context, "$ROOT/preferapn/subId/${info.subscriptionId}") == expected("preferred")) {
            "修改後 APN 選取與原值不一致"
        }
    }

    @SuppressLint("MissingPermission")
    fun restore(context: Context, info: SubscriptionInfo) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val simKey = key(info)
        val saved = prefs.getString(simKey, null) ?: return
        val backup = JSONObject(saved)
        backup.optJSONArray("competition")?.let { restoreCompetition(context, it) }
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
                if (!row.optBoolean("created")) missingRows = true
                continue // Never recreate a deleted APN or silently call it restored.
            }
            if (state.first == row.getInt("carrier")) continue
            check(state.first == backup.getInt("carrier")) { "APN 關聯已被其他操作變更，備份已保留" }
            checkNoConflict(context, row.getLong("id"), row.getInt("carrier"))
            // Undo the test's metadata only when all APN content is back at its original
            // value. Keep the user's edited marker when any connection/name field changed.
            val edited = checkNotNull(ApnMatchingMetadata.restoredEdited(state.second.toString(), row.getInt("edited").toString(),
                row.has("contentFingerprint") &&
                    row.getString("contentFingerprint") == contentFingerprint(readRow(context, row.getLong("id"))))).toInt()
            val values = ContentValues().apply { put("carrier_id", row.getInt("carrier")); put("edited", edited) }
            check(context.contentResolver.update(uri, values, null, null) == 1) { "原 APN 關聯還原未確認" }
            context.contentResolver.query(uri, arrayOf("carrier_id"), null, null, null)?.use {
                check(it.moveToFirst() && it.getInt(0) == row.getInt("carrier")) { "原 APN 關聯回讀不一致" }
            } ?: error("原 APN 關聯回讀失敗")
        }
        check(!missingRows) { "部分原 APN 已被刪除；現存項目關聯已還原，備份已保留" }
        if (context.packageName.endsWith(".s25")) {
            ApnResearchSnapshot.capture(context,
                context.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty(),
                "binding-restored", backup.optString("testNumeric").takeIf { it.isNotEmpty() })
        }
        check(NightzukuRecoveryStore.remove(context, PREFS, simKey)) { "APN 已還原，關聯備份清理失敗" }
    }
}
