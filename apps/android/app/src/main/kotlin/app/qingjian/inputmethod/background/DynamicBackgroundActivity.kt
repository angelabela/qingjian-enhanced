package app.qingjian.inputmethod.background

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.view.WindowManager
import android.content.res.ColorStateList
import app.qingjian.inputmethod.settings.Settings
import app.qingjian.inputmethod.theme.Palette

/** 动态视频背景控制面板：从输入法「常用功能」进入。 */
class DynamicBackgroundActivity : Activity() {
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appSettings = Settings.load(this)
        val palette = Palette.of(appSettings.theme, appSettings.appearance.dark(this))
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.statusBarColor = palette.board
        window.navigationBarColor = palette.board
        window.decorView.systemUiVisibility = if (appSettings.appearance.dark(this)) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            setBackgroundColor(palette.board)
        }
        fun styleButton(button: Button) {
            button.setTextColor(palette.text)
            button.backgroundTintList = ColorStateList.valueOf(palette.functionalKey)
            button.isAllCaps = false
        }
        root.addView(TextView(this).apply {
            text = "动态视频背景"; textSize = 22f; setTextColor(palette.text)
        })
        root.addView(TextView(this).apply {
            text = "导入无声 MP4，设置键盘背景透明度。视频只在键盘显示时播放。"
            textSize = 13f; setTextColor(palette.faint); setPadding(0, dp(8), 0, dp(18))
        })
        status = TextView(this).apply {
            textSize = 14f; setTextColor(palette.brand)
            text = if (prefs.getString(KEY_URI, null) == null) "尚未选择视频" else "已选择自定义视频"
        }
        root.addView(status)
        root.addView(Button(this).apply {
            styleButton(this)
            text = "从手机导入 MP4"
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "video/mp4"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }, REQUEST_VIDEO)
            }
        }, matchWrap())
        root.addView(TextView(this).apply {
            text = "视频透明度"; setTextColor(0xFFE8ECF5.toInt()); setPadding(0, dp(18), 0, dp(4))
        })
        root.addView(SeekBar(this).apply {
            progressTintList = ColorStateList.valueOf(palette.brand)
            thumbTintList = ColorStateList.valueOf(palette.brand)
            max = 100; progress = prefs.getInt(KEY_ALPHA, 72)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) prefs.edit().putInt(KEY_ALPHA, progress).apply()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        })
        root.addView(TextView(this).apply {
            text = "背景亮度"; setTextColor(palette.text); setPadding(0, dp(18), 0, dp(4))
        })
        root.addView(SeekBar(this).apply {
            progressTintList = ColorStateList.valueOf(palette.brand)
            thumbTintList = ColorStateList.valueOf(palette.brand)
            max = 200; progress = prefs.getInt(KEY_BRIGHTNESS, 100).coerceIn(0, 200)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) prefs.edit().putInt(KEY_BRIGHTNESS, progress).apply()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        })
        root.addView(TextView(this).apply {
            text = "背景模糊程度"; setTextColor(palette.text); setPadding(0, dp(18), 0, dp(4))
        })
        root.addView(SeekBar(this).apply {
            progressTintList = ColorStateList.valueOf(palette.brand)
            thumbTintList = ColorStateList.valueOf(palette.brand)
            max = 30; progress = prefs.getInt(KEY_BLUR, 8).coerceIn(0, 30)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) prefs.edit().putInt(KEY_BLUR, progress).apply()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        })
        root.addView(Button(this).apply {
            styleButton(this)
            text = if (prefs.getBoolean(KEY_ENABLED, false)) "关闭视频背景" else "启用视频背景"
            setOnClickListener {
                val enabled = !prefs.getBoolean(KEY_ENABLED, false)
                prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
                text = if (enabled) "关闭视频背景" else "启用视频背景"
                Toast.makeText(this@DynamicBackgroundActivity, if (enabled) "已启用" else "已关闭", Toast.LENGTH_SHORT).show()
            }
        }, matchWrap())
        root.addView(Button(this).apply {
            styleButton(this)
            text = "恢复默认背景"
            setOnClickListener {
                prefs.edit().remove(KEY_URI).putBoolean(KEY_ENABLED, false).apply()
                status.text = "尚未选择视频"
            }
        }, matchWrap())
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        root.addView(Button(this).apply { styleButton(this); text = "完成"; setOnClickListener { finish() } }, matchWrap())
        setContentView(root)
    }

    @Deprecated("Uses document picker result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VIDEO || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        try {
            val readFlag = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, readFlag)
            prefs.edit().putString(KEY_URI, uri.toString()).putBoolean(KEY_ENABLED, true).apply()
            status.text = "已导入视频"
            Toast.makeText(this, "视频已导入", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "无法读取该视频，请重新选择 MP4", Toast.LENGTH_LONG).show()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun matchWrap() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }

    companion object {
        const val PREFS = "dynamic_background"
        const val KEY_URI = "video_uri"
        const val KEY_ENABLED = "video_enabled"
        const val KEY_ALPHA = "video_alpha"
        const val KEY_BRIGHTNESS = "video_brightness"
        const val KEY_BLUR = "video_blur"
        const val REQUEST_VIDEO = 731
    }
}
