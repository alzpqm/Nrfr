package com.github.nrfr.manager

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.os.IBinder
import android.os.ServiceManager
import android.telephony.SubscriptionInfo
import android.telephony.TelephonyManager
import com.github.nrfr.model.OperatorNumeric
import java.lang.reflect.InvocationTargetException
import java.security.MessageDigest
import org.json.JSONObject

// Only called inside the instrumentation's scoped phone-permission delegation.
@TargetApi(29)
@SuppressLint("MissingPermission")
internal object CarrierIdentityAccess {
    private const val PREFS = "carrier_identity_originals"
    data class RestoreResult(val expectedNumeric: String?, val fullyCleared: Boolean = true)
    private fun service(): Any {
        val binder = ServiceManager.getService(Context.TELEPHONY_SERVICE) ?: error("電話服務無法使用")
        return Class.forName("com.android.internal.telephony.ITelephony\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder) ?: error("電話服務介面無法使用")
    }
    private fun key(info: SubscriptionInfo): String {
        // Store a fingerprint, never a raw ICCID or IMSI.
        val identity = info.iccId
        check(!identity.isNullOrEmpty()) { "無法辨識這張 SIM，尚未執行代碼覆寫或還原；請確認 SIM 已就緒" }
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    private fun set(phone: Any, subId: Int, numeric: String, spn: String?) {
        val method = phone.javaClass.methods.filter {
            it.name == "setCarrierTestOverride" && it.parameterTypes.size in listOf(8, 10) &&
                it.parameterTypes.first() == Int::class.javaPrimitiveType &&
                it.parameterTypes.drop(1).all { type -> type == String::class.java }
        }.maxByOrNull { it.parameterTypes.size } ?: error("此系統不支援電信業者代碼覆寫")
        invoke { method.invoke(phone, *CarrierIdentityArguments.build(method.parameterTypes.size, subId, numeric, spn)) }
    }
    fun apply(context: Context, info: SubscriptionInfo, numeric: String, displayName: String?) {
        val code = OperatorNumeric.parse(numeric).value
        val telephony = (context.getSystemService(TelephonyManager::class.java) ?: error("電話服務無法使用"))
            .createForSubscriptionId(info.subscriptionId)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = key(info)
        if (!prefs.contains(key)) {
            val original = telephony.simOperator.orEmpty()
            check(OperatorNumeric.isValid(original)) { "無法讀取原本的電信業者代碼，尚未執行覆寫；請確認 SIM 已就緒" }
            val backup = JSONObject().put("numeric", original).put("name", telephony.simOperatorName.orEmpty())
            check(prefs.edit().putString(key, backup.toString()).commit()) { "無法備份原本的電信設定，尚未執行覆寫" }
        }
        set(service(), info.subscriptionId, code, displayName ?: telephony.simOperatorName)
    }
    fun restore(context: Context, info: SubscriptionInfo): RestoreResult {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.all.isEmpty()) return RestoreResult(null)
        val backup = prefs.getString(key(info), null) ?: return RestoreResult(null)
        val original = JSONObject(backup)
        val numeric = OperatorNumeric.parse(original.getString("numeric")).value
        val phone = service()
        val clear = phone.javaClass.methods.firstOrNull {
            it.name == "clearCarrierTestOverride" && it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType
        }
        if (clear != null) {
            invoke { clear.invoke(phone, info.subscriptionId) }
            return RestoreResult(numeric)
        }
        // The fallback restores the reported code; a reboot/SIM reload exits test mode.
        set(phone, info.subscriptionId, numeric, original.getString("name"))
        return RestoreResult(numeric, fullyCleared = false)
    }
    fun completeRestore(context: Context, info: SubscriptionInfo, result: RestoreResult) {
        if (result.expectedNumeric != null && result.fullyCleared) {
            check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(key(info)).commit()) {
                "設定已還原，但備份清理失敗，請重新讀取目前設定"
            }
        }
    }
    private inline fun <T> invoke(block: () -> T): T = try { block() } catch (error: InvocationTargetException) {
        throw error.targetException
    }
}
