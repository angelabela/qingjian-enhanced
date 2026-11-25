package app.qingjian.inputmethod.background

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import app.qingjian.inputmethod.settings.Settings

/** 仅负责调用系统文档选择器；视频背景参数在输入法常用功能子面板内调整。 */
class DynamicBackgroundActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, REQUEST_VIDEO)
    }

    @Deprecated("Uses document picker result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VIDEO) return
        if (resultCode == RESULT_OK) {
            val result = data
            val uri: Uri? = result?.data
            if (uri != null && result != null) {
                runCatching {
                    val readFlag = result.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(uri, readFlag)
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString(KEY_URI, uri.toString())
                        .putBoolean(KEY_ENABLED, true)
                        .putInt(KEY_DISPLAY_MODE, MODE_VIDEO)
                        .apply()
                    Toast.makeText(this, "视频已导入并启用", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(this, "无法读取该视频，请重新选择", Toast.LENGTH_LONG).show()
                }
            }
        }
        sendBroadcast(Intent(ACTION_SETTINGS_CHANGED).setPackage(packageName))
        finish()
    }

    companion object {
        const val PREFS = "dynamic_background"
        const val KEY_URI = "video_uri"
        const val KEY_ENABLED = "video_enabled"
        const val KEY_ALPHA = "video_alpha"
        const val KEY_BRIGHTNESS = "video_brightness"
        const val KEY_BLUR = "video_blur"
        const val KEY_MODE = "video_mode" // 旧版模式，仅用于首次迁移
        const val KEY_DISPLAY_MODE = "background_display_mode"
        const val MODE_VIDEO = 0
        const val MODE_EFFECT = 1
        const val MODE_NORMAL = 2

        /** 将旧版视频开关/叠加模式一次性迁移为新的单一显示模式。 */
        fun displayMode(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.contains(KEY_DISPLAY_MODE)) {
                return prefs.getInt(KEY_DISPLAY_MODE, MODE_NORMAL).coerceIn(MODE_VIDEO, MODE_NORMAL)
            }
            val settings = Settings.preferences(context)
            val legacyMode = prefs.getInt(KEY_MODE, 2).coerceIn(0, 2)
            val hasVideo = prefs.getBoolean(KEY_ENABLED, false) && !prefs.getString(KEY_URI, null).isNullOrBlank()
            val migrated = when {
                hasVideo && legacyMode != 1 -> MODE_VIDEO
                settings.getBoolean(Settings.KEY_BACKGROUND_EFFECT, false) -> MODE_EFFECT
                else -> MODE_NORMAL
            }
            prefs.edit().putInt(KEY_DISPLAY_MODE, migrated).apply()
            return migrated
        }
        const val KEY_FROSTED = "video_frosted"
        const val KEY_KEYBOARD_MARQUEE = "keyboard_key_marquee"
        const val ACTION_SETTINGS_CHANGED = "app.qingjian.inputmethod.action.DYNAMIC_BACKGROUND_CHANGED"
        const val REQUEST_VIDEO = 731
    }
}
