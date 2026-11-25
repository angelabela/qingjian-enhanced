package app.qingjian.inputmethod.service

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** 号码候选的明确动作面板；只打开系统拨号/短信界面，不直接拨号。 */
class SmartActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val number = intent.getStringExtra(EXTRA_NUMBER).orEmpty()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        root.addView(TextView(this).apply {
            text = number
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(20))
        })
        root.addView(Button(this).apply {
            text = "📞 拨打电话"
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
                finish()
            }
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(Button(this).apply {
            text = "✉️ 发送短信"
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")))
                finish()
            }
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(Button(this).apply {
            text = "取消"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object { const val EXTRA_NUMBER = "phone_number" }
}
