package com.github.nrfr.manager

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import com.github.nrfr.model.SimCardInfo
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object CarrierConfigManager {
    suspend fun getSimCards(context: Context): List<SimCardInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return withContext(Dispatchers.IO) {
            CarrierConfigAccess.readSubscriptionsAsShell()
                .filter { it.subscriptionId >= 0 && it.simSlotIndex >= 0 }
                .sortedBy { it.simSlotIndex }
                .map { info ->
                    SimCardInfo(info.simSlotIndex + 1, info.subscriptionId,
                        info.carrierName?.toString().orEmpty(),
                        CarrierConfigAccess.displayConfig(CarrierConfigAccess.read(context, info.subscriptionId, true)))
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
                CarrierConfigAccess.displayConfig(config))
        }
    }

    // Returns whether the system accepted a persistent override.
    suspend fun setCarrierConfig(context: Context, subId: Int, countryCode: String?, carrierName: String? = null): Boolean {
        val bundle = PersistableBundle()
        if (!countryCode.isNullOrEmpty()) {
            require(countryCode.matches(Regex("[a-zA-Z]{2}"))) { "国家码须为两位英文字母" }
            bundle.putString(CarrierConfigAccess.COUNTRY_ISO, countryCode.lowercase(Locale.ROOT))
        }
        if (!carrierName.isNullOrEmpty()) {
            bundle.putBoolean(CarrierConfigAccess.CARRIER_NAME_OVERRIDE, true)
            bundle.putString(CarrierConfigAccess.CARRIER_NAME, carrierName)
        }
        require(!bundle.isEmpty) { "请先选择国家码或运营商名称" }
        return overrideCarrierConfig(context, subId, bundle)
    }

    suspend fun resetCarrierConfig(context: Context, subId: Int): Boolean =
        overrideCarrierConfig(context, subId, null)

    private suspend fun overrideCarrierConfig(context: Context, subId: Int, bundle: PersistableBundle?): Boolean {
        require(subId >= 0) { "无效的 SIM 订阅 ID" }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return withContext(Dispatchers.IO) {
            CarrierOverridePolicy.apply(Build.VERSION.SDK_INT, bundle == null) {
                CarrierConfigAccess.override(subId, bundle, it, asShell = true)
            }
        }
        return PrivilegedCarrierConfigRunner.run(context,
            PrivilegedCarrierConfigRunner.Request.Override(subId, bundle?.let { PersistableBundle(it) }))
            .getBoolean(PrivilegedCarrierConfigRunner.RESULT_PERSISTENT)
    }
}
