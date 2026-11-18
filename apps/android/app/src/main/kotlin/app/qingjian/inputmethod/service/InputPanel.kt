package app.qingjian.inputmethod.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Matrix
import android.graphics.Shader
import android.graphics.LinearGradient
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.drawable.GradientDrawable
import app.qingjian.inputmethod.candidates.CandidateBarView
import app.qingjian.inputmethod.candidates.CandidateGrid
import app.qingjian.inputmethod.candidates.CloudSuggestionsView
import app.qingjian.inputmethod.cloud.AiChatGenerator
import app.qingjian.inputmethod.cloud.AiExpressionGroup
import app.qingjian.inputmethod.candidates.StripSource
import app.qingjian.inputmethod.core.frame.Candidate
import app.qingjian.inputmethod.core.store.ClipboardStore
import app.qingjian.inputmethod.keyboard.BottomActionsView
import app.qingjian.inputmethod.keyboard.KeyboardView
import app.qingjian.inputmethod.panel.AiChatPanel
import app.qingjian.inputmethod.panel.AiExpressionPanel
import app.qingjian.inputmethod.panel.CatalogPanel
import app.qingjian.inputmethod.panel.ContextToolsPanel
import app.qingjian.inputmethod.panel.FunctionPanel
import app.qingjian.inputmethod.panel.KeyboardPickerPanel
import app.qingjian.inputmethod.panel.PanelActions
import app.qingjian.inputmethod.panel.PanelKind
import app.qingjian.inputmethod.panel.ToolbarPanel
import app.qingjian.inputmethod.panel.VoicePanel
import app.qingjian.inputmethod.panel.clipboard.ClipboardPanel
import app.qingjian.inputmethod.panel.clipboard.PiecesPanel
import app.qingjian.inputmethod.panel.editor.FloatingEditor
import app.qingjian.inputmethod.settings.BottomAction
import app.qingjian.inputmethod.settings.Settings
import app.qingjian.inputmethod.theme.Palette

/**
 * 覆盖整块输入法窗口的流动光影背景。键盘、候选工具栏和底部操作条共用同一层，
 * 避免只在 KeyboardView 自己的画布里绘制而导致上下两排仍露出纯主题色。
 */
private class FlowingKeyboardBackgroundDrawable(
    private val palette: Palette,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val frost = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x18FFFFFF }
    private val matrix = Matrix()
    private var shader: LinearGradient? = null
    private var shaderWidth = 0
    private var shaderHeight = 0
    private val frame = Runnable {
        invalidateSelf()
        scheduleSelf(frame, SystemClock.uptimeMillis() + FRAME_DELAY_MS)
    }

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        super.onBoundsChange(bounds)
        val w = bounds.width()
        val h = bounds.height()
        if (w <= 0 || h <= 0) {
            shader = null
            return
        }
        shaderWidth = w
        shaderHeight = h
        shader = LinearGradient(
            -w.toFloat(), 0f, w.toFloat(), h.toFloat(),
            intArrayOf(
                (palette.board and 0x00FFFFFF) or 0xB8000000.toInt(),
                (palette.brand and 0x00FFFFFF) or 0x55000000,
                (palette.accent and 0x00FFFFFF) or 0x66000000,
                (palette.cloud and 0x00FFFFFF) or 0x44000000,
                (palette.board and 0x00FFFFFF) or 0xB8000000.toInt(),
            ),
            null,
            Shader.TileMode.MIRROR,
        )
        unscheduleSelf(frame)
        scheduleSelf(frame, SystemClock.uptimeMillis() + FRAME_DELAY_MS)
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        val activeShader = shader ?: return
        if (bounds.isEmpty || bounds.width() != shaderWidth || bounds.height() != shaderHeight) return
        val period = (shaderWidth * 2L).coerceAtLeast(1L)
        val offset = (SystemClock.uptimeMillis() % period).toFloat() - shaderWidth
        matrix.setTranslate(offset, 0f)
        activeShader.setLocalMatrix(matrix)
        paint.shader = activeShader
        paint.alpha = 210
        canvas.drawRect(bounds, paint)
        paint.shader = null
        canvas.drawRect(bounds, frost)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

    private companion object {
        const val FRAME_DELAY_MS = 40L
    }
}

/**
 * 输入法的整块视图：候选条 + 键盘，全面屏适配开着且角上放了键时底下再接一条操作条，否则只让出导航栏的高度（游戏键盘里两样都不要）。
 * 面板（[PanelKind]）盖在候选条与键盘那一整块上，高度跟键盘走；底下的键盘留着占位，关面板就露出来。
 */
class InputPanel(
    private val context: Context,
    val settings: Settings,
    barListener: CandidateBarView.Listener,
    stripSource: StripSource,
    keyboardListener: KeyboardView.Listener,
    private val actions: PanelActions,
    onBottomAction: (BottomAction) -> Unit,
    onCloudSelect: (Int) -> Unit,
    private val onGenerateAiExpression: (String, (Result<List<AiExpressionGroup>>) -> Unit) -> Unit,
    private val aiChatGenerator: AiChatGenerator,
    /** 游戏键盘里浮着用（[GameFrame]）：不接底下的操作条，也不让导航栏。 */
    private val floating: Boolean = false,
) {
    // 游戏键盘一律深色：浮在游戏画面上，浅色太扎眼
    private val palette = Palette.of(settings.theme, floating || settings.appearance.dark(context))

    val bar = CandidateBarView(context, palette, settings.textSize.scale, stripSource, settings.toolbar, barListener, settings.fontWeight).apply {
        setBackgroundColor(if (settings.keyboardBackgroundEffect) Color.TRANSPARENT else palette.board)
    }
    private val cloudSuggestions = CloudSuggestionsView(context, palette, onCloudSelect)
    /** Tips 行只占位、不绘制背景；玻璃效果由 Tips 自身的独立窗口提供。 */
    private val cloudTipsSlot = FrameLayout(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
        visibility = View.INVISIBLE
        addView(
            cloudSuggestions,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                0,
                Gravity.CENTER,
            ),
        )
        cloudSuggestions.setAnchorView(this)
    }
    val keyboard = KeyboardView(context, palette, settings, keyboardListener)

    /** 开着的面板；null 是键盘。 */
    var openPanel: PanelKind? = null
        private set

    private var panelView: View? = null
    private var chatPanel: AiChatPanel? = null
    private var voiceView: VoicePanel? = null

    /** AI 对话面板接管当前输入连接，让底部实体键盘可以直接编辑面板字段。 */
    val aiChatEditor: AiChatPanel? get() = chatPanel

    // 云联想使用独立悬浮 Tips，不加入垂直布局、不挤占候选栏和键盘高度。
    private val main = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
        addView(bar)
        addView(keyboard)
    }

    private val stack = Stack(context, main).apply {
        clipChildren = false
        clipToPadding = false
        addView(main)
    }

    /** 建视图时量到的导航栏高度；用户换导航方式（手势 ↔ 三键）后会过时，见 [stale]。 */
    private val bottomInset = navigationBarHeight(context)

    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = if (settings.keyboardBackgroundEffect) {
            FlowingKeyboardBackgroundDrawable(palette)
        } else {
            android.graphics.drawable.ColorDrawable(palette.board)
        }
        clipChildren = false
        clipToPadding = false
        // Tips 行本身透明且高度固定；Tips 内容在这一行水平居中，不贴靠右侧。
        addView(
            cloudTipsSlot,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            },
        )
        addView(stack)
        val inset = bottomInset
        if (floating) return@apply
        // 两个角都不放键时不要那条操作条，只让出导航栏，不留一截空白（#889）
        val corners = settings.bottomLeft != BottomAction.NONE || settings.bottomRight != BottomAction.NONE
        if (settings.fullscreenAdapt && corners) {
            addView(BottomActionsView(context, palette, inset, settings.bottomLeft, settings.bottomRight, onBottomAction, transparentBackground = settings.keyboardBackgroundEffect))
        } else {
            setPadding(0, 0, 0, inset)
        }
    }

    /** 云联想以浮动 Tips 显示；没有候选时连输入法顶部的占位行也一并隐藏。 */
    fun showCloudSuggestions(candidates: List<Pair<Int, String>>) {
        // Tips 在独立悬浮窗口中显示；输入法布局里不再保留 46dp 占位行。
        cloudSuggestions.show(candidates)
    }

    /** 将云 Tips 锚定到当前编辑器光标上方。 */
    fun updateCloudTipsAnchor(screenX: Float, screenY: Float) =
        cloudSuggestions.updateEditorAnchor(screenX, screenY)

    /** 实时设备信息 / 定位天气候选，点击后由服务决定上屏或申请权限。 */
    fun showSmartSuggestions(candidates: List<String>, onSelect: (String) -> Unit) {
        cloudSuggestions.showSmart(candidates, onSelect)
    }

    /** 打开 [kind]；拆词要带上那段文字 [text]。 */
    fun open(kind: PanelKind, text: String = "", hasSelection: Boolean = false) {
        close()
        val header = bar.barHeight
        val panel = when (kind) {
            PanelKind.FUNCTIONS -> FunctionPanel(context, palette, actions, header, settings)
            PanelKind.KEYBOARDS -> KeyboardPickerPanel(context, palette, actions, header, settings.layout)
            PanelKind.TOOLBAR -> ToolbarPanel(context, palette, actions, header, settings.toolbar)
            PanelKind.SYMBOLS, PanelKind.EMOJI -> CatalogPanel(
                context, palette, actions, header, kind,
                leftThumb = settings.arrangement == Settings.Arrangement.LEFT_THUMB,
            )
            PanelKind.CANDIDATES -> CandidateGrid(context, palette, actions, header, settings.textSize.scale)
            PanelKind.CONTEXT_TOOLS -> ContextToolsPanel(
                context, palette, header, text, hasSelection, aiChatGenerator,
                actions::insertAtCursor, actions::replaceSelectedText, actions::copyToClipboard, actions::closePanel,
            )
            PanelKind.AI_CHAT -> AiChatPanel(
                context, palette, header, text, aiChatGenerator, actions::replaceInputText, actions::undoInputReplacement, actions::closePanel,
            )
            PanelKind.AI_EXPRESSION -> AiExpressionPanel(
                context, palette, header, text, onGenerateAiExpression, actions::replaceInputText, actions::undoInputReplacement, actions::closePanel,
            )
            PanelKind.CLIPBOARD, PanelKind.PHRASES -> ClipboardPanel(context, palette, actions, header, phrasesFirst = kind == PanelKind.PHRASES)
            PanelKind.PIECES -> PiecesPanel(context, palette, actions, header, text)
            // 语音面板带着回调，由 [openVoice] 盖上
            PanelKind.VOICE -> return
        }
        cover(kind, panel)
    }

    /** 盖上语音面板：右边那块写 [actionLabel]，点各块接回 [actions]。 */
    fun openVoice(actionLabel: String, actions: VoicePanel.Actions): VoicePanel {
        close()
        // 先把当前键盘画成一张底图，再由 VoicePanel 做背景模糊和深色染色；
        // 不直接改键盘视图本身，语音结束后原键盘可立即恢复交互。
        val backdrop = runCatching {
            val w = main.width
            val h = main.height
            if (w <= 0 || h <= 0) null else Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmap ->
                main.draw(Canvas(bitmap))
            }
        }.getOrNull()
        return VoicePanel(context, palette, actionLabel, actions, backdrop, VoicePanel.resolveAnimationStyle(context)).also { voice ->
            applyFontWeight(voice)
            voiceView = voice
            stack.addView(voice, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            openPanel = PanelKind.VOICE
            overlay?.bringToFront()
            voice.bringToFront()
        }
    }

    private fun cover(kind: PanelKind, panel: View) {
        applyFontWeight(panel)
        // AI 对话与 AI 情境表达使用完全相同的面板容器和测量高度，
        // 不再额外搬动候选栏或创建覆盖整块输入法窗口的特殊层。
        if (kind == PanelKind.AI_CHAT && panel is AiChatPanel) {
            chatPanel = panel
        }
        stack.addView(
            panel,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        main.visibility = View.INVISIBLE
        panelView = panel
        openPanel = kind
        overlay?.bringToFront()
    }

    /** 一直盖在最上面的一层（同步的边缘光）。放进 [stack] 而不是在外面再包一层：输入法的视图外面多包一层，键盘就贴到屏幕顶上去了。 */
    fun addOverlay(view: View) {
        overlay = view
        stack.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private var overlay: View? = null

    val view: View get() = root

    /** 编辑条（常用语编辑框、快捷翻译）摆在最上面（键盘照常可打）；[editor] 为 null 收起。 */
    fun showEditor(editor: View?) {
        root.getChildAt(0)?.takeIf { it is FloatingEditor }?.let(root::removeView)
        editor?.let {
            applyFontWeight(it)
            root.addView(it, 0)
        }
    }

    /** 刷新剪贴板管理面板；临时预览由 ClipboardWatcher 单独更新。 */
    fun refreshClipboard() {
        if (openPanel == PanelKind.CLIPBOARD) open(PanelKind.CLIPBOARD)
    }

    fun refreshFunctions() {
        (panelView as? FunctionPanel)?.refreshState()
    }

    /** 候选展开开着时换上这一帧的拼音与候选。 */
    fun showCandidates(preedit: String, candidates: List<Candidate>, singleChars: Boolean) {
        (panelView as? CandidateGrid)?.show(preedit, candidates, singleChars)
    }

    /**
     * 将字体粗细统一应用到面板内所有 TextView/EditText/Button，递归覆盖各功能面板的文本控件。
     * 保留控件原有的粗体语义；键盘键面与候选条由各自绘制器使用同一设置。
     */
    private fun applyFontWeight(view: View) {
        val selected = settings.fontWeight.typeface
        if (view is TextView) {
            view.typeface = Typeface.create(selected, if (view.typeface?.isBold == true) Typeface.BOLD else Typeface.NORMAL)
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) applyFontWeight(view.getChildAt(index))
        }
    }

    /** 回到键盘；没开面板时什么都不做。 */
    fun close() {
        panelView?.let(stack::removeView)
        voiceView?.let(stack::removeView)
        panelView = null
        voiceView = null
        chatPanel = null
        openPanel = null
        main.visibility = View.VISIBLE
    }

    /** 先量键盘那一块，面板一律量成同样高；MATCH_PARENT 在输入法窗口里会撑满整屏。 */
    private class ChatOverlay(
        context: Context,
        panel: View,
        candidateBar: View,
        candidateHeight: Int,
    ) : LinearLayout(context) {
        init {
            orientation = VERTICAL
            addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
            addView(candidateBar, LayoutParams(LayoutParams.MATCH_PARENT, candidateHeight))
        }
    }

    private class Stack(context: Context, private val main: View) : FrameLayout(context) {
        private fun Int?.orZero() = this ?: 0

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            measureChild(main, widthMeasureSpec, heightMeasureSpec)
            val height = MeasureSpec.makeMeasureSpec(main.measuredHeight, MeasureSpec.EXACTLY)
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child === main) continue
                if (child is CloudSuggestionsView) {
                    // 悬浮 Tips 自己测量高度，不参与候选栏/键盘的垂直排版。
                    measureChild(child, widthMeasureSpec, heightMeasureSpec)
                    continue
                }
                val childHeight = if (child is ChatOverlay) {
                    (main.measuredHeight - (main as? LinearLayout)?.getChildAt(2)?.measuredHeight.orZero()).coerceAtLeast(0)
                } else main.measuredHeight
                child.measure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY))
            }
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), main.measuredHeight)
        }
    }

    /**
     * 导航栏高度变了（换了手势 / 三键导航）：输入法进程不一定重建视图，留着旧的底边，三键的导航栏会盖住最下一排键。
     * 服务每次弹键盘时问一次，变了就重建。
     */
    fun stale(): Boolean = navigationBarHeight(context) != bottomInset

    /** 系统导航栏 / 手势条的高度。输入法窗口自己收不到这份 inset，从窗口度量里取。 */
    private fun navigationBarHeight(context: Context): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return context.getSystemService(WindowManager::class.java).currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom
        }
        val id = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }
}
