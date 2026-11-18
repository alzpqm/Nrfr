package com.github.nrfr.manager

import android.annotation.TargetApi
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Private, read-only research export used only by the separately installed diagnostic package. */
@TargetApi(29)
internal object ApnResearchSnapshot {
    fun capture(context: Context, subscriptions: List<SubscriptionInfo>, name: String = "latest",
        requestedNumeric: String? = null) {
        check(context.packageName.endsWith(".s25"))
        require(name.matches(Regex("[a-zA-Z0-9_-]+")))
        val root = "content://telephony/carriers"
        val sims = JSONArray()
        subscriptions.sortedBy { it.simSlotIndex }.forEach { info ->
            val phone = context.getSystemService(TelephonyManager::class.java)
                .createForSubscriptionId(info.subscriptionId)
            val original = info.mccString.orEmpty() + info.mncString.orEmpty()
            val current = phone.simOperator.orEmpty()
            val sim = JSONObject().put("slot", info.simSlotIndex).put("subId", info.subscriptionId)
                .put("simCarrierId", phone.simCarrierId)
                .put("specificCarrierId", phone.simSpecificCarrierId)
                .put("subscriptionNumeric", original).put("simNumeric", current)
                .put("networkNumeric", phone.networkOperator)
                .put("defaultData", SubscriptionManager.getDefaultDataSubscriptionId() == info.subscriptionId)
            sim.put("rawOriginal", query(context, root, "numeric = ?", arrayOf(original)))
            sim.put("rawCurrent", query(context, root, "numeric = ?", arrayOf(current)))
            requestedNumeric?.let {
                require(it.matches(Regex("[0-9]{5,6}")))
                sim.put("rawRequested", query(context, root, "numeric = ?", arrayOf(it)))
            }
            sim.put("rawCarrier", if (phone.simCarrierId >= 0)
                query(context, root, "carrier_id = ?", arrayOf(phone.simCarrierId.toString()))
                else JSONObject().put("status", "unknown_carrier"))
            sim.put("visible", query(context, "$root/sim_apn_list/${info.subscriptionId}"))
            sim.put("filtered", query(context, "$root/sim_apn_list/filtered/subId/${info.subscriptionId}"))
            sim.put("preferred", query(context, "$root/preferapn/subId/${info.subscriptionId}"))
            val flag = when (info.simSlotIndex) { 0 -> "current"; 1 -> "current1"; else -> null }
            if (flag != null) sim.put("settings", query(context, "$root/sim_apn_list/${info.subscriptionId}",
                "NOT (type='ia' AND (apn=\"\" OR apn IS NULL)) AND $flag = 1"))
            sims.put(sim)
        }
        val snapshot = JSONObject().put("capturedAt", System.currentTimeMillis())
            .put("sdk", Build.VERSION.SDK_INT).put("sims", sims)
        val directory = checkNotNull(context.getExternalFilesDir("apn-research"))
        val pending = File(directory, "$name.json.tmp")
        pending.writeText(snapshot.toString())
        check(pending.renameTo(File(directory, "$name.json"))) { "私人 APN 診斷匯出失敗" }
    }

    private fun query(context: Context, uri: String, selection: String? = null,
        args: Array<String>? = null): JSONObject {
        return try {
            val cursor = context.contentResolver.query(Uri.parse(uri), null, selection, args, null)
                ?: return JSONObject().put("status", "null_cursor")
            cursor.use {
                val rows = JSONArray()
                while (it.moveToNext()) {
                    val row = JSONObject()
                    it.columnNames.forEachIndexed { index, name ->
                        row.put(name, when (it.getType(index)) {
                            Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                            Cursor.FIELD_TYPE_INTEGER -> it.getLong(index)
                            Cursor.FIELD_TYPE_FLOAT -> it.getDouble(index)
                            Cursor.FIELD_TYPE_BLOB -> "<blob>"
                            else -> it.getString(index)
                        })
                    }
                    rows.put(row)
                }
                JSONObject().put("status", "ok").put("rows", rows)
            }
        } catch (_: SecurityException) {
            JSONObject().put("status", "permission_denied")
        } catch (_: IllegalArgumentException) {
            JSONObject().put("status", "unsupported_query")
        } catch (_: Exception) {
            JSONObject().put("status", "query_failed")
        }
    }
}
