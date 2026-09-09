package com.geely.ex2.tools.data.floater

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.core.view.isVisible
import com.geely.ex2.tools.MainActivity
import com.geely.ex2.tools.R
import com.geely.ex2.tools.data.avas.AvasRepository
import com.geely.ex2.tools.data.driving.DrivingModeRepository
import com.geely.ex2.tools.data.vhal.DrivingMode
import com.geely.ex2.tools.data.vhal.VhalBatteryReaderFactory
import com.geely.ex2.tools.data.vhal.VhalSpeedReaderFactory
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bubble nổi (kéo-thả được) + panel Quick Access khi bấm vào bubble.
 * Dùng View thường (không Compose) — theo đúng cách [com.geely.ex2.tools.data.network.CarLinkToast]
 * đã dùng cho overlay: an toàn hơn khi add trực tiếp qua WindowManager từ Service (không có
 * Activity/LifecycleOwner phía sau).
 *
 * Chỉ 1 root view được add vào WindowManager; nội dung đổi qua lại giữa [collapsedView] (bubble
 * tròn) và [expandedView] (panel) — tránh phải quản lý 2 window riêng.
 */
class FloaterOverlayContent(
    private val context: Context,
    private val windowManager: WindowManager,
    private val serviceScope: CoroutineScope,
) {
    private val avasRepository = AvasRepository(context)
    private val drivingRepository = DrivingModeRepository(context)
    private val speedReader = VhalSpeedReaderFactory.create(context)
    private val batteryReader = VhalBatteryReaderFactory.create(context)

    private val mainHandler = Handler(Looper.getMainLooper())

    private var isExpanded = false
    private var isUpdatingAvasSwitch = false
    private var isAttached = false

    private lateinit var root: FrameLayout
    private lateinit var collapsedView: View
    private lateinit var expandedView: View
    private lateinit var drivingModeValueText: TextView
    private lateinit var avasSwitch: Switch
    private lateinit var telemetryText: TextView

    private lateinit var params: WindowManager.LayoutParams

    private val telemetryTick = object : Runnable {
        override fun run() {
            if (!isExpanded) return
            refreshTelemetry()
            mainHandler.postDelayed(this, TELEMETRY_INTERVAL_MS)
        }
    }

    fun attach() {
        if (isAttached) return
        root = buildRoot()
        params = buildLayoutParams()
        try {
            windowManager.addView(root, params)
            isAttached = true
        } catch (_: Throwable) {
            isAttached = false
        }
    }

    fun detach() {
        mainHandler.removeCallbacksAndMessages(null)
        if (isAttached) {
            try {
                windowManager.removeView(root)
            } catch (_: Throwable) {
                // window đã bị gỡ (process restart) — bỏ qua
            }
        }
        isAttached = false
        speedReader.close()
        batteryReader.close()
        avasRepository.close()
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val saved = FloaterSettings.getSavedPosition(context)
        val metrics = context.resources.displayMetrics
        val defaultX = 0
        val defaultY = metrics.heightPixels / 3
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = saved?.first ?: defaultX
            y = saved?.second ?: defaultY
        }
    }

    private fun buildRoot(): FrameLayout {
        val frame = FrameLayout(context)
        collapsedView = buildCollapsedView()
        expandedView = buildExpandedView()
        frame.addView(collapsedView)
        frame.addView(expandedView)
        expandedView.isVisible = false
        return frame
    }

    // -------------------------------------------------------------- Collapsed (bubble)

    private fun buildCollapsedView(): View {
        val size = dp(56)
        return TextView(context).apply {
            text = context.getString(R.string.floater_bubble_label)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = FrameLayout.LayoutParams(size, size)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE6202225.toInt())
            }
            elevation = dp(8).toFloat()
            setOnTouchListener(dragListener())
        }
    }

    private fun dragListener(): View.OnTouchListener {
        var startParamX = 0
        var startParamY = 0
        var startTouchX = 0f
        var startTouchY = 0f
        var moved = false

        return View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startParamX = params.x
                    startParamY = params.y
                    startTouchX = event.rawX
                    startTouchY = event.rawY
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startTouchX).toInt()
                    val dy = (event.rawY - startTouchY).toInt()
                    if (!moved && (abs(dx) > TOUCH_SLOP_PX || abs(dy) > TOUCH_SLOP_PX)) {
                        moved = true
                    }
                    if (moved) {
                        params.x = clamp(startParamX + dx, horizontal = true)
                        params.y = clamp(startParamY + dy, horizontal = false)
                        safeUpdateLayout()
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moved) {
                        FloaterSettings.savePosition(context, params.x, params.y)
                    } else {
                        toggleExpanded()
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun clamp(value: Int, horizontal: Boolean): Int {
        val metrics = context.resources.displayMetrics
        val max = if (horizontal) metrics.widthPixels - dp(56) else metrics.heightPixels - dp(56)
        return value.coerceIn(0, max.coerceAtLeast(0))
    }

    private fun safeUpdateLayout() {
        if (!isAttached) return
        try {
            windowManager.updateViewLayout(root, params)
        } catch (_: Throwable) {
            // window đã bị gỡ giữa chừng — bỏ qua
        }
    }

    // -------------------------------------------------------------- Expanded (panel)

    private fun buildExpandedView(): View {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(dp(220), FrameLayout.LayoutParams.WRAP_CONTENT)
            val pad = dp(14)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0xF2202225.toInt())
            }
            elevation = dp(8).toFloat()
        }

        panel.addView(headerRow())
        panel.addView(divider())
        panel.addView(drivingModeRow())
        panel.addView(avasRow())
        panel.addView(telemetryRow())
        panel.addView(openAppButton())
        return panel
    }

    private fun headerRow(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val title = TextView(context).apply {
            text = context.getString(R.string.floater_panel_title)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val close = TextView(context).apply {
            text = "\u2715"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(8), dp(4), dp(4), dp(4))
            setOnClickListener { collapse() }
        }
        addView(title)
        addView(close)
    }

    private fun divider(): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(10)
            bottomMargin = dp(10)
        }
        setBackgroundColor(0x33FFFFFF)
    }

    private fun drivingModeRow(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(10) }
        val label = TextView(context).apply {
            text = context.getString(R.string.floater_driving_mode_label)
            setTextColor(0xFFB0B3B8.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        drivingModeValueText = TextView(context).apply {
            text = context.getString(R.string.floater_value_unavailable)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(0x33FFFFFF)
            }
        }
        setOnClickListener { cycleDrivingMode() }
        addView(label)
        addView(drivingModeValueText)
    }

    private fun avasRow(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(10) }
        val label = TextView(context).apply {
            text = context.getString(R.string.floater_avas_label)
            setTextColor(0xFFB0B3B8.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        avasSwitch = Switch(context).apply {
            setOnCheckedChangeListener { _, checked ->
                if (isUpdatingAvasSwitch) return@setOnCheckedChangeListener
                setAvasMuted(checked)
            }
        }
        addView(label)
        addView(avasSwitch)
    }

    private fun telemetryRow(): View {
        telemetryText = TextView(context).apply {
            text = context.getString(
                R.string.floater_telemetry_format,
                context.getString(R.string.floater_value_unavailable),
                context.getString(R.string.floater_value_unavailable),
            )
            setTextColor(0xFFB0B3B8.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
        }
        return telemetryText
    }

    private fun openAppButton(): View = TextView(context).apply {
        text = context.getString(R.string.floater_open_app)
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(0xFF3A7DFF.toInt())
        }
        setOnClickListener {
            collapse()
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
        }
    }

    // -------------------------------------------------------------- Expand/collapse

    private fun toggleExpanded() {
        if (isExpanded) collapse() else expand()
    }

    private fun expand() {
        isExpanded = true
        collapsedView.isVisible = false
        expandedView.isVisible = true
        params.x = clamp(params.x, horizontal = true)
        params.y = clamp(params.y, horizontal = false)
        safeUpdateLayout()
        refreshDrivingMode()
        refreshAvas()
        mainHandler.removeCallbacks(telemetryTick)
        mainHandler.post(telemetryTick)
    }

    private fun collapse() {
        isExpanded = false
        expandedView.isVisible = false
        collapsedView.isVisible = true
        mainHandler.removeCallbacks(telemetryTick)
        safeUpdateLayout()
    }

    // -------------------------------------------------------------- Data refresh

    private fun refreshDrivingMode() {
        serviceScope.launch {
            val sample = withContext(Dispatchers.IO) { drivingRepository.readDrivingMode() }
            val labelRes = DrivingMode.labelResFor(sample.modeValue)
            drivingModeValueText.text = if (sample.isAvailable && labelRes != null) {
                context.getString(labelRes)
            } else {
                context.getString(R.string.floater_value_unavailable)
            }
        }
    }

    private fun cycleDrivingMode() {
        serviceScope.launch {
            val current = withContext(Dispatchers.IO) { drivingRepository.readDrivingMode() }
            val options = DrivingMode.selectable
            if (options.isEmpty()) return@launch
            val currentIndex = DrivingMode.selectableIndexFor(current.modeValue)
            val nextOption = options[if (currentIndex < 0) 0 else (currentIndex + 1) % options.size]
            val result = withContext(Dispatchers.IO) { drivingRepository.setDrivingMode(nextOption.vhalValue) }
            drivingModeValueText.text = if (result.ok) {
                context.getString(nextOption.labelRes)
            } else {
                context.getString(R.string.floater_value_unavailable)
            }
        }
    }

    private fun refreshAvas() {
        serviceScope.launch {
            val sample = withContext(Dispatchers.IO) { avasRepository.readAvas() }
            isUpdatingAvasSwitch = true
            avasSwitch.isChecked = sample.isMuted
            avasSwitch.isEnabled = sample.isAvailable
            isUpdatingAvasSwitch = false
        }
    }

    private fun setAvasMuted(muted: Boolean) {
        serviceScope.launch {
            withContext(Dispatchers.IO) { avasRepository.setMuted(muted) }
        }
    }

    private fun refreshTelemetry() {
        serviceScope.launch {
            val speed = withContext(Dispatchers.IO) { speedReader.readSpeed() }
            val battery = withContext(Dispatchers.IO) { batteryReader.readBatterySoc() }
            val speedText = if (speed.isAvailable) {
                context.getString(R.string.floater_speed_value, speed.speedKmh.toInt())
            } else {
                context.getString(R.string.floater_value_unavailable)
            }
            val batteryText = if (battery.isAvailable) {
                context.getString(R.string.floater_battery_value, battery.socPercent.toInt())
            } else {
                context.getString(R.string.floater_value_unavailable)
            }
            telemetryText.text = context.getString(R.string.floater_telemetry_format, speedText, batteryText)
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    companion object {
        private const val TELEMETRY_INTERVAL_MS = 2_000L
        private const val TOUCH_SLOP_PX = 12
    }
}
