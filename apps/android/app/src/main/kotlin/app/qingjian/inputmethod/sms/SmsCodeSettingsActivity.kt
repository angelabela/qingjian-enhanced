package app.qingjian.inputmethod.sms

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/** 短信验证码监测的显式开关；只有用户主动开启时才请求 RECEIVE_SMS。 */
class SmsCodeSettingsActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var toggle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(36), dp(24), dp(24))
            setBackgroundColor(0xFF101522.toInt())
        }
        root.addView(TextView(this).apply {
            text = "短信验证码监测"
            textSize = 22f
            setTextColor(-1)
        })
        root.addView(TextView(this).apply {
            text = "开启后，青简只在本机识别短信中明确标注的 4～6 位验证码，自动复制并在候选栏提供上屏入口。短信正文不会上传。"
            textSize = 14f
            setTextColor(0xFFD0D6E2.toInt())
            setPadding(0, dp(18), 0, dp(22))
        })
        status = TextView(this).apply { textSize = 14f; setTextColor(-1) }
        root.addView(status)
        toggle = Button(this).apply {
            setOnClickListener { toggleMonitoring() }
        }
        root.addView(toggle)
        root.addView(Button(this).apply { text = "返回"; setOnClickListener { finish() } })
        setContentView(root)
        refresh()
    }

    private fun toggleMonitoring() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val enabled = prefs.getBoolean(SmsCodeReceiver.KEY_ENABLED, false)
        if (enabled) {
            prefs.edit().putBoolean(SmsCodeReceiver.KEY_ENABLED, false).apply()
            refresh()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean(SmsCodeReceiver.KEY_ENABLED, true).apply()
            refresh()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECEIVE_SMS), REQUEST_SMS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_SMS && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putBoolean(SmsCodeReceiver.KEY_ENABLED, true).apply()
            Toast.makeText(this, "验证码监测已开启", Toast.LENGTH_SHORT).show()
        }
        refresh()
    }

    private fun refresh() {
        val enabled = getSharedPreferences("settings", MODE_PRIVATE).getBoolean(SmsCodeReceiver.KEY_ENABLED, false)
        status.text = if (enabled) "当前状态：已开启" else "当前状态：已关闭"
        toggle.text = if (enabled) "关闭验证码监测" else "开启验证码监测"
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object { private const val REQUEST_SMS = 2602 }
}
