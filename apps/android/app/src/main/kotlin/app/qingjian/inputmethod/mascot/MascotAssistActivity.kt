package app.qingjian.inputmethod.mascot

import android.content.Intent
import android.net.Uri
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
                "悬浮小精灵",
                listOf(
                    ui.toggle(
                        "启用小精灵智能辅助",
                        "识别当前应用场景；翻译服务关闭时，可拖动小精灵到文字上停留 1 秒尝试复制。默认关闭。",
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
                    ui.link(
                        "翻译功能权限",
                        if (SpriteTranslateService.isEnabled(this)) "已授权 · 拖动同一只悬浮小精灵即可翻译" else "尚未授权 · 点击前往系统设置",
                    ) {
                        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    },
                ),
                footer = "智能辅助和翻译共用同一只悬浮小精灵，不再额外显示第二只精灵。拖动小精灵时显示准星，停留在普通文字上可调用青简翻译；智能复制仅在翻译服务未启用时保留。密码框和敏感节点会跳过。翻译只发送准星命中的文字片段到你配置的翻译服务。",
            ),
            ui.group(
                "输入光标辉光",
                listOf(
                    ui.toggle(
                        "显示外部输入框光标光效",
                        "在其他应用的文字光标旁显示柔和青蓝色辉光；密码和私密输入框会自动关闭。",
                        prefs.getBoolean(KEY_CURSOR_GLOW_ENABLED, false),
                        null,
                    ) { checked ->
                        prefs.edit().putBoolean(KEY_CURSOR_GLOW_ENABLED, checked).apply()
                        if (checked && !Settings.canDrawOverlays(this)) {
                            Toast.makeText(this, "需要允许青简显示在其他应用上层，才能绘制光标辉光", Toast.LENGTH_LONG).show()
                            runCatching {
                                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                            }
                        }
                    },
                ),
                footer = "通过编辑器提供的光标坐标定位，不修改其他应用本身的光标。部分不提供光标坐标的应用可能无法显示。",
            ),
            ui.group(
                "拖动复制说明",
                listOf(
                    ui.row("使用方法", "翻译服务未开启时，开启本页开关并授权后，拖动小精灵到文字上停留超过 1 秒可尝试复制。翻译服务开启时，拖动同一只小精灵会优先显示准星并翻译命中的文字。"),
                    ui.row("隐私保护", "跳过密码输入框、敏感内容和青简自身窗口。不读取整页内容，也不会将识别到的文字发送给云端。"),
                ),
            ),
        )
    }

    companion object {
        const val PREFS = "mascot_assist"
        const val KEY_ENABLED = "cross_app_enabled"
        const val KEY_CURSOR_GLOW_ENABLED = "cursor_glow_enabled"
    }
}
