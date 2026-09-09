package com.geely.ex2.tools.data.floater

import android.content.Context
import com.geely.ex2.tools.data.kv.AppKv
import com.tencent.mmkv.MMKV

/** Cấu hình bật/tắt Floater Quick Access + vị trí bubble đã kéo lần cuối (px, theo Gravity TOP|START). */
object FloaterSettings {
    private const val PREFS = "geelytools_floater"
    private const val KEY_ENABLED = "floater_enabled"
    private const val KEY_POS_X = "floater_pos_x"
    private const val KEY_POS_Y = "floater_pos_y"

    private const val NO_POSITION = Int.MIN_VALUE

    /** Mặc định TẮT — chỉ bật khi người dùng chủ động bật trong Cài đặt. */
    fun isEnabled(context: Context): Boolean = kv(context).decodeBool(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        kv(context).encode(KEY_ENABLED, enabled)
    }

    /** null nếu chưa từng kéo — service tự chọn vị trí mặc định (giữa cạnh phải). */
    fun getSavedPosition(context: Context): Pair<Int, Int>? {
        val store = kv(context)
        val x = store.decodeInt(KEY_POS_X, NO_POSITION)
        val y = store.decodeInt(KEY_POS_Y, NO_POSITION)
        if (x == NO_POSITION || y == NO_POSITION) return null
        return x to y
    }

    fun savePosition(context: Context, x: Int, y: Int) {
        val store = kv(context)
        store.encode(KEY_POS_X, x)
        store.encode(KEY_POS_Y, y)
    }

    private fun kv(context: Context): MMKV {
        AppKv.init(context)
        return AppKv.of(PREFS)
    }
}
