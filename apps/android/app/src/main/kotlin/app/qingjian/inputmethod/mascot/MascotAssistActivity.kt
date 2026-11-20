package app.qingjian.inputmethod.mascot

import android.content.Intent
import android.provider.Settings
import android.view.View
import android.widget.Toast
import app.qingjian.inputmethod.settings.page.PageActivity

class MascotAssistActivity : PageActivity() {
    override val title: Int = app.qingjian.inputmethod.R.string.page_advanced
    override fun onResume() { super.onResume(); rebuild() }
    override fun content(): List<View> {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val enabled = prefs.getBoolean(KEY_ENABLED, false)
        val granted = MascotTextAccessibilityService.isEnabled(this)
        return listOf(
            ui.group(
                "跨应用智能辅助",
                ui.toggle("启用小精灵智能辅助", "识别当前应用场景，并支持拖动小精灵到文字上停留 1 秒后尝试复制。默认关闭。", enabled, null) { checked ->
                    prefs.edit().putBoolean(KEY_ENABLED, checked).apply()
                    if (checked && !MascotTextAccessibilityService.isEnabled(this)) {
                        Toast.makeText(this, "请在系统无障碍设置中手动开启「青简小精灵智能辅助」", Toast.LENGTH_LONG).show()
                        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    }
                    rebuild()
                },
                ui.action("无障碍权限", if (granted) "已授权 · 点击查看系统设置" else "尚未授权 · 点击前往系统设置") {
                    runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                },
                footer = "权限仅在你手动开启后生效。青简只读取当前窗口中系统允许访问的普通文本；密码框会跳过。不会自动把页面文字上传云端。部分应用可能禁止读取或复制。",
            ),
            ui.group(
                "拖动复制说明",
                ui.row("使用方法", "开启本页开关并在系统无障碍设置中授权后，拖动小精灵到要复制的文字上，停住超过 1 秒。青简会尝试复制该文本到系统剪贴板。"),
                ui.row("隐私保护", "跳过密码输入框和青简自身窗口。不读取整页内容，也不会将识别到的文字发送给云端。"),
            ),
        )
    }
    companion object {
        const val PREFS = "mascot_assist"
        const val KEY_ENABLED = "cross_app_enabled"
    }
}
