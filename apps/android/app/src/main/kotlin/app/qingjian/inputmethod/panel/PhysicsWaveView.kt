package app.qingjian.inputmethod.panel

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 语音频谱动效。前两种是 53 号工作流原有的频谱柱、环形频谱；
 * 后五种沿用 PhysicsWaveView 的物理模型。所有样式共用同一组真实 FFT 频带，
 * 不在识别线程中做绘制或额外音频分析。
 */
class PhysicsWaveView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    enum class Style { SPECTRUM_BARS, RING_SPECTRUM, SPRING_BARS, GRAVITY_BALLS, PEAK_HOLD, ELASTIC_STRING, GLOW_ORBS }

    var style = Style.SPRING_BARS
        set(value) { field = value; resetState(); invalidate() }
    var listening = false
        set(value) { field = value; postInvalidateOnAnimation() }
    var color: Int = 0xFF3B82F6.toInt()
        set(value) { field = value; invalidate() }

    private val count = 5
    private val levels = FloatArray(count)
    private val pos = FloatArray(count)
    private val velocity = FloatArray(count)
    private val peaks = FloatArray(count)
    private val peakVelocity = FloatArray(count)
    private val peakHold = FloatArray(count)
    private val fft = FloatArray(48)
    private val smoothFft = FloatArray(48)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()
    private val density = resources.displayMetrics.density
    private val hueSeed = kotlin.random.Random.nextFloat() * 360f
    private var lastFrame = 0L
    private var time = 0f
    private val gravity = 14f

    init {
        // BlurMaskFilter 用于复刻 53 号的柔和外发光；软件图层避免硬件画布忽略模糊。
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    }

    fun update(values: FloatArray) {
        for (i in 0 until count) levels[i] = values.getOrElse(i) { 0f }.coerceIn(0f, 1f)
        postInvalidateOnAnimation()
    }

    fun updateSpectrum(values: FloatArray) {
        for (i in fft.indices) fft[i] = values.getOrElse(i) { 0f }.coerceIn(0f, 1f)
        postInvalidateOnAnimation()
    }

    private fun resetState() {
        pos.fill(0f); velocity.fill(0f); peaks.fill(0f); peakVelocity.fill(0f); peakHold.fill(0f)
        smoothFft.fill(0f)
        lastFrame = 0L
    }

    private fun target(i: Int): Float {
        if (!listening) return 0.035f
        return max(levels[i], 0.07f + 0.03f * sin(time * 2.2f + i * 0.9f))
    }

    private fun step(dt: Float) {
        if (style == Style.GRAVITY_BALLS) {
            for (i in 0 until count) {
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
            for (i in 0 until count) {
                val left = pos[max(0, i - 1)]
                val right = pos[min(count - 1, i + 1)]
                val acceleration = k * (target(i) - pos[i]) - damping * velocity[i] +
                    coupling * (left + right - 2f * pos[i])
                velocity[i] += acceleration * dt
                pos[i] += velocity[i] * dt
            }
        }
        if (style == Style.PEAK_HOLD) {
            for (i in 0 until count) {
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
        val attack = (1.0 - kotlin.math.exp((-dt / 0.045f).toDouble())).toFloat()
        val release = (1.0 - kotlin.math.exp((-dt / 0.19f).toDouble())).toFloat()
        for (i in smoothFft.indices) {
            val blend = if (fft[i] > smoothFft[i]) attack else release
            smoothFft[i] += (fft[i] - smoothFft[i]) * blend
        }
        if (style.ordinal >= Style.SPRING_BARS.ordinal) {
            var remaining = dt
            while (remaining > 0f) {
                val h = min(remaining, 1f / 120f)
                step(h)
                remaining -= h
            }
        }
        when (style) {
            Style.SPECTRUM_BARS -> drawSpectrumBars(canvas)
            Style.RING_SPECTRUM -> drawRingSpectrum(canvas)
            Style.SPRING_BARS -> drawBars(canvas, true, false)
            Style.PEAK_HOLD -> drawBars(canvas, false, true)
            Style.GRAVITY_BALLS -> drawBalls(canvas)
            Style.ELASTIC_STRING -> drawString(canvas)
            Style.GLOW_ORBS -> drawOrbs(canvas)
        }
        val moving = listening || smoothFft.any { it > 0.008f } ||
            (style.ordinal >= Style.SPRING_BARS.ordinal && pos.indices.any {
                if (style == Style.GRAVITY_BALLS) pos[it] > 0.01f || abs(velocity[it]) > 0.05f
                else abs(velocity[it]) > 0.02f || abs(pos[it] - target(it)) > 0.005f
            })
        if (moving) postInvalidateOnAnimation() else lastFrame = 0L
    }

    /** 53 号工作流的 48 频带渐变频谱柱：保留清晰核心线、柔和外发光和流动色相。 */
    private fun drawSpectrumBars(canvas: Canvas) {
        val cx = width * 0.5f
        val cy = height * 0.475f
        val halfWidth = width * 0.43f
        val gap = 3.2f * density
        val barWidth = ((halfWidth * 2f) - gap * (smoothFft.size - 1)) / smoothFft.size
        val hueShift = (time * 32f) % 360f
        for (i in smoothFft.indices) {
            val x = cx - halfWidth + i * (barWidth + gap) + barWidth / 2f
            val energy = smoothFft[i].coerceIn(0f, 1f)
            val barHeight = (2.2f * density + energy * height * 0.23f).coerceAtLeast(2f * density)
            val c = spectrumColor(i, hueShift)
            paint.shader = null
            paint.color = c
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = barWidth.coerceAtLeast(1.2f * density)
            paint.alpha = 75
            paint.maskFilter = BlurMaskFilter(5f * density, BlurMaskFilter.Blur.NORMAL)
            canvas.drawLine(x, cy - barHeight, x, cy + barHeight, paint)
            paint.maskFilter = null
            paint.alpha = 245
            paint.strokeWidth = (barWidth * 0.72f).coerceAtLeast(1.1f * density)
            canvas.drawLine(x, cy - barHeight, x, cy + barHeight, paint)
        }
        paint.alpha = 255
    }

    /** 53 号工作流的环形频谱：48 个真实频带沿圆周分布，颜色与频谱柱同源。 */
    private fun drawRingSpectrum(canvas: Canvas) {
        val cx = width * 0.5f
        val cy = height * 0.475f
        val radius = min(width, height) * 0.19f
        val step = (2.0 * PI / smoothFft.size).toFloat()
        val hueShift = (time * 32f) % 360f
        for (i in smoothFft.indices) {
            val angle = -PI.toFloat() / 2f + i * step
            val energy = smoothFft[i].coerceIn(0f, 1f)
            val inner = radius
            val outer = inner + 4f * density + energy * 42f * density
            val x1 = cx + cos(angle) * inner
            val y1 = cy + sin(angle) * inner
            val x2 = cx + cos(angle) * outer
            val y2 = cy + sin(angle) * outer
            paint.color = spectrumColor(i, hueShift, 3.4f)
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = 2.4f * density
            paint.alpha = 70
            paint.maskFilter = BlurMaskFilter(5f * density, BlurMaskFilter.Blur.NORMAL)
            canvas.drawLine(x1, y1, x2, y2, paint)
            paint.maskFilter = null
            paint.alpha = 245
            canvas.drawLine(x1, y1, x2, y2, paint)
        }
        paint.alpha = 255
    }

    private fun spectrumColor(index: Int, shift: Float, spread: Float = 2.8f): Int =
        Color.HSVToColor(floatArrayOf((hueSeed + shift + index * spread) % 360f, 0.78f, 1f))

    private fun drawBars(canvas: Canvas, centered: Boolean, caps: Boolean) {
        val w = width.toFloat(); val h = height.toFloat()
        val barW = w / 11f
        val maxH = h * 0.92f
        paint.style = Paint.Style.FILL
        for (i in 0 until count) {
            val value = pos[i].coerceIn(0.04f, 1.1f)
            val bh = value * maxH
            val left = barW * (1 + i * 2)
            val top = if (centered) (h - bh) / 2f else h - bh
            val bottom = if (centered) (h + bh) / 2f else h
            drawPhysicalGlow(canvas, left + barW / 2f, (top + bottom) / 2f, barW * 0.42f, bh * 0.5f, i)
            paint.color = physicalColor(i)
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
        val cw = width.toFloat() / count
        val r = cw * 0.26f
        val travel = height - r * 2.6f
        for (i in 0 until count) {
            val cx = cw * (i + 0.5f)
            val cy = height - r * 1.3f - pos[i].coerceIn(0f, 1.2f) * travel
            val stretch = 1f + min(abs(velocity[i]) * 0.07f, 0.4f)
            paint.color = physicalColor(i)
            paint.alpha = 48
            canvas.drawCircle(cx, cy, r * 1.9f, paint)
            paint.alpha = 85
            canvas.drawCircle(cx, cy, r * 1.4f, paint)
            paint.alpha = 70
            rect.set(cx - r, height - r * 0.5f, cx + r, height - r * 0.1f)
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
        val x = FloatArray(count + 2)
        val upper = FloatArray(count + 2)
        val lower = FloatArray(count + 2)
        for (j in 0 until count + 2) {
            val v = if (j == 0 || j == count + 1) 0.03f else pos[j - 1].coerceIn(0.03f, 1.1f)
            x[j] = if (j == 0) 0f else if (j == count + 1) w else w / count * (j - 0.5f)
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
        paint.alpha = 55
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 13f * density
        paint.maskFilter = BlurMaskFilter(9f * density, BlurMaskFilter.Blur.NORMAL)
        canvas.drawPath(path, paint)
        paint.maskFilter = null
        paint.style = Paint.Style.FILL
        paint.alpha = 195
        canvas.drawPath(path, paint)
        paint.alpha = 255
    }

    private fun drawOrbs(canvas: Canvas) {
        val cw = width.toFloat() / count
        val cy = height / 2f
        for (i in 0 until count) {
            val cx = cw * (i + 0.5f)
            val radius = cw * (0.10f + 0.24f * pos[i].coerceIn(0f, 1.15f))
            paint.color = physicalColor(i)
            paint.alpha = 28
            canvas.drawCircle(cx, cy, radius * 2.15f, paint)
            paint.alpha = 58
            canvas.drawCircle(cx, cy, radius * 1.65f, paint)
            paint.alpha = 105
            canvas.drawCircle(cx, cy, radius * 1.28f, paint)
            paint.alpha = 245
            canvas.drawCircle(cx, cy, radius, paint)
            paint.alpha = 255
        }
    }

    private fun physicalColor(index: Int): Int {
        val baseHue = (hueSeed + index * 13f) % 360f
        return Color.HSVToColor(floatArrayOf(baseHue, 0.68f, 1f))
    }

    private fun drawPhysicalGlow(canvas: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, index: Int) {
        paint.color = physicalColor(index)
        paint.alpha = 45
        paint.maskFilter = BlurMaskFilter(7f * density, BlurMaskFilter.Blur.NORMAL)
        canvas.drawOval(cx - rx * 1.8f, cy - ry * 1.15f, cx + rx * 1.8f, cy + ry * 1.15f, paint)
        paint.maskFilter = null
        paint.alpha = 255
    }
}
