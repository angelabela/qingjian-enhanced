package app.qingjian.inputmethod.panel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
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

/** 语音面板：较轻的磨砂背景、双向流动粒子与真实音量响应波形。 */
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
        scrim.setBackgroundColor(0x700A1024.toInt())
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
            x < width * 0.34f -> Target.CANCEL
            x > width * 0.66f -> Target.ACTION
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
        private val particleColors = intArrayOf(
            0xFF57E8FF.toInt(), 0xFF8C78FF.toInt(), 0xFFFF70C8.toInt(),
            0xFFB18CFF.toInt(), 0xFF67CFFF.toInt(), 0xFFE6A5FF.toInt(),
        )

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
            val blend = (1.0 - exp(-deltaSeconds / 0.11)).toFloat()
            for (i in smoothedLevels.indices) {
                smoothedLevels[i] += (samples[i] - smoothedLevels[i]) * blend
            }

            val now = frameNanos / 1_000_000_000.0
            val halfWidth = width * 0.43f
            val laneGap = 15f * d
            val maxAmplitude = 42f * d
            val active = smoothedLevels.any { it > 0.025f }

            for (lane in 0..1) {
                val upper = lane == 0
                val baseY = cy + if (upper) -laneGap else laneGap
                path.reset()
                var previousX = 0f
                var previousY = baseY
                for (i in 0 until SAMPLE_COUNT) {
                    val x = cx - halfWidth + (2f * halfWidth * i / (SAMPLE_COUNT - 1))
                    val envelope = smoothedLevels[i].coerceAtLeast(0f)
                    val phase = now * 2.15 + i * 0.39 + if (upper) 0.0 else 1.2
                    // 静音严格保持水平；有声音时，上轨向上、下轨向下展开。
                    val displacement = if (envelope <= 0.012f) 0f
                        else abs(sin(phase)).toFloat() * envelope * maxAmplitude
                    val y = baseY + if (upper) -displacement else displacement
                    if (i == 0) path.moveTo(x, y) else {
                        val midX = (previousX + x) * 0.5f
                        val midY = (previousY + y) * 0.5f
                        path.quadTo(previousX, previousY, midX, midY)
                    }
                    previousX = x
                    previousY = y
                }
                path.lineTo(previousX, previousY)
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
                paint.strokeWidth = if (upper) 2.5f * d else 1.8f * d
                paint.shader = LinearGradient(
                    cx - halfWidth, baseY, cx + halfWidth, baseY,
                    if (upper) intArrayOf(0x0057E8FF, 0xF057E8FF.toInt(), 0xE98D78FF.toInt(), 0x0057E8FF)
                    else intArrayOf(0x008C78FF, 0xE58C78FF.toInt(), 0xE957CFFF.toInt(), 0x008C78FF),
                    null, Shader.TileMode.CLAMP,
                )
                canvas.drawPath(path, paint)
                paint.shader = null

                // 彩色光点沿两条线流动，方向相反；点位同样读取真实音量包络。
                for (dot in 0 until DOT_COUNT) {
                    val progress = ((dot.toFloat() / DOT_COUNT +
                        (now * DOT_SPEED * if (upper) 1.0 else -1.0)) % 1.0 + 1.0) % 1.0
                    val x = cx - halfWidth + progress.toFloat() * 2f * halfWidth
                    val sampleIndex = (progress * (SAMPLE_COUNT - 1)).toInt().coerceIn(0, SAMPLE_COUNT - 1)
                    val envelope = smoothedLevels[sampleIndex]
                    val phase = now * 2.15 + sampleIndex * 0.39 + if (upper) 0.0 else 1.2
                    val displacement = if (envelope <= 0.012f) 0f
                        else abs(sin(phase)).toFloat() * envelope * maxAmplitude
                    val y = baseY + if (upper) -displacement else displacement
                    val radius = (1.5f + (dot % 3) * 0.55f) * d
                    paint.style = Paint.Style.FILL
                    paint.color = particleColors[(dot + if (upper) 0 else 3) % particleColors.size]
                    paint.alpha = 205
                    canvas.drawCircle(x, y, radius, paint)
                }
            }
            paint.alpha = 255

            val actionY = height * 0.91f
            ink.text(canvas, "撤回", 13f, if (target == Target.CANCEL) palette.brand else 0xFFE0E9F5.toInt(),
                width * 0.17f, actionY, bold = target == Target.CANCEL)
            ink.text(canvas, actionLabel, 13f, if (target == Target.ACTION) palette.brand else 0xFFE0E9F5.toInt(),
                width * 0.83f, actionY, bold = target == Target.ACTION)

            // 粒子持续流动；录音结束后不再无意义地持续刷新。
            if (listening || recognizing) postInvalidateOnAnimation()
        }
    }

    private companion object {
        const val SAMPLE_COUNT = 48
        const val DOT_COUNT = 13
        const val DOT_SPEED = 0.075
    }
}
