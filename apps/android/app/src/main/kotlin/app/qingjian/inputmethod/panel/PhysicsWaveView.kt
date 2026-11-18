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
    enum class Style { SPECTRUM_BARS, RING_SPECTRUM, SPRING_BARS, GRAVITY_BALLS, PEAK_HOLD, ELASTIC_STRING, GLOW_ORBS, FLOWING_RIBBONS }

    var style = Style.SPRING_BARS
        set(value) { field = value; resetState(); invalidate() }
    var listening = false
        set(value) { field = value; postInvalidateOnAnimation() }
    var color: Int = 0xFF3B82F6.toInt()
        set(value) { field = value; invalidate() }

    private val count = 18
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
        // 五种新增物理动效只绘制在面板中部：左右各留8%，上下收进安全区，避开底部操作按钮。
        val compactPhysics = style.ordinal >= Style.SPRING_BARS.ordinal && style != Style.FLOWING_RIBBONS
        canvas.save()
        if (compactPhysics) {
            canvas.translate(width * 0.08f, height * 0.31f)
            canvas.scale(0.84f, 0.38f)
        }
        when (style) {
            Style.SPECTRUM_BARS -> drawSpectrumBars(canvas)
            Style.RING_SPECTRUM -> drawRingSpectrum(canvas)
            Style.SPRING_BARS -> drawBars(canvas, true, false)
            Style.PEAK_HOLD -> drawBars(canvas, false, true)
            Style.GRAVITY_BALLS -> drawBalls(canvas)
            Style.ELASTIC_STRING -> drawString(canvas)
            Style.GLOW_ORBS -> drawOrbs(canvas)
            Style.FLOWING_RIBBONS -> drawFlowingRibbons(canvas)
        }
        canvas.restore()
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
            val barHeight = (2.2f * density + energy * 54f * density).coerceAtLeast(2f * density)
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

    /**
     * 多层流体声波：复刻参考视频中的彩色半透明丝带，而不是频谱柱。
     * 48 个真实 FFT 频带沿水平方向映射到波带包络，波形本身由多组相位错开的
     * 连续曲线构成；静音时保留微弱呼吸，讲话时由真实频谱推动波峰。
     */
    private fun drawFlowingRibbons(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val centerY = h * 0.46f
        val samples = 112
        val layerCount = 5
        val hueOffset = (time * 11f) % 360f
        val palette = intArrayOf(
            Color.rgb(255, 92, 196), Color.rgb(153, 91, 255),
            Color.rgb(55, 157, 255), Color.rgb(35, 230, 226),
            Color.rgb(255, 94, 126), Color.rgb(255, 193, 75)
        )
        for (layer in 0 until layerCount) {
            val upper = Path()
            val lower = Path()
            val phase = layer * 1.17f
            val direction = if (layer % 2 == 0) 1f else -1f
            val layerBase = centerY + (layer - 2) * h * 0.012f
            val points = FloatArray(samples + 1)
            val thickness = FloatArray(samples + 1)
            for (i in 0..samples) {
                val u = i.toFloat() / samples
                val x = u * w
                val bin = (u * (smoothFft.size - 1)).coerceIn(0f, (smoothFft.size - 1).toFloat())
                val lo = bin.toInt()
                val hi = (lo + 1).coerceAtMost(smoothFft.lastIndex)
                val energy = smoothFft[lo] + (smoothFft[hi] - smoothFft[lo]) * (bin - lo)
                val envelope = (0.10f + energy * 1.35f).coerceIn(0.10f, 1.15f)
                val edge = sin(PI.toFloat() * u).let { it * it }
                val broad = sin(u * PI.toFloat() * (1.35f + layer * 0.11f) + time * (0.82f + layer * 0.07f) * direction + phase)
                val detail = sin(u * PI.toFloat() * (3.1f + layer * 0.23f) - time * (1.24f + layer * 0.09f) + phase * 1.7f)
                val ripple = sin(u * PI.toFloat() * 6.2f + time * 1.65f * direction + phase * 0.6f)
                val audioShape = (0.38f + energy * 1.55f)
                val displacement = (broad * 0.62f + detail * 0.27f + ripple * 0.11f) *
                    h * 0.22f * envelope * audioShape * (0.22f + edge * 0.78f)
                points[i] = layerBase + displacement + sin(time * 0.7f + phase) * h * 0.012f
                thickness[i] = h * (0.010f + energy * 0.055f) * (0.35f + edge * 0.65f)
            }
            for (i in 0..samples) {
                val x = w * i / samples
                val y = points[i] - thickness[i]
                if (i == 0) upper.moveTo(x, y) else upper.lineTo(x, y)
            }
            for (i in samples downTo 0) {
                val x = w * i / samples
                val y = points[i] + thickness[i]
                if (i == samples) lower.moveTo(x, y) else lower.lineTo(x, y)
            }
            upper.addPath(lower)
            upper.close()

            val hues = floatArrayOf(315f, 275f, 225f, 185f, 345f, 38f)
            val colors = IntArray(hues.size) { index ->
                Color.HSVToColor(floatArrayOf((hues[index] + hueOffset) % 360f, 0.72f, 1f))
            }
            paint.shader = android.graphics.LinearGradient(
                0f, 0f, w, 0f, colors, null, android.graphics.Shader.TileMode.CLAMP
            )
            paint.style = Paint.Style.FILL
            paint.alpha = 54 + layer * 4
            canvas.drawPath(upper, paint)

            // 柔和外发光与清晰丝带边缘，让重叠处呈现玻璃薄纱层次。
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeWidth = (3.2f + layer * 0.35f) * density
            paint.alpha = 54
            paint.maskFilter = BlurMaskFilter(7.5f * density, BlurMaskFilter.Blur.NORMAL)
            canvas.drawPath(upper, paint)
            paint.maskFilter = null
            paint.strokeWidth = 1.05f * density
            paint.alpha = 150
            canvas.drawPath(upper, paint)
            paint.shader = null
            paint.alpha = 255
        }
        // 中央细光轴，讲话时随整体能量轻微增强。
        val total = smoothFft.average().toFloat().coerceIn(0f, 1f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.color = 0x99A8EFFF.toInt()
        paint.alpha = (55 + total * 115f).toInt()
        paint.maskFilter = BlurMaskFilter(3.5f * density, BlurMaskFilter.Blur.NORMAL)
        canvas.drawLine(w * 0.035f, centerY, w * 0.965f, centerY, paint)
        paint.maskFilter = null
        paint.alpha = 255
        paint.style = Paint.Style.FILL
    }

    private fun spectrumColor(index: Int, shift: Float, spread: Float = 2.8f): Int =
        Color.HSVToColor(floatArrayOf((hueSeed + shift + index * spread) % 360f, 0.78f, 1f))

    private fun drawBars(canvas: Canvas, centered: Boolean, caps: Boolean) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cell = w / count
        val barW = (cell * 0.34f).coerceAtLeast(1.25f * density)
        val maxH = h * 0.58f
        paint.style = Paint.Style.FILL
        for (i in 0 until count) {
            val value = pos[i].coerceIn(0.025f, 1.05f)
            val bh = (value * maxH).coerceAtLeast(2.2f * density)
            val left = cell * i + (cell - barW) / 2f
            val top = if (centered) (h - bh) / 2f else h - bh
            val bottom = if (centered) (h + bh) / 2f else h
            val cx = left + barW / 2f
            drawPhysicalGlow(canvas, cx, (top + bottom) / 2f, barW * 0.85f, bh * 0.48f, i)
            paint.color = physicalColor(i)
            paint.alpha = (155 + 100 * value.coerceIn(0f, 1f)).toInt()
            canvas.drawRoundRect(left, top, left + barW, bottom, barW / 2f, barW / 2f, paint)
            if (caps) {
                val capY = h - peaks[i].coerceIn(0.025f, 1.05f) * maxH - 2.2f * density
                paint.alpha = 220
                canvas.drawRoundRect(left - cell * 0.06f, capY - 1.1f * density,
                    left + barW + cell * 0.06f, capY + 1.1f * density,
                    1.1f * density, 1.1f * density, paint)
            }
        }
        paint.alpha = 255
    }

    private fun drawBalls(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        val cell = width.toFloat() / count
        val radius = (cell * 0.105f).coerceIn(1.7f * density, 3.6f * density)
        val travel = (height - radius * 3f).coerceAtLeast(0f)
        for (i in 0 until count) {
            val cx = cell * (i + 0.5f)
            val energy = pos[i].coerceIn(0f, 1.05f)
            val cy = height - radius * 1.5f - energy * travel
            val stretch = 1f + min(abs(velocity[i]) * 0.025f, 0.18f)
            paint.color = physicalColor(i)
            paint.alpha = 30
            canvas.drawCircle(cx, cy, radius * 2.1f, paint)
            paint.alpha = 70
            canvas.drawCircle(cx, cy, radius * 1.45f, paint)
            paint.alpha = 245
            canvas.drawOval(cx - radius / stretch, cy - radius * stretch,
                cx + radius / stretch, cy + radius * stretch, paint)
        }
        paint.alpha = 255
    }

    private fun drawString(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cell = w / count
        val mid = h * 0.5f
        val points = count + 1
        path.rewind()
        for (j in 0..count) {
            val energy = if (j == count) pos[count - 1] else pos[j]
            val x = j * cell
            val y = mid - energy.coerceIn(0.02f, 1.05f) * h * 0.34f
            if (j == 0) path.moveTo(x, y) else {
                val previousX = (j - 1) * cell
                val previousEnergy = pos[j - 1].coerceIn(0.02f, 1.05f)
                val previousY = mid - previousEnergy * h * 0.34f
                path.quadTo((previousX + x) / 2f, (previousY + y) / 2f, x, y)
            }
        }
        paint.shader = android.graphics.LinearGradient(
            0f, 0f, w, 0f,
            intArrayOf(physicalColor(0), physicalColor(6), physicalColor(12), physicalColor(18), physicalColor(23)),
            null, android.graphics.Shader.TileMode.CLAMP,
        )
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = 5.2f * density
        paint.alpha = 32
        paint.maskFilter = BlurMaskFilter(5f * density, BlurMaskFilter.Blur.NORMAL)
        canvas.drawPath(path, paint)
        paint.maskFilter = null
        paint.strokeWidth = 2.4f * density
        paint.alpha = 225
        canvas.drawPath(path, paint)
        paint.shader = null
        paint.alpha = 255
    }

    private fun drawOrbs(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        val cell = width.toFloat() / count
        val mid = height * 0.5f
        for (i in 0 until count) {
            val energy = pos[i].coerceIn(0f, 1.05f)
            val cx = cell * (i + 0.5f)
            val radius = (cell * (0.075f + energy * 0.045f)).coerceIn(1.35f * density, 3.1f * density)
            val cy = mid + sin(time * 1.7f + i * 0.38f) * height * 0.16f * energy
            paint.color = physicalColor(i)
            paint.alpha = 24
            canvas.drawCircle(cx, cy, radius * 2.5f, paint)
            paint.alpha = 48
            canvas.drawCircle(cx, cy, radius * 1.7f, paint)
            paint.alpha = 230
            canvas.drawCircle(cx, cy, radius, paint)
        }
        paint.alpha = 255
    }

    private fun physicalColor(index: Int): Int {
        val baseHue = (hueSeed + index * 13f) % 360f
        return Color.HSVToColor(floatArrayOf(baseHue, 0.68f, 1f))
    }

    private fun drawPhysicalGlow(canvas: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, index: Int) {
        paint.style = Paint.Style.FILL
        paint.color = physicalColor(index)
        paint.alpha = 45
        paint.maskFilter = BlurMaskFilter(7f * density, BlurMaskFilter.Blur.NORMAL)
        canvas.drawOval(cx - rx * 1.8f, cy - ry * 1.15f, cx + rx * 1.8f, cy + ry * 1.15f, paint)
        paint.maskFilter = null
        paint.alpha = 255
    }
}
