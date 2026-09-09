package com.geely.ex2.tools.data.floater

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import com.geely.ex2.tools.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Foreground service (process `:core`) giữ bubble Floater Quick Access qua [FloaterOverlayContent].
 * Cần quyền overlay (`SYSTEM_ALERT_WINDOW`) — build `system` đã có sẵn theo chữ ký; build `user`
 * phải xin qua [Settings.canDrawOverlays] ở màn Cài đặt Floater trước khi service này khởi động
 * được (nếu chưa cấp, service tự dừng ngay).
 */
class FloaterOverlayService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private var content: FloaterOverlayContent? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val reason = intent?.getStringExtra(EXTRA_REASON) ?: "service start"
        if (!FloaterSettings.isEnabled(this) || !Settings.canDrawOverlays(this)) {
            Log.i(TAG, "Floater service stopping, disabled/no permission: $reason")
            stopSelfCleanly()
            return START_NOT_STICKY
        }
        if (content == null) {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            content = FloaterOverlayContent(this, wm, serviceScope).also { it.attach() }
            Log.i(TAG, "Floater overlay attached ($reason)")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        content?.detach()
        content = null
        serviceScope.cancel()
        Log.i(TAG, "Floater service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopSelfCleanly() {
        content?.detach()
        content = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.floater_channel),
            NotificationManager.IMPORTANCE_MIN,
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
            .setSmallIcon(R.drawable.ic_notification_speed)
            .setContentTitle(getString(R.string.floater_notification_title))
            .setContentText(getString(R.string.floater_notification_text))
            .setOngoing(true)
            .build()
    }

    companion object {
        const val EXTRA_REASON = "reason"
        private const val TAG = "GeelyToolsFloater"
        private const val CHANNEL_ID = "floater_quick_access"
        private const val NOTIFICATION_ID = 4211
    }
}
