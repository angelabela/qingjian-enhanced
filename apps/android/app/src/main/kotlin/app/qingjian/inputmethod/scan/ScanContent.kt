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
        data class Phones(val numbers: List<String>) : ClipboardAction
    }

    /** Detect one actionable item from clipboard text; Wi-Fi credentials take precedence and stay hidden. */
    fun detectClipboardAction(raw: String): ClipboardAction? {
        if (raw.isBlank()) return null
        parseWifi(raw)?.let { return ClipboardAction.Wifi(it) }
        extractSmsCode(raw)?.let { return ClipboardAction.SmsCode(it) }
        findWebUrl(raw)?.let { return ClipboardAction.Url(it) }
        val phones = extractPhones(raw)
        if (phones.size == 1) return ClipboardAction.Phone(phones.first())
        if (phones.size > 1) return ClipboardAction.Phones(phones)
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
        // 先处理运营商常见格式：验证码是[8542]、随机码【7780】。
        // 允许关键词后有少量中文说明，但数字必须是独立的 4–6 位，不能截取手机号/长编号。
        val bracketed = Regex(
            "(?:短信验证码|验证码|校验码|动态码|短信码|随机码|安全码|确认码|verification\\s*code|one[- ]time\\s+password|\\bOTP\\b)" +
                "[^0-9]{0,20}(?<!\\d)(\\d{4,6})(?!\\d)",
            RegexOption.IGNORE_CASE,
        ).find(text)
        bracketed?.groupValues?.getOrNull(1)?.let { return it }

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

    /**
     * 严格提取号码：大陆手机号必须完整符合 1[3-9]xxxxxxxxx 的 11 位结构，
     * 不允许从更长数字串中截取；纯国际号码只在剪贴板内容整体就是号码时接受。
     */
    fun extractPhones(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val found = linkedSetOf<String>()
        MAINLAND_PHONE.findAll(raw).forEach { match ->
            val before = raw.substring(0, match.range.first).trimEnd(' ', '\t', '-')
            val after = raw.substring(match.range.last + 1).trimStart(' ', '\t', '-')
            // 避免从带分隔符的长编号中截取 11 位，也拒绝明显的分机尾号。
            val previousPhone = MAINLAND_PHONE.findAll(before).any { it.range.last == before.lastIndex } ||
                MAINLAND_LANDLINE.findAll(before).any { it.range.last == before.lastIndex }
            val nextPhone = MAINLAND_PHONE.findAll(after).any { it.range.first == 0 } ||
                MAINLAND_LANDLINE.findAll(after).any { it.range.first == 0 }
            if (before.lastOrNull()?.isDigit() == true && !previousPhone) return@forEach
            if (after.firstOrNull()?.isDigit() == true && !nextPhone) return@forEach
            if (Regex("^(?:转|分机|ext\\.?)\\s*\\d", RegexOption.IGNORE_CASE).containsMatchIn(after)) return@forEach
            val digits = match.groupValues[2].filter(Char::isDigit)
            if (digits.length != 11 || digits[0] != '1' || digits[1] !in '3'..'9') return@forEach
            found += if (match.groupValues[1].isNotBlank()) "+86$digits" else digits
        }
        MAINLAND_LANDLINE.findAll(raw).forEach { match ->
            val before = raw.substring(0, match.range.first).trimEnd(' ', '\t', '-')
            val after = raw.substring(match.range.last + 1).trimStart(' ', '\t', '-')
            val previousPhone = MAINLAND_PHONE.findAll(before).any { it.range.last == before.lastIndex } ||
                MAINLAND_LANDLINE.findAll(before).any { it.range.last == before.lastIndex }
            val nextPhone = MAINLAND_PHONE.findAll(after).any { it.range.first == 0 } ||
                MAINLAND_LANDLINE.findAll(after).any { it.range.first == 0 }
            if (before.lastOrNull()?.isDigit() == true && !previousPhone) return@forEach
            if (after.firstOrNull()?.isDigit() == true && !nextPhone) return@forEach
            if (Regex("^(?:转|分机|ext\\.?)\\s*\\d", RegexOption.IGNORE_CASE).containsMatchIn(after)) return@forEach
            val digits = match.groupValues[1].filter(Char::isDigit)
            if (digits.length !in 10..12) return@forEach
            found += digits
        }
        if (found.isNotEmpty()) return found.toList()

        val value = raw.trim()
        if (!value.startsWith("+") || !PLAIN_PHONE.matches(value)) return emptyList()
        val digits = value.filter(Char::isDigit)
        if (digits.length !in 7..15) return emptyList()
        if (value.count { it == '(' } != value.count { it == ')' }) return emptyList()
        if (value.indexOf('+') != 0 || value.count { it == '+' } != 1) return emptyList()
        var parenthesisDepth = 0
        for (ch in value) {
            when (ch) {
                '(' -> { if (parenthesisDepth != 0) return emptyList(); parenthesisDepth++ }
                ')' -> { if (parenthesisDepth == 0) return emptyList(); parenthesisDepth-- }
            }
        }
        if (parenthesisDepth != 0 || value.contains("++") || value.contains("--") || value.contains("  ")) return emptyList()
        return listOf("+$digits")
    }

    /** 兼容单号码调用；有多个号码时返回 null，避免静默选中第一个。 */
    fun extractPhone(raw: String): String? = extractPhones(raw).singleOrNull()

    private fun findWebUrl(raw: String): String? =
        URL_IN_TEXT.findAll(raw).mapNotNull { match ->
            val candidate = match.value.trimEnd('.', ',', ';', ':', '!', '?', '，', '。', '；', '：', '！', '？', ')', '）', ']', '】', '}', '》')
            webUrl(candidate)
        }.firstOrNull()

    private val DOMAIN_PATTERN = Regex("^(?:www\\.)?(?:[A-Za-z0-9-]+\\.)+[A-Za-z]{2,}(?::\\d+)?(?:/[^\\s]*)?(?:\\?[^\\s]*)?(?:#[^\\s]*)?$")

    private val MAINLAND_PHONE = Regex("(?<![A-Za-z0-9])(\\+?86[ \\t-]?)?(1[3-9](?:[ \\t-]?\\d){9})(?![A-Za-z0-9])")
    private val MAINLAND_LANDLINE = Regex("(?<![A-Za-z0-9])(0(?:10|2[0-9]|[3-9][0-9]{2})[ \\t-]?[0-9]{7,8})(?![A-Za-z0-9])")
    private val PLAIN_PHONE = Regex("^\\+[0-9][0-9() .-]{5,25}[0-9]$")
    private val URL_IN_TEXT = Regex("""(?i)(?:https?://|www\.)[^\s<>"]+|(?<![\w@])(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,}(?::\d+)?(?:/[^\s<>"]*)?(?:\?[^\s<>"]*)?(?:#[^\s<>"]*)?""")

}
