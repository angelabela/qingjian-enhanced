package app.qingjian.inputmethod.settings.page

import android.view.View
import app.qingjian.inputmethod.R
import app.qingjian.inputmethod.scan.ScanPreferences

/** 扫码模式、自动回车与成功震动的独立设置页。 */
class ScanSettingsActivity : PageActivity() {
    override val title = R.string.page_scan_settings

    override fun content(): List<View> {
        val prefs = getSharedPreferences(ScanPreferences.PREFS, MODE_PRIVATE)
        val mode = prefs.getString(ScanPreferences.KEY_MODE, ScanPreferences.MODE_FULL)
        val rows = listOf(
            ui.input(
                "指定扫码前缀",
                prefs.getString(ScanPreferences.KEY_PREFIX, "").orEmpty(),
                "留空表示接受所有二维码和条形码",
                secret = false,
            ) { value ->
                prefs.edit().putString(ScanPreferences.KEY_PREFIX, value.trim()).apply()
            },
            ui.toggle(
                "悬浮扫码",
                "在当前应用上方显示可移动、可缩放的扫码窗口",
                mode == ScanPreferences.MODE_FLOATING,
                null,
            ) { enabled ->
                prefs.edit().putString(
                    ScanPreferences.KEY_MODE,
                    if (enabled) ScanPreferences.MODE_FLOATING else ScanPreferences.MODE_FULL,
                ).apply()
                rebuild()
            },
            ui.toggle(
                "全屏扫码",
                "使用独立全屏页面进行持续扫码",
                mode != ScanPreferences.MODE_FLOATING,
                null,
            ) { enabled ->
                prefs.edit().putString(
                    ScanPreferences.KEY_MODE,
                    if (enabled) ScanPreferences.MODE_FULL else ScanPreferences.MODE_FLOATING,
                ).apply()
                rebuild()
            },
            ui.toggle(
                R.string.scan_auto_enter_title,
                R.string.scan_auto_enter_note,
                prefs.getBoolean(ScanPreferences.KEY_AUTO_ENTER, false),
            ) { prefs.edit().putBoolean(ScanPreferences.KEY_AUTO_ENTER, it).apply() },
            ui.toggle(
                R.string.scan_vibrate_title,
                R.string.scan_vibrate_note,
                prefs.getBoolean(ScanPreferences.KEY_VIBRATE, true),
            ) { prefs.edit().putBoolean(ScanPreferences.KEY_VIBRATE, it).apply() },
        )
        return listOf(ui.group(null, rows, getString(R.string.scan_settings_note)))
    }}
