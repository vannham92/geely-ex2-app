package com.geely.ex2.tools.data.sapo.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

/**
 * Base cho 4 widget điều khiển nhanh. Mỗi receiver con chỉ khai báo [kind]; manifest đăng ký
 * từng receiver với label riêng để launcher hiển thị đúng tên trong danh sách widget.
 */
abstract class SapoControlWidgetProvider : AppWidgetProvider() {
    protected abstract val kind: SapoWidgetKind

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        SapoControlWidgetHelper.update(context, kind, "onUpdate")
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            SapoControlWidgetHelper.ACTION_MINUS -> SapoControlWidgetHelper.adjust(context, kind, -1)
            SapoControlWidgetHelper.ACTION_PLUS -> SapoControlWidgetHelper.adjust(context, kind, 1)
        }
    }
}

class SapoHvacTempWidgetProvider : SapoControlWidgetProvider() {
    override val kind = SapoWidgetKind.TEMPERATURE
}

class SapoHvacFanWidgetProvider : SapoControlWidgetProvider() {
    override val kind = SapoWidgetKind.FAN
}

class SapoRegenWidgetProvider : SapoControlWidgetProvider() {
    override val kind = SapoWidgetKind.REGEN
}

class SapoDriveModeWidgetProvider : SapoControlWidgetProvider() {
    override val kind = SapoWidgetKind.DRIVE
}
