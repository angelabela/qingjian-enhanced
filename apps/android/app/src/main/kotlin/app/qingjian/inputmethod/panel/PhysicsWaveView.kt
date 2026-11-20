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
    enum class Style { SPECTRUM_BARS, RING_SPECTRUM, SPRING_BARS, GRAVITY_BALLS, PEAK_HOLD, ELASTIC_STRING, GLOW_ORBS, FLOWING_RIBBONS, REALTIME_WAVEFORM, WAVEFORM_SPECTRUM }

    var style = Style.SPRING_BARS
        set(value) { field = value; resetState(); invalidate() }
    var listening = false
        set(value) {
            field = value
            if (!value) {
                waveform.fill(0f)
                waveRms = 0f
                wavePeak = 0f
            }
            postInvalidateOnAnimation()
        }
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
    // 48 段真实频谱的峰值保持状态，与18段物理弹簧状态分离。
    private val spectrumPeaks = FloatArray(48)
    private val spectrumPeakHold = FloatArray(48)
    private val particlePhases = FloatArray(36) { i -> i * 2.3999632f }
    private val waveform = FloatArray(128)
    private val smoothWaveform = FloatArray(128)
    private var waveRms = 0f
    private var wavePeak = 0f
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

    fun updateWaveform(samples: FloatArray, rms: Float, peak: Float) {
        for (i in waveform.indices) waveform[i] = samples.getOrElse(i) { 0f }.coerceIn(-1f, 1f)
        waveRms = rms.coerceIn(0f, 1f)
        wavePeak = peak.coerceIn(0f, 1f)
        postInvalidateOnAnimation()
    }

    private fun resetState() {
        pos.fill(0f); velocity.fill(0f); peaks.fill(0f); peakVelocity.fill(0f); peakHold.fill(0f)
        smoothFft.fill(0f)
        spectrumPeaks.fill(0f); spectrumPeakHold.fill(0f)
        waveform.fill(0f); smoothWaveform.fill(0f)
        waveRms = 0f; wavePeak = 0f
        lastFrame = 0L
    }

    private fun isPhysicsStyle(): Boolean = when (style) {
        Style.SPRING_BARS, Style.GRAVITY_BALLS, Style.PEAK_HOLD,
        Style.ELASTIC_STRING, Style.GLOW_ORBS, Style.FLOWING_RIBBONS -> true
        else -> false
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
        for (i in smoothWaveform.indices) {
            val blend = if (abs(waveform[i]) > abs(smoothWaveform[i])) attack else release
            smoothWaveform[i] += (waveform[i] - smoothWaveform[i]) * blend
        }
        if (style == Style.PEAK_HOLD) {
            for (i in smoothFft.indices) {
                val level = smoothFft[i]
                if (level >= spectrumPeaks[i]) {
                    spectrumPeaks[i] = level
                    spectrumPeakHold[i] = 0.62f
                } else if (spectrumPeakHold[i] > 0f) {
                    spectrumPeakHold[i] = (spectrumPeakHold[i] - dt).coerceAtLeast(0f)
                } else {
                    // 峰值缓慢回落，但始终不低于当前频谱柱，避免出现倒挂。
                    spectrumPeaks[i] = max(level, spectrumPeaks[i] - dt * 0.42f)
                }
            }
        }
        if (isPhysicsStyle()) {
            var remaining = dt
            while (remaining > 0f) {
                val h = min(remaining, 1f / 120f)
                step(h)
                remaining -= h
            }
        }
        // 所有样式统一限制在面板中央 80% 宽度，左右各留 10% 安全边距。
        // 物理类样式只额外压缩垂直绘制区域，避免波形与底部操作按钮重叠。
        val compactPhysics = isPhysicsStyle() && style != Style.FLOWING_RIBBONS
        canvas.save()
        if (style == Style.RING_SPECTRUM) {
            // 环形频谱必须保持等比例；横向留白缩放会把圆形拉成椭圆。
            drawRingSpectrum(canvas)
        } else {
            canvas.translate(width * 0.05f, 0f)
            canvas.scale(0.90f, 1f)
            if (compactPhysics) {
                canvas.translate(0f, height * 0.31f)
                canvas.scale(1f, 0.38f)
            }
            when (style) {
                Style.SPECTRUM_BARS -> drawSpectrumBars(canvas)
                Style.RING_SPECTRUM -> Unit
                Style.SPRING_BARS -> drawBars(canvas, true, false)
                Style.PEAK_HOLD -> drawPeakHoldSpectrum(canvas)
                Style.GRAVITY_BALLS -> drawBalls(canvas)
                Style.ELASTIC_STRING -> drawString(canvas)
                Style.GLOW_ORBS -> drawOrbs(canvas)
                Style.FLOWING_RIBBONS -> drawFlowingRibbons(canvas)
                Style.REALTIME_WAVEFORM -> drawWaveform(canvas, false)
                Style.WAVEFORM_SPECTRUM -> drawHybridVisualization(canvas)
            }
        }
        canvas.restore()
        val moving = listening || smoothFft.any { it > 0.008f } ||
            smoothWaveform.any { abs(it) > 0.003f } ||
            (isPhysicsStyle() && pos.indices.any {
                if (style == Style.GRAVITY_BALLS) pos[it] > 0.01f || abs(velocity[it]) > 0.05f
                else abs(velocity[it]) > 0.02f || abs(pos[it] - target(it)) > 0.005f
            })
        if (moving) postInvalidateOnAnimation() else lastFrame = 0L
    }

    /** 波形与频谱融合：上方真实时域波形，下方保留 48 段实时频谱。 */
    private fun drawHybridVisualization(canvas: Canvas) {
        canvas.save()
        canvas.translate(0f, height * 0.20f)
        drawSpectrumBars(canvas)
        canvas.restore()
        drawWaveform(canvas, true)
        drawParticleField(canvas, height * 0.53f, height * 0.12f, 0.72f)
        drawFusionCore(canvas)
    }

    /** 使用 PCM 时域采样绘制连续波形，峰值与 RMS 只调节视觉幅度和光晕。 */
    private fun drawWaveform(canvas: Canvas, compact: Boolean) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || smoothWaveform.isEmpty()) return
        val centerY = h * if (compact) 0.34f else 0.50f
        val gain = (1.55f + wavePeak * 0.72f + waveRms * 0.35f).coerceIn(1.55f, 2.35f)
        val amplitude = h * (if (compact) 0.22f else 0.34f) * gain
        path.rewind()
        var previousX = 0f
        var previousY = centerY - smoothWaveform[0] * amplitude
        path.moveTo(previousX, previousY)
        for (i in 1 until smoothWaveform.size) {
            val x = w * i / (smoothWaveform.size - 1)
            val y = centerY - smoothWaveform[i] * amplitude
            val midX = (previousX + x) * 0.5f
            val midY = (previousY + y) * 0.5f
            path.quadTo(previousX, previousY, midX, midY)
            previousX = x
            previousY = y
        }
        path.lineTo(previousX, previousY)

        paint.shader = android.graphics.LinearGradient(
            0f, 0f, w, 0f,
            intArrayOf(0xFFFF5FC8.toInt(), 0xFF9B72FF.toInt(), 0xFF39C8FF.toInt(), 0xFF42F5D4.toInt()),
            null, android.graphics.Shader.TileMode.CLAMP,
        )
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = 7.5f * density
        paint.alpha = (38 + waveRms * 150f).toInt().coerceIn(38, 150)
        paint.maskFilter = BlurMaskFilter(8f * density, BlurMaskFilter.Blur.NORMAL)
        canvas.drawPath(path, paint)
        paint.maskFilter = null
        paint.strokeWidth = 2.2f * density
        paint.alpha = 235
        canvas.drawPath(path, paint)
        paint.shader = null
        paint.alpha = 255
        paint.style = Paint.Style.FILL
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

    /** 48段真实FFT频谱柱，顶部独立峰值标记带短暂停留和缓慢衰减。 */
    private fun drawPeakHoldSpectrum(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val gap = 2.2f * density
        val barWidth = ((w * 0.90f - gap * (smoothFft.size - 1)) / smoothFft.size).coerceAtLeast(1f * density)
        val left = (w - (barWidth * smoothFft.size + gap * (smoothFft.size - 1))) * 0.5f
        val baseline = h * 0.78f
        val maxHeight = h * 0.58f
        val hueShift = (time * 24f) % 360f
        for (i in smoothFft.indices) {
            val x = left + i * (barWidth + gap)
            val energy = smoothFft[i].coerceIn(0f, 1f)
            val barHeight = (3f * density + energy * maxHeight).coerceAtMost(maxHeight)
            val c = spectrumColor(i, hueShift)
            paint.shader = null
            paint.color = c
            paint.style = Paint.Style.FILL
            paint.alpha = 58
            canvas.drawRoundRect(x - 1.2f * density, baseline - barHeight - 3f * density,
                x + barWidth + 1.2f * density, baseline + 2f * density,
                barWidth, barWidth, paint)
            paint.alpha = 238
            canvas.drawRoundRect(x, baseline - barHeight, x + barWidth, baseline,
                barWidth * 0.5f, barWidth * 0.5f, paint)
            val peakHeight = (3f * density + spectrumPeaks[i] * maxHeight).coerceAtMost(maxHeight)
            val capY = baseline - peakHeight
            paint.alpha = if (spectrumPeakHold[i] > 0f) 255 else 190
            paint.strokeWidth = (barWidth * 1.55f).coerceAtLeast(2f * density)
            paint.strokeCap = Paint.Cap.ROUND
            paint.style = Paint.Style.STROKE
            paint.maskFilter = if (spectrumPeakHold[i] > 0f) BlurMaskFilter(3f * density, BlurMaskFilter.Blur.NORMAL) else null
            canvas.drawLine(x - barWidth * 0.22f, capY, x + barWidth * 1.22f, capY, paint)
            paint.maskFilter = null
            paint.style = Paint.Style.FILL
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
        drawParticleField(canvas, h * 0.46f, h * 0.24f, 0.62f)
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
        drawParticleField(canvas, height * 0.54f, height * 0.30f, 0.48f)
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
        val cell = width.toFloat() / count
        val mid = height * 0.5f
        val centersX = FloatArray(count)
        val centersY = FloatArray(count)
        val radii = FloatArray(count)
        // 先绘制柔性光丝，光球之间会随声音能量产生轻微牵引感。
        path.rewind()
        for (i in 0 until count) {
            val energy = pos[i].coerceIn(0f, 1.05f)
            centersX[i] = cell * (i + 0.5f)
            centersY[i] = mid + sin(time * 1.55f + i * 0.34f) * height * (0.10f + energy * 0.12f)
            radii[i] = (cell * (0.085f + energy * 0.075f)).coerceIn(2.2f * density, 7.2f * density)
            if (i == 0) path.moveTo(centersX[i], centersY[i])
            else path.quadTo((centersX[i - 1] + centersX[i]) * 0.5f,
                (centersY[i - 1] + centersY[i]) * 0.5f, centersX[i], centersY[i])
        }
        paint.shader = android.graphics.LinearGradient(0f, 0f, width.toFloat(), 0f,
            intArrayOf(0x22FF62D2, 0x559C79FF, 0x553FCBFF, 0x2244F2D2), null,
            android.graphics.Shader.TileMode.CLAMP)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5f * density
        paint.alpha = 65
        paint.maskFilter = BlurMaskFilter(7f * density, BlurMaskFilter.Blur.NORMAL)
        canvas.drawPath(path, paint)
        paint.maskFilter = null
        paint.strokeWidth = 1.1f * density
        paint.alpha = 115
        canvas.drawPath(path, paint)
        paint.shader = null
        paint.style = Paint.Style.FILL
        for (i in 0 until count) {
            val energy = pos[i].coerceIn(0f, 1.05f)
            val pulse = 1f + 0.10f * sin(time * 2.8f + i * 0.61f) + energy * 0.20f
            val radius = radii[i] * pulse
            val cx = centersX[i]
            val cy = centersY[i]
            val c = physicalColor(i)
            // 多层柔光 + 径向渐变主体 + 顶部高光，形成有纵深的动态玻璃光球。
            paint.color = c
            paint.alpha = (24 + energy * 34f).toInt()
            paint.maskFilter = BlurMaskFilter(radius * 2.5f, BlurMaskFilter.Blur.NORMAL)
            canvas.drawCircle(cx, cy, radius * 2.15f, paint)
            paint.maskFilter = null
            paint.shader = android.graphics.RadialGradient(cx - radius * 0.28f, cy - radius * 0.32f,
                radius * 1.65f, intArrayOf(0xFFF8FDFF.toInt(), c, (c and 0x00FFFFFF) or 0x33000000),
                floatArrayOf(0f, 0.42f, 1f), android.graphics.Shader.TileMode.CLAMP)
            paint.alpha = (205 + energy * 45f).toInt().coerceIn(205, 250)
            canvas.drawCircle(cx, cy, radius, paint)
            paint.shader = null
            paint.color = Color.WHITE
            paint.alpha = 125
            canvas.drawCircle(cx - radius * 0.28f, cy - radius * 0.34f, radius * 0.22f, paint)
        }
        paint.alpha = 255
        paint.maskFilter = null
        paint.shader = null
        drawParticleField(canvas, mid, height * 0.20f, 0.95f)
    }

    /** 低成本粒子层：相位确定、无逐帧随机数和对象队列，跟随频带能量轻微漂移。 */
    private fun drawParticleField(canvas: Canvas, centerY: Float, spreadY: Float, strength: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        for (i in particlePhases.indices) {
            val phase = particlePhases[i]
            val drift = time * (0.018f + (i % 5) * 0.006f)
            val u = ((i * 0.6180339f + drift) % 1f)
            val x = w * (0.035f + u * 0.93f)
            val band = (u * (smoothFft.size - 1)).toInt().coerceIn(0, smoothFft.lastIndex)
            val energy = smoothFft[band]
            val y = centerY + sin(time * (0.72f + (i % 4) * 0.13f) + phase) * spreadY *
                (0.30f + energy * 0.75f)
            val radius = (0.65f + (i % 4) * 0.34f + energy * 1.2f) * density
            val alpha = ((22f + energy * 155f) * strength).toInt().coerceIn(12, 175)
            paint.color = spectrumColor(band, time * 8f)
            paint.alpha = alpha
            if (energy > 0.12f && i % 3 == 0) {
                paint.strokeWidth = (0.7f + energy * 1.1f) * density
                paint.style = Paint.Style.STROKE
                canvas.drawLine(x, y, x - sin(phase) * radius * 2.8f, y - radius * 2.2f, paint)
                paint.style = Paint.Style.FILL
            }
            canvas.drawCircle(x, y, radius, paint)
        }
        paint.alpha = 255
        paint.style = Paint.Style.FILL
    }

    /** 融合模式中央的低亮度能量核心，把上下两层视觉连接起来。 */
    private fun drawFusionCore(canvas: Canvas) {
        val cx = width * 0.5f
        val cy = height * 0.52f
        val energy = (smoothFft.average().toFloat() * 0.7f + waveRms * 1.8f).coerceIn(0f, 1f)
        val radius = (3.5f + energy * 8f + sin(time * 1.8f) * 0.8f) * density
        paint.shader = android.graphics.RadialGradient(cx, cy, radius * 3.2f,
            intArrayOf(0xAAE8F8FF.toInt(), 0x558D7BFF, 0x0045CFFF), null,
            android.graphics.Shader.TileMode.CLAMP)
        paint.alpha = (95 + energy * 90f).toInt().coerceIn(95, 185)
        canvas.drawCircle(cx, cy, radius * 3.2f, paint)
        paint.shader = null
        paint.color = Color.WHITE
        paint.alpha = (90 + energy * 130f).toInt().coerceIn(90, 220)
        canvas.drawCircle(cx, cy, radius * 0.34f, paint)
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
