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
        return listOf(
            ui.group(
                null,
                ui.segmentedRow(
                    R.string.scan_mode,
                    listOf(getString(R.string.scan_mode_full), getString(R.string.scan_mode_floating)),
                    if (mode == ScanPreferences.MODE_FLOATING) 1 else 0,
                ) { selected ->
                    prefs.edit().putString(
                        ScanPreferences.KEY_MODE,
                        if (selected == 1) ScanPreferences.MODE_FLOATING else ScanPreferences.MODE_FULL,
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
                footer = R.string.scan_settings_note,
            ),
        )
    }
}
