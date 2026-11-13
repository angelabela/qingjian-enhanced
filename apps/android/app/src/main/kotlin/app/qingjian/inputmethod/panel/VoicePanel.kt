package app.qingjian.inputmethod.panel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import app.qingjian.inputmethod.keyboard.Haptics
import app.qingjian.inputmethod.panel.base.Ink
import app.qingjian.inputmethod.theme.Palette
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Holographic Radial Equalizer Sphere.
 * The existing speech-level samples drive the radial spectrum; speech recognition,
 * permissions and callbacks remain owned by VoiceInput/Actions.
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
    private val rect = RectF()
    private val levels = FloatArray(LEVEL_SAMPLES)
    private val location = IntArray(2)
    private var phase = 0f
    private var lastFrameNanos = 0L
    private var currentVolume = 0f
    private var peakVolume = 0f
    private var touchTarget = Target.NONE
    var target = Target.NONE
        private set

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

    private fun micRadius() = ink.dp(30f)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val active = holding || listening || recognizing
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 1f / 60f
            else ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
        lastFrameNanos = now
        phase = (phase + dt * if (active) 0.72f else 0.24f) % 1000f

        var sum = 0f
        var peak = 0f
        for (sample in levels) {
            sum += sample
            peak = max(peak, sample)
        }
        val average = sum / LEVEL_SAMPLES
        val targetVolume = if (active) max(average * 2.15f, peak * 0.78f).coerceIn(0f, 1f) else 0f
        currentVolume += (targetVolume - currentVolume) *
            (1f - kotlin.math.exp(-(if (active) 8.5f else 2.1f) * dt))
        peakVolume += ((if (active) peak else 0f) - peakVolume) *
            (1f - kotlin.math.exp(-4.8f * dt))

        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w * 0.5f
        val cy = h * 0.475f
        val minSide = min(w, h)
        val sphereRadius = minSide * 0.285f
        val energy = currentVolume.coerceIn(0f, 1f)
        val breath = 1f + sin(phase * 1.18f) * 0.012f + energy * 0.045f

        drawBackground(canvas, cx, cy, minSide)
        drawSphereAura(canvas, cx, cy, sphereRadius * breath, energy)
        drawRearSpectrum(canvas, cx, cy, sphereRadius * breath, energy)
        drawSphereGrid(canvas, cx, cy, sphereRadius * breath, energy)
        drawRadialEqualizer(canvas, cx, cy, sphereRadius * breath, energy)
        drawFrontSpectrum(canvas, cx, cy, sphereRadius * breath, energy)
        drawCoreGlass(canvas, cx, cy, sphereRadius * breath, energy)
        drawMicrophone(canvas, cx, cy, energy)

        val actionY = height * 0.91f
        ink.text(canvas, "撤回", 13f,
            if (target == Target.CANCEL) palette.brand else 0xFFD6E8F5.toInt(),
            width * 0.17f, actionY, bold = target == Target.CANCEL)
        ink.text(canvas, actionLabel, 13f,
            if (target == Target.ACTION) palette.brand else 0xFFD6E8F5.toInt(),
            width * 0.83f, actionY, bold = target == Target.ACTION)

        if (active) postInvalidateOnAnimation()
    }

    private fun drawBackground(canvas: Canvas, cx: Float, cy: Float, minSide: Float) {
        canvas.drawColor(Color.rgb(2, 4, 13))
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            intArrayOf(0xFF080D22.toInt(), 0xFF08091B.toInt(), 0xFF02040D.toInt()),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = RadialGradient(
            cx, cy, minSide * 0.72f,
            intArrayOf(0x443D59D8, 0x293B2B9D, 0x0002040D),
            floatArrayOf(0f, 0.52f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, minSide * 0.72f, paint)
        paint.shader = null

        // Quiet, fixed star points; no random per-frame flicker.
        for (i in 0 until 28) {
            val x = ((i * 83 + 31) % 997) / 997f * width
            val y = ((i * 149 + 17) % 991) / 991f * height
            val twinkle = 0.45f + 0.55f * (0.5f + 0.5f * sin(phase * 0.34f + i * 1.9f))
            paint.color = Color.argb((twinkle * 92).toInt(), 125, 193, 255)
            canvas.drawCircle(x, y, ink.dp(if (i % 8 == 0) 1.15f else 0.65f), paint)
        }
    }

    private fun drawSphereAura(canvas: Canvas, cx: Float, cy: Float, radius: Float, energy: Float) {
        paint.style = Paint.Style.FILL
        val glow = radius * (1.85f + energy * 0.24f)
        paint.shader = RadialGradient(
            cx, cy, glow,
            intArrayOf(0x6655DFFF, 0x443D8BEE, 0x333D36C8, 0x0002040D),
            floatArrayOf(0f, 0.36f, 0.66f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, glow, paint)
        paint.shader = null
    }

    private fun drawRearSpectrum(canvas: Canvas, cx: Float, cy: Float, radius: Float, energy: Float) {
        val count = 192
        paint.style = Paint.Style.STROKE
        for (i in 0 until count) {
            val angle = (i.toFloat() / count) * (PI.toFloat() * 2f)
            val sample = levels[(i * LEVEL_SAMPLES / count).coerceIn(0, LEVEL_SAMPLES - 1)]
            val wave = 0.5f + 0.5f * sin(angle * 7.0f + phase * 1.6f)
            val modulation = (sample * 0.78f + currentVolume * 0.16f + wave * 0.06f)
            val inner = radius * (0.78f + modulation * 0.10f)
            val outer = radius * (1.02f + modulation * (0.38f + energy * 0.18f))
            val x1 = cx + cos(angle) * inner
            val y1 = cy + sin(angle) * inner * 0.76f
            val x2 = cx + cos(angle) * outer
            val y2 = cy + sin(angle) * outer * 0.76f
            val alpha = (72 + modulation * 155f).toInt().coerceIn(0, 230)
            paint.color = if (i % 4 == 0) Color.argb(alpha, 164, 111, 255)
                else Color.argb(alpha, 72, 221, 255)
            paint.strokeWidth = ink.dp(if (i % 5 == 0) 1.8f else 1.15f)
            canvas.drawLine(x1, y1, x2, y2, paint)
        }
        paint.style = Paint.Style.FILL
    }

    private fun drawSphereGrid(canvas: Canvas, cx: Float, cy: Float, radius: Float, energy: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ink.dp(0.9f)
        for (i in 0 until 5) {
            val scale = 0.42f + i * 0.145f
            rect.set(cx - radius * scale, cy - radius * scale * 0.76f,
                cx + radius * scale, cy + radius * scale * 0.76f)
            paint.color = Color.argb((25 + energy * 35).toInt(), 119, 218, 255)
            canvas.drawOval(rect, paint)
        }
        // Fine longitude arcs give the sphere a holographic 3D mesh.
        for (i in -3..3) {
            val offset = i / 4f
            val half = radius * kotlin.math.sqrt(1f - offset * offset)
            rect.set(cx - half, cy - radius * 0.76f, cx + half, cy + radius * 0.76f)
            paint.color = Color.argb(34, 126, 195, 255)
            canvas.drawOval(rect, paint)
        }
        paint.style = Paint.Style.FILL
    }

    private fun drawRadialEqualizer(canvas: Canvas, cx: Float, cy: Float, radius: Float, energy: Float) {
        val count = 256
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.BUTT
        for (i in 0 until count) {
            val angle = (i.toFloat() / count) * (PI.toFloat() * 2f)
            val sampleIndex = ((i.toFloat() / count) * LEVEL_SAMPLES).toInt()
                .coerceIn(0, LEVEL_SAMPLES - 1)
            val sample = levels[sampleIndex]
            val harmonic = 0.5f + 0.5f * sin(angle * 11f - phase * 1.35f)
            val detail = 0.5f + 0.5f * sin(angle * 23f + phase * 0.72f)
            val amplitude = (sample * 0.72f + currentVolume * 0.20f +
                harmonic * 0.055f + detail * 0.025f).coerceIn(0f, 1f)
            val start = radius * (0.88f + amplitude * 0.045f)
            val end = radius * (1.03f + amplitude * (0.58f + energy * 0.22f))
            val x1 = cx + cos(angle) * start
            val y1 = cy + sin(angle) * start * 0.76f
            val x2 = cx + cos(angle) * end
            val y2 = cy + sin(angle) * end * 0.76f
            val front = sin(angle) > -0.08f
            val alpha = (if (front) 105 else 65) + (amplitude * 145f).toInt()
            paint.color = if (i % 7 == 0)
                Color.argb(alpha.coerceAtMost(245), 189, 131, 255)
            else Color.argb(alpha.coerceAtMost(245), 82, 226, 255)
            paint.strokeWidth = ink.dp(if (i % 8 == 0) 2.0f else 1.25f)
            canvas.drawLine(x1, y1, x2, y2, paint)
        }
        paint.strokeCap = Paint.Cap.ROUND
        paint.style = Paint.Style.FILL
    }

    private fun drawFrontSpectrum(canvas: Canvas, cx: Float, cy: Float, radius: Float, energy: Float) {
        // Bright lower hemisphere arcs sit in front of the sphere, enhancing depth.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ink.dp(1.5f)
        rect.set(cx - radius * 1.02f, cy - radius * 0.77f,
            cx + radius * 1.02f, cy + radius * 0.77f)
        paint.color = Color.argb((75 + energy * 110).toInt(), 88, 222, 255)
        canvas.drawArc(rect, 8f, 164f, false, paint)
        paint.strokeWidth = ink.dp(1.0f)
        paint.color = Color.argb((48 + energy * 82).toInt(), 176, 118, 255)
        canvas.drawArc(rect, 188f, 164f, false, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawCoreGlass(canvas: Canvas, cx: Float, cy: Float, radius: Float, energy: Float) {
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx - radius * 0.24f, cy - radius * 0.31f, radius * 1.12f,
            intArrayOf(0xFF1B4D75.toInt(), 0xE9132B58.toInt(), 0xD50C1234.toInt(),
                0xB10A0B25.toInt(), 0x00204070),
            floatArrayOf(0f, 0.28f, 0.62f, 0.86f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius * 0.94f, paint)
        paint.shader = null

        // Fine glass rim and a soft specular highlight.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ink.dp(1.6f)
        rect.set(cx - radius * 0.94f, cy - radius * 0.94f,
            cx + radius * 0.94f, cy + radius * 0.94f)
        paint.color = Color.argb((90 + energy * 100).toInt(), 130, 232, 255)
        canvas.drawArc(rect, 202f + phase * 7f, 118f, false, paint)
        paint.color = Color.argb(85, 188, 134, 255)
        canvas.drawArc(rect, 22f - phase * 5f, 82f, false, paint)
        paint.style = Paint.Style.FILL

        paint.shader = RadialGradient(
            cx - radius * 0.36f, cy - radius * 0.45f, radius * 0.9f,
            intArrayOf(0x3FFFFFFF, 0x12A5F5FF, 0x00000000),
            floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius * 0.9f, paint)
        paint.shader = null
    }

    private fun drawMicrophone(canvas: Canvas, cx: Float, cy: Float, energy: Float) {
        val r = micRadius() * 0.72f
        paint.shader = null
        paint.color = Color.argb((225 + energy * 30).toInt().coerceIn(0, 255), 244, 253, 255)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ink.dp(2.4f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        canvas.drawRoundRect(cx - r * 0.29f, cy - r * 0.72f,
            cx + r * 0.29f, cy + r * 0.22f, r * 0.29f, r * 0.29f, paint)
        rect.set(cx - r * 0.61f, cy - r * 0.2f, cx + r * 0.61f, cy + r * 0.66f)
        canvas.drawArc(rect, 0f, 180f, false, paint)
        canvas.drawLine(cx, cy + r * 0.66f, cx, cy + r * 0.98f, paint)
        canvas.drawLine(cx - r * 0.34f, cy + r * 0.98f,
            cx + r * 0.34f, cy + r * 0.98f, paint)
        paint.style = Paint.Style.FILL
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        val micHit = hypot(x - width / 2f, y - height * 0.475f) <= micRadius() + ink.dp(18f)
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
