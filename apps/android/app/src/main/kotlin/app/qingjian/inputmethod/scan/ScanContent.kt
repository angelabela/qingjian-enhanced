package app.qingjian.inputmethod.scan

import android.net.Uri

data class WifiQrCredentials(val ssid: String, val password: String, val security: String, val hidden: Boolean)

object ScanContent {
    fun parseWifi(raw: String): WifiQrCredentials? {
        val value = raw.trim()
        if (!value.startsWith("WIFI:", ignoreCase = true)) return null
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
