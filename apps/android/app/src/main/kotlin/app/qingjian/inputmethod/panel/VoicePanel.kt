package app.qingjian.inputmethod.panel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import app.qingjian.inputmethod.keyboard.Haptics
import app.qingjian.inputmethod.panel.base.Ink
import app.qingjian.inputmethod.theme.Palette
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 极光声学网格：将网页 Canvas 的声学网格效果移植为 Android 原生 Canvas。
 * 真实麦克风音量驱动中心涟漪；静音时保留缓慢的极光底波。
 * 语音识别与触摸操作仍由 VoiceInput/Actions 管理。
 */
@SuppressLint("ViewConstructor")
class VoicePanel(
    context: Context,
    private val palette: Palette,
    private val actionLabel: String,
    private val actions: Actions,
) : View(context) {
    interface Actions {
        fun onCancel()
        fun onFinish(act: Boolean, hide: Boolean)
    }

    enum class Target { NONE, CANCEL, ACTION }

    private val ink = Ink(palette, resources.displayMetrics.density)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val micRect = RectF()
    private val location = IntArray(2)
    private val levels = FloatArray(LEVEL_SAMPLES)
    private val hsv = floatArrayOf(190f, 1f, 1f)
    private var phase = 0f
    private var lastFrameNanos = 0L
    private var currentVolume = 0f
    private var touchTarget = Target.NONE
    private var firstFrame = true

    var holding = false
        set(value) { field = value; invalidate() }

    var listening = false
        set(value) { field = value; invalidate() }

    var recognizing = false
        set(value) { field = value; invalidate() }

    var target = Target.NONE
        private set

    init {
        setBackgroundColor(Color.TRANSPARENT)
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        isClickable = false
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
    }

    fun addLevel(level: Float) {
        System.arraycopy(levels, 1, levels, 0, LEVEL_SAMPLES - 1)
        levels[LEVEL_SAMPLES - 1] = level.coerceIn(0f, 1f)
        invalidate()
    }

    fun track(screenX: Float, screenY: Float) {
        getLocationOnScreen(location)
        val next = targetAt(screenX - location[0], screenY - location[1])
        if (next == target) return
        target = next
        if (next != Target.NONE) Haptics.tap(context)
        invalidate()
    }

    private fun targetAt(x: Float, y: Float): Target {
        if (y < height * 0.77f || y > height * 0.96f) return Target.NONE
        return when {
            x < width * 0.34f -> Target.CANCEL
            x > width * 0.66f -> Target.ACTION
            else -> Target.NONE
        }
    }

    private fun micCenterY() = height * 0.49f
    private fun micRadius() = ink.dp(30f)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val active = holding || listening || recognizing
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 1f / 60f
            else ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
        lastFrameNanos = now
        phase = (phase + dt * if (active) 1.18f else 0.62f) % 1000f

        // 语音输入的最近音量包络；使用平滑值避免音量采样抖动直接传到画面。
        var sum = 0f
        for (i in 0 until LEVEL_SAMPLES) sum += levels[i]
        val targetVolume = if (active) (sum / LEVEL_SAMPLES).coerceIn(0f, 1f) else 0f
        val smoothing = 1f - kotlin.math.exp(-(if (active) 10f else 3f) * dt)
        currentVolume += (targetVolume - currentVolume) * smoothing

        // 首帧建立纯深色底图，后续用半透明覆盖形成网页版本的平滑拖尾。
        if (firstFrame) {
            canvas.drawColor(Color.rgb(3, 5, 10))
            firstFrame = false
        } else {
            paint.style = Paint.Style.FILL
            paint.shader = null
            paint.color = Color.argb(52, 3, 5, 10)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }

        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val maxDist = hypot(cx, cy).coerceAtLeast(1f)
        val spacing = ink.dp(25f)
        val columns = ceil(w / spacing).toInt() + 2
        val rows = ceil(h / spacing).toInt() + 2
        val t = phase

        // 极光声学网格：保留网页中的双波叠加、中心衰减和青色到紫色渐变。
        for (row in 0 until rows) {
            val py = row * spacing - spacing
            for (column in 0 until columns) {
                val px = column * spacing - spacing
                val dx = px - cx
                val dy = py - cy
                val dist = hypot(dx, dy)
                val influence = (1f - dist / maxDist).coerceIn(0f, 1f)

                val auroraWave = sin(px * 0.005f + t) *
                    cos(py * 0.005f - t) * ink.dp(10f)
                val voiceWave = sin(dist * 0.02f - t * 5f) *
                    currentVolume * ink.dp(105f) * influence
                val drawY = py + auroraWave + voiceWave

                val hue = (180f + (px / w) * 100f + voiceWave * 0.2f)
                    .coerceIn(175f, 285f)
                val alpha = (0.30f + influence * currentVolume * 0.70f)
                    .coerceIn(0.18f, 1f)
                hsv[0] = hue
                hsv[1] = 1f
                hsv[2] = 1f
                val color = Color.HSVToColor((alpha * 255f).toInt(), hsv)
                val dotRadius = ink.dp(1.35f + currentVolume * influence * 2.2f)

                // 中心高音量区域增加柔光点，不使用重型模糊滤镜，减少掉帧。
                if (influence > 0.5f && currentVolume > 0.2f) {
                    paint.color = (color and 0x00FFFFFF) or
                        (((alpha * 0.22f * 255f).toInt().coerceIn(0, 255)) shl 24)
                    canvas.drawCircle(px, drawY, dotRadius * 3.0f, paint)
                }
                paint.color = color
                canvas.drawCircle(px, drawY, dotRadius, paint)
            }
        }

        // 中央仅保留清晰的麦克风轮廓，不再绘制旧版球体、环形频谱或轨道动画。
        drawMicrophone(canvas, cx, cy)

        val actionY = height * 0.91f
        ink.text(
            canvas, "撤回", 13f,
            if (target == Target.CANCEL) palette.brand else 0xFFD6E8F5.toInt(),
            width * 0.17f, actionY, bold = target == Target.CANCEL
        )
        ink.text(
            canvas, actionLabel, 13f,
            if (target == Target.ACTION) palette.brand else 0xFFD6E8F5.toInt(),
            width * 0.83f, actionY, bold = target == Target.ACTION
        )

        if (active) postInvalidateOnAnimation()
    }

    private fun drawMicrophone(canvas: Canvas, cx: Float, cy: Float) {
        val r = micRadius()
        paint.shader = null
        paint.color = Color.WHITE
        paint.alpha = 245
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ink.dp(2.8f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND

        canvas.drawRoundRect(
            cx - r * 0.30f, cy - r * 0.72f,
            cx + r * 0.30f, cy + r * 0.24f,
            r * 0.30f, r * 0.30f, paint
        )
        micRect.set(cx - r * 0.62f, cy - r * 0.20f,
            cx + r * 0.62f, cy + r * 0.70f)
        canvas.drawArc(micRect, 0f, 180f, false, paint)
        canvas.drawLine(cx, cy + r * 0.70f, cx, cy + r * 1.02f, paint)
        canvas.drawLine(cx - r * 0.36f, cy + r * 1.02f,
            cx + r * 0.36f, cy + r * 1.02f, paint)
        paint.style = Paint.Style.FILL
        paint.alpha = 255
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        val micHit = hypot(x - width / 2f, y - micCenterY()) <= micRadius() + ink.dp(18f)
        val action = targetAt(x, y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchTarget = when {
                    micHit -> Target.NONE
                    action != Target.NONE -> action
                    else -> return false
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> return touchTarget != Target.NONE
            MotionEvent.ACTION_UP -> {
                when {
                    touchTarget == Target.CANCEL || action == Target.CANCEL -> actions.onCancel()
                    touchTarget == Target.ACTION || action == Target.ACTION ->
                        actions.onFinish(act = true, hide = false)
                    touchTarget == Target.NONE && micHit ->
                        actions.onFinish(act = false, hide = false)
                    else -> return false
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
        return touchTarget != Target.NONE
    }

    private companion object {
        const val LEVEL_SAMPLES = 48
    }
}
