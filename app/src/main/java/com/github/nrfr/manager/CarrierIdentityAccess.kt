package com.github.nrfr.manager

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.os.IBinder
import android.os.ServiceManager
import android.os.SystemClock
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import java.lang.reflect.InvocationTargetException
import java.security.MessageDigest
import org.json.JSONObject

// Only called inside the instrumentation's scoped phone-permission delegation.
@TargetApi(29)
@SuppressLint("MissingPermission")
internal object CarrierIdentityAccess {
    private const val PREFS = "carrier_identity_originals"

    private fun service(): Any {
        val binder = ServiceManager.getService(Context.TELEPHONY_SERVICE) ?: error("電話服務無法使用")
        return Class.forName("com.android.internal.telephony.ITelephony\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder) ?: error("電話服務介面無法使用")
    }

    private fun key(info: SubscriptionInfo): String? = info.iccId?.takeIf { it.isNotEmpty() }?.let { identity ->
        MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun restore(context: Context, info: SubscriptionInfo): IdentityRestoreResult {
        val simKey = key(info)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = simKey?.let { prefs.getString(it, null) }
        // A missing/corrupt backup must not block country/name clearing. Subscription data can
        // supply the original code after a reinstall; the currently overridden SIM value cannot.
        val backup = saved?.let { runCatching { JSONObject(it) }.getOrNull() }
        val numeric = NumericOverridePolicy.original(backup?.optString("numeric"),
            info.mccString.orEmpty() + info.mncString.orEmpty())
            ?: return IdentityRestoreResult(null, requiresRestart = true)
        val telephony = context.getSystemService(TelephonyManager::class.java)
            .createForSubscriptionId(info.subscriptionId)
        if (saved == null && telephony.simOperator == numeric) return IdentityRestoreResult(numeric)
        assertPresent(context, info)
        val phone = service()
        val clear = phone.javaClass.methods.firstOrNull {
            it.name == "clearCarrierTestOverride" && it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType
        }
        if (clear != null) {
            invoke { clear.invoke(phone, info.subscriptionId) }
            return IdentityRestoreResult(numeric)
        }
        val set = phone.javaClass.methods.filter {
            it.name == "setCarrierTestOverride" && it.parameterTypes.size in listOf(8, 10) &&
                it.parameterTypes.first() == Int::class.javaPrimitiveType &&
                it.parameterTypes.drop(1).all { type -> type == String::class.java }
        }.maxByOrNull { it.parameterTypes.size } ?: error("此系統未提供相容的代碼還原介面，請重新啟動手機")
        // Restore the saved SPN too: passing null also clears the operator-name property on AOSP.
        val name = backup?.optString("name") ?: info.carrierName?.toString().orEmpty()
        invoke { set.invoke(phone, *CarrierIdentityArguments.build(set.parameterTypes.size, info.subscriptionId, numeric, name)) }
        return IdentityRestoreResult(numeric, requiresRestart = true)
    }

    fun verify(context: Context, info: SubscriptionInfo, numeric: String) {
        val telephony = context.getSystemService(TelephonyManager::class.java)
            .createForSubscriptionId(info.subscriptionId)
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        var stable = 0
        do {
            assertPresent(context, info)
            stable = if (telephony.simOperator == numeric) stable + 1 else 0
            if (stable >= 3) return
            Thread.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("原 SIM 代碼回讀尚未確認")
    }

    fun hasLegacyApns(context: Context, info: SubscriptionInfo): Boolean = key(info)?.let {
        context.getSharedPreferences("apn_override_recovery", Context.MODE_PRIVATE).contains(it)
    } == true

    fun cleanupLegacyApns(context: Context, info: SubscriptionInfo, result: IdentityRestoreResult) {
        val simKey = key(info) ?: error("無法辨識這張 SIM，未清理 APN 項目")
        val backend = LegacyApnAccess(context, info, simKey, checkNotNull(result.expectedNumeric))
        LegacyApnCleanupCoordinator(backend, result.expectedNumeric).cleanup()
    }

    fun completeRestore(context: Context, info: SubscriptionInfo, result: IdentityRestoreResult) {
        if (result.expectedNumeric != null && !result.requiresRestart) {
            key(info)?.let { simKey ->
                check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(simKey).commit()) {
                    "設定已還原，但備份清理失敗"
                }
            }
        }
    }

    private fun assertPresent(context: Context, info: SubscriptionInfo) {
        val active = context.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty()
            .firstOrNull { it.subscriptionId == info.subscriptionId && it.simSlotIndex == info.simSlotIndex }
        check(active != null && active.iccId == info.iccId) { "SIM 已移除或更換，已停止代碼還原" }
    }

    private inline fun <T> invoke(block: () -> T): T = try { block() } catch (error: InvocationTargetException) {
        throw error.targetException
    }
}
