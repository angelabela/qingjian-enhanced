package app.qingjian.inputmethod.core.store

import android.content.Context

/**
 * 本机混合编码词条。只存储用户明确输入并以空格/分隔符结束的字母数字符号串；
 * 不接云端、不参与私密输入，也不写入统计数据。
 */
object StructuredTokenStore {
    private const val PREFS = "qingjian_structured_token_memory"
    private const val KEY_TOKENS = "tokens"
    private const val MAX_TOKENS = 600

    @Synchronized
    fun remember(context: Context, token: String) {
        if (!isRememberable(token)) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val tokens = LinkedHashSet(prefs.getStringSet(KEY_TOKENS, emptySet()).orEmpty())
        tokens.remove(token)
        tokens.add(token)
        while (tokens.size > MAX_TOKENS) {
            val oldest = tokens.firstOrNull() ?: break
            tokens.remove(oldest)
        }
        prefs.edit().putStringSet(KEY_TOKENS, tokens).apply()
    }

    @Synchronized
    fun suggest(context: Context, prefix: String): List<String> {
        if (prefix.length < 2) return emptyList()
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_TOKENS, emptySet()).orEmpty()
            .asSequence()
            .filter { it.length > prefix.length && it.startsWith(prefix) }
            .sortedWith(compareBy<String>({ it.length }, { it }))
            .take(5)
            .toList()
    }

    private fun isRememberable(token: String): Boolean {
        if (token.length !in 4..64 || token.any { it.isWhitespace() || it.code >= 128 }) return false
        val hasLetter = token.any(Char::isLetter)
        val hasDigit = token.any(Char::isDigit)
        val hasSymbol = token.any { !it.isLetterOrDigit() }
        return hasLetter && (hasDigit || hasSymbol)
    }
}
