package com.github.nrfr.manager

import android.annotation.SuppressLint
import android.content.Context
import android.os.IBinder
import android.os.PersistableBundle
import android.os.ServiceManager
import android.telephony.SubscriptionInfo
import android.telephony.CarrierConfigManager as SystemCarrierConfigManager
import java.lang.reflect.InvocationTargetException
import rikka.shizuku.ShizukuBinderWrapper

internal object CarrierConfigAccess {
    const val COUNTRY_ISO = "sim_country_iso_override_string"
    const val CARRIER_NAME_OVERRIDE = SystemCarrierConfigManager.KEY_CARRIER_NAME_OVERRIDE_BOOL
    const val CARRIER_NAME = SystemCarrierConfigManager.KEY_CARRIER_NAME_STRING

    private fun service(name: String, interfaceName: String, asShell: Boolean): Any {
        val binder = ServiceManager.getService(name)
            ?: error("系统服务不可用: $name")
        return invoke {
            Class.forName("$interfaceName\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, if (asShell) ShizukuBinderWrapper(binder) else binder)
                ?: error("系统接口不可用: $interfaceName")
        }
    }

    fun override(subId: Int, values: PersistableBundle?, persistent: Boolean, asShell: Boolean) {
        require(subId >= 0) { "无效的 SIM 订阅 ID: $subId" }
        val loader = service("carrier_config", "com.android.internal.telephony.ICarrierConfigLoader", asShell)
        val method = try {
            loader.javaClass.getMethod("overrideConfig", Int::class.javaPrimitiveType,
                PersistableBundle::class.java, Boolean::class.javaPrimitiveType)
        } catch (_: NoSuchMethodException) {
            loader.javaClass.getMethod("overrideConfig", Int::class.javaPrimitiveType,
                PersistableBundle::class.java)
        }
        invoke {
            if (method.parameterTypes.size == 3) method.invoke(loader, subId, values, persistent)
            else method.invoke(loader, subId, values)
        }
    }

    // The caller has adopted the scoped phone permissions, or explicitly uses Shizuku.
    @SuppressLint("MissingPermission")
    fun read(context: Context, subId: Int, asShell: Boolean = false): PersistableBundle {
        if (!asShell) {
            return context.getSystemService(SystemCarrierConfigManager::class.java)
                ?.getConfigForSubId(subId) ?: error("无法读取 SIM 配置: $subId")
        }
        val loader = service("carrier_config", "com.android.internal.telephony.ICarrierConfigLoader", true)
        return invoke {
            val method = loader.javaClass.getMethod("getConfigForSubId", Int::class.javaPrimitiveType,
                String::class.java)
            method.invoke(loader, subId, "com.android.shell") as? PersistableBundle
                ?: error("无法读取 SIM 配置: $subId")
        }
    }

    // Legacy Android 8/9 path; modern releases read through delegated app permissions.
    @Suppress("UNCHECKED_CAST")
    fun readSubscriptionsAsShell(): List<SubscriptionInfo> {
        val sub = service("isub", "com.android.internal.telephony.ISub", true)
        val method = sub.javaClass.methods.first {
            it.name == "getActiveSubscriptionInfoList" && it.parameterTypes.size in 1..3
        }
        return invoke {
            val args: Array<Any?> = when (method.parameterTypes.size) {
                1 -> arrayOf("com.android.shell")
                2 -> arrayOf("com.android.shell", null)
                else -> arrayOf("com.android.shell", null, true)
            }
            method.invoke(sub, *args) as? List<SubscriptionInfo> ?: emptyList()
        }
    }

    fun displayConfig(config: PersistableBundle): Map<String, String> = buildMap {
        config.getString(COUNTRY_ISO)?.takeIf { it.isNotEmpty() }?.let { put("国家码", it) }
        if (config.getBoolean(CARRIER_NAME_OVERRIDE, false)) {
            config.getString(CARRIER_NAME)?.let { put("运营商名称", it) }
        }
    }

    private inline fun <T> invoke(call: () -> T): T = try {
        call()
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }
}
