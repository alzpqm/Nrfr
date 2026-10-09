package com.github.nrfr.manager

import android.Manifest
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.os.Process
import android.os.SystemClock
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import rikka.shizuku.Shizuku

@TargetApi(29)
class CarrierConfigInstrumentation : Instrumentation() {
    private var arguments: Bundle? = null

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        this.arguments = arguments
        start()
    }

    @SuppressLint("MissingPermission")
    override fun onStart() {
        val result = Bundle()
        var delegated = false
        var manager: android.app.IActivityManager? = null
        try {
            val request = PrivilegedCarrierConfigRunner.takeRequest(arguments)
                ?: error("設定請求已過期或不屬於目前進程")
            val deadline = SystemClock.elapsedRealtime() + 3_000L
            while (!Shizuku.pingBinder() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
            check(Shizuku.pingBinder()) { "Shizuku 未執行" }
            manager = PrivilegedCarrierConfigRunner.activityManager()
            val permissions = mutableListOf(
                Manifest.permission.READ_PHONE_STATE,
                "android.permission.READ_PRIVILEGED_PHONE_STATE",
            )
            if (request is PrivilegedCarrierConfigRunner.Request.Override) {
                permissions += Manifest.permission.MODIFY_PHONE_STATE
            }
            manager.startDelegateShellPermissionIdentity(Process.myUid(), permissions.toTypedArray())
            delegated = true
            val subscriptions = targetContext.getSystemService(SubscriptionManager::class.java)
                ?.activeSubscriptionInfoList.orEmpty()
                .filter { it.subscriptionId >= 0 && it.simSlotIndex >= 0 }
            when (request) {
                PrivilegedCarrierConfigRunner.Request.ReadSimCards -> {
                    val cards = subscriptions.sortedBy { it.simSlotIndex }.map { info ->
                        val telephony = (targetContext.getSystemService(TelephonyManager::class.java) ?: error("電話服務無法使用"))
                            .createForSubscriptionId(info.subscriptionId)
                        Bundle().apply {
                            putInt("slot", info.simSlotIndex + 1)
                            putInt("sub_id", info.subscriptionId)
                            putString("carrier_name", info.carrierName?.toString().orEmpty())
                            putParcelable("config", CarrierConfigAccess.read(targetContext, info.subscriptionId))
                            putString("sim_operator", telephony.simOperator)
                            putString("network_operator", telephony.networkOperator)
                            putString("subscription_operator", info.mccString.orEmpty() + info.mncString.orEmpty())
                            putString("sim_country_iso", telephony.simCountryIso)
                        }
                    }
                    result.putParcelableArrayList(PrivilegedCarrierConfigRunner.RESULT_SIM_CARDS, ArrayList(cards))
                }
                is PrivilegedCarrierConfigRunner.Request.Override -> {
                    val info = subscriptions.firstOrNull { it.subscriptionId == request.subId }
                    check(info != null) {
                        "所選 SIM 已失效，請重新選擇"
                    }
                    val restored = if (request.values == null) CarrierIdentityAccess.restore(targetContext, info) else null
                    request.operatorNumeric?.let {
                        CarrierIdentityAccess.apply(targetContext, info, it, request.values?.getString(CarrierConfigAccess.CARRIER_NAME))
                    }
                    // Identity changes may reload CarrierConfig. Apply country/name afterwards.
                    val persistent = if (request.values == null || !request.values.isEmpty) {
                        CarrierOverridePolicy.apply(Build.VERSION.SDK_INT, request.values == null) {
                            CarrierConfigAccess.override(request.subId, request.values, it, asShell = false)
                        }
                    } else false
                    verifyConfig(request.subId, request.values, request.operatorNumeric ?: restored?.expectedNumeric)
                    restored?.let { CarrierIdentityAccess.completeRestore(targetContext, info, it) }
                    result.putBoolean(PrivilegedCarrierConfigRunner.RESULT_PERSISTENT, persistent && request.operatorNumeric == null)
                    result.putBoolean(PrivilegedCarrierConfigRunner.RESULT_REQUIRES_RESTART, restored?.fullyCleared == false)
                }
            }
            result.putBoolean(PrivilegedCarrierConfigRunner.RESULT_SUCCESS, true)
        } catch (error: Throwable) {
            Log.e("NrfrCarrierConfig", "CarrierConfig operation failed", error)
            result.putBoolean(PrivilegedCarrierConfigRunner.RESULT_SUCCESS, false)
            result.putString(PrivilegedCarrierConfigRunner.RESULT_ERROR, error.message ?: error.javaClass.simpleName)
        } finally {
            if (delegated) {
                try {
                    manager?.stopDelegateShellPermissionIdentity()
                } catch (error: Exception) {
                    Log.e("NrfrCarrierConfig", "Permission delegation cleanup failed", error)
                    result.putBoolean(PrivilegedCarrierConfigRunner.RESULT_SUCCESS, false)
                    result.putString(PrivilegedCarrierConfigRunner.RESULT_ERROR, "權限清理失敗: ${error.message}")
                }
            }
            finish(Activity.RESULT_OK, result)
        }
    }

    private fun verifyConfig(subId: Int, expected: PersistableBundle?, operatorNumeric: String?) {
        if (expected == null && operatorNumeric == null) return
        val country = expected?.getString(CarrierConfigAccess.COUNTRY_ISO)
        val telephony = targetContext.getSystemService(TelephonyManager::class.java)
            ?.createForSubscriptionId(subId)
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        do {
            val actual = CarrierConfigAccess.read(targetContext, subId)
            @Suppress("DEPRECATION")
            val configMatches = expected?.keySet()?.all { expected[it] == actual[it] } != false
            // Confirm the value apps see as well as the CarrierConfig bundle.
            if (configMatches && (country == null || telephony?.simCountryIso == country) &&
                (operatorNumeric == null || telephony?.simOperator == operatorNumeric)) return
            Thread.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("設定已提交，但系統回讀不一致，尚未確認生效；請重新讀取目前設定、核對 SIM 代碼或還原")
    }
}
