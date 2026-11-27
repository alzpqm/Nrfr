package com.github.nrfr.manager

import android.content.Context
import android.net.Uri
import android.telephony.SubscriptionInfo
import androidx.annotation.RequiresApi

/** Read-only counts. Never log APN values, row IDs, SIM identifiers, or provider exceptions. */
@RequiresApi(29)
internal object ApnDiagnostics {
    private fun requiredCount(context: Context, uri: String, selection: String? = null,
        args: Array<String>? = null): Int {
        try {
            // TYPE is allowed by the provider's legacy read policy; newer policies may still deny it.
            val cursor = context.contentResolver.query(Uri.parse(uri), arrayOf("type"),
                selection, args, null) ?: error("APN 查詢未回傳資料；代碼尚未確認可安全修改")
            return cursor.use { it.count }
        } catch (_: SecurityException) {
            error("APN 讀取權限遭拒；代碼尚未確認可安全修改")
        } catch (_: IllegalArgumentException) {
            error("系統不支援 APN 安全查詢；代碼尚未確認可安全修改")
        } catch (_: Exception) {
            error("APN 查詢失敗；代碼尚未確認可安全修改")
        }
    }

    fun rawForNumeric(context: Context, numeric: String): Int =
        requiredCount(context, "content://telephony/carriers", "numeric = ?", arrayOf(numeric))

    fun visibleForSubscription(context: Context, subId: Int): Int =
        requiredCount(context, "content://telephony/carriers/sim_apn_list/$subId")

    fun filteredForSubscription(context: Context, subId: Int): Int =
        requiredCount(context, "content://telephony/carriers/sim_apn_list/filtered/subId/$subId")

    /** Samsung APN Settings adds a slot-specific current flag to SIM_APN_URI. */
    fun settingsVisibleForSubscription(context: Context, info: SubscriptionInfo): Int {
        val flag = currentFlag(info)
        return requiredCount(context, "content://telephony/carriers/sim_apn_list/${info.subscriptionId}",
            "NOT (type='ia' AND (apn=\"\" OR apn IS NULL)) AND $flag = 1")
    }

    private fun currentFlag(info: SubscriptionInfo): String =
        when (info.simSlotIndex) {
            0 -> "current"
            1 -> "current1"
            else -> error("無法確認此 SIM 卡槽的 APN 篩選條件")
        }

    /** Compare private row IDs in memory; never expose them in diagnostics or errors. */
    fun currentFlagRows(context: Context, info: SubscriptionInfo): Set<Long> {
        try {
            val cursor = context.contentResolver.query(Uri.parse("content://telephony/carriers"),
                arrayOf("_id"), "${currentFlag(info)} = 1", null, null)
                ?: error("APN 目前電信業者查詢失敗")
            return cursor.use {
                buildSet { while (it.moveToNext()) add(it.getLong(0)) }
            }
        } catch (_: SecurityException) {
            error("APN 讀取權限遭拒；代碼尚未確認可安全修改")
        } catch (_: Exception) {
            error("APN 目前電信業者查詢失敗；代碼尚未確認可安全修改")
        }
    }

}
