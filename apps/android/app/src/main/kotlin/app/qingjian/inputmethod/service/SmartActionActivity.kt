package app.qingjian.inputmethod.service

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.qingjian.inputmethod.scan.ScanContent

/** 剪贴板号码动作面板；多号码先选号码，再选拨号或短信，只打开系统应用、不直接拨号。 */
class SmartActionActivity : Activity() {
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        setContentView(ScrollView(this).apply { addView(content) })

        // 保留旧的 EXTRA_NUMBER 调用方式，同时支持剪贴板传入多个已校验号码。
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
        content.addView(TextView(this).apply {
            text = "选择电话号码"
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(16))
        })
        numbers.forEach { number ->
            content.addView(Button(this).apply {
                text = number
                setOnClickListener { showActions(number) }
            }, buttonParams())
        }
        content.addView(cancelButton(), buttonParams())
    }

    private fun showActions(number: String) {
        content.removeAllViews()
        content.addView(TextView(this).apply {
            text = number
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(20))
        })
        content.addView(Button(this).apply {
            text = "📞 拨打电话"
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))
                finish()
            }
        }, buttonParams())
        content.addView(Button(this).apply {
            text = "✉️ 发送短信"
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)))
                finish()
            }
        }, buttonParams())
        content.addView(cancelButton(), buttonParams())
    }

    private fun cancelButton() = Button(this).apply {
        text = "取消"
        setOnClickListener { finish() }
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
