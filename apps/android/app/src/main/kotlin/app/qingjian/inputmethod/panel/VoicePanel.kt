package app.qingjian.inputmethod.panel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import app.qingjian.inputmethod.keyboard.Haptics
import app.qingjian.inputmethod.panel.base.Ink
import app.qingjian.inputmethod.theme.Palette
import app.qingjian.inputmethod.voice.VoiceAudioFrame
import kotlin.math.exp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

/** 语音面板：较轻的磨砂背景、随机渐变双声波与真实音量响应光晕。 */
@SuppressLint("ViewConstructor")
class VoicePanel(
    context: Context,
    private val palette: Palette,
    private val actionLabel: String,
    private val actions: Actions,
    private val blurredKeyboard: Bitmap? = null,
    private val animationStyle: Int = ANIMATION_DISABLED,
    private val animatedKeyboardBackground: Boolean = false,
    private val glassStrength: Int = DEFAULT_GLASS_STRENGTH,
) : FrameLayout(context) {

    interface Actions {
        fun onCancel()
        fun onFinish(act: Boolean, hide: Boolean)
        /** Close the panel immediately while allowing the recognizer to finish its final result. */
        fun onClose()
        fun onUndo()
        fun onRedo()
    }

    enum class Target { NONE, CANCEL, ACTION }

    private val ink = Ink(palette, resources.displayMetrics.density, resources.displayMetrics.scaledDensity)
    private val backdrop = ImageView(context)
    private val scrim = View(context)
    private val spectrumView = SpectrumView(context).apply {
        lowColor = Color.rgb(235, 120, 40)
        highColor = Color.rgb(250, 175, 90)
        glowNearColor = Color.rgb(200, 212, 123)
        glowFarColor = Color.rgb(243, 90, 20)
        additive = true
    }
    private val useSpectrumView = animationStyle == ANIMATION_SPECTRUM_GLOW
    // 所有已启用的语音动效统一使用 65 根镜像频谱；样式编号保留以兼容旧设置。
    private val useSpectrumView = animationStyle != ANIMATION_DISABLED
    private val finishButton = TextView(context)
    private val undoButton = TextView(context)
    private val spectrumBands = FloatArray(SAMPLE_COUNT)
    private var touchTarget = Target.NONE
    private var lastTargetHapticAt = 0L
    var target = Target.NONE
        private set

    var holding = false
        set(value) {
            field = value
            spectrumView.invalidate()
        }

    var listening = false
        set(value) {
            field = value
            spectrumView.invalidate()
            if (useSpectrumView) {
                if (value) spectrumView.resetCollapse() else spectrumView.collapse()
            }
        }

    var recognizing = false
        set(value) {
            field = value
            spectrumView.invalidate()
        }

    private fun glassButtonBackground(selected: Boolean): android.graphics.drawable.Drawable {
        val density = resources.displayMetrics.density
        val tint = if (selected) Color.rgb(7, 193, 96) else palette.functionalKey
        fun alpha(color: Int, value: Int) = (color and 0x00FFFFFF) or (value.coerceIn(0, 255) shl 24)
        val shadow = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(0x30000000)
        }
        val body = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(alpha(Color.WHITE, 0x52), alpha(tint, 0xC8), alpha(tint, 0xA8)),
        ).apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setStroke((1.2f * density).toInt().coerceAtLeast(1), alpha(Color.WHITE, if (selected) 0xD8 else 0xA8))
        }
        val highlight = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(alpha(Color.WHITE, if (selected) 0x58 else 0x48), 0x10FFFFFF, 0x00FFFFFF),
        ).apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
        }
        return android.graphics.drawable.LayerDrawable(arrayOf(shadow, body, highlight)).apply {
            setLayerInset(0, 0, (3f * density).toInt(), 0, 0)
            setLayerInset(1, (1f * density).toInt(), 0, (1f * density).toInt(), (2f * density).toInt())
            setLayerInset(2, (3f * density).toInt(), (2f * density).toInt(), (3f * density).toInt(), (5f * density).toInt())
        }
    }

    init {
        // 拖动选中态会向外扩展光环；父面板和按钮行均不能裁掉外扩部分。
        clipChildren = false
        clipToPadding = false
        // 语音面板是覆盖层：空白背景也必须消费触摸，绝不能把事件交回底层键盘。
        isClickable = true
        isFocusable = true
        // 背景快照、磨砂遮罩和频谱动画仅作装饰；实际可操作按钮仍由原生 TextView 暴露。
        backdrop.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        scrim.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        backdrop.scaleType = ImageView.ScaleType.FIT_XY
        blurredKeyboard?.let(backdrop::setImageBitmap)
        // 动态键盘背景仍持续绘制在底层；叠加低透明度的模糊快照，恢复语音面板的磨砂层次，
        // 避免把动态背景完全遮住。普通主题则使用完整磨砂底图。
        // Opaque frosted-glass overlay; remove the translucent wash.
        backdrop.alpha = if (animatedKeyboardBackground) 0.94f else 1f
        backdrop.visibility = if (blurredKeyboard != null) View.VISIBLE else View.GONE
        val strength = glassStrength.coerceIn(1, 50)
        if (Build.VERSION.SDK_INT >= 31 && blurredKeyboard != null) {
            val radius = (1.5f + strength * 0.56f) * resources.displayMetrics.density
            backdrop.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
        }
        addView(backdrop, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // 与键帽「磨砂效果」统一：半透明主题底层、白色雾面层、顶部柔光与细描边。
        val glassRadius = 26f * resources.displayMetrics.density
        fun themedAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)
        scrim.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = glassRadius
            val tintAlpha = (0x40 + strength * 96 / 50).coerceIn(0x40, 0xA0)
            setColor(themedAlpha(palette.board, tintAlpha))
            setStroke((1f * resources.displayMetrics.density).toInt().coerceAtLeast(1), 0x45FFFFFF)
        }
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val glassHighlight = View(context).apply {
            val topAlpha = (8 + strength * 40 / 50).coerceIn(0, 0x30)
            val middleAlpha = (4 + strength * 20 / 50).coerceIn(0, 0x18)
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf((topAlpha shl 24) or 0x00FFFFFF, (middleAlpha shl 24) or 0x00FFFFFF, 0x00FFFFFF),
            ).apply { cornerRadius = glassRadius }
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(glassHighlight, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        if (useSpectrumView) {
            addView(spectrumView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
            configureSpectrumColors()
        }
        // 统一频谱从中心柔和展开；仅首次布局播放一次。
        spectrumView.alpha = 0f
        spectrumView.scaleX = 0.92f
        spectrumView.post {
            if (spectrumView.isAttachedToWindow && useSpectrumView) {
                spectrumView.pivotX = spectrumView.width / 2f
                spectrumView.animate().cancel()
                spectrumView.animate().scaleX(1f).alpha(1f)
                    .setDuration(720L)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(1.1f))
                    .start()
            }
        }
        val density = resources.displayMetrics.density
        fun styleActionButton(view: TextView, label: String, action: () -> Unit) {
            view.text = label
            view.textSize = 14f
            view.setTextColor(palette.text)
            view.gravity = android.view.Gravity.CENTER
            view.isClickable = true
            view.isFocusable = true
            view.includeFontPadding = false
            view.setPadding(0, 0, 0, 0)
            view.background = glassButtonBackground(selected = false)
            view.setOnClickListener {
                // 语音面板与键盘按键共用 App 内「按键震动」开关和强度档位。
                Haptics.tap(context)
                action()
            }
        }
        styleActionButton(undoButton, "撤销", actions::onUndo)
        styleActionButton(finishButton, "完成", actions::onClose)
        // 两个按钮使用相同的固定尺寸与间距，成组居中，避免分别贴在屏幕两侧。
        val actionRow = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            clipChildren = false
            clipToPadding = false
            addView(undoButton, android.widget.LinearLayout.LayoutParams((64f * density).toInt(), (64f * density).toInt()))
            addView(finishButton, android.widget.LinearLayout.LayoutParams((64f * density).toInt(), (64f * density).toInt()).apply {
                // 两个圆形按钮之间留出更明显的视觉与触摸间隔。
                marginStart = (34f * density).toInt()
            })
        }
        addView(actionRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = (18f * density).toInt()
        })
    }

    private fun configureSpectrumColors() {
        val selected = context.getSharedPreferences("voice", Context.MODE_PRIVATE)
            .getInt(PREF_SPECTRUM_GRADIENT, 0).coerceIn(0, 5)
        if (selected == 0) {
            // 默认保留镜像辉光原本的金橙色，同时借用当前主题品牌色/强调色调节光晕。
            spectrumView.lowColor = Color.rgb(235, 120, 40)
            spectrumView.highColor = Color.rgb(250, 175, 90)
            spectrumView.glowNearColor = palette.brand
            spectrumView.glowFarColor = palette.accent
        } else {
            val stops = PhysicsWaveView.SPECTRUM_GRADIENT_COLORS[selected]
            spectrumView.lowColor = stops.first()
            spectrumView.highColor = stops.last()
            spectrumView.glowNearColor = stops[1]
            spectrumView.glowFarColor = stops[2]
        }
        val luminance = (0.2126 * Color.red(palette.board) +
            0.7152 * Color.green(palette.board) +
            0.0722 * Color.blue(palette.board)) / 255.0
        spectrumView.additive = luminance < 0.48
    }

    fun addAudioFrame(frame: VoiceAudioFrame) {
        if (animationStyle == ANIMATION_DISABLED) return
        updateSpectrum(frame.spectrum)
    }

    fun addSpectrum(bands: FloatArray) {
        if (animationStyle == ANIMATION_DISABLED) return
        updateSpectrum(bands)
    }

    private fun updateSpectrum(bands: FloatArray) {
        synchronized(spectrumBands) {
            for (i in 0 until SAMPLE_COUNT) spectrumBands[i] = bands.getOrElse(i) { 0f }.coerceIn(0f, 1f)
        }
        if (useSpectrumView) spectrumView.feedBands(spectrumBands)
    }

    fun track(screenX: Float, screenY: Float) {
        val previous = target
        val next = targetAt(screenX, screenY)
        if (next == previous) return
        target = next
        undoButton.isSelected = next == Target.CANCEL
        finishButton.isSelected = next == Target.ACTION
        fun targetBackground(selected: Boolean): android.graphics.drawable.Drawable {
            val glass = glassButtonBackground(selected)
            if (!selected) return glass
            val density = resources.displayMetrics.density
            val halo = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke((5f * density).toInt().coerceAtLeast(1), 0x5507C160)
            }
            return android.graphics.drawable.LayerDrawable(arrayOf(halo, glass)).apply {
                setLayerInset(0, (-4f * density).toInt(), (-4f * density).toInt(), (-4f * density).toInt(), (-4f * density).toInt())
            }
        }
        fun animateTarget(view: TextView, selected: Boolean) {
            view.animate().cancel()
            view.background = targetBackground(selected)
            view.setTextColor(if (selected) Color.WHITE else palette.text)
            view.animate()
                .alpha(if (selected) 1f else 0.9f)
                .scaleX(if (selected) 1.055f else 1f)
                .scaleY(if (selected) 1.055f else 1f)
                .translationY(if (selected) -2f * resources.displayMetrics.density else 0f)
                .setDuration(120L)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.25f))
                .start()
        }
        animateTarget(undoButton, undoButton.isSelected)
        animateTarget(finishButton, finishButton.isSelected)
        val now = android.os.SystemClock.elapsedRealtime()
        if (previous == Target.NONE && next != Target.NONE && now - lastTargetHapticAt >= 260L) {
            lastTargetHapticAt = now
            Haptics.tap(context)
        }
        spectrumView.invalidate()
    }

    private fun targetAt(screenX: Float, screenY: Float): Target {
        // 按钮的 left/top 是 actionRow 局部坐标，不能直接与面板局部坐标比较。
        // 分别换算成屏幕坐标后命中，覆盖撤销与完成两个按钮的真实区域。
        fun contains(view: View): Boolean {
            if (view.width <= 0 || view.height <= 0) return false
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            val radius = minOf(view.width, view.height) / 2f
            val dx = screenX - (location[0] + view.width / 2f)
            val dy = screenY - (location[1] + view.height / 2f)
            return dx * dx + dy * dy <= radius * radius
        }
        return when {
            contains(undoButton) -> Target.CANCEL
            contains(finishButton) -> Target.ACTION
            else -> Target.NONE
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // 点按启动的语音面板：任何位置抬手都立即结束识别并提交最终结果。
        // 统一在父面板截获完整触摸序列，避免撤销/完成子按钮与全屏点按产生双重动作。
        if (!holding) {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                Haptics.tap(context)
                actions.onClose()
            }
            return true
        }
        // 长按空格模式仍保留原有拖动到「撤销 / 完成」目标的手势。
        val handled = super.dispatchTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> track(event.rawX, event.rawY)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                if (target != Target.NONE) track(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
        }
        return handled || true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = true

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (Build.VERSION.SDK_INT >= 31) backdrop.setRenderEffect(null)
        backdrop.setImageDrawable(null)
        blurredKeyboard?.takeIf { !it.isRecycled }?.recycle()
    }

    companion object {
        const val SAMPLE_COUNT = 48
        const val PREF_STYLE = "voice_animation_style"
        const val PREF_SPECTRUM_GRADIENT = "voice_spectrum_gradient"
        const val PREF_GLASS_STRENGTH = "voice_glass_strength"
        const val DEFAULT_GLASS_STRENGTH = 40
        const val ANIMATION_DISABLED = -2
        const val ANIMATION_SPECTRUM_GLOW = 15
        const val ANIMATION_RANDOM_INDEX = 16
        val ANIMATION_STYLE_LABELS = listOf("频谱柱", "环形频谱", "弹簧柱", "重力弹跳球", "峰值保持", "弹性丝带", "呼吸光球", "流体声波", "实时波形", "波形频谱融合", "中心对称", "双层交错", "粒子矩阵", "阶梯频谱", "空间隧道", "镜像辉光频谱", "每次启动随机")

        fun resolveAnimationStyle(context: Context): Int {
            val selected = context.getSharedPreferences("voice", Context.MODE_PRIVATE)
                .getInt(PREF_STYLE, ANIMATION_DISABLED)
            return when {
                selected == ANIMATION_DISABLED -> ANIMATION_DISABLED
                selected == -1 -> kotlin.random.Random.nextInt(16)
                else -> selected.coerceIn(0, 15)
            }
        }
    }
}
