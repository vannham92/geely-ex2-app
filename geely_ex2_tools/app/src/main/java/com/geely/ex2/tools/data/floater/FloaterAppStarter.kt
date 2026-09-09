package com.geely.ex2.tools.data.floater

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat

/** Start/stop [FloaterOverlayService] — gọi từ Settings screen, boot receiver, và app starter chung. */
object FloaterAppStarter {
    private const val TAG = "GeelyToolsFloater"

    fun hasOverlayPermission(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** Không tự xin quyền ở đây — UI (FloaterSettingsScreen) chịu trách nhiệm điều hướng người dùng
     * sang màn cấp quyền overlay hệ thống trước khi gọi hàm này. */
    fun startServiceIfEnabled(context: Context, reason: String) {
        val appContext = context.applicationContext
        if (!FloaterSettings.isEnabled(appContext)) return
        if (!hasOverlayPermission(appContext)) {
            Log.i(TAG, "Chưa có quyền overlay, không khởi động Floater ($reason)")
            return
        }
        val intent = Intent(appContext, FloaterOverlayService::class.java).apply {
            putExtra(FloaterOverlayService.EXTRA_REASON, reason)
        }
        try {
            ContextCompat.startForegroundService(appContext, intent)
        } catch (t: Throwable) {
            Log.w(TAG, "Không khởi động được FloaterOverlayService ($reason)", t)
        }
    }

    fun stopService(context: Context, reason: String) {
        val appContext = context.applicationContext
        Log.i(TAG, "Dừng Floater service ($reason)")
        appContext.stopService(Intent(appContext, FloaterOverlayService::class.java))
    }
}
