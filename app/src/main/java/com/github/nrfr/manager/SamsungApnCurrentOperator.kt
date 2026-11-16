package com.github.nrfr.manager

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.telephony.SubscriptionInfo
import com.github.nrfr.model.OperatorNumeric

/** Uses Samsung's per-subscription current-operator provider operation; never copies APN rows. */
internal object SamsungApnCurrentOperator {
    fun isRequired(): Boolean = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    fun set(context: Context, info: SubscriptionInfo, numeric: String) {
        if (!isRequired()) return
        check(info.simSlotIndex in 0..1) { "無法確認此 SIM 卡槽的 APN 狀態" }
        val code = OperatorNumeric.parse(numeric).value
        val values = ContentValues().apply { put("numeric", code) }
        val uri = Uri.parse("content://telephony/carriers/current/subId/${info.subscriptionId}")
        try {
            // This provider operation may return null even when its slot-specific update succeeds.
            // The caller must verify the resulting APN query instead of trusting the return URI.
            context.contentResolver.insert(uri, values)
        } catch (_: SecurityException) {
            error("APN 更新權限遭拒")
        } catch (_: IllegalArgumentException) {
            error("系統不支援依 SIM 更新 APN 電信業者")
        }
    }
}
