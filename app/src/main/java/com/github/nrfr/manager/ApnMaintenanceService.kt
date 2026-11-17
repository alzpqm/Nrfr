package com.github.nrfr.manager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.github.nrfr.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/** Receives APN saves while the user edits in Samsung Settings. No periodic APN writes. */
class ApnMaintenanceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var dirty = false
    private var working = false
    private var events = 0
    private var reconciliations = 0
    private var changes = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "SIM APN 維護", NotificationManager.IMPORTANCE_LOW))
        showNotification("修改 SIM 代碼期間，保留實際網路的 APN；請勿強制停止 Nrfr。")
    }

    private fun showNotification(message: String) {
        val intent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(41, Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("Nrfr：SIM APN 維護中")
            .setContentText(message).setContentIntent(intent).setOngoing(true).build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        schedule()
        return START_STICKY
    }

    private fun schedule() {
        dirty = true
        if (working) return
        working = true
        scope.launch {
            try {
                while (dirty) {
                    dirty = false
                    delay(250)
                    if (!SamsungApnCarrierBinding.hasAnyBackup(this@ApnMaintenanceService)) {
                        stopSelf()
                        break
                    }
                    try {
                        val result = PrivilegedCarrierConfigRunner.run(this@ApnMaintenanceService,
                            PrivilegedCarrierConfigRunner.Request.ReconcileApns)
                        reconciliations++
                        changes += result.getInt("apn_changes")
                        writeStatus("ok")
                    } catch (failure: Exception) {
                        writeStatus("failed", failure)
                        showNotification("APN 維護未完成，請開啟 Nrfr 核對 Nightzuku／Shizuku 或還原設定。")
                    }
                }
            } finally { working = false }
        }
    }

    private fun writeStatus(status: String, failure: Exception? = null) {
        if (!packageName.endsWith(".s25")) return
        val directory = getExternalFilesDir("apn-research") ?: return
        File(directory, "maintenance.json").writeText(JSONObject().put("status", status)
            .put("events", events).put("reconciliations", reconciliations).put("changes", changes)
            .put("errorClass", failure?.javaClass?.simpleName).put("error", failure?.message).toString())
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        if (observer != null) {
            contentResolver.unregisterContentObserver(checkNotNull(observer))
            observer = null
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "sim_apn_maintenance"
        private var instance: ApnMaintenanceService? = null
        private var observer: ContentObserver? = null

        // Register while the instrumentation holds WRITE_APN_SETTINGS. Subsequent writes use
        // fresh, serialized delegation; the service never keeps shell permissions adopted.
        internal fun ensureWatching(context: Context) {
            if (observer != null) return
            val callback = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    instance?.let { it.events++; it.schedule() }
                }
            }
            context.contentResolver.registerContentObserver(Uri.parse("content://telephony/carriers"), true, callback)
            observer = callback
        }

        internal fun refresh(context: Context) {
            if (SamsungApnCarrierBinding.hasAnyBackup(context)) {
                ensureWatching(context)
                context.startForegroundService(Intent(context, ApnMaintenanceService::class.java))
            } else context.stopService(Intent(context, ApnMaintenanceService::class.java))
        }
    }
}
