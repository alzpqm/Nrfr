package com.github.nrfr.manager

import android.os.Binder
import android.os.Process
import androidx.annotation.Keep

/** Nightzuku-owned shell process, exposing only this app's fixed file operations. */
@Keep
class RecoveryUserService @Keep constructor() : IRecoveryStore.Stub() {
    private var caller = -1

    @Synchronized
    private fun checkCaller() {
        val uid = Binder.getCallingUid()
        if (caller == -1) caller = uid
        check(caller == uid) { "Recovery caller changed" }
    }

    override fun uid(): Int {
        checkCaller()
        return Process.myUid()
    }

    override fun exchange(scope: String, operation: String, input: ByteArray): ByteArray {
        checkCaller()
        return RecoveryShellMain.exchange(scope, operation, input)
    }

    override fun destroy() { kotlin.system.exitProcess(0) }
}
