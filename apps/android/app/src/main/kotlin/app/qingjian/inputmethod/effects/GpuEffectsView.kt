package app.qingjian.inputmethod.effects

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Hardware-accelerated Canvas effect layer for the IME.
 * A normal View is intentionally used instead of SurfaceView/GLSurfaceView:
 * it composes reliably with keyboard and panel children and never steals touch input.
 */
object GpuEffectBus {
    @Volatile internal var sink: ((Float, Float, Float) -> Unit)? = null

    fun pulse(screenX: Float, screenY: Float, strength: Float = 0.9f) {
        sink?.invoke(screenX, screenY, strength.coerceIn(0f, 1.5f))
    }
}

class GpuEffectsView(context: Context) : View(context) {
    private data class Pulse(
        val x: Float,
        val y: Float,
        val strength: Float,
        val startedAt: Long,
    )

    private val pulses = ArrayList<Pulse>(4)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private val frame = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            pulses.removeAll { now - it.startedAt >= DURATION_MS }
            invalidate()
            if (pulses.isNotEmpty()) postOnAnimation(this)
        }
    }

    private val effectSink: (Float, Float, Float) -> Unit = { x, y, strength ->
        triggerScreen(x, y, strength)
    }

    init {
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        GpuEffectBus.sink = effectSink
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        GpuEffectBus.sink = effectSink
    }

    fun triggerScreen(screenX: Float, screenY: Float, strength: Float) {
        if (width <= 0 || height <= 0) {
            post { triggerScreen(screenX, screenY, strength) }
            return
        }
        val location = IntArray(2)
        getLocationOnScreen(location)
        val x = (screenX - location[0]).coerceIn(0f, width.toFloat())
        val y = (screenY - location[1]).coerceIn(0f, height.toFloat())
        if (pulses.size >= 4) pulses.removeAt(0)
        pulses.add(Pulse(x, y, strength, SystemClock.uptimeMillis()))
        removeCallbacks(frame)
        postOnAnimation(frame)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (pulses.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        val maxRadius = (width.coerceAtLeast(height) * 0.46f).coerceAtLeast(96f * density)

        pulses.forEach { pulse ->
            val elapsed = (now - pulse.startedAt).coerceAtLeast(0L).toFloat()
            val t = (elapsed / DURATION_MS).coerceIn(0f, 1f)
            val fade = (1f - t) * pulse.strength.coerceAtMost(1.25f)
            val cx = pulse.x
            val cy = pulse.y

            // Bright cyan-violet-pink bloom makes the interaction readable on both light and dark themes.
            paint.style = Paint.Style.FILL
            paint.shader = RadialGradient(
                cx, cy, maxRadius * (0.22f + 0.58f * t),
                intArrayOf(0xBFFFFFFF.toInt(), 0xB83BEAFF.toInt(), 0x965B42FF.toInt(), 0x00E72BBD),
                floatArrayOf(0f, 0.22f, 0.62f, 1f),
                Shader.TileMode.CLAMP
            )
            paint.alpha = (190f * fade).toInt().coerceIn(0, 210)
            canvas.drawCircle(cx, cy, maxRadius * (0.22f + 0.58f * t), paint)
            paint.shader = null

            // Three expanding rings with staggered phases create a layered ripple, not a faint flat flash.
            for (ring in 0..2) {
                val phase = (t * 1.18f - ring * 0.19f).coerceIn(0f, 1f)
                if (t * 1.18f < ring * 0.19f) continue
                val radius = 22f * density + phase * maxRadius
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = (3.2f - ring * 0.55f) * density
                paint.shader = null
                paint.color = when (ring) {
                    0 -> 0xF23DEBFF.toInt()
                    1 -> 0xE47755FF.toInt()
                    else -> 0xDFFF55C9.toInt()
                }
                paint.alpha = (fade * (235f - ring * 24f)).toInt().coerceIn(0, 245)
                canvas.drawCircle(cx, cy, radius, paint)
            }

            // Spark points orbit the contact point briefly for a more premium, energetic response.
            paint.style = Paint.Style.FILL
            paint.shader = null
            for (i in 0 until 12) {
                val angle = i * (2.0 * PI / 12.0) + t * 1.8
                val distance = 18f * density + t * maxRadius * 0.68f
                val px = cx + cos(angle).toFloat() * distance
                val py = cy + sin(angle).toFloat() * distance
                paint.color = if (i % 3 == 0) 0xFFFFE6FF.toInt() else 0xFF63EFFF.toInt()
                paint.alpha = (fade * 220f * (1f - t * 0.55f)).toInt().coerceIn(0, 220)
                canvas.drawCircle(px, py, (1.4f + (i % 3) * 0.45f) * density, paint)
            }
        }
        paint.shader = null
        paint.alpha = 255
        paint.style = Paint.Style.FILL
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frame)
        pulses.clear()
        if (GpuEffectBus.sink === effectSink) GpuEffectBus.sink = null
        super.onDetachedFromWindow()
    }

    private companion object {
        const val DURATION_MS = 1050L
    }
}
