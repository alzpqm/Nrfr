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
            if (request is PrivilegedCarrierConfigRunner.Request.Override) {
                NumericOverridePolicy.requireAllowed(request.operatorNumeric)
            }
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
                    if (request.values == null) {
                        val report = CarrierRestoreCoordinator(
                            restoreIdentity = { CarrierIdentityAccess.restore(targetContext, info) },
                            clearConfig = {
                                CarrierOverridePolicy.apply(Build.VERSION.SDK_INT, true) {
                                    CarrierConfigAccess.override(request.subId, null, it, asShell = false)
                                }
                            },
                            verifyIdentity = { CarrierIdentityAccess.verify(targetContext, info, it) },
                            cleanupApns = { restored ->
                                if (CarrierIdentityAccess.hasLegacyApns(targetContext, info)) {
                                    // Request APN permissions only after the identity is confirmed.
                                    checkNotNull(manager).startDelegateShellPermissionIdentity(Process.myUid(),
                                        (permissions + Manifest.permission.WRITE_APN_SETTINGS).toTypedArray())
                                    CarrierIdentityAccess.cleanupLegacyApns(targetContext, info, restored)
                                }
                            },
                            completeRestore = { CarrierIdentityAccess.completeRestore(targetContext, info, it) },
                        ).restore()
                        result.putBoolean(PrivilegedCarrierConfigRunner.RESULT_PERSISTENT, report.persistent)
                        result.putBoolean(PrivilegedCarrierConfigRunner.RESULT_REQUIRES_RESTART, report.requiresRestart)
                        result.putString(PrivilegedCarrierConfigRunner.RESULT_RESTORED_NUMERIC, report.restoredNumeric)
                        result.putString(PrivilegedCarrierConfigRunner.RESULT_WARNING, report.warning)
                    } else {
                        val persistent = CarrierOverridePolicy.apply(Build.VERSION.SDK_INT, false) {
                            CarrierConfigAccess.override(request.subId, request.values, it, asShell = false)
                        }
                        verifyConfig(request.subId, request.values)
                        result.putBoolean(PrivilegedCarrierConfigRunner.RESULT_PERSISTENT, persistent)
                    }
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
                    result.putString(PrivilegedCarrierConfigRunner.RESULT_ERROR, "權限清理失敗，請重新啟動應用程式並核對目前設定")
                }
            }
            finish(Activity.RESULT_OK, result)
        }
    }

    private fun verifyConfig(subId: Int, expected: PersistableBundle) {
        val country = expected.getString(CarrierConfigAccess.COUNTRY_ISO)?.takeIf { it.isNotEmpty() }
        val telephony = targetContext.getSystemService(TelephonyManager::class.java)
            ?.createForSubscriptionId(subId)
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        do {
            val actual = CarrierConfigAccess.read(targetContext, subId)
            @Suppress("DEPRECATION")
            val configMatches = expected.keySet().all { expected[it] == actual[it] }
            // Confirm the value apps see as well as the CarrierConfig bundle.
            if (configMatches && (country == null || telephony?.simCountryIso == country)) return
            Thread.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("設定已提交，但系統回讀不一致，尚未確認生效；請重新讀取目前設定、核對 SIM 代碼或還原")
    }
}
