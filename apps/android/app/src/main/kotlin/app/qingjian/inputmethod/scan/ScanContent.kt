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


    sealed interface ClipboardAction {
        data class Wifi(val credentials: WifiQrCredentials) : ClipboardAction
        data class Url(val url: String) : ClipboardAction
        data class SmsCode(val code: String) : ClipboardAction
        data class Phone(val number: String) : ClipboardAction
    }

    /** Detect one actionable item from clipboard text; Wi-Fi credentials take precedence and stay hidden. */
    fun detectClipboardAction(raw: String): ClipboardAction? {
        if (raw.isBlank()) return null
        parseWifi(raw)?.let { return ClipboardAction.Wifi(it) }
        extractSmsCode(raw)?.let { return ClipboardAction.SmsCode(it) }
        findWebUrl(raw)?.let { return ClipboardAction.Url(it) }
        extractPhone(raw)?.let { return ClipboardAction.Phone(it) }
        return null
    }

    /** Extract only a code adjacent to an explicit verification-code phrase; never guess from bare numbers. */
    fun extractSmsCode(text: String): String? {
        if (text.isBlank()) return null
        val context = Regex(
            "(验证码|校验码|动态码|短信码|随机码|安全码|确认码|verification\\s*code|one[- ]time\\s+password|\\bOTP\\b)",
            RegexOption.IGNORE_CASE,
        )
        val digits = Regex("(?<!\\d)(\\d{4,6})(?!\\d)")
        val matches = context.findAll(text).toList()
        if (matches.isEmpty()) return null
        data class Candidate(val code: String, val distance: Int, val afterKeyword: Boolean)
        val candidates = matches.flatMap { match ->
            val start = (match.range.first - 48).coerceAtLeast(0)
            val end = (match.range.last + 49).coerceAtMost(text.length)
            digits.findAll(text.substring(start, end)).mapNotNull { number ->
                val numberStart = start + number.range.first
                val numberEnd = start + number.range.last
                when {
                    numberStart > match.range.last ->
                        Candidate(number.groupValues[1], numberStart - match.range.last - 1, true)
                    numberEnd < match.range.first ->
                        Candidate(number.groupValues[1], match.range.first - numberEnd - 1, false)
                    else -> null
                }
            }.toList()
        }
        return candidates.filter { it.afterKeyword }.minByOrNull { it.distance }?.code
            ?: candidates.filterNot { it.afterKeyword }.minByOrNull { it.distance }?.code
    }

    /** Find a mainland mobile number in mixed text; accept a plain international number only when it is the whole clip. */
    fun extractPhone(raw: String): String? {
        val match = MAINLAND_PHONE.find(raw)
        if (match != null) {
            val digits = match.groupValues[2].filter(Char::isDigit)
            val prefix = match.groupValues[1]
            return if (prefix.isNotBlank()) "+86$digits" else digits
        }
        val value = raw.trim()
        if (value.length !in 7..40 || !PLAIN_PHONE.matches(value)) return null
        val digits = value.filter(Char::isDigit)
        if (digits.length !in 7..15) return null
        return if (value.startsWith("+")) "+$digits" else digits
    }

    private fun findWebUrl(raw: String): String? =
        URL_IN_TEXT.findAll(raw).mapNotNull { match ->
            val candidate = match.value.trimEnd('.', ',', ';', ':', '!', '?', '，', '。', '；', '：', '！', '？', ')', '）', ']', '】', '}', '》')
            webUrl(candidate)
        }.firstOrNull()

    private val DOMAIN_PATTERN = Regex("^(?:www\\.)?(?:[A-Za-z0-9-]+\\.)+[A-Za-z]{2,}(?::\\d+)?(?:/[^\\s]*)?(?:\\?[^\\s]*)?(?:#[^\\s]*)?$")

    private val MAINLAND_PHONE = Regex("(?<!\\d)(\\+?86[\\s-]?)?(1[3-9](?:[\\s-]?\\d){9})(?!\\d)")
    private val PLAIN_PHONE = Regex("[+0-9()\\-\\s]+")
    private val URL_IN_TEXT = Regex("""(?i)(?:https?://|www\.)[^\s<>"]+|(?<![\w@])(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,}(?::\d+)?(?:/[^\s<>"]*)?(?:\?[^\s<>"]*)?(?:#[^\s<>"]*)?""")

}
