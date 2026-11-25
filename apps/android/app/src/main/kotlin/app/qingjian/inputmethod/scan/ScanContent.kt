package app.qingjian.inputmethod.scan

import android.net.Uri

data class WifiQrCredentials(val ssid: String, val password: String, val security: String, val hidden: Boolean)

object ScanContent {
    fun parseWifi(raw: String): WifiQrCredentials? {
        val value = raw.trim()
        if (value.isEmpty()) return null

        // 标准 ZXing Wi-Fi 二维码：WIFI:T:WPA;S:名称;P:密码;;
        if (value.startsWith("WIFI:", ignoreCase = true)) {
            val fields = linkedMapOf<String, String>()
            val token = StringBuilder()
            val parts = mutableListOf<String>()
            var escaped = false
            for (ch in value.substringAfter(':')) {
                when {
                    escaped -> { token.append(ch); escaped = false }
                    ch == '\\' -> escaped = true
                    ch == ';' -> { parts += token.toString(); token.setLength(0) }
                    else -> token.append(ch)
                }
            }
            if (token.isNotEmpty()) parts += token.toString()
            for (part in parts) {
                val separator = part.indexOf(':')
                if (separator > 0) fields[part.substring(0, separator).uppercase()] = part.substring(separator + 1)
            }
            val ssid = fields["S"]?.takeIf { it.isNotBlank() } ?: return null
            val security = fields["T"].orEmpty().ifBlank { "WPA" }.uppercase()
            val password = fields["P"].orEmpty()
            if (security !in setOf("NOPASS", "OPEN", "NONE") && password.isBlank()) return null
            return WifiQrCredentials(ssid, password, security, fields["H"].equals("true", ignoreCase = true))
        }

        // 部分光猫/运营商设备把 Wi-Fi 参数和设备信息一起编码成 URL 查询串，
        // 例如 ssid1=...&wifipwd=...&username=...&userpwd=...。
        // 只读取明确的 Wi-Fi 字段，绝不把 userpwd/设备 SN/MAC 等误当作 Wi-Fi 密码。
        val queryStart = value.indexOf('?').let { if (it >= 0) it + 1 else 0 }
        val query = value.substring(queryStart)
        if (!query.contains('=') || !Regex("(?i)(?:^|[&;])(?:ssid\\d*|wifipwd|wifi_password|wifiPassword|password|pwd)=").containsMatchIn(query)) {
            return null
        }
        val fields = linkedMapOf<String, String>()
        query.split('&', ';').forEach { item ->
            val split = item.indexOf('=')
            if (split <= 0) return@forEach
            val key = item.substring(0, split).trim().lowercase()
            val encoded = item.substring(split + 1)
            val decoded = runCatching { java.net.URLDecoder.decode(encoded, Charsets.UTF_8.name()) }.getOrDefault(encoded)
            fields[key] = decoded
        }
        val ssid = sequenceOf("ssid", "ssid1", "ssid2", "ssid_1", "ssid_2")
            .mapNotNull { fields[it] }
            .firstOrNull { it.isNotBlank() } ?: return null
        val password = sequenceOf("wifipwd", "wifi_password", "wifipassword", "password", "pwd")
            .mapNotNull { fields[it] }
            .firstOrNull().orEmpty()
        val security = sequenceOf("wifisecurity", "security", "encryption", "authtype")
            .mapNotNull { fields[it] }
            .firstOrNull().orEmpty().ifBlank { if (password.isBlank()) "OPEN" else "WPA" }.uppercase()
        if (security !in setOf("NOPASS", "OPEN", "NONE") && password.isBlank()) return null
        val hidden = fields["hidden"].equals("true", true) || fields["hidden"].equals("1")
        return WifiQrCredentials(ssid, password, security, hidden)
    }

    fun webUrl(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty() || value.any(Char::isWhitespace)) return null
        val candidate = when {
            value.startsWith("https://", true) || value.startsWith("http://", true) -> value
            DOMAIN_PATTERN.matches(value) -> "https://$value"
            else -> return null
        }
        val uri = runCatching { Uri.parse(candidate) }.getOrNull() ?: return null
        return candidate.takeIf {
            (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) && !uri.host.isNullOrBlank()
        }
    }

    private val DOMAIN_PATTERN = Regex("^(?:www\\.)?(?:[A-Za-z0-9-]+\\.)+[A-Za-z]{2,}(?::\\d+)?(?:/[^\\s]*)?(?:\\?[^\\s]*)?(?:#[^\\s]*)?$")
}
