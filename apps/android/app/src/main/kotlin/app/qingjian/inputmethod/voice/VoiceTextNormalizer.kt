package app.qingjian.inputmethod.voice

/**
 * 语音识别文本轻量清洗：只移除独立的语气填充词，合并中文字符间因停顿产生的空格。
 * 不删除「然后」「就是」等可能承载语义的连接词，也保留英文单词之间的空格。
 */
internal object VoiceTextNormalizer {
    private val interjections = Regex(
        "(^|[，,。！？、；：\\s])(?:嗯+|呃+|额+|唔+|啊+)(?=$|[，,。！？、；：\\s])"
    )
    private val cjkPause = Regex("(?<=[\\u3400-\\u9FFF])\\s+(?=[\\u3400-\\u9FFF])")
    private val beforePunctuation = Regex("\\s+([，。！？、；：])")
    private val repeatedPunctuation = Regex("([，。！？、；：]){2,}")

    fun normalize(input: String): String {
        if (input.isBlank()) return ""
        return input
            .replace('\u0000'.toString(), "")
            .replace(interjections) { it.groupValues[1] }
            .replace(cjkPause, "")
            .replace(beforePunctuation, "$1")
            .replace(repeatedPunctuation) { it.value.take(1) }
            .replace(Regex("[ \\t]{2,}"), " ")
            .trim()
    }
}
