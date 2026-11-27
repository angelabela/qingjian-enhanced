package app.qingjian.inputmethod.service

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.qingjian.inputmethod.R
import app.qingjian.inputmethod.scan.ScanContent

/** 圆角悬浮号码动作卡片；多号码先选择号码，再选择拨号或短信，只打开系统应用、不直接拨号。 */
class SmartActionActivity : Activity() {
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(22), dp(22), dp(18))
            background = GradientDrawable().apply {
                setColor(getColor(R.color.app_background))
                cornerRadius = dp(26).toFloat()
                setStroke(dp(1), 0x267A8A80)
            }
            elevation = dp(18).toFloat()
            isClickable = true
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            addView(content)
            setOnClickListener { }
        }
        val backdrop = FrameLayout(this).apply {
            setBackgroundColor(0x66000000)
            setOnClickListener { finish() }
            addView(
                scroll,
                FrameLayout.LayoutParams(
                    minOf(dp(360), (resources.displayMetrics.widthPixels - dp(40)).coerceAtLeast(dp(260))),
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ).apply {
                    leftMargin = dp(20)
                    rightMargin = dp(20)
                },
            )
        }
        setContentView(backdrop)

        val incoming = intent.getStringArrayListExtra(EXTRA_NUMBERS)
            ?: arrayListOf(intent.getStringExtra(EXTRA_NUMBER).orEmpty())
        val numbers = incoming.distinct().filter { ScanContent.extractPhones(it).singleOrNull() == it }
        if (numbers.isEmpty()) {
            finish()
        } else if (numbers.size == 1) {
            showActions(numbers.single())
        } else {
            showNumberPicker(numbers)
        }
    }

    private fun showNumberPicker(numbers: List<String>) {
        content.removeAllViews()
        content.addView(title("选择电话号码"))
        content.addView(subtitle("检测到 ${numbers.size} 个号码，请先选择一个"))
        numbers.take(20).forEach { number ->
            content.addView(actionButton(number, primary = false) { showActions(number) }, buttonParams())
        }
        content.addView(textButton("取消", 0xFF68736D.toInt()) { finish() }, buttonParams())
    }

    private fun showActions(number: String) {
        content.removeAllViews()
        content.addView(title("电话号码"))
        content.addView(subtitle(number))
        content.addView(actionButton("拨打电话", primary = true) {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))
            finish()
        }, buttonParams())
        content.addView(actionButton("发送短信", primary = false) {
            startActivity(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)))
            finish()
        }, buttonParams())
        content.addView(textButton("取消", 0xFF68736D.toInt()) { finish() }, buttonParams())
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTextColor(0xFF202B25.toInt())
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, dp(8))
    }

    private fun subtitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(0xFF65736B.toInt())
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, dp(16))
    }

    private fun actionButton(text: String, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
        this.text = if (primary) "☎  $text" else text
        textSize = 15.5f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        minHeight = dp(48)
        setTextColor(if (primary) 0xFFFFFFFF.toInt() else getColor(R.color.brand))
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(if (primary) getColor(R.color.brand) else getColor(R.color.app_wash))
        }
        setOnClickListener { onClick() }
    }

    private fun textButton(text: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 14f
        gravity = Gravity.CENTER
        minHeight = dp(42)
        setTextColor(color)
        setOnClickListener { onClick() }
    }

    private fun buttonParams() = LinearLayout.LayoutParams(-1, -2).apply {
        bottomMargin = dp(8)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_NUMBER = "phone_number"
        const val EXTRA_NUMBERS = "phone_numbers"
    }
}
