package app.qingjian.inputmethod.service

import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.Build
import android.net.Uri
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import app.qingjian.inputmethod.R
import app.qingjian.inputmethod.candidates.CandidateBarView
import app.qingjian.inputmethod.cloud.AiChatGenerator
import app.qingjian.inputmethod.cloud.CandidateContextReranker
import app.qingjian.inputmethod.cloud.CloudConfig
import app.qingjian.inputmethod.cloud.AiExpressionGenerator
import app.qingjian.inputmethod.clipboard.ClipboardImageProvider
import app.qingjian.inputmethod.clipboard.sync.ClipboardSync
import app.qingjian.inputmethod.clipboard.ClipboardWatcher
import app.qingjian.inputmethod.clipboard.sync.NeonEdgeView
import org.json.JSONObject
import android.icu.text.Transliterator
import android.icu.text.BreakIterator
import java.text.Normalizer
import app.qingjian.inputmethod.contacts.ContactsSync
import app.qingjian.inputmethod.core.AppLog
import app.qingjian.inputmethod.localai.LocalAiModelManager
import app.qingjian.inputmethod.core.Engine
import app.qingjian.inputmethod.core.InputOptions
import app.qingjian.inputmethod.core.frame.Candidate
import app.qingjian.inputmethod.core.store.ClipboardStore
import app.qingjian.inputmethod.core.store.StructuredTokenStore
import app.qingjian.inputmethod.core.store.PhraseItem
import app.qingjian.inputmethod.core.store.PhraseRules
import app.qingjian.inputmethod.core.store.Shuangpin
import app.qingjian.inputmethod.keyboard.KeyboardView
import app.qingjian.inputmethod.keyboard.ShiftState
import app.qingjian.inputmethod.keyboard.layout.Key
import app.qingjian.inputmethod.keyboard.layout.KeyboardLayout
import app.qingjian.inputmethod.panel.PanelActions
import app.qingjian.inputmethod.panel.PanelKind
import app.qingjian.inputmethod.panel.VoicePanel
import app.qingjian.inputmethod.panel.clipboard.ClipboardPanel
import app.qingjian.inputmethod.panel.editor.FloatingEditor
import app.qingjian.inputmethod.panel.editor.PhraseEditor
import app.qingjian.inputmethod.service.input.Composer
import app.qingjian.inputmethod.service.input.Language
import app.qingjian.inputmethod.service.input.enterLabel
import app.qingjian.inputmethod.service.input.wantsDirectInput
import app.qingjian.inputmethod.service.input.wantsPlainPunctuation
import app.qingjian.inputmethod.service.input.wantsNumbers
import app.qingjian.inputmethod.service.input.wantsPrivacy
import app.qingjian.inputmethod.service.input.wantsPasswordSuggestions
import app.qingjian.inputmethod.settings.BottomAction
import app.qingjian.inputmethod.settings.GameKeyboard
import app.qingjian.inputmethod.settings.Settings
import app.qingjian.inputmethod.settings.ToolbarTool
import app.qingjian.inputmethod.settings.home.MainActivity
import app.qingjian.inputmethod.settings.page.AppearanceActivity
import app.qingjian.inputmethod.settings.page.CloudSettingsActivity
import app.qingjian.inputmethod.settings.page.VoiceSettingsActivity
import app.qingjian.inputmethod.theme.Palette
import app.qingjian.inputmethod.translate.QuickTranslate
import app.qingjian.inputmethod.translate.TranslateBar
import app.qingjian.inputmethod.voice.VoiceInput
import app.qingjian.inputmethod.scan.BarcodeScannerActivity
import app.qingjian.inputmethod.scan.ScanPreferences
import app.qingjian.inputmethod.scan.ScanOverlayService
import androidx.core.content.ContextCompat
import app.qingjian.inputmethod.sms.SmsCodeReceiver
import app.qingjian.inputmethod.mascot.FloatingMascotService
import app.qingjian.inputmethod.mascot.MascotDialogueEngine

/** 输入法服务：管生命周期、设置与视图，输入逻辑在 [Composer]。 */
class QingjianInputMethodService :
    InputMethodService(), Composer.Host, CandidateBarView.Listener, KeyboardView.Listener, PanelActions, VoiceInput.Host,
    GameMode.Host {
    private lateinit var engine: Engine
    private val phraseTransliterator by lazy { Transliterator.getInstance("Han-Latin; Latin-ASCII") }
    private lateinit var composer: Composer
    private var panel: InputPanel? = null
    private var hasActiveSelection = false
    private var lastInputEditorPackage: String? = null
    private var lastInputEditorFieldId: Int? = null
    private var quickAiSequence = 0
    private val quickAiHandler = Handler(Looper.getMainLooper())
    private var quickAiLoadingRunnable: Runnable? = null
    private var quickAiLoadingConnection: InputConnection? = null

    override val selectionActive: Boolean get() = hasActiveSelection

    private val game = GameMode(this, this)

    private lateinit var cloud: CloudServices
    private lateinit var aiExpression: AiExpressionGenerator
    private lateinit var aiChat: AiChatGenerator

    private lateinit var candidateReranker: CandidateContextReranker
    private val candidateRerankHandler = Handler(Looper.getMainLooper())
    // 常用语自动记忆包含拼音转换与磁盘写入，必须串行放后台，不能阻塞输入法主线程。
    private val phraseMemoryThread = HandlerThread("qingjian-phrase-memory").apply { start() }
    private val phraseMemoryHandler = Handler(phraseMemoryThread.looper)
    private var candidateRerankRunnable: Runnable? = null
    /** 30 秒批量落盘：只写学习表，不重置输入中的组句。 */
    private val learningFlushRunnable = object : Runnable {
        override fun run() {
            if (::engine.isInitialized) engine.flushLearning()
            candidateRerankHandler.postDelayed(this, 30_000L)
        }
    }
    private var candidateRerankSequence = 0
    /** 显示顺序到 Rust 原始候选下标的映射，确保重排后点击仍选中正确候选。 */
    private var candidateIndexMap: List<Int> = emptyList()

    /** 当前输入框的回车键文字，见 [enterLabel]。 */
    private var enterLabel: String? = null

    /** 候选条上最新的拼音与候选，展开候选时交给 [CandidateGrid][app.qingjian.inputmethod.candidates.CandidateGrid]。 */
    private var shownPreedit = ""
    private var shownCandidates: List<Candidate> = emptyList()

    /** 拖动调高度开始时的百分比；拖动中不为 null。 */
    private var resizeFrom: Int? = null

    /** 就地改设置会重建键盘，重建完重新打开这个面板。 */
    private var reopenPanel: PanelKind? = null
    /** 当前由用户选择并应保持显示的面板；系统输入连接重启不能擅自清除此状态。 */
    private var activePanelState: PanelKind? = null
    /** 剪贴板面板所属编辑器；同一应用的 InputConnection 重启 / 表格单元格更新仍视为同一场景。 */
    private var clipboardPanelEditorPackage: String? = null
    private var clipboardPanelOwnerFieldId: Int? = null

    private fun isClipboardPanel(kind: PanelKind? = activePanelState): Boolean =
        kind == PanelKind.CLIPBOARD || kind == PanelKind.PHRASES

    private fun shouldKeepClipboardPanel(info: EditorInfo, restarting: Boolean): Boolean =
        isClipboardPanel() &&
            clipboardPanelEditorPackage == info.packageName &&
            (restarting || clipboardPanelOwnerFieldId == info.fieldId)

    private val clipboardWatcher by lazy { ClipboardWatcher(this) }

    /** 短信验证码只通过本机广播送到候选条；短信正文不进入输入法服务。 */
    private val smsCodeListener = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            val code = intent.getStringExtra(SmsCodeReceiver.EXTRA_CODE)?.takeIf { it.matches(Regex("\\d{4,6}")) } ?: return
            panel?.bar?.show("", emptyList())
            panel?.bar?.showPersistentNotice("验证码 $code · 点击上屏") { commitText(code) }
        }
    }

    /** 短信验证码开关变化时只刷新常用功能图标，不重建输入法窗口。 */
    private val smsStateListener = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            panel?.refreshFunctions()
        }
    }

    /** 视频背景设置变化只更新现有背景层，绝不重建输入视图或当前设置面板。 */
    private val dynamicBackgroundListener = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            panel?.refreshDynamicBackground()
            panel?.refreshFunctions()
        }
    }

    /** 扫码 Activity 将识别结果发回当前输入法服务，由当前 InputConnection 直接上屏。 */
    private val scanResultListener = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            val value = intent.getStringExtra(ScanPreferences.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
                ?: run { AppLog.warn("[SCAN_DIAG] result broadcast empty"); return }
            commitScanResult(value, intent.getBooleanExtra(ScanPreferences.EXTRA_ENTER, false), 0)
        }
    }

    /**
     * 悬浮扫码结果可能恰好落在宿主切换焦点、InputConnection 短暂为空的窗口期。
     * 不把第一次广播直接丢弃：短间隔重试，且每次先检查光标附近文本，避免重复上屏。
     */
    private fun commitScanResult(value: String, enter: Boolean, attempt: Int) {
        val connection = currentInputConnection
        if (connection == null) {
            if (attempt < SCAN_COMMIT_RETRIES) {
                AppLog.warn("[SCAN_DIAG] InputConnection unavailable; retry scan commit attempt=${attempt + 1}")
                Handler(mainLooper).postDelayed({ commitScanResult(value, enter, attempt + 1) }, SCAN_COMMIT_RETRY_MS)
            } else {
                AppLog.error("[SCAN_DIAG] scan commit abandoned: InputConnection stayed null")
            }
            return
        }
        AppLog.info("[SCAN_DIAG] result received length=${value.length} editor=${currentInputEditorInfo?.packageName} field=${currentInputEditorInfo?.fieldId} connection=${connection.javaClass.simpleName}")
        val before = runCatching { connection.getTextBeforeCursor(256, 0)?.toString().orEmpty() }.getOrDefault("")
        val after = runCatching { connection.getTextAfterCursor(256, 0)?.toString().orEmpty() }.getOrDefault("")
        // 只把光标紧邻、且左右为边界的完整串号视为重复。不能用 contains：
        // D8097F-ZTEGD867A245 后面再扫 ZTEGD867A245，短串是长串的后缀但不是重复扫描。
        fun isCodeChar(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.'
        val beforeDuplicate = before.endsWith(value) &&
            (before.length == value.length || !isCodeChar(before[before.length - value.length - 1]))
        val afterDuplicate = after.startsWith(value) &&
            (after.length == value.length || !isCodeChar(after[value.length]))
        if (beforeDuplicate || afterDuplicate) {
            AppLog.info("[SCAN_DIAG] exact adjacent duplicate ignored editor=${currentInputEditorInfo?.packageName}")
            return
        }

        // 扫码是外部文本注入，不走 Composer 的编辑历史快照。
        // 微信输入框、Excel 单元格等宿主对文本快照支持不一致，直接 commitText 并隔离 Binder 异常。
        val committed = runCatching { connection.commitText(value, 1) }
            .onFailure { AppLog.error("[SCAN_DIAG] commitText threw editor=${currentInputEditorInfo?.packageName}: ${it.javaClass.simpleName}") }
            .getOrDefault(false)
        if (!committed) {
            if (attempt < SCAN_COMMIT_RETRIES) {
                AppLog.warn("[SCAN_DIAG] host rejected scan text; retry attempt=${attempt + 1} editor=${currentInputEditorInfo?.packageName}")
                Handler(mainLooper).postDelayed({ commitScanResult(value, enter, attempt + 1) }, SCAN_COMMIT_RETRY_MS)
            } else {
                AppLog.error("[SCAN_DIAG] scan commit failed after retries editor=${currentInputEditorInfo?.packageName}")
            }
            return
        }

        runCatching {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("扫码结果", value))
        }.onFailure { android.util.Log.w("QingjianScan", "Unable to copy scan result", it) }

        if (enter) {
            val newlineCommitted = runCatching { connection.commitText("\n", 1) }.getOrDefault(false)
            if (!newlineCommitted) runCatching {
                connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
        }
    }

    private companion object {
        const val SCAN_COMMIT_RETRIES = 10
        const val SCAN_COMMIT_RETRY_MS = 220L
    }

    private var neon: NeonEdgeView? = null

    private val mascotTipSelectedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            val index = intent.getIntExtra(app.qingjian.inputmethod.mascot.FloatingMascotService.EXTRA_TIP_INDEX, -1)
            if (index < 0 || shownCandidates.getOrNull(index)?.cloud != true) return
            composer.select(originalCandidateIndex(index))
        }
    }

    /** 同步的事件：键盘边缘亮灯，收到内容后开着的剪贴板面板重读。 */
    private val syncListener = object : ClipboardSync.Listener {
        override fun onSyncEvent(type: String, event: JSONObject) {
            val glow = neon?.takeIf { ClipboardSync.neonEnabled(this@QingjianInputMethodService) }
            when (type) {
                "started" -> glow?.start()
                "progress" -> glow?.speed(event.optLong("speed"))
                "finished" -> {
                    glow?.finish(true)
                    panel?.refreshClipboard()
                }
                "failed" -> glow?.finish(false)
            }
        }
    }

    /** AI 整段替换前保存的原文与光标位置，仅保留最近一次，供撤销使用。 */
    private var aiReplacementUndo: Pair<String, Int>? = null

    /** 浮在键盘上方接管打字的编辑条（常用语编辑框、快捷翻译）；null 是没开。 */
    private var editor: FloatingEditor? = null

    private lateinit var quickTranslate: QuickTranslate

    /** 语音输入：长按空格、工具栏话筒、全面屏底栏的语音键都进它。 */
    private lateinit var voice: VoiceInput
    private val smartInfo by lazy { SmartInfoProvider(this) }
    private var smartInfoSequence = 0
    private var localAiSuggestionSequence = 0
    private var lastMascotReaction = ""
    private var lastMascotReactionAt = 0L

    /** 返回键关了面板，它的抬起也要吞掉。 */
    private var backConsumed = false

    /** 自己写设置（拖完高度）时不重建键盘。 */
    private var writingSettings = false

    override fun writeSettings(block: () -> Unit) {
        writingSettings = true
        try {
            block()
        } finally {
            writingSettings = false
        }
    }

    /** 设置页改了就重建键盘，开着键盘能马上看到。 */
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // 剪贴板面板内的开关也保存在 Settings.preferences 中，但它不是键盘布局设置。
        // 若按通用设置变更逻辑重建 InputView，正在显示的面板会被销毁并退回键盘。
        // 因此这类面板状态只持久化，不触发整套输入法视图重建。
        if (key == Settings.KEY_CLIPBOARD_PASTE_ENTER ||
            key == Settings.KEY_CLIPBOARD_PASTE_NEXT_CELL ||
            key == SmsCodeReceiver.KEY_ENABLED
        ) return@OnSharedPreferenceChangeListener
        contacts.sync()
        if (!writingSettings) setInputView(onCreateInputView())
    }

    /** 通讯录里的名字（设置里开了才读）。 */
    private lateinit var contacts: ContactsSync

    override fun onCreate() {
        super.onCreate()
        AppLog.info("ime create")
        engine = Engine(this) {
            composer.onEngineReady()
            cloud.sync()
            cloud.syncVoice()
            contacts.sync()
        }
        contacts = ContactsSync(this, engine)
        cloud = CloudServices(this, engine)
        aiExpression = AiExpressionGenerator(this)
        aiChat = AiChatGenerator(this)
        candidateReranker = CandidateContextReranker(this)
        composer = Composer(engine, this)
        quickTranslate = QuickTranslate(engine) { currentInputConnection }
        voice = VoiceInput(this, engine, this)
        Settings.load(this).let { settings ->
            composer.restoreLanguages(
                Language.of(settings.language, Language.CHINESE),
                Language.of(settings.partner, Language.ENGLISH),
            )
        }
        Settings.preferences(this).registerOnSharedPreferenceChangeListener(settingsListener)
        val smsFilter = IntentFilter(SmsCodeReceiver.ACTION_CODE_READY)
        // 动态广播仅允许本应用接收方处理，兼容 Android 8+，避免旧系统默认导出带来的伪造广播风险。
        ContextCompat.registerReceiver(this, smsCodeListener, smsFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(
            this,
            smsStateListener,
            IntentFilter(app.qingjian.inputmethod.sms.SmsCodeSettingsActivity.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val scanFilter = IntentFilter(ScanPreferences.ACTION_RESULT)
        ContextCompat.registerReceiver(this, scanResultListener, scanFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(
            this,
            dynamicBackgroundListener,
            IntentFilter(app.qingjian.inputmethod.background.DynamicBackgroundActivity.ACTION_SETTINGS_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        cloud.start()
        clipboardWatcher.start()
        ContextCompat.registerReceiver(
            this,
            mascotTipSelectedReceiver,
            IntentFilter(app.qingjian.inputmethod.mascot.FloatingMascotService.ACTION_TIP_SELECTED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ClipboardSync.addListener(syncListener)
        ClipboardSync.apply(this)
    }

    override fun onDestroy() {
        AppLog.info("ime destroy")
        clipboardWatcher.stop()
        contacts.stop()
        ClipboardSync.removeListener(syncListener)
        Settings.preferences(this).unregisterOnSharedPreferenceChangeListener(settingsListener)
        runCatching { unregisterReceiver(smsCodeListener) }
        runCatching { unregisterReceiver(smsStateListener) }
        runCatching { unregisterReceiver(scanResultListener) }
        runCatching { unregisterReceiver(dynamicBackgroundListener) }
        runCatching { unregisterReceiver(mascotTipSelectedReceiver) }
        candidateRerankHandler.removeCallbacksAndMessages(null)
        LocalAiModelManager.releaseRuntime()
        phraseMemoryHandler.removeCallbacksAndMessages(null)
        phraseMemoryThread.quitSafely()
        candidateRerankSequence++
        localAiSuggestionSequence++
        stopQuickAiLoading(clearText = true)
        quickAiHandler.removeCallbacksAndMessages(null)
        cloud.stop()
        engine.close()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val viewBuildStartedAt = android.os.SystemClock.elapsedRealtime()
        // 键盘重建，开着的语音面板跟着没了
        voice.cancel()
        val target = game.target()
        AppLog.info("create input view${if (target != null) " (game keyboard)" else ""}")
        val gameSettings = target?.let { GameKeyboard.load(this) }
        val settings = Settings.load(this).let { settings ->
            gameSettings ?: return@let settings
            settings.copy(heightPercent = gameSettings.size.heightPercent, fullscreenAdapt = false)
        }
        composer.layout = settings.layout
        composer.traditional = settings.traditional
        composer.keepChinese = settings.keepChinese
        composer.shuangpinScheme = settings.shuangpinScheme
        composer.shuangpinRawPreedit = settings.shuangpinRawPreedit
        composer.wubiMixed = settings.wubiMixed
        composer.options = settings.inputOptions.let { options ->
            if (gameSettings?.glosses == false) options.copy(learningLanguage = InputOptions.LEARNING_OFF) else options
        }
        composer.englishCandidates = settings.englishCandidates
        engine.setAppearance(settings.theme, settings.candidateFontSize / 20f, settings.fontWeight.weight)
        // 云配置由 Engine.onReady 与 CloudServices 配置监听器同步；这里不再重复解密/装配，
        // 避免输入法首次显示键盘时在主线程做额外配置工作。
        // 布局没变时 layout 的 setter 不会重设方案，双拼方案、混输这些改了也得补上
        composer.applyScheme()
        val panelToRestore = reopenPanel ?: activePanelState?.takeIf { it == PanelKind.DYNAMIC_BACKGROUND || isClipboardPanel(it) }
            ?: this.panel?.openPanel?.takeIf { it == PanelKind.DYNAMIC_BACKGROUND || isClipboardPanel(it) }
        val panel = InputPanel(
            this, settings, this,
            stripSource = { highlighted, dark, visibleWidth -> engine.renderStrip(highlighted, dark, visibleWidth) },
            keyboardListener = this,
            actions = this,
            onBottomAction = ::onBottomAction,
            onCloudSelect = { composer.select(originalCandidateIndex(it)) },
            onGenerateAiExpression = { source, callback -> aiExpression.generate(source, callback) },
            aiChatGenerator = aiChat,
            floating = target != null,
        )
        this.panel = panel
        showKeyboard()
        // 延后到首帧之后同步设置，既保留 cloudPredict 等开关的即时生效，也不阻塞键盘首屏显示。
        Handler(mainLooper).post { if (engine.ready) cloud.sync(settings) }
        showSideItems(emptyList())
        panelToRestore?.let(panel::open)
        reopenPanel = null
        game.wrap(target, settings, gameSettings, panel.view)?.let { frame ->
            neon = null
            return frame
        }
        val glow = NeonEdgeView(this)
        neon = glow
        panel.addOverlay(glow)
        AppLog.info("input view ready in ${android.os.SystemClock.elapsedRealtime() - viewBuildStartedAt}ms engineReady=${engine.ready}")
        return panel.view
    }

    /** 游戏键盘不进横屏的全屏编辑（那会盖住游戏），其余照系统的判断。 */
    override fun onEvaluateFullscreenMode(): Boolean = game.target() == null && super.onEvaluateFullscreenMode()

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        game.computeInsets(outInsets, window.window?.decorView?.height)
    }

    override val editorPackage: String? get() = currentInputEditorInfo?.packageName

    override fun rebuild() {
        closePanel()
        setInputView(onCreateInputView())
        updateFullscreenMode()
    }

    override fun hide() = onHideKeyboard()

    override val gameKeyboard: Boolean? get() = game.enabledHere

    override fun toggleGameKeyboard() = game.toggle()

    /** 深浅色跟着系统变。 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setInputView(onCreateInputView())
    }

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        // 输入目标变化时清除旧输入框里的 AI 动画，防止异步结果写入新输入框。
        stopQuickAiLoading(clearText = true)
        // 在系统切换 InputConnection 前取消语音会话，防止迟到 AI 回复写入新输入框。
        voice.cancel()
        super.onStartInput(attribute, restarting)
        val nextEditorPackage = attribute.packageName
        val nextFieldId = attribute.fieldId
        val previousEditorPackage = lastInputEditorPackage
        val previousFieldId = lastInputEditorFieldId
        android.util.Log.i("QingjianScanDiag", "onStartInput previous=$previousEditorPackage/$previousFieldId next=$nextEditorPackage/$nextFieldId restarting=$restarting floating=${ScanPreferences.isFloating(this)} prefixEditing=${ScanPreferences.isPrefixEditorActive()}")
        val editingOwnScanPrefix = nextEditorPackage == packageName && ScanPreferences.isPrefixEditorActive()
        val targetChanged = previousEditorPackage != null &&
            (previousEditorPackage != nextEditorPackage || (previousFieldId != null && previousFieldId != nextFieldId))
        if (!restarting && !editingOwnScanPrefix && targetChanged && ScanPreferences.isFloating(this)) {
            AppLog.info("[SCAN_DIAG] stop floating scanner: input target changed $previousEditorPackage/$previousFieldId -> $nextEditorPackage/$nextFieldId")
            // 直接停止已运行的前台悬浮服务；不要为了发送 ACTION_STOP 再 startService，
            // Android 12+ 可能因此触发前台服务启动限制并影响输入法生命周期。
            runCatching {
                stopService(Intent(this, ScanOverlayService::class.java))
            }.onFailure { AppLog.warn("[SCAN_DIAG] unable to stop floating scanner: ${it.message}") }
        }
        if (!editingOwnScanPrefix) {
            lastInputEditorPackage = nextEditorPackage
            lastInputEditorFieldId = nextFieldId
        }
        quickAiSequence++
        val keepClipboard = shouldKeepClipboardPanel(attribute, restarting)
        if (isClipboardPanel() && !keepClipboard) {
            AppLog.info(
                "clipboard panel released: owner=$clipboardPanelEditorPackage/$clipboardPanelOwnerFieldId, " +
                    "next=${attribute.packageName}/${attribute.fieldId}, restarting=$restarting",
            )
            activePanelState = null
            clipboardPanelEditorPackage = null
            clipboardPanelOwnerFieldId = null
            panel?.close()
        } else if (keepClipboard) {
            AppLog.info("clipboard panel retained for ${attribute.packageName}/${attribute.fieldId}, restarting=$restarting")
        }
        hasActiveSelection = false
        val passwordSuggestions = attribute.wantsPasswordSuggestions()
        // 密码框保留本地拼音组句/候选，但绝不学习或上传；邮箱等其他直输框行为不变。
        composer.directInput = attribute.wantsDirectInput() && !passwordSuggestions
        composer.plainPunctuation = attribute.wantsPlainPunctuation()
        composer.privateInput = attribute.wantsPrivacy() || passwordSuggestions
        enterLabel = attribute.enterLabel()
        panel?.keyboard?.enterLabel = enterLabel
        if (!(restarting && composer.resume())) composer.abandon()
        if (attribute.wantsNumbers()) composer.showNumbers()
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        AppLog.info(
            "start input view: ${info.packageName}, inputType 0x${Integer.toHexString(info.inputType)}, " +
                "imeOptions 0x${Integer.toHexString(info.imeOptions)}, restarting $restarting",
        )
        game.rememberLandscapeApp(info.packageName)
        val keepClipboard = shouldKeepClipboardPanel(info, restarting)
        if (keepClipboard && panel?.openPanel != activePanelState) {
            // Excel 等表格会在粘贴后重启输入视图；若视图对象被系统替换，按用户选择恢复剪贴板页。
            activePanelState?.let { panel?.open(it) }
            showKeyboard()
            AppLog.info("restored clipboard panel after input view restart")
        }
        if (panel?.stale() == true || game.target() != game.packageName) {
            setInputView(onCreateInputView())
            updateFullscreenMode()
        }
        // 网络可能换了（「只在 Wi-Fi 下使用」）
        cloud.sync()
        // 上次读通讯录时系统给了空结果（「仅在使用中允许」时后台读不到），键盘弹出时再读
        contacts.retryIfEmpty()
        // 键盘弹出时按设置补录剪贴板历史，不显示临时提示。
        clipboardWatcher.capture()
        // 订阅编辑器光标坐标；云 Tips 根据插入光标实时浮在输入框上方。
        runCatching { currentInputConnection?.requestCursorUpdates(InputConnection.CURSOR_UPDATE_MONITOR) }
        // 进程可能刚被系统杀过又拉起，或开关刚改过；顺便补一轮
        ClipboardSync.apply(this)
        ClipboardSync.changed()
    }

    /** 系统问「该不该显示键盘」：接着实体键盘时缺省不显示，有时候弹不出来先看这一条。 */
    override fun onEvaluateInputViewShown(): Boolean {
        val shown = super.onEvaluateInputViewShown()
        if (!shown) {
            val config = resources.configuration
            AppLog.warn("input view not shown: keyboard ${config.keyboard}, hardKeyboardHidden ${config.hardKeyboardHidden}")
        }
        return shown
    }

    override fun onWindowShown() {
        super.onWindowShown()
        AppLog.info("window shown")
        // 定时学习数据落盘只在键盘可见期间运行；隐藏后由 onFinishInputView 负责最终落盘。
        candidateRerankHandler.removeCallbacks(learningFlushRunnable)
        candidateRerankHandler.postDelayed(learningFlushRunnable, 30_000L)
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        AppLog.info("window hidden")
        android.util.Log.w("QingjianScanDiag", "IME window hidden editor=${currentInputEditorInfo?.packageName} field=${currentInputEditorInfo?.fieldId}")
        // 隐藏键盘后停止周期性落盘，取消未执行的候选重排，并释放常驻本地 AI 引擎。
        candidateRerankHandler.removeCallbacks(learningFlushRunnable)
        cancelPendingCandidateRerank()
        localAiSuggestionSequence++
        LocalAiModelManager.releaseRuntime()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        AppLog.info("finish input view: finishingInput=$finishingInput, editor=${currentInputEditorInfo?.packageName}, panel=${panel?.openPanel}, clipboardOwner=$clipboardPanelEditorPackage")
        android.util.Log.w("QingjianScanDiag", "finishInputView finishing=$finishingInput editor=${currentInputEditorInfo?.packageName} field=${currentInputEditorInfo?.fieldId}")
        super.onFinishInputView(finishingInput)
        voice.cancel()
        closeEditor()
        // 部分编辑器（尤其表格单元格）在提交文本后会以 finishingInput=true
        // 结束当前输入视图，但焦点仍属于同一个编辑器。剪贴板面板需要跨过这次
        // 生命周期抖动；切换到另一个应用时再由 onStartInput 按 package 清理。
        val keepClipboard = isClipboardPanel()
        if (finishingInput && !keepClipboard) {
            activePanelState = null
            panel?.close()
        } else if (keepClipboard) {
            AppLog.info("keeping clipboard panel through finishInputView; finishingInput=$finishingInput")
        }
        composer.finishStructuredToken()
        composer.abandon()
        // 即使本轮没有触发 reset，输入视图结束时也确保学习数据立即落盘。
        engine.flushLearning()
    }

    override fun onFinishInput() {
        AppLog.info("finish input: editor=${currentInputEditorInfo?.packageName}, panelState=$activePanelState")
        super.onFinishInput()
        // 不在这里清理剪贴板状态：Excel 会先结束 InputConnection，再用同一应用重新启动。
        // 真正切换应用时由下一次 onStartInput 按 packageName / fieldId 判断并清理。
    }

    override fun onUpdateCursorAnchorInfo(cursorAnchorInfo: CursorAnchorInfo) {
        super.onUpdateCursorAnchorInfo(cursorAnchorInfo)
        val x = cursorAnchorInfo.insertionMarkerHorizontal
        val y = cursorAnchorInfo.insertionMarkerTop
        if (x.isFinite() && y.isFinite() && y >= 0f) {
            panel?.updateCloudTipsAnchor(x, y)
        }
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd,
        )
        hasActiveSelection = newSelStart >= 0 && newSelEnd >= 0 && newSelStart != newSelEnd
        maybeShowMascotSemanticReaction(newSelStart, newSelEnd, oldSelEnd)
        // 表格粘贴后可能连续触发多次 selection 更新；只更新选区状态，不重建候选或切换面板。
        if (isClipboardPanel()) return
        if (editor != null) return
        val moved = newSelStart != candidatesEnd || newSelEnd != candidatesEnd
        if (candidatesEnd != -1 && moved) composer.onCursorMovedAway()
        if (candidatesEnd == -1 && newSelStart == newSelEnd) composer.refreshCompletions()
        refreshSmartInfoSuggestions()
    }


    /** 本地语义回应：只处理普通非密码输入框中刚提交的文本，不联网、不上传。 */
    private fun maybeShowMascotSemanticReaction(newSelStart: Int, newSelEnd: Int, oldSelEnd: Int) {
        if (newSelStart < 0 || newSelEnd != newSelStart || newSelEnd <= oldSelEnd) return
        if (editor != null || composer.privateInput || composer.directInput || shownPreedit.isNotEmpty()) return
        val info = currentInputEditorInfo ?: return
        val variation = info.inputType and android.text.InputType.TYPE_MASK_VARIATION
        if (variation == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        ) return
        val prefs = getSharedPreferences(FloatingMascotService.PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean(FloatingMascotService.KEY_ENABLED, false) || !android.provider.Settings.canDrawOverlays(this)) return
        val before = currentInputConnection?.getTextBeforeCursor(48, 0)?.toString().orEmpty()
        val response = MascotDialogueEngine.response(before) ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (response == lastMascotReaction && now - lastMascotReactionAt < 5_000L) return
        lastMascotReaction = response
        lastMascotReactionAt = now
        val intent = Intent(this, FloatingMascotService::class.java)
            .setAction(FloatingMascotService.ACTION_SHOW_MESSAGE)
            .putExtra(FloatingMascotService.EXTRA_MESSAGE, response)
        runCatching { ContextCompat.startForegroundService(this, intent) }
            .onFailure { AppLog.warn("mascot semantic response unavailable: " + it.javaClass.simpleName) }
    }

    /** 输入地点 / 天气 / 内存等关键词后，异步更新一条可点击的实时信息候选。 */
    private fun refreshSmartInfoSuggestions() {
        val currentPanel = panel ?: return
        if (editor != null || currentPanel.openPanel != null || shownPreedit.isNotEmpty()) return
        val before = currentInputConnection?.getTextBeforeCursor(96, 0)?.toString().orEmpty()
        val trigger = SmartInfoProvider.TRIGGERS.firstOrNull { before.endsWith(it) }
        if (trigger == null) {
            smartInfoSequence++
            scheduleLocalAiSuggestions(before, currentPanel)
            return
        }
        val request = ++smartInfoSequence
        smartInfo.resolve(trigger) { value ->
            if (request != smartInfoSequence) return@resolve
            val latest = currentInputConnection?.getTextBeforeCursor(64, 0)?.toString().orEmpty()
            if (!latest.endsWith(trigger)) return@resolve
            currentPanel.showSmartSuggestions(listOf(value)) { selected ->
                if (selected == SmartInfoProvider.LOCATION_PERMISSION_HINT) {
                    startActivity(
                        Intent(this, LocationPermissionActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } else {
                    val input = currentInputConnection ?: return@showSmartSuggestions
                    input.deleteSurroundingText(trigger.length, 0)
                    input.commitText(selected, 1)
                    currentPanel.showSmartSuggestions(emptyList()) {}
                }
            }
        }
    }

    /**
     * 输入停顿后触发本地联想/整句续写。只追加用户点击的续写，不替换或自动提交已有文字。
     * 请求带序号与上下文二次校验，防止慢推理结果覆盖用户后续输入。
     */
    private fun scheduleLocalAiSuggestions(before: String, currentPanel: InputPanel) {
        val request = ++localAiSuggestionSequence
        if (before.length < 3 || composer.privateInput || composer.directInput ||
            currentInputEditorInfo?.wantsPrivacy() == true || !LocalAiModelManager.isInstalled(this)
        ) {
            currentPanel.showSmartSuggestions(emptyList()) {}
            return
        }
        candidateRerankHandler.postDelayed({
            if (request != localAiSuggestionSequence || panel !== currentPanel ||
                editor != null || currentPanel.openPanel != null || shownPreedit.isNotEmpty()
            ) return@postDelayed
            val liveConnection = currentInputConnection ?: return@postDelayed
            val liveBefore = runCatching {
                liveConnection.getTextBeforeCursor(96, 0)?.toString().orEmpty()
            }.getOrDefault("")
            if (liveBefore != before) return@postDelayed
            LocalAiModelManager.generateSuggestions(this, before) { result ->
                if (request != localAiSuggestionSequence || panel !== currentPanel ||
                    currentInputConnection !== liveConnection || editor != null ||
                    currentPanel.openPanel != null || shownPreedit.isNotEmpty()
                ) return@generateSuggestions
                val latest = runCatching {
                    currentInputConnection?.getTextBeforeCursor(96, 0)?.toString().orEmpty()
                }.getOrDefault("")
                if (latest != before) return@generateSuggestions
                val suggestions = result.getOrDefault(emptyList())
                currentPanel.showSmartSuggestions(suggestions) { selected ->
                    if (selected.isBlank() || request != localAiSuggestionSequence) return@showSmartSuggestions
                    val connection = currentInputConnection ?: return@showSmartSuggestions
                    val currentText = runCatching {
                        connection.getTextBeforeCursor(96, 0)?.toString().orEmpty()
                    }.getOrDefault("")
                    if (currentText != before) return@showSmartSuggestions
                    connection.commitText(selected, 1)
                    currentPanel.showSmartSuggestions(emptyList()) {}
                }
            }
        }, 750L)
    }

    // 编辑条开着时打的字进编辑条
    override val connection: InputConnection? get() = editor?.connection ?: currentInputConnection

    override var shift: ShiftState
        get() = panel?.keyboard?.shift ?: ShiftState.OFF
        set(value) {
            panel?.keyboard?.shift = value
        }

    override fun sendKey(keyCode: Int) = editor?.onKey(keyCode) ?: sendDownUpKeyEvents(keyCode)

    override fun performEditorAction(): Boolean = editor == null && sendDefaultEditorAction(true)

    override fun showComposition(preedit: String, candidates: List<Candidate>) {
        candidateRerankSequence++
        candidateRerankRunnable?.let(candidateRerankHandler::removeCallbacks)
        candidateRerankRunnable = null
        shownPreedit = preedit
        shownCandidates = candidates
        candidateIndexMap = candidates.indices.toList()
        renderShownComposition(preedit, candidates)
        scheduleCandidateRerank(preedit, candidates)
    }

    /** 只重画显示层；candidateIndexMap 保证视觉重排不会改变引擎原始候选的选择下标。 */
    private fun renderShownComposition(preedit: String, candidates: List<Candidate>) {
        panel?.bar?.show(preedit, candidates)
        panel?.showCloudSuggestions(candidates.mapIndexedNotNull { index, candidate ->
            if (candidate.cloud) index to candidate.text else null
        })
        if (panel?.openPanel == PanelKind.CANDIDATES) {
            if (candidates.isEmpty()) closePanel() else panel?.showCandidates(preedit, candidates, composer.singleChars)
        }
    }

    /**
     * 停止输入 650ms 后才请求一次。结合光标前 64 字与后 32 字判断搭配、句法和指代；
     * 私密/密码/直接输入不读取、不上传上下文。
     */
    private fun scheduleCandidateRerank(preedit: String, candidates: List<Candidate>) {
        if (candidates.size < 2 || preedit.isBlank() || composer.privateInput ||
            composer.directInput || !composer.chinese || editor != null) return
        val input = currentInputConnection ?: return
        val before = runCatching { input.getTextBeforeCursor(64, 0)?.toString().orEmpty() }.getOrDefault("")
        val after = runCatching { input.getTextAfterCursor(32, 0)?.toString().orEmpty() }.getOrDefault("")
        if (before.isBlank() && after.isBlank()) return
        val candidateTexts = candidates.map { it.text }
        if (candidateTexts.distinct().size != candidateTexts.size) return
        val requestSequence = candidateRerankSequence
        val editorPackage = currentInputEditorInfo?.packageName
        val task = Runnable {
            val applyRanking: (List<String>?) -> Unit = { ranked ->
                if (ranked != null && requestSequence == candidateRerankSequence &&
                    shownPreedit == preedit && shownCandidates.map { it.text } == candidateTexts &&
                    currentInputEditorInfo?.packageName == editorPackage
                ) {
                    val liveInput = currentInputConnection
                    val liveBefore = runCatching { liveInput?.getTextBeforeCursor(64, 0)?.toString().orEmpty() }.getOrDefault("")
                    val liveAfter = runCatching { liveInput?.getTextAfterCursor(32, 0)?.toString().orEmpty() }.getOrDefault("")
                    if (liveBefore == before && liveAfter == after) {
                        val remaining = candidates.indices.toMutableList()
                        val order = ranked.mapNotNull { value ->
                            remaining.firstOrNull { candidateTexts[it] == value }?.also { remaining.remove(it) }
                        } + remaining
                        if (order.size == candidates.size && order != candidates.indices.toList()) {
                            candidateIndexMap = order
                            shownCandidates = order.map(candidates::get)
                            renderShownComposition(preedit, shownCandidates)
                        }
                    }
                }
            }
            // 本地模型优先；不可用或输出不合规时再回退原有云端重排。
            LocalAiModelManager.rerankCandidates(this, before, after, preedit, candidateTexts) { localRanked ->
                if (localRanked != null) applyRanking(localRanked)
                else candidateReranker.rerank(before, after, preedit, candidateTexts, applyRanking)
            }
        }
        candidateRerankRunnable = task
        candidateRerankHandler.postDelayed(task, 650L)
    }

    private fun originalCandidateIndex(displayIndex: Int): Int =
        candidateIndexMap.getOrNull(displayIndex) ?: displayIndex

    private fun cancelPendingCandidateRerank() {
        candidateRerankSequence++
        candidateRerankRunnable?.let(candidateRerankHandler::removeCallbacks)
        candidateRerankRunnable = null
    }

    override fun onExpand() {
        openPanel(PanelKind.CANDIDATES)
        panel?.showCandidates(shownPreedit, shownCandidates, composer.singleChars)
    }

    override fun selectCandidate(index: Int) { cancelPendingCandidateRerank(); composer.select(originalCandidateIndex(index)) }

    override fun toggleSingleChars() = composer.toggleSingleChars()

    override fun clearComposition() = composer.onKey(Key.Clear)

    override fun showKeyboard() {
        val panel = panel ?: return
        val keyboard = panel.keyboard
        val settings = panel.settings
        keyboard.chinese = composer.chinese
        keyboard.languageLabel = composer.language.key
        keyboard.languageHint = composer.partner.key
        keyboard.languageChoices = Language.entries.map { it.title }
        keyboard.languageChoice = composer.language.ordinal
        keyboard.enterLabel = editor?.enterLabel ?: enterLabel
        val fullWidth = composer.options.fullWidthPunctuation
        // 滑行只给拼音全键盘与英文键盘（常用语编辑条的英文键盘也算）：双拼、五笔、日语的字母不是这样拼词的
        keyboard.swipeLayout = composer.page == Composer.Page.LETTERS && (
            editor?.lettersOnly == true || (!composer.nineKey && !composer.zhuyin && !composer.shuangpin && !composer.wubi && !composer.japanese)
        )
        when {
            composer.page == Composer.Page.NUMBERS -> keyboard.setRows(KeyboardLayout.numbers(), side = true)
            composer.page == Composer.Page.SYMBOLS -> keyboard.setRows(KeyboardLayout.symbols(composer.chinese, fullWidth))
            // 常用语的输入码只收字母，九宫格、注音打不出来，换成英文全键盘
            editor?.lettersOnly == true -> keyboard.setRows(KeyboardLayout.qwerty(chinese = false))
            composer.nineKey -> keyboard.setRows(KeyboardLayout.nineKey(), side = true)
            composer.zhuyin -> keyboard.setRows(KeyboardLayout.zhuyin(fullWidth))
            composer.shuangpin -> keyboard.setRows(
                KeyboardLayout.shuangpin(
                    Shuangpin.keycaps(composer.shuangpinScheme), composer.shuangpinScheme, fullWidth,
                    hints = settings.shuangpinHints,
                ),
            )
            else -> keyboard.setRows(KeyboardLayout.qwerty(composer.chinese, composer.japanese, fullWidth))
        }
        showSideItems(emptyList())
    }

    /** 左列：数字键盘上是符号，九宫格组句中是同码拼音，平时是标点。 */
    override fun showSideItems(pinyinChoices: List<String>) {
        panel?.keyboard?.setSideItems(
            when {
                composer.page == Composer.Page.NUMBERS -> KeyboardLayout.NUMBER_SIDE
                else -> pinyinChoices.ifEmpty { KeyboardLayout.sidePunctuation(composer.options.fullWidthPunctuation) }
            },
        )
    }

    override fun onKey(key: Key) = composer.onKey(key)

    override fun onOpenClipboard() = clipboard()

    /** 四个功能键的自定义上 / 下滑动作，复用输入法已有的操作入口。 */
    override fun onGestureAction(action: Settings.GestureAction) {
        when (action) {
            Settings.GestureAction.DEFAULT, Settings.GestureAction.NONE -> Unit
            Settings.GestureAction.CLEAR_ALL -> onClearAll()
            Settings.GestureAction.CLEAR_BEFORE_CURSOR -> onClearBeforeCursor()
            // 保留旧配置枚举兼容性，但不再执行删除键下滑撤销清空动作。
            Settings.GestureAction.RESTORE_CLEAR -> composer.restoreLastClear()
            Settings.GestureAction.UNDO -> composer.undo()
            Settings.GestureAction.REDO -> composer.redo()
            Settings.GestureAction.OPEN_CLIPBOARD -> onOpenClipboard()
            Settings.GestureAction.SELECT_ALL -> onEditShortcut(KeyboardView.EditShortcut.SELECT_ALL)
            Settings.GestureAction.CUT -> onEditShortcut(KeyboardView.EditShortcut.CUT)
            Settings.GestureAction.COPY -> onEditShortcut(KeyboardView.EditShortcut.COPY)
            Settings.GestureAction.PASTE -> onEditShortcut(KeyboardView.EditShortcut.PASTE)
            Settings.GestureAction.OPEN_SYMBOLS -> openPanel(PanelKind.SYMBOLS)
            Settings.GestureAction.OPEN_SCANNER -> openBarcodeScanner()
            Settings.GestureAction.OPEN_THEME_PICKER -> startActivity(Intent(this, AppearanceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Settings.GestureAction.ENTER -> composer.onKey(Key.Enter())
            Settings.GestureAction.SWITCH_LANGUAGE -> composer.onKey(Key.Language)
            Settings.GestureAction.BACKSPACE -> composer.onKey(Key.Backspace)
            Settings.GestureAction.AI_CHAT_QUICK -> runQuickAiChat()
            Settings.GestureAction.AI_EXPRESSION_QUICK -> runQuickAiExpression()
        }
    }

    /** 输入框优先；输入框为空时才读取系统剪贴板最新的纯文本内容。 */
    private fun quickAiSource(connection: InputConnection): String {
        val fieldText = (
            connection.getTextBeforeCursor(3000, 0)?.toString().orEmpty() +
                connection.getSelectedText(0)?.toString().orEmpty() +
                connection.getTextAfterCursor(1500, 0)?.toString().orEmpty()
            ).trim()
        if (fieldText.isNotBlank()) return fieldText.takeLast(4500)
        return runCatching {
            val clip = getSystemService(ClipboardManager::class.java)?.primaryClip ?: return@runCatching ""
            if (clip.itemCount <= 0) return@runCatching ""
            clip.getItemAt(0).coerceToText(this).toString().trim().takeLast(4500)
        }.getOrDefault("")
    }

    /** 按常用功能面板选择的样式绘制 AI 等待提示；所有 span 仅附着于这段 composing 文本。 */
    private fun quickAiLoadingFrame(phase: Int): CharSequence {
        val style = Settings.preferences(this).getInt(Settings.KEY_AI_LOADING_EFFECT, 0).coerceIn(0, 14)
        val counter = (phase % 100).toString().padStart(2, '0')
        val label = when (style) {
            3, 8 -> "● AI 正在生成"
            4 -> "● ✦ AI 正在生成"
            9 -> "AI 正在生成 |"
            10 -> "✧ AI 正在生成"
            11 -> "${listOf("✦", "✧", "✺", "✧")[((phase / 4) % 4)]} · AI 正在生成 ·"
            12 -> "◉ AI 正在生成"
            13 -> "AI 正在生成 ▰"
            14 -> "AI 正在生成 $counter"
            else -> "✦ AI 正在生成"
        }
        val themeText = Palette.of(this).text
        val palettes = when (style) {
            1 -> intArrayOf(0xFF00E5FF.toInt(), 0xFFFF35C8.toInt(), 0xFFFFD54F.toInt(), 0xFF7CFF6B.toInt(), 0xFF00E5FF.toInt())
            2 -> intArrayOf(0xFFFF3B5C.toInt(), 0xFFFFA52F.toInt(), 0xFFFFEA45.toInt(), 0xFF45E879.toInt(), 0xFF36CFFF.toInt(), 0xFF6577FF.toInt(), 0xFFD65BFF.toInt(), 0xFFFF3B5C.toInt())
            5 -> intArrayOf(0xFF747C85.toInt(), 0xFFE7EBEF.toInt(), 0xFF747C85.toInt())
            6 -> intArrayOf(0xFF858585.toInt(), 0xFFF2F2F2.toInt(), 0xFF858585.toInt())
            7, 9 -> intArrayOf(themeText, themeText)
            8 -> intArrayOf(0xFF929AA2.toInt(), 0xFFE7EBEF.toInt(), 0xFF929AA2.toInt())
            10 -> intArrayOf(0xFF2DE2C5.toInt(), 0xFF3E8BFF.toInt(), 0xFF9B62FF.toInt(), 0xFF2DE2C5.toInt())
            11 -> intArrayOf(0xFFC7D7FF.toInt(), 0xFF8BA7FF.toInt(), 0xFFFFFFFF.toInt(), 0xFFC7D7FF.toInt())
            12 -> intArrayOf(0xFF00E5FF.toInt(), 0xFF8B5CFF.toInt(), 0xFFFF4FC8.toInt(), 0xFF00E5FF.toInt())
            13 -> intArrayOf(0xFFF6E7FF.toInt(), 0xFF6DE7FF.toInt(), 0xFFFFFFFF.toInt(), 0xFFA88BFF.toInt())
            14 -> intArrayOf(0xFF49FF88.toInt(), 0xFFA8FFB9.toInt(), 0xFF2BCA70.toInt(), 0xFF49FF88.toInt())
            else -> intArrayOf(0xFF8B72FF.toInt(), 0xFF42B8F5.toInt(), 0xFFFF78B7.toInt(), 0xFF8B72FF.toInt())
        }
        fun colorAt(position: Float): Int {
            val wrapped = ((position % 1f) + 1f) % 1f
            val point = wrapped * (palettes.size - 1)
            val segment = point.toInt().coerceIn(0, palettes.size - 2)
            val fraction = point - segment
            val a = palettes[segment]
            val b = palettes[segment + 1]
            fun channel(shift: Int): Int {
                val from = (a ushr shift) and 0xFF
                val to = (b ushr shift) and 0xFF
                return (from + (to - from) * fraction).toInt().coerceIn(0, 255)
            }
            return android.graphics.Color.rgb(channel(16), channel(8), channel(0))
        }

        return SpannableString(label).apply {
            label.forEachIndexed { index, char ->
                if (char.isWhitespace()) return@forEachIndexed
                when (style) {
                    3 -> {
                        val color = if (index == 0) colorAt(phase * 0.02f) else themeText
                        setSpan(ForegroundColorSpan(color), index, index + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    5 -> {
                        val pulse = (0.5 + 0.5 * kotlin.math.sin(phase * 0.075)).toFloat()
                        val color = android.graphics.Color.rgb((116 + pulse * 115).toInt(), (124 + pulse * 111).toInt(), (133 + pulse * 106).toInt())
                        setSpan(ForegroundColorSpan(color), index, index + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    6 -> {
                        val p = index.toFloat() / (label.length - 1).coerceAtLeast(1)
                        setSpan(ForegroundColorSpan(colorAt(p + phase * 0.026f)), index, index + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        setSpan(StyleSpan(android.graphics.Typeface.BOLD), index, index + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    7 -> {
                        setSpan(ForegroundColorSpan(themeText), index, index + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        if (index == phase % label.length) {
                            setSpan(UnderlineSpan(), index, index + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            setSpan(StyleSpan(android.graphics.Typeface.BOLD), index, index + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                    8 -> {
                        val pulse = (0.5 + 0.5 * kotlin.math.sin(phase * 0.09)).toFloat()
                        val color = if (index == 0) android.graphics.Color.rgb((110 + pulse * 130).toInt(), (116 + pulse * 130).toInt(), (124 + pulse * 130).toInt()) else themeText
                        setSpan(ForegroundColorSpan(color), index, index + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    9 -> {
                        val color = if (char == '|') {
                            if (phase % 12 < 6) android.graphics.Color.WHITE else 0xFF777777.toInt()
                        } else themeText
                        setSpan(ForegroundColorSpan(color), index, index + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        if (char == '|') setSpan(StyleSpan(android.graphics.Typeface.BOLD), index, index + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    10 -> {
                        val p = index.toFloat() / (label.length - 1).coerceAtLeast(1)
                        val color = colorAt(p + phase * 0.018f + kotlin.math.sin(phase * 0.035f) * 0.025f)
                        setSpan(ForegroundColorSpan(color), index, index + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        if (index == phase % label.length) setSpan(UnderlineSpan(), index, index + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    11 -> {
                        val pulse = (0.5 + 0.5 * kotlin.math.sin(phase * 0.11)).toFloat()
                        val color = if (index == 0 || char == '·') {
                            android.graphics.Color.rgb((150 + pulse * 105).toInt(), (175 + pulse * 80).toInt(), 255)
                        } else colorAt(index.toFloat() / label.length + phase * 0.012f)
                        setSpan(ForegroundColorSpan(color), index, index + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    12 -> {
                        val p = index.toFloat() / (label.length - 1).coerceAtLeast(1)
                        val color = colorAt(p + phase * 0.024f)
                        setSpan(ForegroundColorSpan(color), index, index + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        setSpan(StyleSpan(android.graphics.Typeface.BOLD), index, index + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        if (index == phase % label.length) setSpan(BackgroundColorSpan((color and 0x00FFFFFF) or 0x44000000), index, index + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    13 -> {
                        val p = index.toFloat() / (label.length - 1).coerceAtLeast(1)
                        setSpan(ForegroundColorSpan(colorAt(p + phase * 0.016f)), index, index + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        if (index == phase % label.length) {
                            setSpan(ForegroundColorSpan(android.graphics.Color.WHITE), index, index + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            setSpan(UnderlineSpan(), index, index + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                    14 -> {
                        val pulse = (0.5 + 0.5 * kotlin.math.sin(phase * 0.10)).toFloat()
                        val color = if (char.isDigit()) android.graphics.Color.rgb(80, (190 + pulse * 65).toInt(), (110 + pulse * 100).toInt())
                            else colorAt(index.toFloat() / label.length + phase * 0.008f)
                        setSpan(ForegroundColorSpan(color), index, index + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        if (char.isDigit()) setSpan(StyleSpan(android.graphics.Typeface.BOLD), index, index + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    else -> {
                        val gradientPosition = index.toFloat() / (label.length - 1).coerceAtLeast(1)
                        val color = colorAt(gradientPosition + phase * if (style == 2) 0.031f else 0.02f)
                        setSpan(ForegroundColorSpan(color), index, index + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        if (style == 1) {
                            // 用低透明度彩色底光衬托霓虹字，避免改变输入框或键盘背景。
                            setSpan(BackgroundColorSpan((color and 0x00FFFFFF) or 0x28000000),
                                index, index + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            setSpan(StyleSpan(android.graphics.Typeface.BOLD), index, index + 1,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        if (style == 4) {
                            val sweepIndex = phase % label.length
                            if (index == sweepIndex) {
                                setSpan(ForegroundColorSpan(android.graphics.Color.WHITE), index, index + 1,
                                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                setSpan(BackgroundColorSpan(0x55FFFFFF), index, index + 1,
                                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            }
                            if (index == 0) setSpan(ForegroundColorSpan(colorAt(phase * 0.02f)),
                                index, index + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                }
            }
        }
    }

    private fun showQuickAiLoading(connection: InputConnection) {
        stopQuickAiLoading(clearText = true)
        // 先结束用户正在编辑的临时 composing 区，再建立独立的 AI 状态区，避免覆盖拼音预编辑。
        runCatching { connection.finishComposingText() }
        quickAiLoadingConnection = connection
        var phase = 0
        val animation = object : Runnable {
            override fun run() {
                if (quickAiLoadingConnection !== connection) return
                runCatching { connection.setComposingText(quickAiLoadingFrame(phase), 1) }
                phase = (phase + 1) % 1000
                // 80ms 小步推进色带，降低相邻帧色彩跳变；动画仅在 AI 请求期间运行。
                quickAiHandler.postDelayed(this, 80L)
            }
        }
        quickAiLoadingRunnable = animation
        animation.run()
    }

    /** 成功时以结果替换动画提示；失败/切换输入框时清除提示，不留下残字。 */
    private fun stopQuickAiLoading(clearText: Boolean, connection: InputConnection? = quickAiLoadingConnection) {
        quickAiLoadingRunnable?.let(quickAiHandler::removeCallbacks)
        quickAiLoadingRunnable = null
        val active = quickAiLoadingConnection
        quickAiLoadingConnection = null
        val target = connection ?: active
        if (clearText && target != null) runCatching { target.commitText("", 1) }
    }

    private fun finishQuickAiGeneration(connection: InputConnection, text: String?, error: String?) {
        if (quickAiLoadingConnection !== connection) return
        quickAiLoadingRunnable?.let(quickAiHandler::removeCallbacks)
        quickAiLoadingRunnable = null
        quickAiLoadingConnection = null
        val committed = runCatching { connection.commitText(text.orEmpty(), 1) }.getOrDefault(false)
        if (text.isNullOrBlank()) {
            toast(error ?: "AI 没有生成可用内容")
        } else if (!committed) {
            toast("AI 结果上屏失败")
        }
    }

    private fun runQuickAiChat() {
        val connection = currentInputConnection ?: run { toast("当前没有可用的输入框"); return }
        val info = currentInputEditorInfo ?: run { toast("无法识别当前输入框"); return }
        if (composer.privateInput || composer.directInput || info.wantsPrivacy() ||
            (info.inputType and android.text.InputType.TYPE_MASK_VARIATION) in setOf(
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
                android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            )
        ) { toast("当前输入框不支持 AI 快捷生成"); return }
        val contextText = quickAiSource(connection)
        if (contextText.isBlank()) { toast("输入框和剪贴板都没有可用内容"); return }
        val targetPackage = info.packageName
        val targetField = info.fieldId
        val sequence = ++quickAiSequence
        showQuickAiLoading(connection)
        aiChat.suggestReplies(contextText) { result ->
            if (sequence != quickAiSequence) return@suggestReplies
            val current = currentInputEditorInfo
            if (current == null || current.packageName != targetPackage || current.fieldId != targetField || currentInputConnection !== connection) return@suggestReplies
            result.onSuccess { replies ->
                val reply = replies.firstOrNull()?.trim().orEmpty()
                finishQuickAiGeneration(connection, reply.takeIf(String::isNotEmpty), if (reply.isEmpty()) "AI 对话没有生成可用内容" else null)
            }.onFailure { finishQuickAiGeneration(connection, null, it.message ?: "AI 对话生成失败") }
        }
    }

    private fun runQuickAiExpression() {
        val connection = currentInputConnection ?: run { toast("当前没有可用的输入框"); return }
        val info = currentInputEditorInfo ?: run { toast("无法识别当前输入框"); return }
        if (composer.privateInput || composer.directInput || info.wantsPrivacy() ||
            (info.inputType and android.text.InputType.TYPE_MASK_VARIATION) in setOf(
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
                android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            )
        ) { toast("当前输入框不支持 AI 快捷生成"); return }
        val contextText = quickAiSource(connection)
        if (contextText.isBlank()) { toast("输入框和剪贴板都没有可用内容"); return }
        val targetPackage = info.packageName
        val targetField = info.fieldId
        val sequence = ++quickAiSequence
        showQuickAiLoading(connection)
        aiExpression.generate(contextText) { result ->
            if (sequence != quickAiSequence) return@generate
            val current = currentInputEditorInfo
            if (current == null || current.packageName != targetPackage || current.fieldId != targetField || currentInputConnection !== connection) return@generate
            result.onSuccess { groups ->
                val expression = groups.firstNotNullOfOrNull { group -> group.items.firstOrNull()?.trim()?.takeIf(String::isNotEmpty) }.orEmpty()
                finishQuickAiGeneration(connection, expression.takeIf(String::isNotEmpty), if (expression.isEmpty()) "AI 情景表达没有生成可用内容" else null)
            }.onFailure { finishQuickAiGeneration(connection, null, it.message ?: "AI 情景表达生成失败") }
        }
    }

    /** 用户确认上屏后，把连续两词及以上的短语缩写写入本机常用语词库。 */
    override fun onPhraseCommitted(text: String) {
        // 只在主线程读取 Core 当前输入模式并取得分词结果；逐字转拼音、组合码生成和文件写入移到后台。
        if (text.isBlank() || composer.privateInput || composer.directInput || !composer.chinese) return
        phraseMemoryHandler.post { rememberPhraseShortcuts(text) }
    }

    override fun rememberStructuredToken(token: String) {
        if (::composer.isInitialized && !composer.privateInput && !composer.directInput) {
            StructuredTokenStore.remember(this, token)
        }
    }

    override fun structuredTokenSuggestions(prefix: String): List<String> {
        if (!::composer.isInitialized || composer.privateInput || composer.directInput) return emptyList()
        return StructuredTokenStore.suggest(this, prefix)
    }

    /** Z/X/C/V 下滑编辑操作：交由当前输入框执行系统标准上下文菜单动作。 */
    override fun onEditShortcut(action: KeyboardView.EditShortcut) {
        val actionId = when (action) {
            KeyboardView.EditShortcut.SELECT_ALL -> android.R.id.selectAll
            KeyboardView.EditShortcut.CUT -> android.R.id.cut
            KeyboardView.EditShortcut.COPY -> android.R.id.copy
            KeyboardView.EditShortcut.PASTE -> android.R.id.paste
        }
        if (currentInputConnection?.performContextMenuAction(actionId) != true) {
            toast("当前输入框暂不支持此操作")
        } else if (action == KeyboardView.EditShortcut.CUT || action == KeyboardView.EditShortcut.PASTE) {
            composer.invalidateEditHistory()
        }
    }

    override fun onKeyChoice(text: String) = composer.onKeyChoice(text)

    override fun onLanguageChoice(index: Int) = composer.chooseLanguage(Language.entries[index])

    /** 语言换了：记进设置（自己写的不重建键盘），中英键跟着重画。 */
    override fun onLanguageChanged() {
        writeSettings {
            Settings.preferences(this).edit()
                .putString(Settings.KEY_LANGUAGE, composer.language.name)
                .putString(Settings.KEY_PARTNER, composer.partner.name)
                .commit()
        }
        showKeyboard()
    }

    override fun onCursorMove(steps: Int) = composer.moveCursor(steps)

    override fun onSideItem(item: String) = composer.onSideItem(item)

    override fun onSelect(index: Int) { cancelPendingCandidateRerank(); composer.select(originalCandidateIndex(index)) }

    override fun onSmartAction(text: String, action: String) {
        val number = Regex("(?<!\\d)(?:\\+?86[- ]?)?(1[3-9]\\d{9})(?!\\d)")
            .find(text)?.groupValues?.getOrNull(1)
        val intent = if (action == "phone") {
            Intent(Intent.ACTION_DIAL, Uri.parse(if (number == null) "tel:" else "tel:$number"))
        } else {
            Intent(Intent.ACTION_SENDTO, Uri.parse(if (number == null) "smsto:" else "smsto:$number"))
        }
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onMoveCaret(index: Int) = composer.moveCaret(index)

    override fun onSelectTranslation(index: Int, sense: Int) { cancelPendingCandidateRerank(); composer.selectTranslation(originalCandidateIndex(index), sense) }

    override fun onForget(index: Int) { cancelPendingCandidateRerank(); composer.forget(originalCandidateIndex(index)) }

    override fun showNotice(text: String) = toast(text)

    override fun openSymbolPanel() = openPanel(PanelKind.SYMBOLS)

    override fun onToolbar(tool: ToolbarTool) = when (tool) {
        ToolbarTool.EMOJI -> openPanel(PanelKind.EMOJI)
        ToolbarTool.KEYBOARDS -> openPanel(PanelKind.KEYBOARDS)
        ToolbarTool.VOICE -> voiceInput()
        ToolbarTool.SYMBOLS -> openPanel(PanelKind.SYMBOLS)
        ToolbarTool.CLIPBOARD -> clipboard()
        ToolbarTool.TRANSLATE -> toggleTranslate()
        ToolbarTool.AI_EXPRESSION -> openPanel(PanelKind.AI_EXPRESSION)
        ToolbarTool.AI_CHAT -> openPanel(PanelKind.AI_CHAT)
        ToolbarTool.RESIZE -> startResize()
        ToolbarTool.PHRASES -> openPanel(PanelKind.PHRASES)
        ToolbarTool.SWITCH_INPUT_METHOD -> switchInputMethod()
        ToolbarTool.SETTINGS -> openSettings()
        ToolbarTool.SCAN -> openBarcodeScanner()
    }

    override fun openFunctions() = openPanel(PanelKind.FUNCTIONS)

    private fun openBarcodeScanner() {
        startActivity(Intent(this, BarcodeScannerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun openPanel(kind: PanelKind) {
        activePanelState = kind
        if (isClipboardPanel(kind)) {
            clipboardPanelEditorPackage = currentInputEditorInfo?.packageName
            clipboardPanelOwnerFieldId = currentInputEditorInfo?.fieldId
        } else {
            clipboardPanelEditorPackage = null
            clipboardPanelOwnerFieldId = null
        }
        val needsContext = kind == PanelKind.AI_EXPRESSION || kind == PanelKind.AI_CHAT ||
            kind == PanelKind.CONTEXT_TOOLS
        val info = currentInputEditorInfo
        val variation = info?.inputType?.and(android.text.InputType.TYPE_MASK_VARIATION) ?: 0
        val passwordField = variation == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        // 不从密码、私密或直接输入框读取上下文。文字工具优先取用户明确选中的文字，
        // 没有选区时只读取光标前最多 800 字；打开面板本身不会触发任何云端请求。
        val safeToRead = needsContext && !composer.privateInput && !composer.directInput && !passwordField
        val selected = if (safeToRead && kind == PanelKind.CONTEXT_TOOLS) {
            currentInputConnection?.getSelectedText(0)?.toString().orEmpty()
        } else ""
        val source = if (safeToRead) {
            val input = currentInputConnection
            if (kind == PanelKind.CONTEXT_TOOLS) {
                selected.takeIf { it.isNotBlank() }
                    ?: input?.getTextBeforeCursor(800, 0)?.toString().orEmpty().takeLast(800)
            } else {
                val before = input?.getTextBeforeCursor(2500, 0)?.toString().orEmpty()
                val after = input?.getTextAfterCursor(1500, 0)?.toString().orEmpty()
                (before + after).takeLast(4000)
            }
        } else ""
        // 文字工具仅展示本地识别结果；只有用户点击具体 AI 操作时才会请求云端。
        panel?.open(kind, source, selected.isNotBlank())
        showKeyboard()
    }

    override fun closePanel() {
        // 返回键关的是语音面板：这一段不要了
        if (voice.active) {
            voice.cancel()
            return
        }
        val candidates = panel?.openPanel == PanelKind.CANDIDATES
        activePanelState = null
        clipboardPanelEditorPackage = null
        clipboardPanelOwnerFieldId = null
        panel?.close()
        showKeyboard()
        // 单字是展开面板里的筛选：收起就回到全部候选
        if (candidates && composer.singleChars) composer.toggleSingleChars()
    }

    override fun commitText(text: String) {
        // AI 回复直接提交到宿主输入框。
        composer.commitPanelText(text)
    }

    override fun insertAtCursor(text: String) {
        val ic = currentInputConnection ?: return
        runCatching {
            val selected = ic.getSelectedText(0)?.isNotEmpty() == true
            if (selected) {
                val extracted = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return
                val end = maxOf(extracted.selectionStart, extracted.selectionEnd)
                if (end >= 0) {
                    ic.setSelection(end, end)
                    hasActiveSelection = false
                } else return
            }
            if (!composer.commitExternalText(text, ic)) {
                toast("插入失败，当前输入框不支持此操作")
            }
        }.onFailure { toast("插入失败，当前输入框不支持此操作") }
    }

    override fun replaceSelectedText(text: String): Boolean {
        val ic = currentInputConnection ?: return false
        if (ic.getSelectedText(0).isNullOrEmpty()) return false
        // 选区替换暂不纳入普通撤销栈，避免在受限 InputConnection 上猜测被选中的原文。
        composer.invalidateEditHistory()
        val replaced = runCatching { ic.commitText(text, 1) }.getOrDefault(false)
        if (replaced) hasActiveSelection = false
        return replaced
    }

    override fun commitClipboardText(text: String) {
        val prefs = Settings.preferences(this)
        val connection = currentInputConnection
        AppLog.info("clipboard commit: editor=${currentInputEditorInfo?.packageName}, enter=${prefs.getBoolean(Settings.KEY_CLIPBOARD_PASTE_ENTER, false)}, nextCell=${prefs.getBoolean(Settings.KEY_CLIPBOARD_PASTE_NEXT_CELL, false)}, panel=${panel?.openPanel}")
        // 点击项目只执行粘贴与可选的 Enter/Tab；绝不主动关闭剪贴板面板。
        // 剪贴板面板保持当前状态，用户只有点击面板返回键才会回到键盘。
        composer.commitPanelText(text)
        if (connection != null) {
            when {
                prefs.getBoolean(Settings.KEY_CLIPBOARD_PASTE_NEXT_CELL, false) -> {
                    connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB))
                    connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB))
                }
                prefs.getBoolean(Settings.KEY_CLIPBOARD_PASTE_ENTER, false) -> {
                    if (!composer.commitExternalText("\n", connection)) {
                        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                    }
                }
            }
        }
        AppLog.info("clipboard paste completed; panel remains open")
    }

    override fun replaceInputText(text: String): Boolean {
        val ic = currentInputConnection ?: return false
        val before = ic.getTextBeforeCursor(10000, 0)?.toString() ?: return false
        val after = ic.getTextAfterCursor(10000, 0)?.toString() ?: return false
        val cursor = before.length
        aiReplacementUndo = (before + after) to cursor
        composer.invalidateEditHistory()
        return runCatching {
            ic.beginBatchEdit()
            try {
                if (!ic.performContextMenuAction(android.R.id.selectAll)) return false
                ic.commitText(text, 1)
            } finally {
                ic.endBatchEdit()
            }
        }.getOrDefault(false)
    }

    override fun undoInputReplacement(): Boolean {
        val saved = aiReplacementUndo ?: return false
        val ic = currentInputConnection ?: return false
        return runCatching {
            ic.beginBatchEdit()
            try {
                if (!ic.performContextMenuAction(android.R.id.selectAll)) return false
                ic.commitText(saved.first, 1)
                val position = saved.second.coerceIn(0, saved.first.length)
                ic.setSelection(position, position)
                aiReplacementUndo = null
                true
            } finally {
                ic.endBatchEdit()
            }
        }.getOrDefault(false)
    }

    override fun deleteBackward() = composer.onKey(Key.Backspace)

    override fun startResize() {
        closePanel()
        panel?.bar?.startResize()
    }

    override fun switchInputMethod() {
        getSystemService(InputMethodManager::class.java).showInputMethodPicker()
    }

    override fun chooseLayout(layout: Settings.Layout) {
        closePanel()
        composer.chooseLanguage(Language.CHINESE)
        Settings.preferences(this).edit().putString(Settings.KEY_LAYOUT, layout.name).apply()
    }

    override fun chooseEnglish() {
        closePanel()
        composer.chooseLanguage(Language.ENGLISH)
    }

    override fun chooseJapanese() {
        closePanel()
        composer.chooseLanguage(Language.JAPANESE)
    }

    override val english: Boolean get() = !composer.chinese && !composer.japanese

    override val japanese: Boolean get() = composer.japanese

    override fun setToggle(key: String, on: Boolean) {
        reopenPanel = panel?.openPanel
        Settings.preferences(this).edit().putBoolean(key, on).apply()
    }

    override fun setChoice(key: String, value: String) {
        reopenPanel = panel?.openPanel
        Settings.preferences(this).edit().putString(key, value).apply()
    }

    /** 面板开着时系统返回键先关面板。 */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.repeatCount == 0 && panel?.openPanel != null) {
            closePanel()
            backConsumed = true
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && backConsumed) {
            backConsumed = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun openSettings() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    private fun onHideKeyboard() = requestHideSelf(0)

    private fun onBottomAction(action: BottomAction) = when (action) {
        BottomAction.SWITCH_INPUT_METHOD -> switchInputMethod()
        BottomAction.VOICE -> voiceInput()
        BottomAction.CLIPBOARD -> clipboard()
        BottomAction.HIDE_KEYBOARD -> onHideKeyboard()
        BottomAction.NONE -> Unit
    }

    /** 工具栏、全面屏底栏与空格键下滑的剪贴板入口。 */
    private fun clipboard() = openPanel(PanelKind.CLIPBOARD)

    /**
     * 将用户确认上屏的中文按 Core 分词，保存所有连续二词及以上片段的逐字拼音首字母。
     * 使用常用语的本机存储，因此可随既有短语备份/恢复，并由 Core 作为个人候选召回。
     */
    private fun rememberPhraseShortcuts(text: String) {
        if (text.isBlank()) return
        // 使用 Android ICU 中文分词器在后台切词，避免为了自动记忆再次同步调用 Core/JNI。
        val iterator = BreakIterator.getWordInstance(java.util.Locale.CHINA)
        iterator.setText(text)
        val pieces = mutableListOf<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val piece = text.substring(start, end)
            if (piece.any { char -> isHanCodePoint(char.code) }) pieces += piece
            start = end
            end = iterator.next()
        }
        if (pieces.isEmpty()) return
        val maxCode = PhraseRules.maxCode.takeIf { it > 0 } ?: 32
        val known = ClipboardStore.phrases(this).phrases
            .mapTo(mutableSetOf()) { it.text to it.code }
        var changed = false
        // 同时保存单个词和连续 2～5 词组合；这样“安装包”这类单个分词也能记住 azb。
        // 5 词以上另外保存完整短句，避免超长句产生大量重复条目。
        val spans = mutableListOf<Pair<Int, Int>>()
        for (start in pieces.indices) {
            for (length in 1..minOf(5, pieces.size - start)) {
                spans += start to (start + length)
            }
        }
        if (pieces.size > 5) spans += 0 to pieces.size
        for ((start, end) in spans) {
            val phrase = pieces.subList(start, end).joinToString("")
            // 同一词保存多种可输入码：全拼首字母，以及从前往后逐步展开的混合简拼。
            // 例如“主题色”会记住 zts、zhuts、zhutis、zhutise，方便用户按习惯缩写召回。
            for (code in pinyinShortcutVariants(phrase)) {
                if (code.length !in 2..maxCode || !known.add(phrase to code)) continue
                ClipboardStore.addPhrase(this, phrase, "自动记忆", code)
                changed = true
            }
        }
        if (changed) candidateRerankHandler.post { if (::engine.isInitialized) engine.reloadPhrases(ClipboardStore.userDir(this)) }
    }

    private fun isHanCodePoint(codePoint: Int): Boolean =
        Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN

    /** 逐字取得无声调拼音，保留音节边界供多种简拼编码使用。 */
    private fun pinyinSyllables(text: String): List<String> =
        text.codePoints().toArray().asSequence().mapNotNull { codePoint ->
            if (!isHanCodePoint(codePoint)) return@mapNotNull null
            val raw = phraseTransliterator.transliterate(String(Character.toChars(codePoint))).lowercase()
            Normalizer.normalize(raw, Normalizer.Form.NFD)
                .filter { it in 'a'..'z' }
                .takeIf(String::isNotEmpty)
        }.toList()

    /**
     * 生成逐步展开的简拼：首字母码 + 前缀音节逐个展开的混合码。
     * 三字词“主题色”对应 zts / zhuts / zhutis / zhutise。
     * 长句不做指数级组合，只保存逐段展开码，控制个人词库体积。
     */
    private fun pinyinShortcutVariants(text: String): Set<String> {
        val syllables = pinyinSyllables(text)
        if (syllables.isEmpty()) return emptySet()
        val variants = linkedSetOf(syllables.joinToString("") { it.first().toString() })
        if (syllables.size <= 6) {
            val combinations = 1 shl syllables.size
            for (mask in 1 until combinations) {
                variants += syllables.mapIndexed { index, syllable ->
                    if ((mask and (1 shl index)) != 0) syllable else syllable.first().toString()
                }.joinToString("")
            }
        } else {
            for (expanded in 1..syllables.size) {
                variants += syllables.mapIndexed { index, syllable ->
                    if (index < expanded) syllable else syllable.first().toString()
                }.joinToString("")
            }
            variants += syllables.joinToString("")
        }
        return variants
    }

    override val clipboardLimit: Int get() = Settings.load(this).clipboardLimit

    override fun openPieces(text: String) {
        panel?.open(PanelKind.PIECES, text)
    }

    override fun textPieces(text: String): List<String> = engine.textPieces(text)

    override fun phrasesChanged() = engine.reloadPhrases(ClipboardStore.userDir(this))

    override fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun voiceNeedsSetup() = needsSetup(getString(R.string.voice_needs_setup), VoiceSettingsActivity::class.java)

    /**
     * 要先配好才能用的功能（语音、快捷翻译）：在工具栏的位置放一句提示，点它打开 [page]；
     * 不只弹 toast：toast 一闪就过，也没有去设置的路。键盘没建起来时退回 toast。
     */
    private fun needsSetup(message: String, page: Class<*>) {
        val bar = panel?.bar ?: return toast(message)
        closePanel()
        bar.showNotice(message) {
            startActivity(Intent(this, page).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun composeText(text: String) {
        currentInputConnection?.setComposingText(text, 1)
    }

    override fun finishComposeText() {
        currentInputConnection?.finishComposingText()
    }

    override fun copyToClipboard(text: String) {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("青简", text))
        toast("已复制")
    }

    /**
     * 剪贴板里点了图片：输入框收图片就用 `commitContent` 发过去（对方经 [ClipboardImageProvider] 临时授权读），
     * 不收的就放回系统剪贴板，让用户长按粘贴。
     */
    override fun commitImage(path: String) {
        val uri = ClipboardImageProvider.uriOf(path)
        val mime = ClipboardImageProvider.mimeOf(path)
        val accepts = currentInputEditorInfo?.contentMimeTypes.orEmpty().any { ClipDescription.compareMimeTypes(mime, it) }
        val connection = currentInputConnection
        if (accepts && connection != null) {
            val info = InputContentInfo(uri, ClipDescription("图片", arrayOf(mime)), null)
            if (connection.commitContent(info, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)) return
        }
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newUri(contentResolver, "图片", uri))
        toast("这里不能直接发图片，已放进剪贴板，长按输入框粘贴")
    }

    /** 加 / 改常用语：收起面板，键盘上方摆编辑框，打的字进编辑框；完成或收起回常用语页。 */
    override fun editPhrase(phrase: PhraseItem?, group: String) {
        closeEditor()
        closePanel()
        composer.abandon()
        val groups = ClipboardStore.phrases(this).groups.let { if (group in it) it else it + group }
        val editor = PhraseEditor(
            this, Palette.of(this), groups, phrase, group,
            onDone = { text, chosenGroup, code ->
                val saved = if (phrase == null) {
                    ClipboardStore.addPhrase(this, text, chosenGroup, code)
                } else {
                    ClipboardStore.updatePhrase(this, phrase.index, text, chosenGroup, code)
                }
                if (!saved.operationSucceeded) {
                    toast(if (text.isBlank()) "常用语内容不能为空" else saved.operationError ?: "常用语保存失败，详细原因请查看 qingjian-phrases 日志")
                } else {
                    ClipboardPanel.lastGroup = chosenGroup.trim().ifEmpty { PhraseRules.defaultGroup }
                    phrasesChanged()
                    closeEditor()
                    openPanel(PanelKind.PHRASES)
                }
            },
            onCancel = {
                closeEditor()
                openPanel(PanelKind.PHRASES)
            },
            onFocusCode = { code ->
                composer.abandon()
                composer.directInput = code || currentInputEditorInfo?.wantsDirectInput() == true
                showKeyboard()
            },
        )
        engine.setMetered(false)
        this.editor = editor
        panel?.showEditor(editor)
    }

    override fun editClip(index: Int, text: String) {
        closeEditor()
        closePanel()
        composer.abandon()
        val editor = PhraseEditor(
            this, Palette.of(this), emptyList(), null, "",
            onDone = { edited, _, _ ->
                ClipboardStore.edit(this, index, text, edited, clipboardLimit)
                ClipboardSync.changed()
                closeEditor()
                openPanel(PanelKind.CLIPBOARD)
            },
            onCancel = {
                closeEditor()
                openPanel(PanelKind.CLIPBOARD)
            },
            onFocusCode = {},
            clip = text,
        )
        engine.setMetered(false)
        this.editor = editor
        panel?.showEditor(editor)
    }

    /**
     * 工具栏的翻译：开快捷翻译条（再点一下收起，译文留着）。没配云端就提示去设置。
     * 方向胶囊写学习语言。
     */
    private fun toggleTranslate() {
        if (editor is TranslateBar) {
            closeEditor()
            return
        }
        if (!cloud.sync(translate = true)) {
            needsSetup(getString(R.string.translate_needs_setup), CloudSettingsActivity::class.java)
            return
        }
        closeEditor()
        closePanel()
        composer.abandon()
        val bar = TranslateBar(
            this, Palette.of(this), partner = "英",
            onSource = quickTranslate::onSource,
            onConfirm = { closeEditor() },
            onCancel = {
                quickTranslate.discard()
                closeEditor()
            },
        )
        quickTranslate.bar = bar
        engine.setMetered(false)
        editor = bar
        panel?.showEditor(bar)
        showKeyboard()
    }

    private fun closeEditor() {
        if (editor == null) return
        // 快捷翻译收起（确定、再点图标、换输入框）都把已有的译文落定；取消在这之前已经撤掉了
        if (editor is TranslateBar) quickTranslate.finish()
        composer.abandon()
        // 编辑条里打的字不进应用，不算「打了多少字」；收起后照常记
        engine.setMetered(true)
        editor = null
        composer.directInput = currentInputEditorInfo?.wantsDirectInput() == true
        panel?.showEditor(null)
        showKeyboard()
    }

    /** 工具栏的话筒、全面屏底栏的语音键：点一下开始说，说完点「完成」。 */
    private fun voiceInput() {
        voice.start(holding = false)
    }

    override fun onVoiceHold(): Boolean = voice.start(holding = true)

    override fun onVoiceDrag(screenX: Float, screenY: Float) = voice.drag(screenX, screenY)

    override fun onVoiceRelease(cancel: Boolean) = voice.release(cancel)

    override fun onSwipe(input: String) = composer.onSwipe(input)

    override fun onBackspaceDown() = composer.onBackspaceDown()

    override fun onClearAll() = composer.clearAll()

    override fun onClearBeforeCursor() = composer.clearBeforeCursor()


    // 回车键写的字（发送、搜索…）照搬到面板右边的键上；多行框没有动作，写「完成」
    override fun openVoicePanel(actions: VoicePanel.Actions): VoicePanel? =
        panel?.openVoice((editor?.enterLabel ?: enterLabel) ?: getString(R.string.voice_done), actions)

    override fun hideKeyboard() = onHideKeyboard()

    override fun closeVoicePanel() {
        panel?.close()
    }

    override fun canStartVoice(): Boolean {
        val info = currentInputEditorInfo ?: return false
        val variation = info.inputType and android.text.InputType.TYPE_MASK_VARIATION
        val passwordField = variation == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        return !passwordField && !composer.privateInput && !composer.directInput
    }

    override fun correctVoiceText(text: String, callback: (Result<String>) -> Unit) {
        aiChat.correctVoiceText(text, callback)
    }

    override fun refineCommittedVoice(original: String, corrected: String): Boolean =
        composer.refineCommittedVoice(original, corrected)

    override fun undoVoice(): Boolean = composer.undo()

    override fun redoVoice(): Boolean = composer.redo()

    override fun prepareVoice() = composer.prepareVoice()

    // 走 [connection]：快捷翻译、常用语编辑条开着时字进编辑条
    override fun composeVoice(text: String) {
        val connection = connection ?: return
        connection.setComposingText(text, 1)
        if (text.isEmpty()) connection.finishComposingText()
    }

    override fun commitVoice(text: String) = composer.commitVoice(text)

    override fun performVoiceAction() {
        if (editor == null && enterLabel != null) performEditorAction()
    }

    override fun onResizeDrag(deltaPx: Float, done: Boolean) {
        val keyboard = panel?.keyboard ?: return
        val from = resizeFrom ?: Settings.load(this).heightPercent.also { resizeFrom = it }
        val percent = (from + deltaPx / keyboard.baseHeight * 100).toInt()
            .coerceIn(Settings.HEIGHT_MIN, Settings.HEIGHT_MAX)
        keyboard.setHeightPercent(percent)
        if (done) {
            resizeFrom = null
            writeSettings { Settings.preferences(this).edit().putInt(Settings.KEY_HEIGHT, percent).commit() }
        }
    }
}
