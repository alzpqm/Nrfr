package com.github.nrfr.manager

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.ims.ImsManager
import android.telephony.ims.RegistrationManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

/** A bounded, read-only MMTEL snapshot under the selected subscription's delegated permissions. */
internal object ImsRegistrationAccess {
    data class Snapshot(val state: Int = -1, val transport: Int = -1)

    @SuppressLint("MissingPermission")
    fun read(context: Context, subId: Int): Snapshot {
        if (Build.VERSION.SDK_INT < 30) return Snapshot()
        val ims = try {
            context.getSystemService(ImsManager::class.java)?.getImsMmTelManager(subId)
        } catch (_: IllegalArgumentException) {
            null
        } ?: return Snapshot()
        val state = readValue { executor, callback -> ims.getRegistrationState(executor, callback) }
        val transport = if (state == RegistrationManager.REGISTRATION_STATE_REGISTERED) {
            readValue { executor, callback -> ims.getRegistrationTransportType(executor, callback) }
        } else -1
        return Snapshot(state, transport)
    }

    private fun readValue(request: (Executor, Consumer<Int>) -> Unit): Int {
        val received = CountDownLatch(1)
        var value = -1
        return try {
            request(Executor { it.run() }, Consumer { value = it; received.countDown() })
            if (received.await(1, TimeUnit.SECONDS)) value else -1
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            -1
        } catch (_: Exception) {
            -1
        }
    }
}
