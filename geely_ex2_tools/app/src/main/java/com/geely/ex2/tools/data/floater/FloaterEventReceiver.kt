package com.geely.ex2.tools.data.floater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Khởi động lại Floater sau khi xe/màn hình boot hoặc app được cập nhật, nếu đang bật. */
class FloaterEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        FloaterAppStarter.startServiceIfEnabled(context, "receiver: ${intent.action}")
    }
}
