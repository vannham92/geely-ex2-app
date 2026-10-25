package com.geely.ex2.tools.data.sapo.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import androidx.annotation.StringRes
import com.geely.ex2.tools.MainActivity
import com.geely.ex2.tools.R
import com.geely.ex2.tools.data.driving.DrivingModeRepository
import com.geely.ex2.tools.data.vhal.CarPropertyHvacController
import com.geely.ex2.tools.data.vhal.CarPropertyIo
import com.geely.ex2.tools.data.vhal.DrivingMode
import com.geely.ex2.tools.data.vhal.EnergyRegeneration
import com.geely.ex2.tools.data.vhal.HvacSample

/** Bốn widget dùng chung một layout; khác nhau ở nguồn đọc và cặp nút +/-. */
enum class SapoWidgetKind(
    @StringRes val labelRes: Int,
    val providerClass: Class<out AppWidgetProvider>,
) {
    TEMPERATURE(R.string.sapo_widget_temperature, SapoHvacTempWidgetProvider::class.java),
    FAN(R.string.sapo_widget_fan, SapoHvacFanWidgetProvider::class.java),
    REGEN(R.string.sapo_widget_regen, SapoRegenWidgetProvider::class.java),
    DRIVE(R.string.sapo_widget_drive, SapoDriveModeWidgetProvider::class.java),
}

object SapoControlWidgetHelper {
    const val ACTION_MINUS = "com.geely.ex2.tools.action.SAPO_WIDGET_MINUS"
    const val ACTION_PLUS = "com.geely.ex2.tools.action.SAPO_WIDGET_PLUS"

    fun updateAll(context: Context, reason: String) {
        CarPropertyIo.execute {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext) ?: return@execute
            val idsByKind = SapoWidgetKind.entries.associateWith {
                manager.getAppWidgetIds(ComponentName(appContext, it.providerClass))
            }
            if (idsByKind.values.all { it.isEmpty() }) return@execute
            idsByKind.forEach { (kind, widgetIds) ->
                val views = build(appContext, kind)
                widgetIds.forEach { manager.updateAppWidget(it, views) }
            }
            Log.i(TAG, "Sapo widgets updated ($reason)")
        }
    }

    fun update(context: Context, kind: SapoWidgetKind, reason: String) {
        CarPropertyIo.execute {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext) ?: return@execute
            val widgetIds = manager.getAppWidgetIds(ComponentName(appContext, kind.providerClass))
            if (widgetIds.isEmpty()) return@execute
            val views = build(appContext, kind)
            widgetIds.forEach { manager.updateAppWidget(it, views) }
            Log.i(TAG, "${kind.name} widget updated ($reason)")
        }
    }

    fun adjust(context: Context, kind: SapoWidgetKind, delta: Int) {
        CarPropertyIo.execute {
            val appContext = context.applicationContext
            when (kind) {
                SapoWidgetKind.TEMPERATURE -> adjustTemperature(appContext, delta)
                SapoWidgetKind.FAN -> adjustFan(appContext, delta)
                SapoWidgetKind.REGEN -> adjustRegen(appContext, delta)
                SapoWidgetKind.DRIVE -> adjustDrive(appContext, delta)
            }
            update(appContext, kind, "button ${if (delta > 0) "+" else "-"}")
        }
    }

    private fun build(context: Context, kind: SapoWidgetKind): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_sapo_control)
        views.setTextViewText(R.id.widget_sapo_value, stateText(context, kind))
        views.setTextViewText(R.id.widget_sapo_label, context.getString(kind.labelRes))
        views.setContentDescription(R.id.widget_sapo_minus, context.getString(R.string.sapo_widget_minus))
        views.setContentDescription(R.id.widget_sapo_plus, context.getString(R.string.sapo_widget_plus))
        views.setOnClickPendingIntent(
            R.id.widget_sapo_minus,
            broadcast(context, kind, ACTION_MINUS, REQUEST_MINUS),
        )
        views.setOnClickPendingIntent(
            R.id.widget_sapo_plus,
            broadcast(context, kind, ACTION_PLUS, REQUEST_PLUS),
        )
        views.setOnClickPendingIntent(R.id.widget_sapo_root, openApp(context, kind))
        return views
    }

    private fun stateText(context: Context, kind: SapoWidgetKind): String {
        val unavailable = context.getString(R.string.sapo_widget_unavailable)
        return when (kind) {
            SapoWidgetKind.TEMPERATURE -> {
                val sample = readHvac(context)
                if (sample.available && sample.tempC in MIN_TEMP_C..MAX_TEMP_C) {
                    context.getString(R.string.sapo_widget_temp_value, sample.tempC)
                } else {
                    unavailable
                }
            }

            SapoWidgetKind.FAN -> {
                val sample = readHvac(context)
                if (sample.available) {
                    context.getString(R.string.sapo_widget_fan_value, sample.fanSpeed)
                } else {
                    unavailable
                }
            }

            SapoWidgetKind.REGEN -> {
                val sample = DrivingModeRepository(context).readEnergyRegeneration()
                val labelRes = if (sample.isAvailable) EnergyRegeneration.labelResFor(sample.levelValue) else null
                if (labelRes != null) context.getString(labelRes) else unavailable
            }

            SapoWidgetKind.DRIVE -> {
                val sample = DrivingModeRepository(context).readDrivingMode()
                val labelRes = if (sample.isAvailable) DrivingMode.labelResFor(sample.modeValue) else null
                if (labelRes != null) context.getString(labelRes) else unavailable
            }
        }
    }

    private fun adjustTemperature(context: Context, delta: Int) {
        val sample = readHvac(context)
        if (!sample.available) return
        val current = if (sample.tempC in MIN_TEMP_C..MAX_TEMP_C) sample.tempC else DEFAULT_TEMP_C
        val next = (current + delta * TEMP_STEP_C).coerceIn(MIN_TEMP_C, MAX_TEMP_C)
        writeHvac(context) { it.setTemperature(next) }
    }

    private fun adjustFan(context: Context, delta: Int) {
        val sample = readHvac(context)
        if (!sample.available) return
        val next = (sample.fanSpeed + delta).coerceIn(0, MAX_FAN_LEVEL)
        writeHvac(context) { it.setFanSpeed(next) }
    }

    private fun adjustRegen(context: Context, delta: Int) {
        val repository = DrivingModeRepository(context)
        val current = repository.readEnergyRegeneration()
        if (!current.isAvailable) return
        val options = EnergyRegeneration.selectable
        if (options.isEmpty()) return
        val index = EnergyRegeneration.selectableIndexFor(current.levelValue)
        val nextIndex = if (index < 0) 0 else (index + delta + options.size) % options.size
        repository.setEnergyRegeneration(options[nextIndex].vhalValue)
    }

    private fun adjustDrive(context: Context, delta: Int) {
        val repository = DrivingModeRepository(context)
        val current = repository.readDrivingMode()
        if (!current.isAvailable) return
        val options = DrivingMode.selectable
        if (options.isEmpty()) return
        val index = DrivingMode.selectableIndexFor(current.modeValue)
        val nextIndex = if (index < 0) 0 else (index + delta + options.size) % options.size
        repository.setDrivingMode(options[nextIndex].vhalValue)
    }

    private fun readHvac(context: Context): HvacSample {
        val controller = CarPropertyHvacController(context)
        return try {
            controller.read()
        } finally {
            controller.close()
        }
    }

    private fun writeHvac(context: Context, block: (CarPropertyHvacController) -> Unit) {
        val controller = CarPropertyHvacController(context)
        try {
            block(controller)
        } finally {
            controller.close()
        }
    }

    private fun broadcast(context: Context, kind: SapoWidgetKind, action: String, offset: Int): PendingIntent {
        val intent = Intent(context, kind.providerClass).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            kind.ordinal * REQUEST_STRIDE + offset,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openApp(context: Context, kind: SapoWidgetKind): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context,
            kind.ordinal,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // Giới hạn theo dải thường dùng của EX2; ECU sẽ từ chối giá trị ngoài dải thật.
    private const val MIN_TEMP_C = 16f
    private const val MAX_TEMP_C = 30f
    private const val TEMP_STEP_C = 0.5f
    private const val DEFAULT_TEMP_C = 22f
    private const val MAX_FAN_LEVEL = 7
    private const val REQUEST_MINUS = 1
    private const val REQUEST_PLUS = 2
    private const val REQUEST_STRIDE = 10
    private const val TAG = "GeelyToolsSapoWidget"
}
