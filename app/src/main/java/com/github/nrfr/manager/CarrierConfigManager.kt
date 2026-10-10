package com.github.nrfr.manager

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import com.github.nrfr.model.SimCardInfo
import com.github.nrfr.model.OperatorNumeric
import android.telephony.TelephonyManager
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object CarrierConfigManager {
    data class OperationResult(val persistent: Boolean, val requiresRestart: Boolean = false,
        val warning: String? = null, val restoredNumeric: String? = null)
    suspend fun getSimCards(context: Context): List<SimCardInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return withContext(Dispatchers.IO) {
            CarrierConfigAccess.readSubscriptionsAsShell()
                .filter { it.subscriptionId >= 0 && it.simSlotIndex >= 0 }
                .sortedBy { it.simSlotIndex }
                .map { info ->
                    val telephony = context.getSystemService(TelephonyManager::class.java)?.createForSubscriptionId(info.subscriptionId)
                    SimCardInfo(info.simSlotIndex + 1, info.subscriptionId,
                        info.carrierName?.toString().orEmpty(),
                        CarrierConfigAccess.displayConfig(CarrierConfigAccess.read(context, info.subscriptionId, true)),
                        telephony?.simOperator.orEmpty(), telephony?.networkOperator.orEmpty(),
                        simCountryIso = telephony?.simCountryIso.orEmpty())
                }
        }
        val result = PrivilegedCarrierConfigRunner.run(context,
            PrivilegedCarrierConfigRunner.Request.ReadSimCards)
        @Suppress("DEPRECATION")
        val cards = result.getParcelableArrayList<Bundle>(PrivilegedCarrierConfigRunner.RESULT_SIM_CARDS).orEmpty()
        return cards.map {
            @Suppress("DEPRECATION")
            val config = it.getParcelable<PersistableBundle>("config") ?: PersistableBundle()
            SimCardInfo(it.getInt("slot"), it.getInt("sub_id"), it.getString("carrier_name").orEmpty(),
                CarrierConfigAccess.displayConfig(config), it.getString("sim_operator").orEmpty(),
                it.getString("network_operator").orEmpty(), it.getString("subscription_operator").orEmpty(),
                it.getString("sim_country_iso").orEmpty())
        }
    }

    // Returns whether the system accepted a persistent override.
    suspend fun setCarrierConfig(context: Context, subId: Int, countryCode: String?, carrierName: String? = null,
        operatorNumeric: String? = null): OperationResult {
        val bundle = PersistableBundle()
        if (!countryCode.isNullOrEmpty()) {
            require(countryCode.matches(Regex("[a-zA-Z]{2}"))) { "國家碼須為兩位英文字母" }
            bundle.putString(CarrierConfigAccess.COUNTRY_ISO, countryCode.lowercase(Locale.ROOT))
        }
        if (!carrierName.isNullOrEmpty()) {
            bundle.putBoolean(CarrierConfigAccess.CARRIER_NAME_OVERRIDE, true)
            bundle.putString(CarrierConfigAccess.CARRIER_NAME, carrierName)
        }
        val numeric = operatorNumeric?.let { OperatorNumeric.parse(it).value }
        require(!bundle.isEmpty || numeric != null) { "請先設定國家碼、電信業者名稱或代碼" }
        return overrideCarrierConfig(context, subId, bundle, numeric)
    }

    suspend fun resetCarrierConfig(context: Context, subId: Int): OperationResult =
        overrideCarrierConfig(context, subId, null)

    private suspend fun overrideCarrierConfig(context: Context, subId: Int, bundle: PersistableBundle?,
        operatorNumeric: String? = null): OperationResult {
        require(subId >= 0) { "無效的 SIM 訂閱 ID" }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return withContext(Dispatchers.IO) {
            require(operatorNumeric == null) { "電信業者代碼覆寫需要 Android 10 以上" }
            OperationResult(CarrierOverridePolicy.apply(Build.VERSION.SDK_INT, bundle == null) {
                CarrierConfigAccess.override(subId, bundle, it, asShell = true)
            })
        }
        val result = PrivilegedCarrierConfigRunner.run(context,
            PrivilegedCarrierConfigRunner.Request.Override(subId, bundle?.let { PersistableBundle(it) }, operatorNumeric))
        return OperationResult(result.getBoolean(PrivilegedCarrierConfigRunner.RESULT_PERSISTENT),
            result.getBoolean(PrivilegedCarrierConfigRunner.RESULT_REQUIRES_RESTART),
            result.getString(PrivilegedCarrierConfigRunner.RESULT_WARNING),
            result.getString(PrivilegedCarrierConfigRunner.RESULT_RESTORED_NUMERIC))
    }
}
