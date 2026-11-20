package app.qingjian.inputmethod.mascot

/** 纯本地轻量语义触发器：仅匹配光标前刚输入的短文本，不联网、不上传。 */
object MascotDialogueEngine {
    fun response(textBeforeCursor: String): String? {
        val text = textBeforeCursor.trim().takeLast(24)
        if (text.isBlank()) return null
        val rules = listOf(
            listOf("你好", "您好", "嗨", "哈喽", "hello") to "你好呀！见到你真开心～",
            listOf("开心", "高兴", "太好了", "棒极了", "好耶") to "看你这么开心，我也跟着开心起来啦！",
            listOf("加油", "努力", "坚持", "冲呀", "冲吧") to "加油加油！我会一直给你打气的！",
            listOf("谢谢", "感谢", "多谢") to "不客气呀，这是我应该做的～",
            listOf("难过", "伤心", "失落", "委屈", "心累") to "抱抱你～别着急，我陪你慢慢来。",
            listOf("生气", "气死", "讨厌", "郁闷") to "先深呼吸一下，我在这儿陪你。",
            listOf("晚安", "睡觉", "睡了") to "晚安啦，祝你做个甜甜的梦！",
            listOf("早安", "早上好") to "早上好呀，今天也元气满满！",
            listOf("再见", "拜拜", "回头见") to "拜拜啦，期待下次见面！",
            listOf("哈哈", "笑死", "有趣") to "嘿嘿，开心就好！"
        )
        return rules.firstOrNull { (keys, _) -> keys.any { text.endsWith(it, ignoreCase = true) } }?.second
    }
}
