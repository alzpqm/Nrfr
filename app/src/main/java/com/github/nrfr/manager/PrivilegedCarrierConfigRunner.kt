package com.github.nrfr.manager

import android.app.Activity
import android.app.IActivityManager
import android.app.IInstrumentationWatcher
import android.app.UiAutomationConnection
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PersistableBundle
import android.os.Process
import android.os.ServiceManager
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper

internal object PrivilegedCarrierConfigRunner {
    const val ARG_TOKEN = "request_token"
    const val ARG_PID = "caller_pid"
    const val RESULT_SUCCESS = "success"
    const val RESULT_ERROR = "error"
    const val RESULT_PERSISTENT = "persistent"
    const val RESULT_SIM_CARDS = "sim_cards"
    const val RESULT_REQUIRES_RESTART = "requires_restart"
    const val RESULT_WARNING = "warning"
    const val RESULT_RESTORED_NUMERIC = "restored_numeric"
    private const val TIMEOUT_MS = 30_000L
    private const val INSTR_FLAG_NO_RESTART = 8

    sealed interface Request {
        data object ReadSimCards : Request
        data class Override(val subId: Int, val values: PersistableBundle?, val operatorNumeric: String? = null) : Request
    }

    private val requests = PendingRequestStore<Request>(TIMEOUT_MS, SystemClock::elapsedRealtime)
    private val mutex = Mutex()
    private var activeResult: CompletableDeferred<Bundle>? = null

    fun takeRequest(arguments: Bundle?): Request? {
        if (arguments?.getInt(ARG_PID, -1) != Process.myPid()) return null
        val token = arguments.getString(ARG_TOKEN) ?: return null
        return requests.take(token, Process.myPid())
    }

    fun activityManager(): IActivityManager {
        val binder = ServiceManager.getService(Context.ACTIVITY_SERVICE)
            ?: error("ActivityManager 服務不可用")
        return IActivityManager.Stub.asInterface(ShizukuBinderWrapper(binder))
    }

    suspend fun run(context: Context, request: Request): Bundle = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(Shizuku.pingBinder()) { "Shizuku 未執行" }
            check(Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                "請重新授予 Shizuku 權限"
            }
            check(activeResult?.isCompleted != false) {
                "上一次操作仍未結束，請稍後重試或重新啟動應用程式"
            }
            val token = requests.add(Process.myPid(), request)
            val component = ComponentName(context, CarrierConfigInstrumentation::class.java)
            val result = CompletableDeferred<Bundle>()
            val watcher = object : IInstrumentationWatcher.Stub() {
                override fun instrumentationStatus(name: ComponentName?, code: Int, results: Bundle?) {}

                override fun instrumentationFinished(name: ComponentName?, code: Int, results: Bundle?) {
                    if (name == component) {
                        if (code == Activity.RESULT_OK && results != null) result.complete(results)
                        else result.completeExceptionally(IllegalStateException("設定服務未正常結束"))
                    }
                }
            }
            var started = false
            try {
                activeResult = result
                val arguments = Bundle().apply {
                    putString(ARG_TOKEN, token)
                    putInt(ARG_PID, Process.myPid())
                }
                started = activityManager().startInstrumentation(
                    component, null, INSTR_FLAG_NO_RESTART, arguments, watcher,
                    UiAutomationConnection(), Process.myUid() / 100_000, null,
                )
                check(started) { "系統拒絕啟動設定服務" }
                val reply = withTimeoutOrNull(TIMEOUT_MS) { result.await() }
                    ?: error("設定操作逾時，尚未確認生效")
                check(reply.getBoolean(RESULT_SUCCESS, false)) {
                    reply.getString(RESULT_ERROR) ?: "設定操作失敗"
                }
                reply
            } catch (error: CancellationException) {
                // Keep serialization until the instrumentation releases its delegation.
                if (started) withContext(NonCancellable) {
                    withTimeoutOrNull(TIMEOUT_MS) { runCatching { result.await() } }
                }
                throw error
            } finally {
                requests.remove(token)
                if (!started) activeResult = null
                // Keep an unfinished result after timeout to refuse overlapping delegations.
            }
        }
    }
}
