package app.qingjian.inputmethod.scan

import android.content.Context

/** 扫码模式与提交行为：独立于键盘外观设置，所有页面和扫码 Activity 共用。 */
object ScanPreferences {
    const val PREFS = "scan_settings"
    const val KEY_MODE = "scan_mode"
    const val MODE_FULL = "full"
    const val MODE_FLOATING = "floating"
    const val KEY_AUTO_ENTER = "scan_auto_enter"
    const val KEY_VIBRATE = "scan_vibrate"
    const val KEY_PREFIX = "scan_barcode_prefix"
    const val KEY_PREFIX_ENABLED = "scan_barcode_prefix_enabled"
    const val ACTION_RESULT = "app.qingjian.inputmethod.action.SCAN_RESULT"
    const val EXTRA_TEXT = "scan_text"
    const val EXTRA_ENTER = "scan_enter"

    fun isFloating(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_MODE, MODE_FULL) == MODE_FLOATING

    fun autoEnter(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_AUTO_ENTER, false)

    fun vibrate(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_VIBRATE, true)

    fun prefix(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_PREFIX, "").orEmpty().trim()

    /** 已有前缀默认保持启用；关闭开关时临时扫描全部，不清除已输入的前缀。 */
    fun prefixEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_PREFIX_ENABLED, prefs.getString(KEY_PREFIX, "").orEmpty().trim().isNotEmpty())
    }

    /** 空前缀或关闭过滤时接受全部条码；开启后只接受指定前缀。 */
    fun accepts(context: Context, value: String): Boolean {
        val prefix = prefix(context)
        return !prefixEnabled(context) || prefix.isEmpty() || value.startsWith(prefix)
    }
}
