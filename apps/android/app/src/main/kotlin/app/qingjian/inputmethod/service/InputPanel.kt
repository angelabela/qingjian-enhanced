package app.qingjian.inputmethod.service

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Outline
import android.graphics.Shader
import android.graphics.LinearGradient
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import app.qingjian.inputmethod.candidates.CandidateBarView
import app.qingjian.inputmethod.mascot.FloatingMascotService
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
import app.qingjian.inputmethod.panel.DynamicBackgroundPanel
import app.qingjian.inputmethod.panel.KeyEffectsPanel
import app.qingjian.inputmethod.background.DynamicBackgroundActivity
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
import androidx.core.content.ContextCompat

/**
 * 覆盖整块输入法窗口的流动光影背景。键盘、候选工具栏和底部操作条共用同一层，
 * 避免只在 KeyboardView 自己的画布里绘制而导致上下两排仍露出纯主题色。
 */
private class FlowingKeyboardBackgroundDrawable(
    private val palette: Palette,
    private val style: Int,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val motionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val frost = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x18FFFFFF }
    private val matrix = Matrix()
    private var shader: LinearGradient? = null
    private var shaderWidth = 0
    private var shaderHeight = 0
    private val frame: Runnable = object : Runnable {
        override fun run() {
            invalidateSelf()
            scheduleSelf(this, SystemClock.uptimeMillis() + FRAME_DELAY_MS)
        }
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
            colorsForStyle(style),
            FloatArray(colorsForStyle(style).size) { index ->
                index.toFloat() / (colorsForStyle(style).size - 1).coerceAtLeast(1)
            },
            Shader.TileMode.MIRROR,
        )
        unscheduleSelf(frame)
        scheduleSelf(frame, SystemClock.uptimeMillis() + FRAME_DELAY_MS)
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        val activeShader = shader ?: return
        if (bounds.isEmpty || bounds.width() != shaderWidth || bounds.height() != shaderHeight) return
        // 多个不同周期叠加，连续运动；比上一版明显加快，同时避免短周期机械回跳。
        val now = SystemClock.uptimeMillis()
        fun phase(period: Long, shift: Double = 0.0): Double =
            (now % period).toDouble() / period.toDouble() * (Math.PI * 2.0) + shift
        val p = phase(BACKGROUND_CYCLE_MS)
        val driftX = kotlin.math.sin(p) * 0.66 +
            kotlin.math.sin(phase(117_000L, 1.7)) * 0.23 +
            kotlin.math.sin(phase(49_000L, 0.4)) * 0.09
        val driftY = kotlin.math.sin(phase(96_000L, 2.1)) * 0.16
        val offsetX = (driftX * shaderWidth * 1.18).toFloat()
        val offsetY = (driftY * shaderHeight * 1.18).toFloat()
        matrix.setTranslate(offsetX, offsetY)
        activeShader.setLocalMatrix(matrix)
        paint.shader = activeShader
        paint.alpha = 190
        canvas.drawColor(palette.board)
        canvas.drawRect(bounds, paint)
        paint.shader = null
        canvas.drawRect(bounds, frost)

        // 第1套保留原有极光流彩；其余三套使用不同的运动构图，而非只替换色板。
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        val t = now / 1000.0
        motionPaint.style = Paint.Style.STROKE
        when (style.coerceIn(0, 6)) {
            1 -> { // 深海蓝潮：三层错相海浪横向推进
                for (layer in 0 until 4) {
                    val path = Path()
                    val baseY = h * (0.40f + layer * 0.16f)
                    for (step in 0..48) {
                        val x = w * step / 48f
                        val wave = kotlin.math.sin(x / w * Math.PI * 3.2 + t * (0.72 + layer * 0.09) + layer).toFloat()
                        val swell = kotlin.math.sin(x / w * Math.PI * 1.15 - t * 0.38 + layer * 0.8).toFloat()
                        val y = baseY + wave * h * 0.045f + swell * h * 0.025f
                        if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    motionPaint.color = when (layer % 2) { 0 -> 0x6654E7F2; 1 -> 0x5540A8FF; else -> 0x4478F4D8 }
                    motionPaint.strokeWidth = (2.5f + layer * 1.1f) * (h / 240f).coerceAtLeast(0.7f)
                    canvas.drawPath(path, motionPaint)
                }
            }
            2 -> { // 日落熔金：缓慢升降的太阳圆盘与地平线光环
                val sunX = w * (0.70f + kotlin.math.sin(t * 0.19).toFloat() * 0.08f)
                val sunY = h * (0.43f + kotlin.math.sin(t * 0.23).toFloat() * 0.055f)
                motionPaint.style = Paint.Style.FILL
                motionPaint.color = 0x44FFD16A
                canvas.drawCircle(sunX, sunY, h * 0.27f, motionPaint)
                motionPaint.color = 0x66FFB45E
                canvas.drawCircle(sunX, sunY, h * 0.17f, motionPaint)
                motionPaint.color = 0x99FFE0A0.toInt()
                canvas.drawCircle(sunX, sunY, h * 0.105f, motionPaint)
                motionPaint.style = Paint.Style.STROKE
                for (ring in 0..2) {
                    motionPaint.color = (0x36 + ring * 0x0A shl 24) or 0x00FFB86B
                    motionPaint.strokeWidth = 1.5f * (ring + 1)
                    canvas.drawCircle(sunX, sunY, h * (0.21f + ring * 0.075f), motionPaint)
                }
                motionPaint.style = Paint.Style.FILL
                motionPaint.color = 0x30FF8C58
                canvas.drawRect(0f, h * 0.72f, w, h, motionPaint)
            }
            3 -> { // 翡翠幻境：偏心旋转的叶片/涡旋轨迹，带呼吸式扩张
                val cx = w * 0.52f
                val cy = h * 0.52f
                for (arm in 0 until 5) {
                    val path = Path()
                    for (step in 0..90) {
                        val u = step / 90f
                        val angle = u * (Math.PI * 2.6) + t * 0.52 + arm * (Math.PI * 2.0 / 5.0)
                        val radius = h * (0.035f + u * 0.34f + kotlin.math.sin(t * 0.31 + arm).toFloat() * 0.018f)
                        val x = cx + kotlin.math.cos(angle).toFloat() * radius * 1.45f
                        val y = cy + kotlin.math.sin(angle).toFloat() * radius * 0.72f
                        if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    motionPaint.color = if (arm % 2 == 0) 0x6659E5B5 else 0x555CBF91
                    motionPaint.strokeWidth = (2.2f + arm * 0.45f) * (h / 240f).coerceAtLeast(0.7f)
                    motionPaint.style = Paint.Style.STROKE
                    canvas.drawPath(path, motionPaint)
                }
                motionPaint.style = Paint.Style.FILL
                motionPaint.color = 0x448FFFD0
                canvas.drawCircle(cx, cy, h * (0.045f + 0.012f * kotlin.math.sin(t * 0.8).toFloat()), motionPaint)
            }
            4 -> { // 冰晶星雾：多组缓慢漂移的六角晶体与闪烁星点
                for (crystal in 0 until 9) {
                    val seed = crystal * 2.399
                    val cx = (w * (0.08 + ((crystal * 37) % 83) / 100.0) +
                        kotlin.math.sin(t * 0.16 + seed) * w * 0.025).toFloat()
                    val cy = (h * (0.12 + ((crystal * 29) % 76) / 100.0) +
                        kotlin.math.cos(t * 0.13 + seed) * h * 0.035).toFloat()
                    val radius = h * (0.025f + (crystal % 3) * 0.012f)
                    motionPaint.color = 0x779DDCFF
                    motionPaint.strokeWidth = 1.2f
                    motionPaint.style = Paint.Style.STROKE
                    val points = ArrayList<Pair<Float, Float>>(6)
                    for (side in 0 until 6) {
                        val a = side * Math.PI / 3.0 + t * 0.12 * if (crystal % 2 == 0) 1 else -1
                        points.add(Pair(cx + kotlin.math.cos(a).toFloat() * radius, cy + kotlin.math.sin(a).toFloat() * radius))
                    }
                    val crystalPath = Path()
                    points.forEachIndexed { index, point ->
                        if (index == 0) crystalPath.moveTo(point.first, point.second)
                        else crystalPath.lineTo(point.first, point.second)
                    }
                    crystalPath.close()
                    canvas.drawPath(crystalPath, motionPaint)
                    for (side in 0 until 3) {
                        canvas.drawLine(cx, cy, points[side].first, points[side].second, motionPaint)
                    }
                    val twinkle = (0.5 + 0.5 * kotlin.math.sin(t * 1.2 + seed)).toFloat()
                    motionPaint.style = Paint.Style.FILL
                    motionPaint.color = (0x55 + (twinkle * 0x66).toInt() shl 24) or 0x00E7F7FF
                    canvas.drawCircle(cx, cy, radius * (0.12f + twinkle * 0.12f), motionPaint)
                }
            }
            5 -> { // 魔幻炫彩粒子：多轨旋涡、粒子尾迹与脉冲光核
                val cx = w * (0.50f + kotlin.math.sin(t * 0.17).toFloat() * 0.08f)
                val cy = h * (0.50f + kotlin.math.cos(t * 0.14).toFloat() * 0.08f)
                motionPaint.style = Paint.Style.STROKE
                for (orbit in 0 until 4) {
                    val orbitRadius = h * (0.16f + orbit * 0.095f)
                    val path = Path()
                    for (step in 0..72) {
                        val a = step / 72.0 * Math.PI * 2.0 + t * (0.12 + orbit * 0.035) * if (orbit % 2 == 0) 1 else -1
                        val wobble = (1.0 + 0.10 * kotlin.math.sin(a * 3.0 + t * 0.7 + orbit)).toFloat()
                        val x = cx + kotlin.math.cos(a).toFloat() * orbitRadius * wobble * 1.55f
                        val y = cy + kotlin.math.sin(a).toFloat() * orbitRadius * wobble * 0.68f
                        if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    motionPaint.color = when (orbit % 3) { 0 -> 0x557FFFE5; 1 -> 0x665E9BFF; else -> 0x66E47BFF }
                    motionPaint.strokeWidth = (1.3f + orbit * 0.45f) * (h / 240f).coerceAtLeast(0.7f)
                    canvas.drawPath(path, motionPaint)
                }
                motionPaint.style = Paint.Style.FILL
                for (particle in 0 until 42) {
                    val seed = particle * 2.399
                    val orbit = h * (0.10f + (particle % 7) * 0.055f)
                    val angle = seed + t * (0.22 + (particle % 5) * 0.035) * if (particle % 2 == 0) 1 else -1
                    val pulse = (0.5 + 0.5 * kotlin.math.sin(t * 1.8 + seed)).toFloat()
                    val x = cx + kotlin.math.cos(angle).toFloat() * orbit * 1.6f
                    val y = cy + kotlin.math.sin(angle).toFloat() * orbit * 0.72f
                    motionPaint.color = when (particle % 4) { 0 -> 0xCC9CFFF0.toInt(); 1 -> 0xCC8BB8FF.toInt(); 2 -> 0xCCF49BFF.toInt(); else -> 0xCCFFE49A.toInt() }
                    canvas.drawCircle(x, y, (0.8f + pulse * 2.0f) * (h / 240f).coerceAtLeast(0.65f), motionPaint)
                    motionPaint.color = when (particle % 4) { 0 -> 0x338CFFE9; 1 -> 0x335F9DFF; 2 -> 0x33F18BFF; else -> 0x33FFE49A }
                    canvas.drawCircle(x, y, (3.5f + pulse * 4.0f) * (h / 240f).coerceAtLeast(0.65f), motionPaint)
                }
                motionPaint.color = 0x66B7A0FF
                canvas.drawCircle(cx, cy, h * (0.045f + 0.012f * kotlin.math.sin(t * 1.3).toFloat()), motionPaint)
            }
            6 -> { // 星云裂隙：多层丝带星云、缓慢旋转的星尘与流星
                val cx = w * 0.53f
                val cy = h * 0.50f
                for (ribbon in 0 until 6) {
                    val path = Path()
                    for (step in 0..80) {
                        val u = step / 80.0
                        val a = u * Math.PI * 2.4 + t * (0.11 + ribbon * 0.018) + ribbon * 0.7
                        val radius = h * (0.08f + u.toFloat() * 0.30f + 0.025f * kotlin.math.sin(t * 0.35 + ribbon).toFloat())
                        val x = cx + kotlin.math.cos(a).toFloat() * radius * 1.5f
                        val y = cy + kotlin.math.sin(a).toFloat() * radius * 0.65f
                        if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    motionPaint.style = Paint.Style.STROKE
                    motionPaint.color = when (ribbon % 3) { 0 -> 0x557F8CFF; 1 -> 0x557DE7D9; else -> 0x66D17BFF }
                    motionPaint.strokeWidth = (2f + ribbon * 0.55f) * (h / 240f).coerceAtLeast(0.7f)
                    canvas.drawPath(path, motionPaint)
                }
                motionPaint.style = Paint.Style.FILL
                for (star in 0 until 54) {
                    val seed = star * 1.618
                    val x = (w * ((star * 47 % 97) / 100.0) + kotlin.math.sin(t * 0.19 + seed) * w * 0.025).toFloat()
                    val y = (h * ((star * 31 % 91) / 100.0) + kotlin.math.cos(t * 0.16 + seed) * h * 0.045).toFloat()
                    val blink = (0.35 + 0.65 * (0.5 + 0.5 * kotlin.math.sin(t * 1.4 + seed))).toFloat()
                    motionPaint.color = if (star % 3 == 0) 0xD8D7C8FF.toInt() else 0xB88CF4FF.toInt()
                    canvas.drawCircle(x, y, (0.65f + blink * 1.45f) * (h / 240f).coerceAtLeast(0.65f), motionPaint)
                    if (star % 9 == 0) {
                        motionPaint.color = 0x558EDCFF
                        canvas.drawLine(x - 5f * blink, y, x + 5f * blink, y, motionPaint)
                        canvas.drawLine(x, y - 5f * blink, x, y + 5f * blink, motionPaint)
                    }
                }
            }
        }
        motionPaint.style = Paint.Style.FILL
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

    private companion object {
        const val FRAME_DELAY_MS = 33L
        const val BACKGROUND_CYCLE_MS = 75_000L

        private fun colorsForStyle(style: Int): IntArray {
            val colors = when (style.coerceIn(0, 6)) {
                1 -> intArrayOf(0xC811B8A6.toInt(), 0xC81D8FE8.toInt(), 0xC835C6D8.toInt(), 0xC8126F9A.toInt(), 0xC811B8A6.toInt())
                2 -> intArrayOf(0xC8FF8A4C.toInt(), 0xC8F04F8B.toInt(), 0xC8B45BE8.toInt(), 0xC8FFD166.toInt(), 0xC8FF8A4C.toInt())
                3 -> intArrayOf(0xC82DE2C5.toInt(), 0xC843B66D.toInt(), 0xC8559BEF.toInt(), 0xC89B62FF.toInt(), 0xC82DE2C5.toInt())
                4 -> intArrayOf(0xC8D8F3FF.toInt(), 0xC887B8E8.toInt(), 0xC8B7A6F6.toInt(), 0xC8F4E9FF.toInt(), 0xC8D8F3FF.toInt())
                5 -> intArrayOf(0xC820164A.toInt(), 0xC82C2C78.toInt(), 0xC858287E.toInt(), 0xC81B6B9B.toInt(), 0xC820164A.toInt())
                6 -> intArrayOf(0xC81B235A.toInt(), 0xC839397C.toInt(), 0xC85C3D8C.toInt(), 0xC82C7C91.toInt(), 0xC81B235A.toInt())
                else -> intArrayOf(0xC82DE2C5.toInt(), 0xC83E8BFF.toInt(), 0xC89B62FF.toInt(), 0xC8FF62B5.toInt(), 0xC8FFB347.toInt(), 0xC82DE2C5.toInt())
            }
            return colors
        }
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
    private val dynamicBackgroundPrefs = context.getSharedPreferences("dynamic_background", Context.MODE_PRIVATE)
    private val videoBackgroundEnabled = dynamicBackgroundPrefs.getBoolean("video_enabled", false)
    private val videoBackgroundMode = dynamicBackgroundPrefs.getInt("video_mode", 2).coerceIn(0, 2)
    private val videoBackgroundFrosted = dynamicBackgroundPrefs.getBoolean("video_frosted", true)
    private val videoBackgroundUri = if (videoBackgroundEnabled && videoBackgroundMode != 1) dynamicBackgroundPrefs.getString("video_uri", null) else null
    private val backgroundEffectActive = settings.keyboardBackgroundEffect && (videoBackgroundUri == null || videoBackgroundMode != 0)
    private var transparentBackground = backgroundEffectActive || videoBackgroundEnabled || videoBackgroundUri != null
    private val videoBackgroundAlpha = dynamicBackgroundPrefs.getInt("video_alpha", 72).coerceIn(0, 100) / 100f
    private val videoBackgroundBrightness = dynamicBackgroundPrefs.getInt("video_brightness", 100).coerceIn(0, 200)
    private val videoBackgroundBlur = if (videoBackgroundFrosted) dynamicBackgroundPrefs.getInt("video_blur", 8).coerceIn(0, 30) else 0

    val candidateTextStyleConfig = app.qingjian.inputmethod.candidates.CandidateTextStyleEngine.Config.forPreset(
        palette.text, settings.fontWeight, settings.candidateTextPreset,
    )
    val bar = CandidateBarView(context, palette, settings.candidateFontSize / 20f, stripSource, settings.toolbar, barListener,
        settings.fontWeight, transparentBackground, candidateTextStyleConfig).apply {
        setBackgroundColor(if (transparentBackground) Color.TRANSPARENT else palette.board)
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
    private val keyBackgroundAlpha = dynamicBackgroundPrefs.getInt("keyboard_key_alpha", 100).coerceIn(0, 100) / 100f
    private val keyBackgroundFrosted = dynamicBackgroundPrefs.getBoolean("keyboard_key_frosted", false)
    val keyboard = KeyboardView(context, palette, settings, keyboardListener, transparentBackground = transparentBackground,
        keyBackgroundAlpha = keyBackgroundAlpha, keyBackgroundFrosted = keyBackgroundFrosted,
        keyMarqueeEnabled = dynamicBackgroundPrefs.getBoolean(DynamicBackgroundActivity.KEY_KEYBOARD_MARQUEE, false))

    /** 开着的面板；null 是键盘。 */
    var openPanel: PanelKind? = null
        private set

    private var panelView: View? = null
    private var chatPanel: AiChatPanel? = null
    private var voiceView: VoicePanel? = null

    /** AI 对话面板接管当前输入连接，让底部实体键盘可以直接编辑面板字段。 */
    val aiChatEditor: AiChatPanel? get() = chatPanel

    /** 快捷翻译等浮动编辑条使用与键盘相同的动态光效/视频背景透明策略。 */
    val editorBackgroundTransparent: Boolean get() = transparentBackground

    private val bottomInset = navigationBarHeight(context)

    // 云联想使用独立悬浮 Tips，不加入垂直布局、不挤占候选栏和键盘高度。
    private val main = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
        addView(bar)
        addView(keyboard)
        val corners = settings.bottomLeft != BottomAction.NONE || settings.bottomRight != BottomAction.NONE
        if (!floating && settings.fullscreenAdapt && corners) {
            addView(BottomActionsView(context, palette, bottomInset, settings.bottomLeft, settings.bottomRight, onBottomAction, transparentBackground = transparentBackground))
        }
    }

    private val stack = Stack(context, main, videoBackgroundUri, videoBackgroundAlpha, videoBackgroundBrightness, videoBackgroundBlur) { luminance -> bar.setVideoBackgroundLuminance(luminance) }.apply {
        clipChildren = false
        clipToPadding = false
    }


    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = if (backgroundEffectActive) {
            FlowingKeyboardBackgroundDrawable(palette, settings.keyboardBackgroundStyle)
        } else {
            android.graphics.drawable.ColorDrawable(palette.board)
        }
        clipChildren = false
        clipToPadding = false
        // 输入法面板顶部左右圆角，底部保持方正；裁切范围覆盖候选栏、键盘与底部操作条。
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val radius = 26f * context.resources.displayMetrics.density
                val path = Path().apply {
                    moveTo(radius, 0f)
                    lineTo(view.width - radius, 0f)
                    quadTo(view.width.toFloat(), 0f, view.width.toFloat(), radius)
                    lineTo(view.width.toFloat(), view.height.toFloat())
                    lineTo(0f, view.height.toFloat())
                    lineTo(0f, radius)
                    quadTo(0f, 0f, radius, 0f)
                    close()
                }
                outline.setConvexPath(path)
            }
        }
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
        if (!settings.fullscreenAdapt || !corners) setPadding(0, 0, 0, inset)
    }

    /** 只替换视频/亮度层，保留键盘、候选栏和当前打开的子面板实例。 */
    fun refreshDynamicBackground() {
        val prefs = context.getSharedPreferences("dynamic_background", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("video_enabled", false)
        val mode = prefs.getInt("video_mode", 2).coerceIn(0, 2)
        val uri = if (enabled && mode != 1) prefs.getString("video_uri", null) else null
        val alpha = prefs.getInt("video_alpha", 72).coerceIn(0, 100) / 100f
        val brightness = prefs.getInt("video_brightness", 100).coerceIn(0, 200)
        val blur = if (prefs.getBoolean("video_frosted", true)) prefs.getInt("video_blur", 8).coerceIn(0, 30) else 0
        stack.updateVideoBackground(uri, alpha, brightness, blur)
        val keyAlpha = prefs.getInt("keyboard_key_alpha", 100).coerceIn(0, 100) / 100f
        val keyFrosted = prefs.getBoolean("keyboard_key_frosted", false)
        keyboard.setKeySurface(keyAlpha, keyFrosted)
        keyboard.setMarqueeEnabled(prefs.getBoolean(DynamicBackgroundActivity.KEY_KEYBOARD_MARQUEE, false))
        // 边缘发光、立体键帽和自定义颜色在同一 Painter 中立即刷新，不依赖关闭/重开面板。
        keyboard.refreshKeyEffects()
        val settingsPrefs = Settings.preferences(context)
        val effectEnabled = settingsPrefs.getBoolean(Settings.KEY_BACKGROUND_EFFECT, settings.keyboardBackgroundEffect) &&
            (!enabled || mode != 0)
        transparentBackground = effectEnabled || enabled || uri != null
        bar.setBackgroundColor(if (transparentBackground) Color.TRANSPARENT else palette.board)
        when (val active = panelView) {
            is app.qingjian.inputmethod.panel.base.PanelView -> active.useAnimatedBackground(transparentBackground)
            is AiChatPanel -> active.setTransparentBackground(transparentBackground)
            is AiExpressionPanel -> active.setTransparentBackground(transparentBackground)
        }
        val style = (settingsPrefs.getString(Settings.KEY_BACKGROUND_STYLE, settings.keyboardBackgroundStyle.toString())?.toIntOrNull() ?: settings.keyboardBackgroundStyle).coerceIn(0, 6)
        root.background = when {
            effectEnabled -> FlowingKeyboardBackgroundDrawable(palette, style)
            enabled || uri != null -> android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            else -> android.graphics.drawable.ColorDrawable(palette.board)
        }
        root.invalidate()
    }

    /** 云联想以浮动 Tips 显示；没有候选时连输入法顶部的占位行也一并隐藏。 */
    fun showCloudSuggestions(candidates: List<Pair<Int, String>>) {
        val mascotPrefs = context.getSharedPreferences(FloatingMascotService.PREFS, Context.MODE_PRIVATE)
        val mascotEnabled = mascotPrefs.getBoolean(FloatingMascotService.KEY_ENABLED, false) &&
            android.provider.Settings.canDrawOverlays(context)
        if (mascotEnabled) {
            // 旧 Tips 只负责智能建议等其他场景；云候选交给全局小精灵气泡呈现。
            cloudSuggestions.show(emptyList())
            val intent = Intent(context, FloatingMascotService::class.java).apply {
                action = if (candidates.isEmpty()) FloatingMascotService.ACTION_HIDE_TIPS else FloatingMascotService.ACTION_SHOW_TIPS
                putStringArrayListExtra(FloatingMascotService.EXTRA_TIP_LABELS, ArrayList(candidates.map { it.second }))
                putIntegerArrayListExtra(FloatingMascotService.EXTRA_TIP_INDEXES, ArrayList(candidates.map { it.first }))
                putExtra(FloatingMascotService.ACTION_COLOR_SURFACE, palette.surface)
                putExtra(FloatingMascotService.ACTION_COLOR_TEXT, palette.text)
                putExtra(FloatingMascotService.ACTION_COLOR_CLOUD, palette.cloud)
                putExtra(FloatingMascotService.ACTION_COLOR_ACCENT, palette.accent)
                putExtra(FloatingMascotService.ACTION_COLOR_BORDER, palette.faint)
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { android.util.Log.w("QingjianMascot", "Unable to update cloud tips bubble", it) }
        } else {
            // 小精灵关闭时不再弹出独立云联想浮窗；云候选仍保留在普通候选栏中。
            cloudSuggestions.show(emptyList())
        }
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
            PanelKind.DYNAMIC_BACKGROUND -> DynamicBackgroundPanel(context, palette, actions, header)
            PanelKind.KEY_EFFECTS -> KeyEffectsPanel(context, palette, actions, header)
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
                context, palette, header, text, aiChatGenerator, actions::replaceInputText, actions::undoInputReplacement, actions::closePanel, transparentBackground,
            )
            PanelKind.AI_EXPRESSION -> AiExpressionPanel(
                context, palette, header, text, onGenerateAiExpression, actions::replaceInputText, actions::undoInputReplacement, actions::closePanel, transparentBackground,
            )
            PanelKind.CLIPBOARD, PanelKind.PHRASES -> ClipboardPanel(context, palette, actions, header, phrasesFirst = kind == PanelKind.PHRASES)
            PanelKind.PIECES -> PiecesPanel(context, palette, actions, header, text)
            // 语音面板带着回调，由 [openVoice] 盖上
            PanelKind.VOICE -> return
        }
        cover(kind, panel)
    }

    /** 生成跨 Android 版本一致的磨砂底图：先缩小再高质量放大，避免依赖仅部分设备生效的 RenderEffect。 */
    private fun blurVoiceBackdrop(source: Bitmap, strength: Int): Bitmap {
        val width = source.width
        val height = source.height
        val scale = 1.2f + (strength.coerceIn(1, 50) - 1) / 49f * 4.8f
        val reduced = Bitmap.createScaledBitmap(
            source,
            (width / scale).toInt().coerceAtLeast(1),
            (height / scale).toInt().coerceAtLeast(1),
            true,
        )
        val blurred = Bitmap.createScaledBitmap(reduced, width, height, true)
        if (reduced !== source && !reduced.isRecycled) reduced.recycle()
        if (!source.isRecycled) source.recycle()
        return blurred
    }

    /** 盖上语音面板：右边那块写 [actionLabel]，点各块接回 [actions]。 */
    fun openVoice(actionLabel: String, actions: VoicePanel.Actions): VoicePanel {
        close()
        // 先把当前键盘画成一张底图，再由 VoicePanel 做背景模糊和深色染色；
        // 不直接改键盘视图本身，语音结束后原键盘可立即恢复交互。
        val backdrop = runCatching {
            val w = stack.width
            val h = stack.height
            if (w <= 0 || h <= 0) null else Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmap ->
                stack.draw(Canvas(bitmap))
            }
        }.getOrNull()
        val voicePrefs = context.getSharedPreferences("voice", Context.MODE_PRIVATE)
        val glassStrength = voicePrefs.getInt(VoicePanel.PREF_GLASS_STRENGTH, VoicePanel.DEFAULT_GLASS_STRENGTH).coerceIn(1, 50)
        val frostedBackdrop = backdrop?.let { bitmap ->
            runCatching { blurVoiceBackdrop(bitmap, glassStrength) }.getOrElse {
                if (!bitmap.isRecycled) bitmap.recycle()
                null
            }
        }
        return VoicePanel(context, palette, actionLabel, actions, frostedBackdrop, VoicePanel.resolveAnimationStyle(context), transparentBackground, glassStrength).also { voice ->
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
        (panel as? app.qingjian.inputmethod.panel.base.PanelView)?.useAnimatedBackground(transparentBackground)
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
        // 编辑条必须放在 Stack 的 main 内容层中：视频背景先绘制，编辑条再叠在其上；
        // 之前放到 root 外层会让编辑条与视频背景脱离，始终露出默认纯色底。
        main.getChildAt(0)?.takeIf { it is FloatingEditor }?.let(main::removeView)
        editor?.let {
            applyFontWeight(it)
            main.addView(
                it,
                0,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
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

    private class Stack(
        context: Context,
        private val main: View,
        videoUri: String?,
        videoAlpha: Float,
        videoBrightness: Int,
        videoBlur: Int,
        private val onVideoLuminance: (Float) -> Unit,
    ) : FrameLayout(context) {
        private var videoBackground: DynamicVideoBackgroundView? = videoUri?.let { DynamicVideoBackgroundView(context, Uri.parse(it), videoAlpha, videoBlur).apply { onAverageLuminance = onVideoLuminance } }
        private var videoTint: View? = makeVideoTint(context, videoUri, videoBrightness)

        fun updateVideoBackground(uri: String?, alpha: Float, brightness: Int, blur: Int) {
            videoBackground?.let { removeView(it) }
            videoTint?.let(::removeView)
            videoBackground = uri?.let { DynamicVideoBackgroundView(context, Uri.parse(it), alpha, blur).apply { onAverageLuminance = onVideoLuminance } }
            videoTint = makeVideoTint(context, uri, brightness)
            videoBackground?.let { addView(it, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP)) }
            videoTint?.let { addView(it, if (videoBackground != null) 1 else 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP)) }
            invalidate()
        }

        private fun makeVideoTint(context: Context, uri: String?, brightness: Int): View? = uri?.let {
            View(context).apply {
                val amount = (kotlin.math.abs(brightness - 100) / 100f * 0.55f).coerceIn(0f, 0.55f)
                setBackgroundColor(if (brightness < 100) Color.BLACK else Color.WHITE)
                alpha = amount
            }
        }

        init {
            videoBackground?.let {
                addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP))
            }
            videoTint?.let {
                addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP))
            }
            // The keyboard content must be a child of the overlay container.
            addView(main, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            ))
        }

        private fun Int?.orZero() = this ?: 0

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            measureChild(main, widthMeasureSpec, heightMeasureSpec)
            val height = MeasureSpec.makeMeasureSpec(main.measuredHeight, MeasureSpec.EXACTLY)
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child === main) continue
                if (child === videoBackground || child === videoTint) {
                    child.measure(widthMeasureSpec, height)
                    continue
                }
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
