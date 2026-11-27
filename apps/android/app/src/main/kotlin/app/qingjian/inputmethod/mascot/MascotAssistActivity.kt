package app.qingjian.inputmethod.mascot

import android.content.Intent
import android.provider.Settings
import android.view.View
import android.widget.Toast
import app.qingjian.inputmethod.settings.page.PageActivity

/** 用户可单独控制跨应用辅助；无障碍授权始终由用户在 Android 系统设置中手动开启。 */
class MascotAssistActivity : PageActivity() {
    override val title: Int = app.qingjian.inputmethod.R.string.page_advanced

    override fun onResume() {
        super.onResume()
        rebuild()
    }

    override fun content(): List<View> {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val enabled = prefs.getBoolean(KEY_ENABLED, false)
        val granted = MascotTextAccessibilityService.isEnabled(this)
        return listOf(
            ui.group(
                "跨应用智能辅助",
                listOf(
                    ui.toggle(
                        "启用小精灵智能辅助",
                        "识别当前应用场景，并支持拖动小精灵到文字上停留 1 秒后尝试复制。默认关闭。",
                        enabled,
                        null,
                    ) { checked ->
                        prefs.edit().putBoolean(KEY_ENABLED, checked).apply()
                        if (checked && !MascotTextAccessibilityService.isEnabled(this)) {
                            Toast.makeText(this, "请在系统无障碍设置中手动开启「青简小精灵智能辅助」", Toast.LENGTH_LONG).show()
                            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                        }
                        rebuild()
                    },
                    ui.link(
                        "无障碍权限",
                        if (granted) "已授权 · 点击查看系统设置" else "尚未授权 · 点击前往系统设置",
                    ) {
                        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    },
                ),
                footer = "权限仅在你手动开启后生效。青简只读取当前窗口中系统允许访问的普通文本；密码框和敏感节点会跳过。不会自动把页面文字上传云端。部分应用可能禁止读取或复制。",
            ),
            ui.group(
                "小精灵翻译",
                listOf(
                    ui.link(
                        "启用小精灵翻译",
                        "在系统无障碍设置中开启「青简小精灵翻译」；拖动屏幕边缘的小精灵指向文字，即可调用青简现有翻译引擎。",
                    ) {
                        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    },
                ),
                footer = "翻译使用「设置 → 云端大模型」中的现有接口与密钥，并遵循青简当前学习语言设置；不使用 ML Kit，也不额外下载翻译模型。",
            ),
            ui.group(
                "拖动复制说明",
                listOf(
                    ui.row("使用方法", "开启本页开关并在系统无障碍设置中授权后，拖动小精灵到要复制的文字上，停住超过 1 秒。青简会尝试复制该文本到系统剪贴板。"),
                    ui.row("隐私保护", "跳过密码输入框、敏感内容和青简自身窗口。不读取整页内容，也不会将识别到的文字发送给云端。"),
                ),
            ),
        )
    }

    companion object {
        const val PREFS = "mascot_assist"
        const val KEY_ENABLED = "cross_app_enabled"
    }
}
