package app.qingjian.inputmethod.mascot

import app.qingjian.inputmethod.sms.SmsCodeReceiver
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
        val granted = SpriteTranslateService.isEnabled(this)
        val smsPrefs = getSharedPreferences("settings", MODE_PRIVATE)
        val smsPreview = smsPrefs.getBoolean(SmsCodeReceiver.KEY_MASCOT_PREVIEW, false)
        return listOf(
            ui.group(
                "悬浮小精灵",
                listOf(
                    ui.toggle(
                        "启用小精灵智能辅助",
                        "开启后屏幕显示一只动态小精灵。点击切换翻译/复制；拖动时准星指向文字，翻译模式实时翻译，复制模式松手复制。",
                        enabled,
                        null,
                    ) { checked ->
                        prefs.edit().putBoolean(KEY_ENABLED, checked).apply()
                        if (checked && !SpriteTranslateService.isEnabled(this)) {
                            Toast.makeText(this, "请在系统无障碍设置中开启「青简悬浮小精灵智能辅助」", Toast.LENGTH_LONG).show()
                            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                        }
                        rebuild()
                    },
                    ui.link(
                        "无障碍权限（翻译与复制共用）",
                        if (granted) "已授权 · 点击查看系统设置" else "尚未授权 · 点击前往系统设置",
                    ) {
                        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    },
                    ui.toggle(
                        "显示短信摘要",
                        "需要短信读取权限；默认关闭。开启后只在本机显示短信来源和短摘要，不自动复制短信正文。",
                        smsPreview,
                        null,
                    ) { checked ->
                        smsPrefs.edit().putBoolean(SmsCodeReceiver.KEY_MASCOT_PREVIEW, checked).apply()
                        if (checked && !smsPrefs.getBoolean(SmsCodeReceiver.KEY_ENABLED, false)) {
                            Toast.makeText(this, "如果尚未授予短信权限，请先开启验证码监测完成授权", Toast.LENGTH_LONG).show()
                        }
                        rebuild()
                    },
                ),
                footer = "只使用一只悬浮小精灵。点击图标切换模式：青绿色为翻译，橙色为复制。拖动时准星会自动避开屏幕边缘；停止拖动后准星收起。密码框、可编辑输入框和系统敏感节点会跳过；翻译只发送准星命中的单段文字到你配置的翻译服务。",
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
                "小精灵操作",
                listOf(
                    ui.row("翻译模式", "点击小精灵切换到青绿色翻译模式，拖动时准星指向哪段文字，就翻译哪段文字。"),
                    ui.row("复制模式", "点击小精灵切换到橙色复制模式，拖动准星到文字后松手复制。"),
                    ui.row("智能识别", "识别到电话号码可点按拨号，网址可点按打开，验证码可点按复制；开启短信摘要后可在小精灵气泡中查看短信摘要。"),
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
