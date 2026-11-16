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
import com.github.nrfr.model.OperatorNumeric
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
                request.operatorNumeric?.let { OperatorNumeric.parse(it) }
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
                if (request.operatorNumeric != null ||
                    (request.values == null && SamsungApnCurrentOperator.isRequired())) {
                    permissions += Manifest.permission.WRITE_APN_SETTINGS
                }
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
                    // Candidate diagnosis only: a permission denial is never an empty APN list.
                    subscriptions.forEach { info ->
                        try {
                            Log.i("NrfrApnDiag", "slot=${info.simSlotIndex + 1} " +
                                ApnDiagnostics.summary(targetContext, info))
                        } catch (_: Exception) {
                            Log.i("NrfrApnDiag", "slot=${info.simSlotIndex + 1} diagnosis_failed")
                        }
                    }
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
                            restoreApnCurrent = { restored ->
                                if (SamsungApnCurrentOperator.isRequired()) {
                                    val original = checkNotNull(restored.expectedNumeric)
                                    SamsungApnCurrentOperator.set(targetContext, info, original)
                                    check(ApnDiagnostics.settingsVisibleForSubscription(targetContext, info) > 0) {
                                        "原 APN 清單尚未恢復"
                                    }
                                }
                            },
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
                        val requestedNumeric = request.operatorNumeric
                        val previous = requestedNumeric?.let { CarrierIdentityAccess.previous(targetContext, info) }
                        val samsung = SamsungApnCurrentOperator.isRequired()
                        val otherSim = subscriptions.firstOrNull { it.simSlotIndex != info.simSlotIndex }
                        val otherSettingsCount = if (requestedNumeric != null && samsung && otherSim != null)
                            ApnDiagnostics.settingsVisibleForSubscription(targetContext, otherSim) else null
                        if (requestedNumeric != null && previous != null && previous.numeric != requestedNumeric) {
                            ApnSafetyGate.preflight(
                                if (samsung) ApnDiagnostics.settingsVisibleForSubscription(targetContext, info)
                                else ApnDiagnostics.visibleForSubscription(targetContext, info.subscriptionId),
                                ApnDiagnostics.rawForNumeric(targetContext, requestedNumeric))
                        }
                        val persistent = CarrierApplyCoordinator(
                            applyIdentity = {
                                CarrierIdentityAccess.apply(targetContext, info, it,
                                    request.values.getString(CarrierConfigAccess.CARRIER_NAME))
                                CarrierIdentityAccess.verify(targetContext, info, it)
                                if (previous != null && previous.numeric != it) {
                                    if (samsung) SamsungApnCurrentOperator.set(targetContext, info, it)
                                    // The provider can reload asynchronously after the SIM property changes.
                                    repeat(6) {
                                        Thread.sleep(1_000)
                                        ApnSafetyGate.afterApply(
                                            if (samsung) ApnDiagnostics.settingsVisibleForSubscription(targetContext, info)
                                            else ApnDiagnostics.visibleForSubscription(targetContext, info.subscriptionId),
                                            ApnDiagnostics.filteredForSubscription(targetContext, info.subscriptionId))
                                    }
                                    if (otherSim != null && otherSettingsCount != null) check(
                                        ApnDiagnostics.settingsVisibleForSubscription(targetContext, otherSim) == otherSettingsCount) {
                                        "另一張 SIM 的 APN 清單已改變"
                                    }
                                }
                            },
                            applyConfig = {
                                CarrierOverridePolicy.apply(Build.VERSION.SDK_INT, false) {
                                    CarrierConfigAccess.override(request.subId, request.values, it, asShell = false)
                                }
                            },
                            verify = {
                                verifyConfig(request.subId, request.values, it)
                                if (previous != null && previous.numeric != it) {
                                    repeat(3) {
                                        Thread.sleep(1_000)
                                        ApnSafetyGate.afterApply(
                                            if (samsung) ApnDiagnostics.settingsVisibleForSubscription(targetContext, info)
                                            else ApnDiagnostics.visibleForSubscription(targetContext, info.subscriptionId),
                                            ApnDiagnostics.filteredForSubscription(targetContext, info.subscriptionId))
                                    }
                                    if (otherSim != null && otherSettingsCount != null) check(
                                        ApnDiagnostics.settingsVisibleForSubscription(targetContext, otherSim) == otherSettingsCount) {
                                        "另一張 SIM 的 APN 清單已改變"
                                    }
                                }
                            },
                            rollbackIdentity = {
                                if (previous != null && previous.numeric != requestedNumeric) {
                                    try {
                                        CarrierIdentityAccess.reapplyPrevious(targetContext, info, previous)
                                    } finally {
                                        if (samsung) SamsungApnCurrentOperator.set(targetContext, info, previous.numeric)
                                    }
                                    var stableApns = 0
                                    repeat(6) { attempt ->
                                        val visible = if (samsung)
                                            ApnDiagnostics.settingsVisibleForSubscription(targetContext, info)
                                        else ApnDiagnostics.visibleForSubscription(targetContext, info.subscriptionId)
                                        val filtered = ApnDiagnostics.filteredForSubscription(targetContext,
                                            info.subscriptionId)
                                        stableApns = if (visible > 0 && filtered > 0) stableApns + 1 else 0
                                        if (attempt < 5) Thread.sleep(1_000)
                                    }
                                    check(stableApns >= 3) { "原 APN 清單尚未穩定恢復" }
                                    if (otherSim != null && otherSettingsCount != null) check(
                                        ApnDiagnostics.settingsVisibleForSubscription(targetContext, otherSim) == otherSettingsCount) {
                                        "另一張 SIM 的 APN 清單尚未恢復"
                                    }
                                }
                            },
                        ).apply(request.operatorNumeric, !request.values.isEmpty)
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

    private fun verifyConfig(subId: Int, expected: PersistableBundle, operatorNumeric: String?) {
        val country = expected.getString(CarrierConfigAccess.COUNTRY_ISO)?.takeIf { it.isNotEmpty() }
        val telephony = targetContext.getSystemService(TelephonyManager::class.java)
            ?.createForSubscriptionId(subId)
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        do {
            val actual = CarrierConfigAccess.read(targetContext, subId)
            @Suppress("DEPRECATION")
            val configMatches = expected.keySet().all { expected[it] == actual[it] }
            // Confirm the value apps see as well as the CarrierConfig bundle.
            if (configMatches && (country == null || telephony?.simCountryIso == country) &&
                (operatorNumeric == null || telephony?.simOperator == operatorNumeric)) return
            Thread.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("設定已提交，但系統回讀不一致，尚未確認生效；請重新讀取目前設定、核對 SIM 代碼或還原")
    }
}
