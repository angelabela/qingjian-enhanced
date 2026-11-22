package app.qingjian.inputmethod.scan

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/** 将扫码网络提交为系统 Wi-Fi 建议；最终接入确认由 Android 系统管理。 */
class WifiJoinActivity : Activity() {
    private var ssid = ""
    private var password = ""
    private var security = "WPA"
    private var hidden = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ssid = intent.getStringExtra(EXTRA_SSID).orEmpty()
        password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty()
        security = intent.getStringExtra(EXTRA_SECURITY).orEmpty().ifBlank { "WPA" }
        hidden = intent.getBooleanExtra(EXTRA_HIDDEN, false)
        if (ssid.isBlank()) {
            Toast.makeText(this, "Wi-Fi 信息不完整", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES), REQUEST_NEARBY_WIFI)
        } else submitSuggestion()
    }

    @Deprecated("Uses runtime permission callback")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_NEARBY_WIFI) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) submitSuggestion()
        else {
            Toast.makeText(this, "未获得附近 Wi-Fi 权限，无法自动提交网络", Toast.LENGTH_LONG).show()
            openWifiSettings()
        }
    }

    private fun submitSuggestion() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, "当前 Android 版本不支持自动提交 Wi-Fi，请在系统页面手动加入", Toast.LENGTH_LONG).show()
            openWifiSettings()
            return
        }
        val builder = WifiNetworkSuggestion.Builder().setSsid(ssid).setIsHiddenSsid(hidden)
        when (security.uppercase()) {
            "NOPASS", "OPEN", "NONE" -> Unit
            "WEP" -> {
                Toast.makeText(this, "系统不支持自动提交此类 WEP 网络，请在 Wi-Fi 设置中手动加入", Toast.LENGTH_LONG).show()
                openWifiSettings()
                return
            }
            "WPA3", "SAE" -> builder.setWpa3Passphrase(password)
            else -> builder.setWpa2Passphrase(password)
        }
        val wifi = getSystemService(WIFI_SERVICE) as? WifiManager
        if (wifi == null) {
            Toast.makeText(this, "无法访问系统 Wi-Fi 服务", Toast.LENGTH_LONG).show()
            openWifiSettings()
            return
        }
        val status = runCatching { wifi.addNetworkSuggestions(listOf(builder.build())) }.getOrElse {
            Toast.makeText(this, "提交 Wi-Fi 失败：${it.message ?: "系统拒绝"}", Toast.LENGTH_LONG).show()
            openWifiSettings()
            return
        }
        when (status) {
            WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS ->
                Toast.makeText(this, "已提交「$ssid」接入请求，请按系统提示确认", Toast.LENGTH_LONG).show()
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE ->
                Toast.makeText(this, "「$ssid」已提交过接入请求", Toast.LENGTH_SHORT).show()
            else -> Toast.makeText(this, "系统未接受 Wi-Fi 请求（$status），请在设置中手动加入", Toast.LENGTH_LONG).show()
        }
        openWifiSettings()
    }

    private fun openWifiSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        finish()
    }

    companion object {
        const val EXTRA_SSID = "wifi_ssid"
        const val EXTRA_PASSWORD = "wifi_password"
        const val EXTRA_SECURITY = "wifi_security"
        const val EXTRA_HIDDEN = "wifi_hidden"
        private const val REQUEST_NEARBY_WIFI = 8041
    }
}
