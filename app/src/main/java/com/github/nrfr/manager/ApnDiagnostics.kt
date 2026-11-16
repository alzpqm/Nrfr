package com.github.nrfr.manager

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

/** Read-only counts. Never log APN values, row IDs, SIM identifiers, or provider exceptions. */
internal object ApnDiagnostics {
    private fun count(context: Context, uri: String, selection: String? = null,
        args: Array<String>? = null): String {
        return try {
            val cursor: Cursor = context.contentResolver.query(Uri.parse(uri), arrayOf("_id"),
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
