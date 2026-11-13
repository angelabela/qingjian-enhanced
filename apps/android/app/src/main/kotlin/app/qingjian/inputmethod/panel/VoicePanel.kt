package app.qingjian.inputmethod.panel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import app.qingjian.inputmethod.keyboard.Haptics
import app.qingjian.inputmethod.panel.base.Ink
import app.qingjian.inputmethod.theme.Palette
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Aurora Core：深空等离子共振语音视觉。
 * 使用现有语音音量采样驱动核心脉动、能量环和粒子，不创建第二路麦克风。
 * 语音识别、权限与操作回调仍由现有 VoiceInput/Actions 管理。
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
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val micRect = RectF()
    private val ringRect = RectF()
    private val path = Path()
    private val levels = FloatArray(LEVEL_SAMPLES)
    private val location = IntArray(2)
    private var phase = 0f
    private var lastFrameNanos = 0L
    private var currentVolume = 0f
    private var peakVolume = 0f
    private var touchTarget = Target.NONE
    private var target = Target.NONE

    var holding = false
        set(value) { field = value; invalidate() }

    var listening = false
        set(value) { field = value; invalidate() }

    var recognizing = false
        set(value) { field = value; invalidate() }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        isClickable = false
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
        phase = (phase + dt * if (active) 1.0f else 0.38f) % 1000f

        var sum = 0f
        var peak = 0f
        for (level in levels) {
            sum += level
            peak = max(peak, level)
        }
        val average = sum / LEVEL_SAMPLES
        val targetVolume = if (active) max(average * 2.0f, peak * 0.68f).coerceIn(0f, 1f) else 0f
        val smoothing = 1f - kotlin.math.exp(-(if (active) 9f else 2.6f) * dt)
        currentVolume += (targetVolume - currentVolume) * smoothing
        peakVolume += ((if (active) peak else 0f) - peakVolume) *
            (1f - kotlin.math.exp(-5.5f * dt))

        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w * 0.5f
        val cy = h * 0.49f
        val minSide = min(w, h)
        val coreRadius = minSide * 0.205f
        val energy = currentVolume.coerceIn(0f, 1f)
        val breath = 1f + sin(phase * 1.35f) * 0.018f + energy * 0.10f
        val coreR = coreRadius * breath

        drawDeepSpace(canvas, cx, cy, minSide)
        drawOuterNebula(canvas, cx, cy, coreR, minSide, energy)
        drawOrbitRings(canvas, cx, cy, coreR, energy)
        drawEnergyFilaments(canvas, cx, cy, coreR, energy)
        drawParticles(canvas, cx, cy, coreR, energy)
        drawCore(canvas, cx, cy, coreR, energy)
        drawMicrophone(canvas, cx, cy, coreR, energy)

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

    private fun drawDeepSpace(canvas: Canvas, cx: Float, cy: Float, minSide: Float) {
        canvas.drawColor(Color.rgb(3, 5, 12))
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            intArrayOf(0xFF080B1C.toInt(), 0xFF080719.toInt(), 0xFF03050C.toInt()),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = RadialGradient(
            cx, cy, minSide * 0.76f,
            intArrayOf(0x553D2A83, 0x29202B72, 0x0003050C),
            floatArrayOf(0f, 0.48f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, minSide * 0.76f, paint)
        paint.shader = null

        // 稀疏且稳定的星尘，避免随机闪烁。
        for (i in 0 until 34) {
            val x = ((i * 73 + 19) % 997) / 997f * width
            val y = ((i * 137 + 41) % 991) / 991f * height
            val twinkle = 0.35f + 0.65f * (0.5f + 0.5f * sin(phase * 0.55f + i * 1.7f))
            paint.color = Color.argb((twinkle * 105).toInt(), 150, 205, 255)
            canvas.drawCircle(x, y, ink.dp(if (i % 7 == 0) 1.35f else 0.7f), paint)
        }
    }

    private fun drawOuterNebula(
        canvas: Canvas, cx: Float, cy: Float, radius: Float, minSide: Float, energy: Float
    ) {
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx, cy, radius * (2.65f + energy * 0.42f),
            intArrayOf(
                0x6657DFFF, 0x553F69E8, 0x443D20A5, 0x162C1D76, 0x0003050C
            ),
            floatArrayOf(0f, 0.28f, 0.52f, 0.76f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius * (2.65f + energy * 0.42f), paint)
        paint.shader = null
    }

    private fun drawOrbitRings(canvas: Canvas, cx: Float, cy: Float, radius: Float, energy: Float) {
        val base = radius * (1.55f + energy * 0.08f)
        val rings = 3
        for (i in 0 until rings) {
            val scale = 1f + i * 0.34f
            val rotation = phase * (if (i % 2 == 0) 13f else -9f) + i * 57f
            canvas.save()
            canvas.rotate(rotation, cx, cy)
            ringRect.set(cx - base * scale, cy - base * scale * 0.48f,
                cx + base * scale, cy + base * scale * 0.48f)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = ink.dp(if (i == 0) 1.7f else 1.0f)
            paint.color = when (i) {
                0 -> Color.argb((95 + energy * 95).toInt(), 93, 231, 255)
                1 -> Color.argb((78 + energy * 65).toInt(), 153, 111, 255)
                else -> Color.argb(48, 104, 174, 255)
            }
            canvas.drawOval(ringRect, paint)
            // 一段高亮弧线制造全息轨道的方向感。
            paint.color = Color.argb((105 + energy * 110).toInt(), 173, 240, 255)
            paint.strokeWidth = ink.dp(2f)
            canvas.drawArc(ringRect, 205f + i * 38f, 34f + energy * 30f, false, paint)
            canvas.restore()
        }
        paint.style = Paint.Style.FILL
    }

    private fun drawEnergyFilaments(canvas: Canvas, cx: Float, cy: Float, radius: Float, energy: Float) {
        for (i in 0 until 5) {
            val angle = phase * (0.22f + i * 0.035f) + i * 1.256f
            val spread = radius * (1.08f + i * 0.09f)
            val sx = cx + cos(angle) * spread
            val sy = cy + sin(angle) * spread * 0.56f
            val ex = cx + cos(angle + 1.55f) * spread * 0.94f
            val ey = cy + sin(angle + 1.55f) * spread * 0.56f
            path.reset()
            path.moveTo(sx, sy)
            path.cubicTo(
                cx + cos(angle + 0.8f) * radius * (0.7f + energy * 0.18f),
                cy + sin(angle + 0.8f) * radius * 0.8f,
                cx + cos(angle + 1.9f) * radius * 1.1f,
                cy + sin(angle + 1.9f) * radius * 0.52f,
                ex, ey
            )
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = ink.dp(if (i == 0) 2.0f else 1.1f)
            paint.color = if (i % 2 == 0)
                Color.argb((38 + energy * 105).toInt(), 76, 224, 255)
            else Color.argb((42 + energy * 100).toInt(), 174, 105, 255)
            canvas.drawPath(path, paint)
        }
        paint.style = Paint.Style.FILL
    }

    private fun drawParticles(canvas: Canvas, cx: Float, cy: Float, radius: Float, energy: Float) {
        for (i in 0 until 42) {
            val angle = i * (Math.PI.toFloat() * 2f / 42f) +
                phase * (if (i % 2 == 0) 0.32f else -0.24f)
            val orbit = radius * (1.25f + (i % 5) * 0.19f + energy * (i % 3) * 0.10f)
            val x = cx + cos(angle) * orbit
            val y = cy + sin(angle) * orbit * 0.58f
            val pulse = 0.55f + 0.45f * sin(phase * 1.7f + i * 2.1f)
            val alpha = (45 + pulse * 95 + energy * 100).toInt().coerceIn(0, 235)
            paint.color = if (i % 3 == 0) Color.argb(alpha, 194, 126, 255)
                else Color.argb(alpha, 96, 226, 255)
            val dot = ink.dp((0.8f + (i % 4) * 0.32f) * (1f + energy * 0.65f))
            canvas.drawCircle(x, y, dot, paint)
        }
    }

    private fun drawCore(canvas: Canvas, cx: Float, cy: Float, radius: Float, energy: Float) {
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx - radius * 0.24f, cy - radius * 0.30f, radius * 1.28f,
            intArrayOf(
                0xFFF0FFFF.toInt(), 0xFF75EFFF.toInt(), 0xFF3B8EEB.toInt(),
                0xFF7139C5.toInt(), 0xFF17112F.toInt(), 0x0017112F
            ),
            floatArrayOf(0f, 0.12f, 0.34f, 0.62f, 0.88f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius * (1.03f + energy * 0.08f), paint)
        paint.shader = null

        // 玻璃质感高光与内部流动的弧形纹理。
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ink.dp(1.3f)
        paint.color = Color.argb((100 + energy * 90).toInt(), 203, 250, 255)
        ringRect.set(cx - radius * 0.78f, cy - radius * 0.78f,
            cx + radius * 0.78f, cy + radius * 0.78f)
        canvas.drawArc(ringRect, 205f + phase * 18f, 112f, false, paint)
        paint.color = Color.argb((65 + energy * 100).toInt(), 190, 128, 255)
        canvas.drawArc(ringRect, 28f - phase * 12f, 88f, false, paint)
        paint.style = Paint.Style.FILL

        // 核心中心的亮点，不使用实时模糊滤镜，控制 GPU 开销。
        paint.shader = RadialGradient(
            cx - radius * 0.12f, cy - radius * 0.18f, radius * 0.62f,
            intArrayOf(0x99FFFFFF.toInt(), 0x3389F5FF, 0x001A1A55),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius * 0.62f, paint)
        paint.shader = null
    }

    private fun drawMicrophone(canvas: Canvas, cx: Float, cy: Float, radius: Float, energy: Float) {
        val r = micRadius() * 0.78f
        paint.shader = null
        paint.color = Color.argb((225 + energy * 30).toInt().coerceIn(0, 255), 255, 255, 255)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ink.dp(2.5f)
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
