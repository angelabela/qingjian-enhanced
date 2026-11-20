package app.qingjian.inputmethod.service.input

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import app.qingjian.inputmethod.core.Engine
import app.qingjian.inputmethod.core.InputOptions
import app.qingjian.inputmethod.core.frame.Candidate
import app.qingjian.inputmethod.core.frame.Frame
import app.qingjian.inputmethod.keyboard.ShiftState
import app.qingjian.inputmethod.keyboard.layout.Key
import app.qingjian.inputmethod.keyboard.layout.KeyboardLayout
import app.qingjian.inputmethod.settings.Settings
import kotlin.math.abs

/**
 * 输入逻辑：把键盘的键翻成 Engine 操作，把帧落到输入框（组句文本）上，再让 [Host] 重画。
 * 不碰视图，也不管服务的生命周期。
 */
class Composer(private val engine: Engine, private val host: Host) {
    private data class ClearSnapshot(val text: String, val selectionStart: Int, val selectionEnd: Int)

    /** 最近六步输入前的编辑框快照；每个词/短句作为一组，撤销时逐步回退。 */
    private val undoHistory = ArrayDeque<ClearSnapshot>()
    /** 面向普通编辑的 50 步撤销/重做历史；与「撤销清空」旧快照分开。 */
    private val editHistory = InputEditHistory()
    private var lastClearSnapshot: ClearSnapshot? = null
    private var pendingBackspaceSnapshot: ClearSnapshot? = null
    /** 删除键手势开始时的快照；普通退格后仍保留，供同一次上滑清空使用。 */
    private var clearGestureSnapshot: ClearSnapshot? = null
    private var undoGroupOpen = false
    private val undoGroupTimeout = Runnable { if (!composing) undoGroupOpen = false }

    private val handler = Handler(Looper.getMainLooper())

    interface Host {
        val connection: InputConnection?

        /** 当前宿主是否存在选区；选区替换不猜测原文，避免错误撤销。 */
        val selectionActive: Boolean

        /** Shift 的状态；单次的出过一个大写字母后由这边熄掉。 */
        var shift: ShiftState

        fun sendKey(keyCode: Int)

        /** 执行输入框的缺省动作（搜索、发送、下一项）；没有缺省动作返回 false。 */
        fun performEditorAction(): Boolean

        fun showComposition(preedit: String, candidates: List<Candidate>)

        /** 键位表变了（中英、符号页、九宫格）。 */
        fun showKeyboard()

        fun showSideItems(pinyinChoices: List<String>)

        /** 打开分类符号面板。 */
        fun openSymbolPanel()

        /** 语言或中英键的另一边变了：记下来，中英键重画。 */
        fun onLanguageChanged()

        /** 一句话提示（删候选的结果）。 */
        fun showNotice(text: String)

        /** 用户确认上屏的内容：供 Android 壳保存个人短语缩写。 */
        fun onPhraseCommitted(text: String)

        /** 保存/查询本机的混合字母、数字与符号词条；私密输入时 Composer 不会调用。 */
        fun rememberStructuredToken(token: String)
        fun structuredTokenSuggestions(prefix: String): List<String>
    }

    /** 上一帧的 preedit，非空即在组句。 */
    private var preedit = ""

    /** 这段组句里最近交给输入框的几版 preedit（新的在后），重启输入后认输入框里停在哪一版，见 [resume]。 */
    private val sentPreedits = ArrayDeque<String>()

    /** 重启输入后输入框里的组合文字标记没了，下次改输入框前要先删掉留在框里的拼音，见 [resume]。 */
    private var composingLost = false

    private var pinyinChoices: List<String> = emptyList()

    /** 候选条上是补全（邮箱后缀），见 [refreshCompletions]。 */
    private var completing = false

    /** 删除键按下时在组句（见 [onBackspaceDown]）。 */
    private var backspaceComposing = false

    /** 删除键按下时光标前的文字，上划清空的撤销用；组句中为 null。 */
    private var beforeBackspace: String? = null

    /** 这个输入框不过引擎，见 [wantsDirectInput]。 */
    var directInput = false

    /** 网址框：标点不转全角、敲标点时原样落下字母，见 [wantsPlainPunctuation]。 */
    var plainPunctuation = false

    /** 私密输入（不学习、不记统计与生词、不发云端），见 [wantsPrivacy]；引擎没装好时先记着，装好了在 [onEngineReady] 里补。 */
    var privateInput = false
        set(value) {
            field = value
            engine.setPrivate(value)
        }

    /** 现在用的语言。 */
    var language = Language.CHINESE
        private set

    /** 中英键按一下切到的另一种语言；与 [language] 是一对。 */
    var partner = Language.ENGLISH
        private set

    /** 长按挑语言时另一边固定是中文（缺省开）；关了就是「当前语言 ↔ 挑的那种」，见 [chooseLanguage]。 */
    var keepChinese = true

    val chinese: Boolean get() = language == Language.CHINESE

    /** 日语罗马音：敲的字母进 Core 的罗马音缓冲区，不经拼音引擎。 */
    val japanese: Boolean get() = language == Language.JAPANESE

    private val english: Boolean get() = language == Language.ENGLISH

    /** 上次点 Shift 的时刻，两次点得够近就锁定。 */
    private var lastShiftTap = 0L

    /** 键盘停在哪一页：字母、常用符号页（英文 !?#）、数字键盘（123）。 */
    enum class Page { LETTERS, SYMBOLS, NUMBERS }

    var page = Page.LETTERS
        private set

    var layout = Settings.Layout.QWERTY
        set(value) {
            if (field == value) return
            field = value
            applyScheme()
        }

    /** 候选出繁体（功能面板的「繁体」）；引擎没装好时先记着，装好了在 [onEngineReady] 里补上。 */
    var traditional = false
        set(value) {
            if (field == value) return
            field = value
            if (engine.ready) apply(engine.setTraditional(value))
        }

    /** 九宫格只管中文；英文模式退回全键盘。 */
    val nineKey: Boolean get() = layout == Settings.Layout.NINE_KEY && chinese

    /** 注音（大千）同样只管中文，英文退回全键盘。 */
    val zhuyin: Boolean get() = layout == Settings.Layout.ZHUYIN && chinese

    /** 双拼、五笔也只管中文；英文退回全键盘、英文模式。 */
    val shuangpin: Boolean get() = layout == Settings.Layout.SHUANGPIN && chinese

    val wubi: Boolean get() = layout == Settings.Layout.WUBI && chinese

    /** 双拼方案与 preedit 写法、五笔混输（键盘设置里的，同 PC 的 `[general]` 那几项）。 */
    var shuangpinScheme = Settings.DEFAULT_SHUANGPIN
    var shuangpinRawPreedit = false
    var wubiMixed = false

    /** 模糊音、中文标点全半角、打拼音时给不给英文词（键盘设置里的，交给 Core）；引擎没装好时先记着。 */
    var options = InputOptions()
        set(value) {
            if (field == value) return
            field = value
            if (engine.ready) apply(engine.setOptions(value))
        }

    /** 英文键盘也组句给英文词候选；关了字母直接上屏。 */
    var englishCandidates = true

    /** 敲的字母进组句：中文、日语总是进，英文看 [englishCandidates]。 */
    private val composesLetters: Boolean get() = !directInput && (chinese || japanese || englishCandidates)

    private val composing: Boolean get() = preedit.isNotEmpty()

    /** 组句处在算式（`v1+2`）或码点里，见 [Frame.expression]。 */
    private var expression = false

    /** 这段组句在单字模式，见 [Frame.singleChars]。 */
    var singleChars = false
        private set

    /** 上一帧的候选。 */
    private var candidates: List<Candidate> = emptyList()
    private var localCompletionStart = Int.MAX_VALUE
    private var localCompletions: List<String> = emptyList()
    private val structuredTokenBuffer = StringBuilder()
    private var structuredCompletionStart = Int.MAX_VALUE
    private var structuredCompletions: List<String> = emptyList()

    /**
     * 滑完一笔（[input] 是轨迹与键位的 JSON）：同点字母，组句时解出的字母进组句，
     * 字母直接上屏时上屏解出的整串（Shift 单次首字母大写，锁定全大写）。
     */
    fun onSwipe(input: String) {
        if (input.isNotEmpty()) lastClearSnapshot = null
        if (!undoGroupOpen) captureUndoSnapshot()
        undoGroupOpen = true
        handler.removeCallbacks(undoGroupTimeout)
        handler.postDelayed(undoGroupTimeout, UNDO_GROUP_TIMEOUT_MS)
        if (composing || (engine.ready && composesLetters && !host.shift.on)) {
            apply(engine.swipe(input))
            return
        }
        val letters = engine.decodeSwipe(input)
        if (letters.isEmpty()) return
        commit(
            when (host.shift) {
                ShiftState.LOCKED -> letters.uppercase()
                ShiftState.ONCE -> letters.replaceFirstChar { it.uppercase() }
                ShiftState.OFF -> letters
            },
        )
        consumeShift()
    }

    /** 将本次混合编码按原样暂存；空格/普通标点等边界到来时再落入本机词库。 */
    private fun trackStructuredTokenInput(key: Key) {
        if (privateInput || directInput) {
            structuredTokenBuffer.clear()
            structuredCompletions = emptyList()
            structuredCompletionStart = Int.MAX_VALUE
            return
        }
        when (key) {
            is Key.Letter -> {
                val char = if (host.shift.on) key.char.uppercaseChar() else key.char
                structuredTokenBuffer.append(char)
            }
            is Key.Digit -> if (key.digit != SEPARATOR_DIGIT) structuredTokenBuffer.append(key.digit)
            is Key.Text -> {
                if (key.text.isNotEmpty() && key.text.all(::isStructuredTokenChar)) {
                    structuredTokenBuffer.append(key.text)
                } else {
                    flushStructuredToken()
                }
            }
            is Key.Space, is Key.Enter -> flushStructuredToken()
            Key.Backspace -> if (structuredTokenBuffer.isNotEmpty()) {
                structuredTokenBuffer.deleteCharAt(structuredTokenBuffer.length - 1)
            }
            Key.Clear -> {
                structuredTokenBuffer.clear()
                structuredCompletions = emptyList()
                structuredCompletionStart = Int.MAX_VALUE
            }
            else -> Unit
        }
    }

    private fun isStructuredTokenChar(char: Char): Boolean =
        char.code in 33..126

    /** 混合大小写、数字或符号出现后，后续编码按原样落字，不交给中文候选改写。 */
    private fun isStructuredTokenMode(): Boolean =
        structuredTokenBuffer.any { it.isUpperCase() || it.isDigit() || !it.isLetterOrDigit() }

    fun finishStructuredToken() = flushStructuredToken()

    private fun flushStructuredToken() {
        val token = structuredTokenBuffer.toString()
        val hasLetter = token.any { it.isLetter() }
        val hasDigit = token.any { it.isDigit() }
        val hasSymbol = token.any { !it.isLetterOrDigit() }
        if (token.length in 4..64 && hasLetter && (hasDigit || hasSymbol)) {
            host.rememberStructuredToken(token)
        }
        structuredTokenBuffer.clear()
        structuredCompletions = emptyList()
        structuredCompletionStart = Int.MAX_VALUE
    }

    /** 组句时把本机混合编码候选追加到 Core 候选末尾，选择后替换当前 preedit。 */
    private fun showCandidatesWithStructured(base: List<Candidate>) {
        val prefix = structuredTokenBuffer.toString()
        structuredCompletions = if (
            composing && !privateInput && !directInput && prefix.length >= 2
        ) {
            host.structuredTokenSuggestions(prefix).filter { it != prefix }.take(5)
        } else {
            emptyList()
        }
        structuredCompletionStart = if (structuredCompletions.isEmpty()) Int.MAX_VALUE else 0
        candidates = structuredCompletions.map { Candidate(text = it, senses = emptyList()) } + base
        host.showComposition(preedit, candidates)
    }

    fun onKey(key: Key) {
        if (key != Key.Backspace) lastClearSnapshot = null
        trackStructuredTokenInput(key)
        val groupedEdit = key is Key.Space || key is Key.Text
        if (groupedEdit) editHistory.beginGroup()
        try {
        if (key == Key.Backspace) {
            pendingBackspaceSnapshot?.let(::pushUndoSnapshot)
            pendingBackspaceSnapshot = null
        }
        val composingBeforeKey = composing
        if (key != Key.Backspace && key != Key.Clear) {
            if (!undoGroupOpen) captureUndoSnapshot()
            val continuesWord = composingBeforeKey || key is Key.Letter || key is Key.Zhuyin || key is Key.Digit
            if (continuesWord) {
                undoGroupOpen = true
                handler.removeCallbacks(undoGroupTimeout)
                handler.postDelayed(undoGroupTimeout, UNDO_GROUP_TIMEOUT_MS)
            } else if (key is Key.Space || key is Key.Text || key is Key.Enter) {
                closeUndoGroup()
            }
        }
        // 候选条上放着电话邮箱或「撤销」时，删除、重输先只收起它们，不动输入框里的字
        if (key == Key.Clear && !composing && completing && engine.hasOffer()) {
            apply(engine.clear())
            refreshCompletions()
            return
        }
        when (key) {
            is Key.Letter -> when {
                // 中文拼音页开启大写（单次或锁定）时，字母键改为直接上屏大写字母，不再送入拼音缓冲区。
                chinese && host.shift.on -> {
                    if (composing) {
                        if (isStructuredTokenMode()) apply(engine.commitRaw()) else selectFirst()
                    }
                    commit(key.char.uppercaseChar().toString())
                    consumeShift()
                }

                // 英文的大小写跟着敲的走（`Compa` 出 Company），验证码这类大小写混着的串也在组句里敲
                english && (composing || (engine.ready && composesLetters)) -> {
                    apply(engine.push((if (host.shift.on) key.char.uppercaseChar() else key.char).code))
                    consumeShift()
                }

                composing || (engine.ready && composesLetters &&
                    (!host.shift.on || (chinese && !shuangpin && !wubi && !zhuyin))) -> {
                    // 中文全拼下 Shift 只改变键面大小写，送进拼音引擎的字母始终保持小写。
                    apply(engine.push(key.char.lowercaseChar().code))
                    consumeShift()
                }

                else -> {
                    commit(if (host.shift.on) key.label.uppercase() else key.label)
                    consumeShift()
                }
            }

            is Key.Zhuyin ->
                if (engine.ready && !directInput) apply(engine.push(key.code.code)) else commit(key.label)

            is Key.Digit -> when {
                !engine.ready -> Unit
                key.digit != SEPARATOR_DIGIT && structuredTokenBuffer.any { it.isLetter() } -> {
                    if (composing) apply(engine.commitRaw())
                    commit(key.digit.toString())
                }
                key.digit != SEPARATOR_DIGIT -> apply(engine.push(key.digit.code))
                // 分词键只在组句中有意义
                composing -> apply(engine.push('\''.code))
            }

            is Key.Text -> {
                // 日语里的长音：罗马音的 `-`，进缓冲区转成「ー」
                if (japanese && engine.ready && !directInput && key.text in LONG_VOWEL) {
                    apply(engine.push('-'.code))
                    return
                }
                if (composing && expression) {
                    onExpressionText(key.text)
                    return
                }
                when {
                    !composing -> Unit
                    // 敲邮箱：@ 前的字母是用户名，原样落下，不顶首选
                    key.text == "@" && !zhuyin -> apply(engine.commitRaw())
                    // 英文同空格：数字、标点前落下敲的字母，不换成候选（验证码、型号这类串不是词）
                    english || plainPunctuation || isStructuredTokenMode() -> apply(engine.commitRaw())
                    else -> selectFirst()
                }
                // 中文标点键：Core 按设置转全角（引号成对、数字后的点保持半角）或原样
                val punct = key.punct
                commitSymbol(
                    when {
                        punct == null -> key.text
                        // 网址框里中文键盘的「。」「，」也落半角
                        plainPunctuation -> punct.toString()
                        chinese && !directInput -> engine.punctuate(punct)
                        else -> key.text
                    },
                    pairable = !plainPunctuation && !directInput,
                )
            }

            Key.Clear -> if (composing) apply(engine.clear())

            // 拼音、日语组句里的大写字母另有含义，组句中只有英文能换大小写
            Key.Shift -> if (!composing || english || (chinese && !shuangpin && !wubi && !zhuyin)) toggleShift()

            Key.Backspace -> {
                if (composing) apply(engine.backspace()) else {
                    deleteBackward()
                    refreshCompletions()
                }
            }

            is Key.Space -> when {
                !composing -> commit(" ")
                chinese || japanese -> apply(engine.space())
                // 英文模式：敲的字母原样上屏，空格照给
                else -> {
                    apply(engine.commitRaw())
                    commit(" ")
                }
            }

            is Key.Enter -> when {
                composing -> settle()
                !host.performEditorAction() -> host.sendKey(KeyEvent.KEYCODE_ENTER)
            }

            is Key.Symbols -> {
                if (composing) selectFirst()
                host.openSymbolPanel()
            }

            is Key.SymbolPage, is Key.NumberPage, is Key.Letters -> {
                // 算式里换去数字键盘接着打，不顶首选
                if (composing && !expression) selectFirst()
                page = when (key) {
                    is Key.SymbolPage -> Page.SYMBOLS
                    is Key.NumberPage -> Page.NUMBERS
                    else -> Page.LETTERS
                }
                host.showKeyboard()
            }

            Key.Language -> {
                val from = language
                switchTo(partner)
                partner = from
                host.onLanguageChanged()
            }
        }
        if (key is Key.Space || key is Key.Text || key is Key.Enter) closeUndoGroup()
        if (!composing && key != Key.Backspace && key != Key.Clear) refreshCompletions()
        } finally {
            if (groupedEdit) editHistory.endGroup()
        }
    }

    /** 上次用的语言与另一边（从设置里读回来）；引擎的模式在 [onEngineReady] 里补。 */
    fun restoreLanguages(language: Language, partner: Language) {
        this.language = language
        this.partner = when {
            partner != language -> partner
            language == Language.CHINESE -> Language.ENGLISH
            else -> Language.CHINESE
        }
    }

    /**
     * 长按中英键或键盘选择里挑了 [target]：切过去，并把中英键的另一边定下来。[keepChinese] 开着另一边总有中文
     * （挑中文时另一边是切走前那种）；关着另一边就是切走前的语言，英 ↔ 日 这种也行。
     */
    fun chooseLanguage(target: Language) {
        if (target == language) return
        val from = language
        partner = if (keepChinese && target != Language.CHINESE && from != Language.CHINESE) Language.CHINESE else from
        switchTo(target)
        host.onLanguageChanged()
    }

    /** 切到 [target]：离开日语先关罗马音缓冲区，进英文 / 中文换引擎的模式；组句中的输入由会话先落下。 */
    private fun switchTo(target: Language) {
        if (target == language) return
        if (composing && zhuyin) selectFirst()
        val wasJapanese = japanese
        language = target
        if (engine.ready) {
            if (wasJapanese) apply(engine.setJapanese(false))
            applyLanguage()
        }
        host.showKeyboard()
    }

    /** 引擎按 [language] 设好模式。 */
    private fun applyLanguage() {
        if (japanese) {
            apply(engine.setJapanese(true))
        } else {
            apply(engine.setEnglishMode(!chinese))
            applyScheme()
        }
    }

    /** 符号 / 表情面板里点的一格：组句中先顶首选再上屏。 */
    fun commitPanelText(text: String) {
        editHistory.beginGroup()
        try {
            if (composing) selectFirst()
            commit(text)
        } finally {
            editHistory.endGroup()
        }
    }

    /** 扫码等服务侧产生的单次上屏也进入统一历史。 */
    fun commitExternalText(text: String, connection: InputConnection? = host.connection): Boolean {
        if (text.isEmpty()) return true
        val target = connection ?: return false
        if (host.selectionActive) {
            editHistory.clear()
            return target.commitText(text, 1)
        }
        val before = editHistory.capture(target)
        val committed = target.commitText(text, 1)
        if (committed) editHistory.record(before, editHistory.capture(target), insertedBefore = text)
        return committed
    }

    /** 宿主应用通过剪切、粘贴或整段替换改文后，废弃无法再安全验证的旧历史。 */
    fun invalidateEditHistory() = editHistory.clear()

    /** 拼音仍在候选栏时，撤销优先取消组合；只有没有组合输入时才回退已上屏编辑。 */
    fun undo(): Boolean {
        if (composing) {
            closeUndoGroup()
            apply(engine.clear())
            return true
        }
        if (host.selectionActive) return false
        val connection = host.connection ?: return false
        return when (editHistory.undo(connection)) {
            InputEditHistory.Result.SUCCESS -> {
                refreshCompletions()
                true
            }
            InputEditHistory.Result.MISMATCH -> {
                host.showNotice("无法撤销")
                false
            }
            InputEditHistory.Result.EMPTY, InputEditHistory.Result.FAILED -> false
        }
    }

    fun redo(): Boolean {
        if (composing || host.selectionActive) return false
        val connection = host.connection ?: return false
        return when (editHistory.redo(connection)) {
            InputEditHistory.Result.SUCCESS -> {
                refreshCompletions()
                true
            }
            InputEditHistory.Result.MISMATCH -> {
                host.showNotice("无法重做")
                false
            }
            InputEditHistory.Result.EMPTY, InputEditHistory.Result.FAILED -> false
        }
    }

    /** 开始说话前：组句落下（同回车），识别出的字接在后面。 */
    fun prepareVoice() {
        if (composing) settle()
    }

    /** 识别出的字上屏：过引擎（繁体输出、输入历史与统计）；不过引擎的输入框与引擎没装好时原样上屏。 */
    fun commitVoice(text: String) {
        if (text.isNotEmpty()) lastClearSnapshot = null
        if (engine.ready && !directInput) apply(engine.commitVoice(text)) else commit(text)
    }

    /** 异步语音校正只修订紧邻光标、且仍是最近一条编辑的语音文本。 */
    fun refineCommittedVoice(original: String, corrected: String): Boolean {
        val connection = host.connection ?: return false
        return editHistory.amendLastInsertion(connection, original, corrected)
    }

    fun select(index: Int) {
        val structuredIndex = index - structuredCompletionStart
        val structured = structuredCompletions.getOrNull(structuredIndex)
        if (structured != null) {
            val typedPrefix = structuredTokenBuffer.toString()
            val activePreeditLength = preedit.length
            val connection = host.connection
            val beforeState = connection?.let(editHistory::capture)
            structuredCompletions = emptyList()
            structuredCompletionStart = Int.MAX_VALUE
            if (composing) apply(engine.clear())
            val committedPrefixLength = (typedPrefix.length - activePreeditLength).coerceAtLeast(0)
            if (connection == null) {
                structuredTokenBuffer.clear()
                return
            }
            val deleted = if (committedPrefixLength > 0) {
                connection.getTextBeforeCursor(committedPrefixLength, 0)?.toString().orEmpty()
            } else ""
            val expectedCommittedPrefix = typedPrefix.dropLast(activePreeditLength.coerceIn(0, typedPrefix.length))
            if (deleted != expectedCommittedPrefix) {
                host.showNotice("无法替换当前编码")
                structuredTokenBuffer.clear()
                return
            }
            connection.beginBatchEdit()
            val replaced = try {
                val removed = committedPrefixLength == 0 ||
                    connection.deleteSurroundingText(committedPrefixLength, 0)
                if (!removed) {
                    false
                } else if (connection.commitText(structured, 1)) {
                    true
                } else {
                    // 替换失败时恢复刚才清掉的原始编码，避免只剩半截输入。
                    connection.commitText(typedPrefix, 1)
                    false
                }
            } finally {
                connection.endBatchEdit()
            }
            if (replaced) {
                editHistory.record(
                    beforeState,
                    editHistory.capture(connection),
                    deletedBefore = typedPrefix,
                    insertedBefore = structured,
                )
            } else {
                host.showNotice("无法替换当前编码")
            }
            structuredTokenBuffer.clear()
            refreshCompletions()
            return
        }
        val structuredCount = structuredCompletions.size
        structuredTokenBuffer.clear()
        structuredCompletions = emptyList()
        structuredCompletionStart = Int.MAX_VALUE
        val nativeIndex = index - structuredCount
        val localIndex = nativeIndex - localCompletionStart
        val local = localCompletions.getOrNull(localIndex)
        if (local != null) {
            localCompletions = emptyList()
            localCompletionStart = Int.MAX_VALUE
            commit(local)
            refreshCompletions()
        } else {
            apply(engine.select(nativeIndex))
            if (!composing) refreshCompletions()
        }
    }

    /** 展开面板里点了「全部 / 单字」。 */
    fun toggleSingleChars() = apply(engine.toggleSingleChars())

    /** 点了拼音行第 [index] 个字之后：光标挪进拼音里，接着敲的字、退格都在那里。 */
    fun moveCaret(index: Int) {
        if (composing) apply(engine.moveCaret(index))
    }

    fun selectTranslation(index: Int, sense: Int) = apply(engine.selectTranslation(index, sense))

    /** 拼音列：组句中点的是拼音（定下这个音节），平时点的是标点。 */
    fun onSideItem(item: String) {
        if (item in pinyinChoices) apply(engine.pin(item)) else onKey(KeyboardLayout.sideKey(item))
    }

    /** 长按候选选了「删除」：用户词整删、词库词清学习记录，结果用一句话提示。 */
    fun forget(index: Int) = apply(engine.forget(index))

    /**
     * 长按气泡里选的字符。全键盘选小写 = 照常敲这个字母；九宫格组句中选小写 = 这一位就是这个字母
     * （进缓冲区，和拼音列定音节一个道理）。其余（数字、大写、符号）先顶首选再上屏；
     * 角标里有全角写法的标点（`;` `?` 这些）同标点键一样交 Core 按设置转全角。
     */
    fun onKeyChoice(text: String) {
        val letter = text.singleOrNull()?.takeIf { it in 'a'..'z' }
        when {
            // 全键盘长按选小写 = 照常敲这个字母
            letter != null && !nineKey -> onKey(Key.Letter(letter))
            composing && letter != null && engine.ready -> apply(engine.push(letter.code))
            else -> onKey(Key.Text(text, punct = text.singleOrNull()?.takeIf { it in FULL_WIDTH_HINTS }))
        }
    }

    /**
     * 算式里按的符号键：数字与运算符进缓冲区（× ÷ 换成 Core 认的 * /）；`=` 上屏「算式=结果」那条，没有就上屏首选；
     * 别的符号先顶首选再上屏。
     */
    private fun onExpressionText(text: String) {
        val c = text.singleOrNull()?.let(engine::expressionChar)
        when {
            c != null -> apply(engine.push(c.code))
            c == '=' -> {
                val index = candidates.indexOfFirst { '=' in it.text }
                if (index >= 0) select(index) else selectFirst()
            }

            else -> {
                selectFirst()
                commit(text)
            }
        }
    }

    /** 按住空格左右滑：不在组句时按方向键挪光标。 */
    fun moveCursor(steps: Int) {
        // 组句中不响应：落下拼音再挪会把没选的字母上屏；拼音里的光标靠点拼音行挪（[moveCaret]）
        if (steps == 0 || composing) return
        val code = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(abs(steps)) { host.sendKey(code) }
    }

    /**
     * 光标停下（上屏、删字、挪光标之后）且不在组句：按光标前的文字问 Core 有没有补全（「用户名@」后的邮箱后缀），
     * 有就放在候选条上，选中走 [select]。只显示、不写输入框，所以不会再触发光标变化。
     */
    fun refreshCompletions() {
        if (composing) return
        if (!engine.ready || directInput || plainPunctuation || privateInput) {
            structuredCompletions = emptyList()
            structuredCompletionStart = Int.MAX_VALUE
            return
        }
        val before = host.connection?.getTextBeforeCursor(COMPLETION_CONTEXT, 0)?.toString().orEmpty()
        val symbols = punctuationSuggestions(before)
        if (before.isEmpty()) {
            completing = false
            localCompletions = emptyList()
            localCompletionStart = Int.MAX_VALUE
            structuredCompletions = emptyList()
            structuredCompletionStart = Int.MAX_VALUE
            candidates = emptyList()
            host.showComposition("", emptyList())
            return
        }
        // 普通中文词句也请求 Core 的上下文补全；不能只在邮箱、网址或标点命中时才显示候选。
        val frame = engine.complete(before)
        localCompletionStart = frame.candidates.size
        localCompletions = symbols
        val base = frame.candidates + symbols.filterNot { symbol -> frame.candidates.any { it.text == symbol } }
            .map { Candidate(text = it, senses = emptyList()) }
        val prefix = structuredTokenBuffer.toString()
        structuredCompletions = if (prefix.length >= 2) {
            host.structuredTokenSuggestions(prefix).filter { it != prefix }.take(5)
        } else {
            emptyList()
        }
        structuredCompletionStart = if (structuredCompletions.isEmpty()) Int.MAX_VALUE else 0
        val merged = structuredCompletions.map { Candidate(text = it, senses = emptyList()) } + base
        candidates = merged
        completing = merged.isNotEmpty()
        host.showComposition("", merged)
    }

    /** 依据句尾语义排序标点候选；明确问句优先问号，情绪表达优先叹号，承接语优先逗号。 */
    private fun punctuationSuggestions(before: String): List<String> {
        if (before.isEmpty() || before != before.trimEnd()) return emptyList()
        if (before.last() in "，。！？；：、,.!?;:") return emptyList()
        val tail = before.takeLast(24)
        val question = Regex("(吗|呢|么|什么|谁|哪个|哪里|何时|为什么|怎么|怎样|怎么样|怎么看|怎么回事|如何|多少|几|是否|能不能|可不可以|有没有|是不是|好不好|行不行|对不对|要不要)$")
        val excited = Regex("(太好了|太棒了|真棒|厉害|恭喜|谢谢|欢迎|完美|加油|好耶|哇|糟糕|竟然|终于)$")
        val connective = Regex("(如果|因为|但是|不过|所以|而且|例如|比如|首先|其次|然后|另外|同时|虽然|既然|一旦|的话|的时候|之后|之前)$")
        return when {
            question.containsMatchIn(tail) -> listOf("？", "。", "！")
            excited.containsMatchIn(tail) -> listOf("！", "。", "？")
            connective.containsMatchIn(tail) -> listOf("，", "。", "；")
            // 没有明确的问句、感叹或承接语义时，不再用通用标点占满候选栏；
            // 此时优先显示 Core 根据光标前文本给出的词语/短语补全。
            else -> emptyList()
        }
    }

    /** 删除键按下时记录状态；清空动作本身统一处理整个输入框。 */
    fun onBackspaceDown() {
        backspaceComposing = composing
        beforeBackspace = null
        val snapshot = readUndoSnapshot()
        pendingBackspaceSnapshot = snapshot
        clearGestureSnapshot = snapshot
        closeUndoGroup()
    }

    /** 删除键下滑：恢复最近一次上划清空前的完整文字与光标/选区。 */
    fun restoreLastClear() {
        val snapshot = lastClearSnapshot ?: run {
            host.showNotice("没有可撤销的清空")
            return
        }
        val connection = host.connection ?: return
        closeUndoGroup()
        // 清空动作可能由宿主的 selectAll 菜单完成，也可能不支持该菜单；
        // 必须验证是否真的选中了全文，否则回退到 InputConnection 的前后文删除。
        connection.beginBatchEdit()
        var restored = false
        try {
            val selectedAll = connection.performContextMenuAction(android.R.id.selectAll) &&
                !connection.getSelectedText(0).isNullOrEmpty()
            if (!selectedAll) {
                for (attempt in 0 until 100) {
                    val before = connection.getTextBeforeCursor(MAX_CLEAR, 0)?.toString().orEmpty()
                    val after = connection.getTextAfterCursor(MAX_CLEAR, 0)?.toString().orEmpty()
                    val selected = connection.getSelectedText(0)?.length ?: 0
                    if (before.isEmpty() && after.isEmpty() && selected == 0) break
                    if (selected > 0) connection.commitText("", 1)
                    else connection.deleteSurroundingText(before.length, after.length)
                    val nextBefore = connection.getTextBeforeCursor(MAX_CLEAR, 0)?.toString().orEmpty()
                    val nextAfter = connection.getTextAfterCursor(MAX_CLEAR, 0)?.toString().orEmpty()
                    if (nextBefore == before && nextAfter == after) break
                }
            }
            // 先清掉 Core 的组句状态，再恢复编辑框，避免空 preedit 覆盖恢复后的光标。
            apply(engine.clear())
            restored = connection.commitText(snapshot.text, 1)
            if (restored) {
                val start = snapshot.selectionStart.coerceIn(0, snapshot.text.length)
                val end = snapshot.selectionEnd.coerceIn(start, snapshot.text.length)
                connection.setSelection(start, end)
            }
        } finally {
            connection.endBatchEdit()
        }
        if (!restored) {
            host.showNotice("撤销清空失败，请重试")
            return
        }
        if (undoHistory.lastOrNull() == snapshot) undoHistory.removeLast() else undoHistory.remove(snapshot)
        lastClearSnapshot = null
        pendingBackspaceSnapshot = null
        clearGestureSnapshot = null
        closeUndoGroup()
        refreshCompletions()
    }

    /** 删除键上划：无论光标位置、选区或是否正在组句，都清空整个输入框。 */
    fun clearAll() {
        val connection = host.connection ?: return
        // 每次清空都必须建立本次快照；不能在捕获失败时沿用上一次清空的旧文本。
        editHistory.clear()
        lastClearSnapshot = null
        val snapshot = clearGestureSnapshot ?: pendingBackspaceSnapshot ?: readUndoSnapshot()
        if (snapshot != null) {
            pushUndoSnapshot(snapshot)
            // 单独保存清空前文本；后续退格按下产生的快照不能覆盖它。
            lastClearSnapshot = snapshot
        }
        clearGestureSnapshot = null
        pendingBackspaceSnapshot = null
        closeUndoGroup()
        backspaceComposing = false
        beforeBackspace = null
        connection.beginBatchEdit()
        try {
            val selectedAll = connection.performContextMenuAction(android.R.id.selectAll) &&
                !connection.getSelectedText(0).isNullOrEmpty()
            if (selectedAll) {
                connection.commitText("", 1)
            } else {
                repeat(100) {
                    val before = connection.getTextBeforeCursor(MAX_CLEAR, 0)?.toString().orEmpty()
                    val after = connection.getTextAfterCursor(MAX_CLEAR, 0)?.toString().orEmpty()
                    val selected = connection.getSelectedText(0)?.length ?: 0
                    if (before.isEmpty() && after.isEmpty() && selected == 0) return@repeat
                    if (selected > 0) connection.commitText("", 1)
                    else connection.deleteSurroundingText(before.length, after.length)
                    val nextBefore = connection.getTextBeforeCursor(MAX_CLEAR, 0)?.toString().orEmpty()
                    val nextAfter = connection.getTextAfterCursor(MAX_CLEAR, 0)?.toString().orEmpty()
                    if (nextBefore == before && nextAfter == after) return@repeat
                }
            }
        } finally {
            connection.endBatchEdit()
        }
        apply(engine.clear())
    }

    /**
     * 删除键下滑：只删除光标左侧的文本，光标右侧内容保持不变。
     * 同时清理引擎组句状态与编辑历史，避免旧预编辑状态在下一次输入时重新出现。
     */
    fun clearBeforeCursor() {
        val connection = host.connection ?: return
        lastClearSnapshot = null
        closeUndoGroup()
        pendingBackspaceSnapshot = null
        clearGestureSnapshot = null
        beforeBackspace = null
        backspaceComposing = false
        val selected = connection.getSelectedText(0)?.toString().orEmpty()
        connection.beginBatchEdit()
        try {
            // 有选区时先删除选区，再重新读取光标左侧文本；不触碰光标右侧内容。
            if (selected.isNotEmpty()) connection.commitText("", 1)
            val before = connection.getTextBeforeCursor(MAX_CLEAR, 0)?.toString().orEmpty()
            if (before.isNotEmpty()) connection.deleteSurroundingText(before.length, 0)
        } finally {
            connection.endBatchEdit()
        }
        editHistory.clear()
        apply(engine.clear())
        closeUndoGroup()
        refreshCompletions()
    }

    /** 组句中光标被点到别处：敲的字母原样落下，不留带 ' 的显示形式。 */
    fun onCursorMovedAway() {
        if (composing) settle()
    }

    /**
     * 同一个输入框重新开始输入（`restartInput`）时接着组句：有的应用每改一次内容就重启一次输入
     * （支付宝里淘宝闪购的搜索框），重启后输入框里已经没有组合文字的标记，拼音还在但成了普通文字。
     * 不接上的话每敲一个字母拼音就被丢掉；只留着状态不处理，下一键的组合文字又会接在旧拼音后面再插一份。
     * 这里只记下「标记丢了」，下一次改输入框时由 [dropStalePreedit] 先删掉留在框里的那版拼音。返回是否留着当前状态。
     * 不在组句、候选条上放着补全（上屏后的联想、电话邮箱、「撤销」）时也留着：上屏这一下同样会触发重启。
     */
    fun resume(): Boolean {
        if (!composing) return completing
        composingLost = true
        return true
    }

    /**
     * 重启输入后第一次改输入框：光标前停在这段组句交过的哪一版 preedit（打得快时可能是还没轮到的旧版），先删掉它。
     * 重启那一刻新连接可能还没就绪、读不准，所以等到真正要写的时候再读；读不到或哪一版都对不上就不删。
     */
    private fun dropStalePreedit(connection: InputConnection) {
        if (!composingLost) return
        composingLost = false
        val versions = sentPreedits + preedit
        val before = connection.getTextBeforeCursor(versions.maxOf { it.length }, 0)?.toString() ?: return
        val stale = versions.filter { before.endsWith(it) }.maxByOrNull { it.length } ?: return
        connection.deleteSurroundingText(stale.length, 0)
    }

    /** 输入框换了或键盘收起：组句不带走。 */
    fun abandon() {
        undoHistory.clear()
        editHistory.clear()
        lastClearSnapshot = null
        pendingBackspaceSnapshot = null
        closeUndoGroup()
        preedit = ""
        sentPreedits.clear()
        composingLost = false
        pinyinChoices = emptyList()
        expression = false
        candidates = emptyList()
        completing = false
        engine.reset()
        host.shift = ShiftState.OFF
        host.showSideItems(pinyinChoices)
        host.showComposition("", emptyList())
        if (page != Page.LETTERS) {
            page = Page.LETTERS
            host.showKeyboard()
        }
    }

    /** 数字类输入框（电话、数字、日期时间）：直接换到数字键盘，「返回」仍可回字母。 */
    fun showNumbers() {
        if (page == Page.NUMBERS) return
        page = Page.NUMBERS
        host.showKeyboard()
    }

    /** 引擎刚装好：补上输入方案与繁体开关。 */
    fun onEngineReady() {
        engine.setPrivate(privateInput)
        applyScheme()
        if (!chinese) applyLanguage()
        if (traditional) apply(engine.setTraditional(true))
        apply(engine.setOptions(options))
    }

    /** 引擎的输入方案跟着键盘走；引擎装好、换布局、切中英时调。 */
    fun applyScheme() {
        if (!engine.ready) return
        apply(engine.setNineKey(nineKey))
        apply(engine.setZhuyin(zhuyin))
        apply(engine.setShuangpin(if (shuangpin) shuangpinScheme else "", shuangpinRawPreedit))
        apply(engine.setWubi(wubi, wubiMixed))
    }

    /**
     * 回车、挪光标时把组句落下：拼音原样落下敲的字母；注音缓冲区里是大千键位的 ASCII，落下没意义，上屏首选（与桌面端回车一致）。
     */
    private fun settle() = if (zhuyin) selectFirst() else apply(engine.commitRaw())

    /** 点 Shift：灭 → 单次；单次时紧接着再点 → 锁定，隔久了再点 → 灭；锁定时点 → 灭。 */
    private fun toggleShift() {
        val now = SystemClock.uptimeMillis()
        host.shift = when (host.shift) {
            ShiftState.OFF -> ShiftState.ONCE
            ShiftState.ONCE -> if (now - lastShiftTap < DOUBLE_TAP_MS) ShiftState.LOCKED else ShiftState.OFF
            ShiftState.LOCKED -> ShiftState.OFF
        }
        lastShiftTap = now
    }

    /** 出过一个字母：单次的 Shift 熄掉，锁定的留着。 */
    private fun consumeShift() {
        if (host.shift == ShiftState.ONCE) host.shift = ShiftState.OFF
    }

    /** 空格 / 标点顶首选；没有候选（切不动）就原样上屏。 */
    private fun selectFirst() {
        var frame = engine.select(0)
        if (frame.commit.isEmpty() && frame.preedit.isNotEmpty()) frame = engine.commitRaw()
        apply(frame)
    }

    private fun apply(frame: Frame) {
        val connection = host.connection ?: return
        if (frame.commit.isNotEmpty() && !undoGroupOpen) captureUndoSnapshot()
        var editBefore: InputEditHistory.CursorState? = null
        var deletedBefore = ""
        var editSucceeded = true
        var replacementSnapshotComplete = true
        if (host.selectionActive) editHistory.clear()
        // 一段组句开头：组合文字放进去之前读光标前后文，给云联想
        val surrounding = if (engine.predicting && preedit.isEmpty() && frame.preedit.isNotEmpty()) {
            (connection.getTextBeforeCursor(SURROUNDING_BEFORE, 0)?.toString().orEmpty()) to
                (connection.getTextAfterCursor(SURROUNDING_AFTER, 0)?.toString().orEmpty())
        } else {
            null
        }
        connection.beginBatchEdit()
        dropStalePreedit(connection)
        if (!host.selectionActive && (frame.commit.isNotEmpty() && frame.preedit.isEmpty() || frame.replace > 0)) {
            editBefore = editHistory.capture(connection)
        }
        if (frame.replace > 0) {
            deletedBefore = connection.getTextBeforeCursor(frame.replace, 0)?.toString().orEmpty()
            replacementSnapshotComplete = deletedBefore.length == frame.replace
            editSucceeded = connection.deleteSurroundingText(frame.replace, 0) && replacementSnapshotComplete
        }
        if (frame.commit.isNotEmpty()) {
            editSucceeded = connection.commitText(frame.commit, 1) && editSucceeded
            host.onPhraseCommitted(frame.commit)
        }
        if (frame.preedit.isNotEmpty()) {
            connection.setComposingText(frame.preedit, 1)
        } else if (frame.commit.isEmpty()) {
            connection.setComposingText("", 1)
            connection.finishComposingText()
        }
        connection.endBatchEdit()
        if (!editSucceeded && (frame.commit.isNotEmpty() || frame.replace > 0)) editHistory.clear()
        if (editSucceeded && (frame.commit.isNotEmpty() || deletedBefore.isNotEmpty()) && frame.preedit.isEmpty()) {
            editHistory.record(
                editBefore,
                editHistory.capture(connection),
                deletedBefore = deletedBefore,
                insertedBefore = frame.commit,
            )
        }
        preedit = frame.preedit
        if (frame.commit.isNotEmpty() && preedit.isEmpty()) closeUndoGroup()
        if (preedit.isEmpty()) {
            sentPreedits.clear()
        } else {
            sentPreedits.addLast(preedit)
            if (sentPreedits.size > SENT_PREEDITS) sentPreedits.removeFirst()
        }
        pinyinChoices = frame.pinyinChoices
        expression = frame.expression
        singleChars = frame.singleChars
        localCompletions = emptyList()
        localCompletionStart = Int.MAX_VALUE
        // 不在组句却带着候选：上屏通讯录名字后接着给的电话、邮箱，光标再动时要问 Core 还留不留
        completing = frame.preedit.isEmpty() && frame.candidates.isNotEmpty()
        host.showSideItems(pinyinChoices)
        showCandidatesWithStructured(frame.candidates)
        if (frame.notice.isNotEmpty()) host.showNotice(frame.notice)
        surrounding?.let { (before, after) -> engine.setSurrounding(before, after) }
        handler.removeCallbacks(pollPrediction)
        if (engine.predicting && composing) handler.postDelayed(pollPrediction, PREDICTION_POLL_MS)
    }

    /** 组句中隔一会儿取一次云联想结果；到了就插进候选重画，组句结束不再取。 */
    private val pollPrediction: Runnable = Runnable {
        if (!composing) return@Runnable
        val frame = engine.pollPrediction()
        if (frame != null && frame.preedit == preedit) {
            showCandidatesWithStructured(frame.candidates)
        }
        if (engine.predicting) handler.postDelayed(pollPrediction, PREDICTION_POLL_MS)
    }

    private fun commit(text: String) {
        if (text.isEmpty()) return
        lastClearSnapshot = null
        if (!undoGroupOpen) captureUndoSnapshot()
        val connection = host.connection ?: return
        if (host.selectionActive) {
            editHistory.clear()
            connection.commitText(text, 1)
            return
        }
        val before = editHistory.capture(connection)
        if (connection.commitText(text, 1)) {
            editHistory.record(before, editHistory.capture(connection), insertedBefore = text)
        }
    }

    /** 保存当前输入框完整内容与选区；相同快照不重复入栈，最多保留六步。 */
    private fun readUndoSnapshot(): ClearSnapshot? {
        val connection = host.connection ?: return null
        val before = connection.getTextBeforeCursor(MAX_SNAPSHOT, 0)?.toString() ?: return null
        val selected = connection.getSelectedText(0)?.toString().orEmpty()
        val after = connection.getTextAfterCursor(MAX_SNAPSHOT, 0)?.toString() ?: return null
        return ClearSnapshot(before + selected + after, before.length, before.length + selected.length)
    }

    private fun captureUndoSnapshot() {
        readUndoSnapshot()?.let(::pushUndoSnapshot)
    }

    private fun pushUndoSnapshot(snapshot: ClearSnapshot) {
        if (undoHistory.lastOrNull() != snapshot) {
            undoHistory.addLast(snapshot)
            while (undoHistory.size > UNDO_HISTORY_LIMIT) undoHistory.removeFirst()
        }
    }

    private fun closeUndoGroup() {
        undoGroupOpen = false
        handler.removeCallbacks(undoGroupTimeout)
    }

    /**
     * 键盘上的标点、符号上屏。成对符号（#928）由 Core 定：左半边连右半边一起上屏、光标停在中间；
     * 补过的右半边在光标后面就跳过它，不在（挪过光标）就照常上屏。`pairable` 为假（网址框、直接输入）时照常上屏。
     */
    private fun commitSymbol(text: String, pairable: Boolean) {
        if (host.selectionActive) {
            editHistory.clear()
            commit(text)
            return
        }
        val edit = if (pairable) engine.pairPunctuation(text, english) else null
        val connection = host.connection
        if (edit == null || connection == null) {
            commit(text)
            return
        }
        val parts = edit.split(':', limit = 3)
        when (parts[0]) {
            "insert" -> {
                val pair = parts[2]
                val back = parts[1].toInt().coerceIn(0, pair.length)
                val left = pair.dropLast(back)
                val right = pair.takeLast(back)
                val before = editHistory.capture(connection)
                connection.beginBatchEdit()
                val firstOk = connection.commitText(left, 1)
                // 光标位置 0 落在这段的开头，也就是左右两半中间
                val secondOk = connection.commitText(right, 0)
                connection.endBatchEdit()
                if (firstOk && secondOk) {
                    editHistory.record(before, editHistory.capture(connection), insertedBefore = left, insertedAfter = right)
                }
            }
            "skip" -> {
                val closer = parts[1]
                if (connection.getTextAfterCursor(closer.length, 0)?.toString() == closer) {
                    // 换掉光标后的那个再上屏同一个字：光标落到它后面，不用算绝对位置
                    val before = editHistory.capture(connection)
                    connection.beginBatchEdit()
                    val deleted = connection.deleteSurroundingText(0, closer.length)
                    val inserted = connection.commitText(closer, 1)
                    connection.endBatchEdit()
                    if (deleted && inserted) {
                        editHistory.record(before, editHistory.capture(connection), deletedAfter = closer, insertedBefore = closer)
                    }
                } else {
                    commit(closer)
                }
            }
            else -> commit(text)
        }
    }

    /** 没在组句时的退格：刚补完一对、光标前后正是这一对就一起删，否则照常退格。 */
    private fun deleteBackward() {
        val pair = engine.pairBackspace()
        val connection = host.connection ?: run {
            host.sendKey(KeyEvent.KEYCODE_DEL)
            return
        }
        if (host.selectionActive) {
            editHistory.clear()
            host.sendKey(KeyEvent.KEYCODE_DEL)
            return
        }
        if (pair != null && pair.length == 2 &&
            connection.getTextBeforeCursor(1, 0)?.toString() == pair.substring(0, 1) &&
            connection.getTextAfterCursor(1, 0)?.toString() == pair.substring(1)
        ) {
            val before = editHistory.capture(connection)
            if (connection.deleteSurroundingText(1, 1)) {
                editHistory.record(before, editHistory.capture(connection), deletedBefore = pair.substring(0, 1), deletedAfter = pair.substring(1))
            }
            return
        }

        // 退格按 Unicode 码点删除，避免把代理项拆开；每次物理退格先记为一步。
        val preceding = connection.getTextBeforeCursor(2, 0)?.toString().orEmpty()
        if (preceding.isEmpty()) {
            host.sendKey(KeyEvent.KEYCODE_DEL)
            return
        }
        val deleted = if (preceding.length >= 2 &&
            Character.isHighSurrogate(preceding[preceding.length - 2]) &&
            Character.isLowSurrogate(preceding.last())
        ) preceding.takeLast(2) else preceding.takeLast(1)
        val before = editHistory.capture(connection)
        if (connection.deleteSurroundingText(deleted.length, 0)) {
            editHistory.record(before, editHistory.capture(connection), deletedBefore = deleted)
        }
    }

    private companion object {
        const val SEPARATOR_DIGIT = '1'

        /** 两次点 Shift 间隔在这以内算双击（锁定大写）。 */
        const val DOUBLE_TAP_MS = 600L

        /** 云联想给的光标前后文：Engine 会按观察窗口再裁（前 64、后 32）。 */
        const val SURROUNDING_BEFORE = 64
        const val SURROUNDING_AFTER = 32

        /** 组句中取云联想结果的间隔。 */
        const val PREDICTION_POLL_MS = 150L

        /** 重启输入后认输入框停在哪一版 preedit 时往回看几版：快打时在路上的改动不会更多。 */
        const val SENT_PREEDITS = 8

        /** 补全看光标前多少个字：用户名加域名前缀够了。 */
        const val COMPLETION_CONTEXT = 80

        /** 每轮清空最多删除的 UTF-16 单位；循环最多 100 轮。 */
        const val MAX_CLEAR = 5000

        /** 撤销快照读取范围，与整框清空的最多删除量（100 轮）一致。 */
        const val MAX_SNAPSHOT = MAX_CLEAR * 100
        const val UNDO_HISTORY_LIMIT = 6
        const val UNDO_GROUP_TIMEOUT_MS = 1400L

        /** 日语里当长音敲的键。 */
        val LONG_VOWEL = setOf("-", "ー")

        /**
         * 字母键角标里转全角后还是同一个符号的标点：中文键盘上划、长按出它们时按「中文标点」设置转全角。
         * `_` `^` 这类转出来是另一个符号（——、……），照键面原样上屏。
         */
        private const val FULL_WIDTH_HINTS = "!?:;()~,."
    }
}
