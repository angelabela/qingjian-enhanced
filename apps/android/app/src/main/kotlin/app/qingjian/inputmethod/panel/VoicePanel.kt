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

/** 语音面板：较轻的磨砂背景、随机渐变双声波与真实音量响应光晕。 */
@SuppressLint("ViewConstructor")
class VoicePanel(
    context: Context,
    private val palette: Palette,
    private val actionLabel: String,
    private val actions: Actions,
    private val blurredKeyboard: Bitmap? = null,
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
    private val overlay = OverlayView(context)
    private val finishButton = TextView(context)
    private val undoButton = TextView(context)
    private val spectrumBands = FloatArray(SAMPLE_COUNT)
    private val smoothedBands = FloatArray(SAMPLE_COUNT)
    private var lastFrameNanos = 0L
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
        scrim.background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                0x55F5FBFF,
                (palette.board and 0x00FFFFFF) or 0x55405070,
                (palette.surface and 0x00FFFFFF) or 0x66405070,
                0x33202A48,
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
                setColor((palette.surface and 0x00FFFFFF) or 0xE0202938.toInt())
                cornerRadius = 22f * density
                setStroke((1f * density).toInt().coerceAtLeast(1), 0x88FFFFFF.toInt())
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
        overlay.postInvalidateOnAnimation()
    }

    fun track(screenX: Float, screenY: Float) {
        val location = IntArray(2)
        getLocationOnScreen(location)
        val next = targetAt(screenX, screenY)
        if (next == target) return
        target = next
        undoButton.isSelected = next == Target.CANCEL
        finishButton.isSelected = next == Target.ACTION
        undoButton.alpha = if (undoButton.isSelected) 1f else 0.86f
        finishButton.alpha = if (finishButton.isSelected) 1f else 0.86f
        undoButton.scaleX = if (undoButton.isSelected) 1.06f else 1f
        undoButton.scaleY = if (undoButton.isSelected) 1.06f else 1f
        finishButton.scaleX = if (finishButton.isSelected) 1.06f else 1f
        finishButton.scaleY = if (finishButton.isSelected) 1.06f else 1f
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

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = super.dispatchTouchEvent(event)

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (Build.VERSION.SDK_INT >= 31) backdrop.setRenderEffect(null)
        backdrop.setImageDrawable(null)
        blurredKeyboard?.takeIf { !it.isRecycled }?.recycle()
    }

    private inner class OverlayView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private val d = resources.displayMetrics.density
        // 每次新建语音面板都重新抽取色相；上下两条线保持协调但不再固定为同一套颜色。
        private val hueSeed = kotlin.random.Random.Default.nextFloat() * 360f
        private val lowerHueSeed = (hueSeed + 62f + kotlin.random.Random.Default.nextFloat() * 78f) % 360f
        init {
            // 仅对两条声波的光晕层做软件模糊，核心线条仍保持清晰。
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }

        /** 固定线条位置，只让渐变色相本身平滑循环变化；不移动 Shader 或线条。 */
        private fun gradientColors(baseHue: Float, hueShift: Float): IntArray {
            val stops = listOf(0f, 34f, 76f)
            val colors = stops.map { offset ->
                Color.HSVToColor(floatArrayOf((baseHue + hueShift + offset) % 360f, 0.82f, 1f))
            }
            fun alpha(color: Int, value: Int) = (color and 0x00FFFFFF) or (value shl 24)
            return intArrayOf(alpha(colors[0], 0), alpha(colors[1], 238), alpha(colors[2], 220), alpha(colors[0], 0))
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width <= 0 || height <= 0) return
            val cx = width * 0.5f
            val cy = height * 0.475f
            val frameBands = FloatArray(SAMPLE_COUNT)
            synchronized(spectrumBands) {
                spectrumBands.copyInto(frameBands)
            }

            val frameNanos = System.nanoTime()
            val deltaSeconds = if (lastFrameNanos == 0L) 1.0 / 60.0
                else ((frameNanos - lastFrameNanos) / 1_000_000_000.0).coerceIn(0.0, 0.05)
            lastFrameNanos = frameNanos
            // 频谱采用快起慢落的包络平滑，保留瞬态，又避免柱高抖动。
            for (i in smoothedBands.indices) {
                val attack = (1.0 - exp(-deltaSeconds / 0.045)).toFloat()
                val release = (1.0 - exp(-deltaSeconds / 0.19)).toFloat()
                val blend = if (frameBands[i] > smoothedBands[i]) attack else release
                smoothedBands[i] += (frameBands[i] - smoothedBands[i]) * blend
            }

            val now = frameNanos / 1_000_000_000.0
            val halfWidth = width * 0.43f
            val maxAmplitude = 54f * d

            // 48 根柱逐一对应真实 FFT 对数频带；不再叠加假音量包络或人为波纹。

            val barCount = SAMPLE_COUNT
            val gap = 3.2f * d
            val barWidth = ((halfWidth * 2f) - gap * (barCount - 1)) / barCount
            val hueShift = (now * 32.0).toFloat() % 360f
            for (i in 0 until barCount) {
                val x = cx - halfWidth + i * (barWidth + gap)
                val bandEnergy = smoothedBands[i].coerceIn(0f, 1f)
                val heightBand = (2.2f * d + bandEnergy * maxAmplitude).coerceAtLeast(2f * d)
                val color = Color.HSVToColor(floatArrayOf((hueSeed + hueShift + i * 2.8f) % 360f, 0.78f, 1f))
                paint.shader = null
                paint.color = color
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = barWidth.coerceAtLeast(1.2f * d)
                paint.alpha = 75
                paint.maskFilter = BlurMaskFilter(5f * d, BlurMaskFilter.Blur.NORMAL)
                canvas.drawLine(x + barWidth / 2f, cy - heightBand, x + barWidth / 2f, cy + heightBand, paint)
                paint.maskFilter = null
                paint.alpha = 245
                paint.strokeWidth = (barWidth * 0.72f).coerceAtLeast(1.1f * d)
                canvas.drawLine(x + barWidth / 2f, cy - heightBand, x + barWidth / 2f, cy + heightBand, paint)
            }
            paint.alpha = 255

            // 顶部双线的颜色持续平滑变化；面板离屏后由 View 生命周期自然停止绘制。
            if (isAttachedToWindow && visibility == View.VISIBLE) postInvalidateOnAnimation()
        }
    }

    private companion object {
        const val SAMPLE_COUNT = 48
    }
}
