package app.qingjian.inputmethod.sms

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 常用功能面板中的验证码开关代理。
 * 已授权时点击立即开/关；首次开启只请求系统 RECEIVE_SMS 权限，不展示独立设置页。
 */
class SmsCodeSettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (prefs.getBoolean(SmsCodeReceiver.KEY_ENABLED, false)) {
            prefs.edit().putBoolean(SmsCodeReceiver.KEY_ENABLED, false).apply()
            Toast.makeText(this, "验证码监测已关闭", Toast.LENGTH_SHORT).show()
            publishAndFinish(false)
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean(SmsCodeReceiver.KEY_ENABLED, true).apply()
            Toast.makeText(this, "验证码监测已开启", Toast.LENGTH_SHORT).show()
            publishAndFinish(true)
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECEIVE_SMS), REQUEST_SMS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = requestCode == REQUEST_SMS &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        if (granted) {
            getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putBoolean(SmsCodeReceiver.KEY_ENABLED, true).apply()
            Toast.makeText(this, "验证码监测已开启", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "未授予短信权限，验证码监测未开启", Toast.LENGTH_SHORT).show()
        }
        publishAndFinish(granted)
    }

    private fun publishAndFinish(enabled: Boolean) {
        sendBroadcast(
            Intent(ACTION_STATE_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_ENABLED, enabled),
        )
        finish()
    }

    companion object {
        const val ACTION_STATE_CHANGED = "app.qingjian.inputmethod.SMS_CODE_STATE_CHANGED"
        const val EXTRA_ENABLED = "enabled"
        private const val REQUEST_SMS = 2602
    }
}
