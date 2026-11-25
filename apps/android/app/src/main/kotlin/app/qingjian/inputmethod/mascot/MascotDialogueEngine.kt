package app.qingjian.inputmethod.mascot

/** 本地关键词触发器：仅匹配光标前刚提交的短文本，不联网、不上传。 */
data class MascotReaction(val message: String, val animation: String)

object MascotDialogueEngine {
    fun reaction(textBeforeCursor: String): MascotReaction? {
        val text = textBeforeCursor.trim().takeLast(24)
        if (text.isBlank()) return null
        val rules = listOf(
            listOf("你好", "您好", "嗨", "哈喽", "hello") to MascotReaction("你好呀！见到你真开心～", "wave"),
            listOf("开心", "高兴", "太好了", "棒极了", "好耶") to MascotReaction("看你这么开心，我也跟着开心起来啦！", "happy"),
            listOf("加油", "努力", "坚持", "冲呀", "冲吧") to MascotReaction("加油加油！我会一直给你打气的！", "cheer"),
            listOf("谢谢", "感谢", "多谢") to MascotReaction("不客气呀，这是我应该做的～", "thanks"),
            listOf("难过", "伤心", "失落", "委屈", "心累") to MascotReaction("抱抱你～别着急，我陪你慢慢来。", "comfort"),
            listOf("生气", "气死", "讨厌", "郁闷") to MascotReaction("先深呼吸一下，我在这儿陪你。", "comfort"),
            listOf("晚安", "睡觉", "睡了") to MascotReaction("晚安啦，祝你做个甜甜的梦！", "idle"),
            listOf("早安", "早上好") to MascotReaction("早上好呀，今天也元气满满！", "wave"),
            listOf("再见", "拜拜", "回头见") to MascotReaction("拜拜啦，期待下次见面！", "wave"),
            listOf("哈哈", "笑死", "有趣") to MascotReaction("嘿嘿，开心就好！", "happy")
        )
        return rules.firstOrNull { (keys, _) -> keys.any { text.endsWith(it, ignoreCase = true) } }?.second
    }

    fun response(textBeforeCursor: String): String? = reaction(textBeforeCursor)?.message
}
