package app.qingjian.inputmethod.scan

import android.app.Activity
import android.content.Intent
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast

/** 将扫码得到的网络交给 Android 系统保存并发起连接。 */
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
        submitNetwork()
    }

    private fun buildNetwork(): WifiNetworkSuggestion? {
        val builder = WifiNetworkSuggestion.Builder()
            .setSsid(ssid)
            .setIsHiddenSsid(hidden)
        when (security.trim().uppercase().replace(" ", "")) {
            "NOPASS", "OPEN", "NONE" -> Unit
            "OWE" -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    Toast.makeText(this, "当前 Android 版本不支持增强型开放 Wi-Fi，请手动连接", Toast.LENGTH_LONG).show()
                    return null
                }
                builder.setIsEnhancedOpen(true)
            }
            "WPA3", "SAE", "WPA3-SAE" -> builder.setWpa3Passphrase(password)
            "WPA", "WPA2", "WPA-PSK", "WPA2-PSK", "WPA/WPA2", "WPA2/WPA3" ->
                builder.setWpa2Passphrase(password)
            "WEP", "EAP", "802.1X", "WPA-EAP", "WPA2-EAP" -> {
                Toast.makeText(this, "识别到的 Wi-Fi 是 $security 加密类型，当前无法自动配置，请在系统 Wi-Fi 页面手动连接", Toast.LENGTH_LONG).show()
                return null
            }
            else -> {
                Toast.makeText(this, "暂不支持「$security」Wi-Fi 加密类型，请在系统页面手动连接", Toast.LENGTH_LONG).show()
                return null
            }
        }
        if (security.uppercase() !in setOf("OPEN", "NOPASS", "NONE", "OWE") && password.isBlank()) {
            Toast.makeText(this, "Wi-Fi 密码为空，无法连接", Toast.LENGTH_LONG).show()
            return null
        }
        return runCatching { builder.build() }.getOrElse {
            Toast.makeText(this, "Wi-Fi 参数无效：${it.message ?: "请检查密码和加密类型"}", Toast.LENGTH_LONG).show()
            null
        }
    }

    private fun submitNetwork() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, "当前 Android 版本不支持应用自动添加 Wi-Fi，请在系统页面手动连接", Toast.LENGTH_LONG).show()
            openWifiSettings()
            return
        }
        val network = buildNetwork() ?: run {
            openWifiSettings()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ 的添加网络 API 会显示系统确认界面；用户同意后保存网络并触发连接。
            val request = Intent(Settings.ACTION_WIFI_ADD_NETWORKS).apply {
                putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(network))
            }
            runCatching { startActivityForResult(request, REQUEST_ADD_WIFI) }
                .onFailure {
                    Toast.makeText(this, "无法打开系统 Wi-Fi 添加页面：${it.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
                    openWifiSettings()
                }
            return
        }

        // Android 10 没有 ACTION_WIFI_ADD_NETWORKS，只能提交网络建议，由系统决定何时连接。
        val wifi = getSystemService(WIFI_SERVICE) as? WifiManager
        if (wifi == null) {
            Toast.makeText(this, "无法访问系统 Wi-Fi 服务", Toast.LENGTH_LONG).show()
            openWifiSettings()
            return
        }
        val status = runCatching { wifi.addNetworkSuggestions(listOf(network)) }.getOrElse {
            Toast.makeText(this, "提交 Wi-Fi 失败：${it.message ?: "系统拒绝"}", Toast.LENGTH_LONG).show()
            openWifiSettings()
            return
        }
        when (status) {
            WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS ->
                Toast.makeText(this, "已提交「$ssid」网络，系统正在评估连接", Toast.LENGTH_LONG).show()
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE ->
                Toast.makeText(this, "「$ssid」网络已提交过，请在系统 Wi-Fi 页面选择连接", Toast.LENGTH_LONG).show()
            else ->
                Toast.makeText(this, "系统未接受 Wi-Fi 请求（$status），请在设置中手动连接", Toast.LENGTH_LONG).show()
        }
        openWifiSettings()
    }

    @Deprecated("Uses the system Wi-Fi confirmation activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_ADD_WIFI) return
        if (resultCode != RESULT_OK) {
            Toast.makeText(this, "已取消添加 Wi-Fi「$ssid」", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val result = data?.getIntegerArrayListExtra(Settings.EXTRA_WIFI_NETWORK_RESULT_LIST)?.firstOrNull()
        when (result) {
            Settings.ADD_WIFI_RESULT_SUCCESS ->
                Toast.makeText(this, "Wi-Fi「$ssid」已保存，系统正在尝试连接", Toast.LENGTH_LONG).show()
            Settings.ADD_WIFI_RESULT_ALREADY_EXISTS ->
                Toast.makeText(this, "Wi-Fi「$ssid」已存在，系统将尝试连接", Toast.LENGTH_LONG).show()
            Settings.ADD_WIFI_RESULT_ADD_OR_UPDATE_FAILED ->
                Toast.makeText(this, "系统保存 Wi-Fi「$ssid」失败，请检查密码和加密方式", Toast.LENGTH_LONG).show()
            else ->
                Toast.makeText(this, "系统未返回有效的 Wi-Fi 保存结果，请检查系统 Wi-Fi 设置", Toast.LENGTH_LONG).show()
        }
        finish()
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
        private const val REQUEST_ADD_WIFI = 8042
    }
}
