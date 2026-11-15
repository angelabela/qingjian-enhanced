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
import app.qingjian.inputmethod.keyboard.Haptics
import app.qingjian.inputmethod.panel.base.Ink
import app.qingjian.inputmethod.theme.Palette
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

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
    }

    enum class Target { NONE, CANCEL, ACTION }

    private val ink = Ink(palette, resources.displayMetrics.density)
    private val backdrop = ImageView(context)
    private val scrim = View(context)
    private val overlay = OverlayView(context)
    private val levels = FloatArray(SAMPLE_COUNT)
    private val smoothedLevels = FloatArray(SAMPLE_COUNT)
    private var sampleCursor = 0
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
    }

    fun addLevel(level: Float) {
        synchronized(levels) {
            levels[sampleCursor] = level.coerceIn(0f, 1f)
            sampleCursor = (sampleCursor + 1) % SAMPLE_COUNT
        }
        overlay.postInvalidateOnAnimation()
    }

    fun track(screenX: Float, screenY: Float) {
        val location = IntArray(2)
        getLocationOnScreen(location)
        val next = targetAt(screenX - location[0], screenY - location[1])
        if (next == target) return
        target = next
        if (next != Target.NONE) Haptics.tap(context)
        overlay.invalidate()
    }

    private fun targetAt(x: Float, y: Float): Target {
        if (y < height * 0.77f || y > height * 0.96f) return Target.NONE
        return when {
            x < width * 0.46f -> Target.CANCEL
            x > width * 0.54f -> Target.ACTION
            else -> Target.NONE
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        val density = resources.displayMetrics.density
        // 保留中央轻触结束识别的触控区域，但不再绘制麦克风图标。
        val centerHit = kotlin.math.hypot(x - width / 2f, y - height * 0.475f) <= 52f * density
        val action = targetAt(x, y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchTarget = when {
                    centerHit -> Target.NONE
                    action != Target.NONE -> action
                    else -> return super.dispatchTouchEvent(event)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> return touchTarget != Target.NONE
            MotionEvent.ACTION_UP -> {
                when {
                    touchTarget == Target.CANCEL || action == Target.CANCEL -> actions.onCancel()
                    touchTarget == Target.ACTION || action == Target.ACTION -> actions.onFinish(act = true, hide = false)
                    touchTarget == Target.NONE && centerHit -> actions.onFinish(act = false, hide = false)
                    else -> {
                        touchTarget = Target.NONE
                        return super.dispatchTouchEvent(event)
                    }
                }
                touchTarget = Target.NONE
                Haptics.tap(context)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                touchTarget = Target.NONE
                return true
            }
        }
        return true
    }

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
        private val samples = FloatArray(SAMPLE_COUNT)
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
            synchronized(levels) {
                for (i in samples.indices) samples[i] = levels[(sampleCursor + i) % SAMPLE_COUNT]
            }

            val frameNanos = System.nanoTime()
            val deltaSeconds = if (lastFrameNanos == 0L) 1.0 / 60.0
                else ((frameNanos - lastFrameNanos) / 1_000_000_000.0).coerceIn(0.0, 0.05)
            lastFrameNanos = frameNanos
            // 音量上升、回落均使用指数平滑，避免跳帧；静音时平滑回到直线。
            val blend = (1.0 - exp(-deltaSeconds / 0.085)).toFloat()
            for (i in smoothedLevels.indices) {
                smoothedLevels[i] += (samples[i] - smoothedLevels[i]) * blend
            }

            val now = frameNanos / 1_000_000_000.0
            val halfWidth = width * 0.43f
            val laneGap = 18f * d
            val maxAmplitude = 48f * d
            val peakLevel = smoothedLevels.maxOrNull()?.coerceIn(0f, 1f) ?: 0f

            // 48 根频谱柱围绕中线成对展开：每根柱都由实时录音音量包络驱动，
            // 通过频段权重与快速/慢速平滑形成低频厚、中高频细的均衡器观感。
            val barCount = SAMPLE_COUNT
            val gap = 3.2f * d
            val barWidth = ((halfWidth * 2f) - gap * (barCount - 1)) / barCount
            val hueShift = (now * 32.0).toFloat() % 360f
            for (i in 0 until barCount) {
                val x = cx - halfWidth + i * (barWidth + gap)
                val source = smoothedLevels[i].coerceIn(0f, 1f)
                val centerWeight = 0.34f + 0.66f * (1f - abs(i - (barCount - 1) / 2f) / (barCount / 2f))
                val ripple = (0.82f + 0.18f * sin(now * 5.0 + i * 0.71)).toFloat()
                val heightBand = (3.0f * d + source * maxAmplitude * centerWeight * ripple).coerceAtLeast(2f * d)
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

            val actionY = height * 0.91f
            ink.text(canvas, "撤回", 14f, if (target == Target.CANCEL) palette.brand else palette.text,
                width * 0.32f, actionY, bold = target == Target.CANCEL)
            ink.text(canvas, actionLabel, 14f, if (target == Target.ACTION) palette.brand else palette.text,
                width * 0.68f, actionY, bold = target == Target.ACTION)

            // 顶部双线的颜色持续平滑变化；面板离屏后由 View 生命周期自然停止绘制。
            if (isAttachedToWindow && visibility == View.VISIBLE) postInvalidateOnAnimation()
        }
    }

    private companion object {
        const val SAMPLE_COUNT = 48
    }
}
