package app.qingjian.inputmethod.panel

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView

/**
 * 语音动效设置独立页面。
 *
 * 输入法服务没有 Activity 窗口 token，直接从 IME Context 显示 AlertDialog
 * 在部分系统上会抛 BadTokenException 并导致输入法进程闪退；改由正常 Activity 承载。
 */
class VoiceAnimationSettingsActivity : Activity() {
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("voice", MODE_PRIVATE)
        val selected = prefs.getInt(VoicePanel.PREF_STYLE, 0).coerceIn(-1, 3)
        val selectedIndex = if (selected == -1) 4 else selected

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(dp(24), dp(28), dp(24), dp(24))
            setBackgroundColor(Color.WHITE)
        }
        root.addView(TextView(this).apply {
            text = "语音面板动效"
            textSize = 22f
            setTextColor(Color.rgb(28, 32, 40))
        }, LinearLayout.LayoutParams(-1, -2))

        root.addView(TextView(this).apply {
            text = "选择语音输入时显示的实时音频动画"
            textSize = 14f
            setTextColor(Color.rgb(105, 112, 124))
            setPadding(0, dp(8), 0, dp(18))
        }, LinearLayout.LayoutParams(-1, -2))

        val choices = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }
        VoicePanel.ANIMATION_STYLE_LABELS.forEachIndexed { index, label ->
            val option = RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                textSize = 16f
                setTextColor(Color.rgb(35, 40, 50))
                setPadding(dp(4), dp(12), dp(4), dp(12))
                isChecked = index == selectedIndex
            }
            choices.addView(option, RadioGroup.LayoutParams(-1, -2))
        }
        choices.setOnCheckedChangeListener { group, checkedId ->
            val index = (0 until group.childCount).firstOrNull {
                group.getChildAt(it).id == checkedId
            } ?: return@setOnCheckedChangeListener
            prefs.edit().putInt(VoicePanel.PREF_STYLE, if (index == 4) -1 else index).apply()
            finish()
        }
        root.addView(choices, LinearLayout.LayoutParams(-1, -2))

        root.addView(Button(this).apply {
            text = "取消"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(18)
        })

        setContentView(root)
    }
}
