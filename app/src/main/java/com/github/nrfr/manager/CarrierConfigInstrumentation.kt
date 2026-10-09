package com.github.nrfr.manager

import android.Manifest
import android.annotation.SuppressLint
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
                ?: error("配置请求已过期或不属于当前进程")
            val deadline = SystemClock.elapsedRealtime() + 3_000L
            while (!Shizuku.pingBinder() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
            check(Shizuku.pingBinder()) { "Shizuku 未运行" }
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
                        Bundle().apply {
                            putInt("slot", info.simSlotIndex + 1)
                            putInt("sub_id", info.subscriptionId)
                            putString("carrier_name", info.carrierName?.toString().orEmpty())
                            putParcelable("config", CarrierConfigAccess.read(targetContext, info.subscriptionId))
                        }
                    }
                    result.putParcelableArrayList(PrivilegedCarrierConfigRunner.RESULT_SIM_CARDS, ArrayList(cards))
                }
                is PrivilegedCarrierConfigRunner.Request.Override -> {
                    check(subscriptions.any { it.subscriptionId == request.subId }) {
                        "所选 SIM 已失效，请重新选择"
                    }
                    val persistent = CarrierOverridePolicy.apply(Build.VERSION.SDK_INT, request.values == null) {
                        CarrierConfigAccess.override(request.subId, request.values, it, asShell = false)
                    }
                    request.values?.let { verifyConfig(request.subId, it) }
                    result.putBoolean(PrivilegedCarrierConfigRunner.RESULT_PERSISTENT, persistent)
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
                    result.putString(PrivilegedCarrierConfigRunner.RESULT_ERROR, "权限清理失败: ${error.message}")
                }
            }
            finish(Activity.RESULT_OK, result)
        }
    }

    private fun verifyConfig(subId: Int, expected: PersistableBundle) {
        val country = expected.getString(CarrierConfigAccess.COUNTRY_ISO)
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
        error("设置已提交，但系统回读不一致，尚未确认生效；请检查系统版本和 SIM 状态")
    }
}
