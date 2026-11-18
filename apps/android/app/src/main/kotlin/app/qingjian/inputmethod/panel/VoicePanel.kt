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
    private val animationStyle: Int = 0,
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

    private val ink = Ink(palette, resources.displayMetrics.density)
    private val backdrop = ImageView(context)
    private val scrim = View(context)
    private val overlay = PhysicsWaveView(context).apply {
        style = when (animationStyle) {
            0 -> PhysicsWaveView.Style.SPECTRUM_BARS
            1 -> PhysicsWaveView.Style.RING_SPECTRUM
            2 -> PhysicsWaveView.Style.SPRING_BARS
            3 -> PhysicsWaveView.Style.GRAVITY_BALLS
            4 -> PhysicsWaveView.Style.PEAK_HOLD
            5 -> PhysicsWaveView.Style.ELASTIC_STRING
            6 -> PhysicsWaveView.Style.GLOW_ORBS
            else -> PhysicsWaveView.Style.SPECTRUM_BARS
        }
        color = palette.brand
    }
    private val finishButton = TextView(context)
    private val undoButton = TextView(context)
    private val spectrumBands = FloatArray(SAMPLE_COUNT)
    private var touchTarget = Target.NONE
    var target = Target.NONE
        private set

    var holding = false
        set(value) {
            field = value
            overlay.invalidate()
        }

    var listening = false
        set(value) {
            field = value
            overlay.listening = value
            overlay.invalidate()
        }

    var recognizing = false
        set(value) {
            field = value
            overlay.invalidate()
        }

    init {
        clipChildren = true
        clipToPadding = true
        // 语音面板是覆盖层：空白背景也必须消费触摸，绝不能把事件交回底层键盘。
        isClickable = true
        isFocusable = true
        backdrop.scaleType = ImageView.ScaleType.FIT_XY
        blurredKeyboard?.let(backdrop::setImageBitmap)
        // 降低模糊半径，保留更多键盘细节；背景遮罩稍加深，减弱过透的感觉。
        if (Build.VERSION.SDK_INT >= 31 && blurredKeyboard != null) {
            val radius = 12f * resources.displayMetrics.density
            backdrop.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
        }
        addView(backdrop, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // 语音面板自身采用液态玻璃质感：半透明冷色渐层、柔和高光和细亮边。
        val glassRadius = 26f * resources.displayMetrics.density
        fun themedAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)
        scrim.background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                themedAlpha(palette.surface, 0x55),
                themedAlpha(palette.board, 0x55),
                themedAlpha(palette.surface, 0x66),
                themedAlpha(palette.board, 0x33),
            ),
        ).apply {
            cornerRadius = glassRadius
            setStroke((1f * resources.displayMetrics.density).toInt().coerceAtLeast(1), 0x77FFFFFF)
        }
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
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
            view.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(palette.functionalKey)
                cornerRadius = 22f * density
            }
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
            addView(undoButton, android.widget.LinearLayout.LayoutParams((104f * density).toInt(), (44f * density).toInt()))
            addView(finishButton, android.widget.LinearLayout.LayoutParams((104f * density).toInt(), (44f * density).toInt()).apply {
                marginStart = (14f * density).toInt()
            })
        }
        addView(actionRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = (18f * density).toInt()
        })
    }

    fun addSpectrum(bands: FloatArray) {
        synchronized(spectrumBands) {
            for (i in 0 until SAMPLE_COUNT) spectrumBands[i] = bands.getOrElse(i) { 0f }.coerceIn(0f, 1f)
        }
        // 五种物理动效使用18个均匀频带：适度精简单元，避免中部窄区域显得拥挤。
        // 按48段FFT的实际区间求均值，低、中、高频仍保持连续响应。
        val physicsBands = FloatArray(18) { band ->
            val start = band * SAMPLE_COUNT / 18
            val end = (band + 1) * SAMPLE_COUNT / 18
            var sum = 0f
            for (i in start until end) sum += spectrumBands[i]
            (sum / (end - start).coerceAtLeast(1)).coerceIn(0f, 1f)
        }
        overlay.updateSpectrum(spectrumBands)
        overlay.update(physicsBands)
    }

    fun track(screenX: Float, screenY: Float) {
        val next = targetAt(screenX, screenY)
        if (next == target) return
        target = next
        undoButton.isSelected = next == Target.CANCEL
        finishButton.isSelected = next == Target.ACTION
        fun targetBackground(selected: Boolean): android.graphics.drawable.Drawable {
            val density = resources.displayMetrics.density
            val green = Color.rgb(7, 193, 96) // 微信绿
            val layers = arrayOf(
                android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 24f * density
                    setColor(Color.TRANSPARENT)
                    setStroke((7f * density).toInt().coerceAtLeast(1), (green and 0x00FFFFFF) or (0x20 shl 24))
                },
                android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 23f * density
                    setColor(Color.TRANSPARENT)
                    setStroke((4f * density).toInt().coerceAtLeast(1), (green and 0x00FFFFFF) or (0x55 shl 24))
                },
                android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 22f * density
                    setColor(if (selected) green else palette.functionalKey)
                    setStroke((if (selected) 1.8f else 1f).times(density).toInt().coerceAtLeast(1),
                        if (selected) 0xFF8AFFB8.toInt() else 0x44FFFFFF)
                },
            )
            return android.graphics.drawable.LayerDrawable(layers).apply {
                setLayerInset(0, (-5f * density).toInt(), (-5f * density).toInt(), (-5f * density).toInt(), (-5f * density).toInt())
                setLayerInset(1, (-2f * density).toInt(), (-2f * density).toInt(), (-2f * density).toInt(), (-2f * density).toInt())
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
        if (next != Target.NONE) Haptics.tap(context)
        overlay.invalidate()
    }

    private fun targetAt(screenX: Float, screenY: Float): Target {
        // 按钮的 left/top 是 actionRow 局部坐标，不能直接与面板局部坐标比较。
        // 分别换算成屏幕坐标后命中，覆盖撤销与完成两个按钮的真实区域。
        fun contains(view: View): Boolean {
            if (view.width <= 0 || view.height <= 0) return false
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            return screenX >= location[0] && screenX < location[0] + view.width &&
                screenY >= location[1] && screenY < location[1] + view.height
        }
        return when {
            contains(undoButton) -> Target.CANCEL
            contains(finishButton) -> Target.ACTION
            else -> Target.NONE
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // 先让按钮等子控件处理；若没有子控件命中，父面板仍消费事件，阻断键盘穿透。
        return super.dispatchTouchEvent(event) || true
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
        val ANIMATION_STYLE_LABELS = listOf("频谱柱", "环形频谱", "弹簧柱", "重力弹跳球", "峰值保持", "弹性丝带", "呼吸光球", "每次启动随机")

        fun resolveAnimationStyle(context: Context): Int {
            val selected = context.getSharedPreferences("voice", Context.MODE_PRIVATE)
                .getInt(PREF_STYLE, 0)
            return if (selected == -1) kotlin.random.Random.nextInt(7) else selected.coerceIn(0, 6)
        }
    }
}
