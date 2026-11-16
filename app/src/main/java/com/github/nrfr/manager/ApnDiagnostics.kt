package com.github.nrfr.manager

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
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

    private fun count(context: Context, uri: String, selection: String? = null,
        args: Array<String>? = null): String {
        return try {
            val cursor: Cursor = context.contentResolver.query(Uri.parse(uri), arrayOf("type"),
                selection, args, null) ?: return "null_cursor"
            cursor.use { it.count.toString() }
        } catch (_: SecurityException) {
            "permission_denied"
        } catch (_: IllegalArgumentException) {
            "unsupported_query"
        } catch (_: Exception) {
            "query_failed"
        }
    }

    fun summary(context: Context, info: SubscriptionInfo): String {
        val subId = info.subscriptionId
        val telephony = context.getSystemService(TelephonyManager::class.java)
            .createForSubscriptionId(subId)
        val subscriptionNumeric = info.mccString.orEmpty() + info.mncString.orEmpty()
        val currentNumeric = telephony.simOperator.orEmpty()
        val root = "content://telephony/carriers"
        val original = if (subscriptionNumeric.matches(Regex("[0-9]{5,6}")))
            count(context, root, "numeric = ?", arrayOf(subscriptionNumeric)) else "unknown_numeric"
        val current = if (currentNumeric.matches(Regex("[0-9]{5,6}")))
            count(context, root, "numeric = ?", arrayOf(currentNumeric)) else "unknown_numeric"
        val visible = count(context, "$root/sim_apn_list/$subId")
        val filtered = count(context, "$root/sim_apn_list/filtered/subId/$subId")
        val preferred = count(context, "$root/preferapn/subId/$subId")
        return "rawOriginal=$original rawCurrent=$current visible=$visible " +
            "filtered=$filtered preferred=$preferred " +
            "simMatchesSubscription=${currentNumeric == subscriptionNumeric} " +
            "carrierId=${telephony.simSpecificCarrierId} " +
            "defaultData=${SubscriptionManager.getDefaultDataSubscriptionId() == subId}"
    }
}
