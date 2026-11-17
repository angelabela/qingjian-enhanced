package app.qingjian.inputmethod.panel

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** 五路物理声波：弹簧、重力、峰值保持、弹性曲线与光球。 */
class PhysicsWaveView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    enum class Style { SPRING_BARS, GRAVITY_BALLS, PEAK_HOLD, ELASTIC_STRING, GLOW_ORBS }

    var style = Style.SPRING_BARS
        set(value) { field = value; resetState(); invalidate() }
    var listening = false
        set(value) { field = value; postInvalidateOnAnimation() }
    var color: Int = 0xFF3B82F6.toInt()
        set(value) { field = value; invalidate() }

    private val n = 5
    private val levels = FloatArray(n)
    private val pos = FloatArray(n)
    private val velocity = FloatArray(n)
    private val peaks = FloatArray(n)
    private val peakVelocity = FloatArray(n)
    private val peakHold = FloatArray(n)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()
    private var lastFrame = 0L
    private var time = 0f
    private val gravity = 14f

    fun update(values: FloatArray) {
        for (i in 0 until n) levels[i] = values.getOrElse(i) { 0f }.coerceIn(0f, 1f)
        postInvalidateOnAnimation()
    }

    private fun resetState() {
        pos.fill(0f); velocity.fill(0f); peaks.fill(0f); peakVelocity.fill(0f); peakHold.fill(0f)
        lastFrame = 0L
    }

    private fun target(i: Int): Float {
        if (!listening) return 0.035f
        return max(levels[i], 0.07f + 0.03f * sin(time * 2.2f + i * 0.9f))
    }

    private fun step(dt: Float) {
        if (style == Style.GRAVITY_BALLS) {
            for (i in 0 until n) {
                velocity[i] -= gravity * dt
                pos[i] += velocity[i] * dt
                if (pos[i] < 0f) {
                    pos[i] = 0f
                    velocity[i] = -velocity[i] * 0.55f
                    if (velocity[i] < 0.35f) velocity[i] = 0f
                }
                if (listening && levels[i] > 0.15f && pos[i] < levels[i] * 0.5f) {
                    val kick = sqrt(2f * gravity * levels[i])
                    if (velocity[i] < kick * 0.5f) velocity[i] = kick
                }
            }
        } else {
            val k = when (style) {
                Style.SPRING_BARS -> 220f
                Style.PEAK_HOLD -> 300f
                Style.ELASTIC_STRING -> 140f
                else -> 120f
            }
            val damping = when (style) {
                Style.SPRING_BARS -> 15f
                Style.PEAK_HOLD -> 26f
                Style.ELASTIC_STRING -> 9f
                else -> 12f
            }
            val coupling = when (style) {
                Style.SPRING_BARS -> 60f
                Style.ELASTIC_STRING -> 220f
                Style.PEAK_HOLD -> 0f
                else -> 30f
            }
            for (i in 0 until n) {
                val left = pos[max(0, i - 1)]
                val right = pos[min(n - 1, i + 1)]
                val acceleration = k * (target(i) - pos[i]) - damping * velocity[i] +
                    coupling * (left + right - 2f * pos[i])
                velocity[i] += acceleration * dt
                pos[i] += velocity[i] * dt
            }
        }
        if (style == Style.PEAK_HOLD) {
            for (i in 0 until n) {
                if (pos[i] >= peaks[i]) {
                    peaks[i] = pos[i]; peakVelocity[i] = 0f; peakHold[i] = 0.35f
                } else if (peakHold[i] > 0f) {
                    peakHold[i] -= dt
                } else {
                    peakVelocity[i] -= 3.2f * dt
                    peaks[i] += peakVelocity[i] * dt
                    if (peaks[i] < pos[i]) { peaks[i] = pos[i]; peakVelocity[i] = 0f }
                }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1e9f).coerceAtMost(1f / 30f)
        lastFrame = now
        time += dt
        var remaining = dt
        while (remaining > 0f) {
            val h = min(remaining, 1f / 120f)
            step(h)
            remaining -= h
        }
        when (style) {
            Style.SPRING_BARS -> drawBars(canvas, true, false)
            Style.PEAK_HOLD -> drawBars(canvas, false, true)
            Style.GRAVITY_BALLS -> drawBalls(canvas)
            Style.ELASTIC_STRING -> drawString(canvas)
            Style.GLOW_ORBS -> drawOrbs(canvas)
        }
        val moving = listening || pos.indices.any { abs(velocity[it]) > 0.02f || abs(pos[it] - target(it)) > 0.005f }
        if (moving) postInvalidateOnAnimation() else lastFrame = 0L
    }

    private fun drawBars(canvas: Canvas, centered: Boolean, caps: Boolean) {
        val w = width.toFloat(); val h = height.toFloat()
        val barW = w / 11f
        val maxH = h * 0.92f
        paint.style = Paint.Style.FILL
        for (i in 0 until n) {
            val value = pos[i].coerceIn(0.04f, 1.1f)
            val bh = value * maxH
            val left = barW * (1 + i * 2)
            val top = if (centered) (h - bh) / 2f else h - bh
            val bottom = if (centered) (h + bh) / 2f else h
            paint.color = color
            paint.alpha = (150 + 105 * pos[i].coerceIn(0f, 1f)).toInt()
            canvas.drawRoundRect(left, top, left + barW, bottom, barW / 2f, barW / 2f, paint)
            if (caps) {
                val capY = h - peaks[i].coerceIn(0.04f, 1.1f) * maxH - barW * 0.9f
                paint.alpha = 255
                canvas.drawRoundRect(left, capY - barW * 0.35f, left + barW, capY + barW * 0.35f, barW / 4f, barW / 4f, paint)
            }
        }
        paint.alpha = 255
    }

    private fun drawBalls(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cw = w / n
        val r = cw * 0.26f
        val travel = h - r * 2.6f
        for (i in 0 until n) {
            val cx = cw * (i + 0.5f)
            val cy = h - r * 1.3f - pos[i].coerceIn(0f, 1.2f) * travel
            val stretch = 1f + min(abs(velocity[i]) * 0.07f, 0.4f)
            paint.color = color
            paint.alpha = (70 * (1f - pos[i].coerceIn(0f, 1f))).toInt()
            rect.set(cx - r, h - r * 0.5f, cx + r, h - r * 0.1f)
            canvas.drawOval(rect, paint)
            paint.alpha = 255
            rect.set(cx - r / stretch, cy - r * stretch, cx + r / stretch, cy + r * stretch)
            canvas.drawOval(rect, paint)
        }
    }

    private fun drawString(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val mid = h / 2f
        val maxH = h * 0.92f
        val x = FloatArray(n + 2)
        val upper = FloatArray(n + 2)
        val lower = FloatArray(n + 2)
        for (j in 0 until n + 2) {
            val v = if (j == 0 || j == n + 1) 0.03f else pos[j - 1].coerceIn(0.03f, 1.1f)
            x[j] = if (j == 0) 0f else if (j == n + 1) w else w / n * (j - 0.5f)
            val half = v * maxH / 2f
            upper[j] = mid - half
            lower[j] = mid + half
        }
        path.rewind()
        path.moveTo(x[0], upper[0])
        for (i in 1 until x.size) path.quadTo(x[i - 1], upper[i - 1], x[i], upper[i])
        path.lineTo(x.last(), lower.last())
        for (i in x.lastIndex - 1 downTo 0) path.quadTo(x[i + 1], lower[i + 1], x[i], lower[i])
        path.close()
        paint.color = color
        paint.alpha = 200
        paint.style = Paint.Style.FILL
        canvas.drawPath(path, paint)
        paint.alpha = 255
    }

    private fun drawOrbs(canvas: Canvas) {
        val cw = width.toFloat() / n
        val cy = height / 2f
        paint.color = color
        paint.style = Paint.Style.FILL
        for (i in 0 until n) {
            val cx = cw * (i + 0.5f)
            val radius = cw * (0.10f + 0.24f * pos[i].coerceIn(0f, 1.15f))
            paint.alpha = 40; canvas.drawCircle(cx, cy, radius * 1.9f, paint)
            paint.alpha = 90; canvas.drawCircle(cx, cy, radius * 1.4f, paint)
            paint.alpha = 255; canvas.drawCircle(cx, cy, radius, paint)
        }
    }
}