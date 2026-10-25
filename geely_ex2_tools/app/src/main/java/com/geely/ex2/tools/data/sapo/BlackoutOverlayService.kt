package com.geely.ex2.tools.data.sapo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.geely.ex2.tools.R

/**
 * "Tela preta": phủ một lớp đen toàn màn hình để tắt màn hình ban đêm mà không sleep máy.
 * Giữ nguyên độ sáng hệ thống; thoát bằng cách giữ 3 giây ở bất kỳ đâu.
 * Không che camera/assist — lớp phủ là cửa sổ riêng nằm trên Activity, nên các app đó vẫn chạy
 * nhưng không nhìn thấy. Nếu cần giữ camera hiển thị thì phải biết vùng camera, chưa làm.
 */
class BlackoutOverlayService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private val dismiss = Runnable { stopSelfCleanly() }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            Log.i(TAG, "Blackout stopped: overlay permission missing")
            stopSelfCleanly()
            return START_NOT_STICKY
        }
        if (overlay == null) attachOverlay()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        overlay?.let { view ->
            try {
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view)
            } catch (_: Throwable) {
                // window đã bị gỡ (process restart) — bỏ qua
            }
        }
        overlay = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun attachOverlay() {
        val view = TextView(this).apply {
            text = getString(R.string.sapo_blackout_hint)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setTextColor(0x66FFFFFF)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            isClickable = true
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        handler.removeCallbacks(dismiss)
                        handler.postDelayed(dismiss, HOLD_TO_EXIT_MS)
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(dismiss)
                }
                true
            }
            // Chỉ xoá chữ gợi ý; giảm alpha cả view sẽ làm lớp đen trong suốt và mất tác dụng.
            postDelayed({ text = "" }, HINT_VISIBLE_MS)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(view, params)
            overlay = view
            Log.i(TAG, "Blackout overlay attached")
        } catch (e: Throwable) {
            Log.w(TAG, "Blackout overlay failed", e)
            stopSelfCleanly()
        }
    }

    private fun stopSelfCleanly() {
        handler.removeCallbacksAndMessages(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.sapo_blackout_channel),
                NotificationManager.IMPORTANCE_MIN,
            ),
        )
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_speed)
            .setContentTitle(getString(R.string.sapo_blackout_notification_title))
            .setContentText(getString(R.string.sapo_blackout_notification_text))
            .setOngoing(true)
            .build()

    companion object {
        private const val TAG = "GeelyToolsBlackout"
        private const val CHANNEL_ID = "sapo_blackout"
        private const val NOTIFICATION_ID = 4781
        private const val HOLD_TO_EXIT_MS = 3_000L
        private const val HINT_VISIBLE_MS = 4_000L
    }
}
