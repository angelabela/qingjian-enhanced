package app.qingjian.inputmethod.panel

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import org.json.JSONArray
import kotlin.math.*

/**
 * 复刻录屏里的居中发光频谱：65 根竖条、左右镜像、静止时是一排小圆点，
 * 说完后收拢成一根竖光标。尺寸全部以录屏的 720px 宽为基准量得，随视图宽度等比缩放。
 *
 * 用法：
 *   spectrum.feedPcm(pcm, sampleRate)        // 实时音频，建议每 ~30ms 喂一次最近的采样
 *   spectrum.playRecording(parseRecording(json))   // 回放从视频里量出的真实高度，用来对照效果
 *   spectrum.collapse()                      // 说完后收拢成光标
 *
 * 发光用 BlurMaskFilter：API 28 以下自动切到软件图层。
 * 背景是深色时 additive = true（加色混合，和录屏一致）；浅色背景请设成 false。
 */
class SpectrumView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // ───────── 可改的颜色 ─────────
    var lowColor = Color.rgb(235, 120, 40)       // 低矮的条
    var highColor = Color.rgb(250, 175, 90)      // 高的条（偏亮）
    var glowNearColor = Color.rgb(200, 212, 123) // 近处光晕
    var glowFarColor = Color.rgb(243, 90, 20)    // 远处光晕（画两遍，等于 R=486）
    var additive = true

    // ───────── 音频映射与平滑（估计值，按手感调）─────────
    var floorDb = -72f
    var rangeDb = 50f
    var attack = 0.58f     // 稍微加快上升响应，并略微提高低音量拾取灵敏度
    var release = 0.22f    // 每 33ms 一步，下降

    private val hRef = FloatArray(BARS) { REST_H }   // 单位：720px 基准下的像素
    private val level = FloatArray(BARS)
    private val target = FloatArray(BARS)
    private val raw = FloatArray(BARS)
    private var lastTick = 0L
    private var recording: Array<IntArray>? = null
    private var recStart = 0L
    private var recLoop = false
    private var collapseP = 0f
    private var running = true

    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowNear = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowFar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    private val fftRe = FloatArray(FFT_N)
    private val fftIm = FloatArray(FFT_N)
    private val window = FloatArray(FFT_N) { (0.5 - 0.5 * cos(2.0 * PI * it / (FFT_N - 1))).toFloat() }

    init {
        if (Build.VERSION.SDK_INT < 28) setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    // ───────── 对外接口 ─────────

    fun feedBands(bands: FloatArray) {
        recording = null
        val half = BARS / 2
        for (d in 0..half) {
            val index = (d * bands.size / (half + 1)).coerceIn(0, bands.lastIndex.coerceAtLeast(0))
            val value = bands.getOrElse(index) { 0f }.coerceIn(0f, 1f)
            raw[half + d] = value
            raw[half - d] = value
        }
        for (i in 0 until BARS) {
            val a = raw[max(0, i - 1)]
            val c = raw[min(BARS - 1, i + 1)]
            target[i] = 0.25f * a + 0.5f * raw[i] + 0.25f * c
        }
        resetCollapse()
        postInvalidateOnAnimation()
    }
    fun feedPcm(pcm: ShortArray, sampleRate: Int) {
        recording = null
        val start = max(0, pcm.size - FFT_N)
        for (i in 0 until FFT_N) {
            val j = start + i
            fftRe[i] = if (j < pcm.size) pcm[j] / 32768f * window[i] else 0f
            fftIm[i] = 0f
        }
        fft(fftRe, fftIm)
        val half = BARS / 2
        val binHz = sampleRate.toFloat() / FFT_N
        for (d in 0..half) {
            // 中心放低频，往两边频率升高（对数分布）；左右镜像
            val f = F_MIN * (F_MAX / F_MIN).pow(d / half.toFloat())
            val lo = max(1, (f / 1.12f / binHz).toInt())
            val hi = min(FFT_N / 2 - 1, max(lo, (f * 1.12f / binHz).toInt()))
            var m = 0f
            for (b in lo..hi) m = max(m, hypot(fftRe[b], fftIm[b]))
            val db = 20f * log10(m / (FFT_N / 4f) + 1e-6f)
            val v = ((db - floorDb) / rangeDb).coerceIn(0f, 1f)
            raw[half + d] = v
            raw[half - d] = v
        }
        for (i in 0 until BARS) {  // 3 点空间平滑：相邻条高度接近，和视频一致
            val a = raw[max(0, i - 1)]
            val c = raw[min(BARS - 1, i + 1)]
            target[i] = 0.25f * a + 0.5f * raw[i] + 0.25f * c
        }
        postInvalidateOnAnimation()
    }

    /** 回放视频里量出的高度（60fps，每帧 65 个值，720px 基准）。 */
    fun playRecording(frames: Array<IntArray>, loop: Boolean = false) {
        recording = frames; recLoop = loop; recStart = SystemClock.uptimeMillis()
        collapseP = 0f
        postInvalidateOnAnimation()
    }

    fun collapse(durationMs: Long = 400) {
        ValueAnimator.ofFloat(collapseP, 1f).apply {
            duration = durationMs
            addUpdateListener { collapseP = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun resetCollapse() { collapseP = 0f; invalidate() }
    fun start() { running = true; postInvalidateOnAnimation() }
    fun stop() { running = false }

    // ───────── 绘制 ─────────

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        setMeasuredDimension(width, resolveSize((REF_H * width / REF_W).toInt(), h))
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val s = w / REF_W
        glowNear.maskFilter = BlurMaskFilter(max(1f, radius(6.5f * s)), BlurMaskFilter.Blur.NORMAL)
        glowFar.maskFilter = BlurMaskFilter(max(1f, radius(28f * s)), BlurMaskFilter.Blur.NORMAL)
    }

    override fun onDraw(canvas: Canvas) {
        val s = width / REF_W
        advance()
        val mid = BARS / 2
        val cx = width / 2f
        val cy = height / 2f
        val e1 = ease(min(1f, collapseP / 0.5f))                  // 先压成小圆点
        val e2 = ease(max(0f, (collapseP - 0.5f) / 0.5f))         // 再长成光标
        val span = SPAN * s * (1f - ease(max(0f, (collapseP - 0.35f) / 0.65f)))

        setGlowBlend()
        path.rewind()
        for (i in 0 until BARS) {
            var h = lerp(hRef[i], REST_H, e1)
            var a = 1f
            if (i == mid) h = lerp(h, CURSOR_H, e2) else a = 1f - e2
            if (a <= 0.02f) continue
            val x = cx + (i - mid) * span / (BARS - 1)
            val hp = h * s
            val half = max(0.75f, BAR_W * s / 2f)
            rect.set(x - half, cy - hp / 2f, x + half, cy + hp / 2f)
            core.color = barColor(h)
            core.alpha = (a * (if (h < 3f) 140 else 255)).toInt()
            canvas.drawRoundRect(rect, half, half, core)
            path.addRoundRect(rect, half, half, Path.Direction.CW)
        }
        canvas.drawPath(path, glowFar)
        canvas.drawPath(path, glowFar)
        canvas.drawPath(path, glowNear)

        if (running) postInvalidateOnAnimation()
    }

    // ───────── 内部 ─────────

    private fun setGlowBlend() {
        glowNear.color = glowNearColor
        glowFar.color = glowFarColor
        for (p in arrayOf(glowNear, glowFar)) {
            if (additive) {
                if (Build.VERSION.SDK_INT >= 29) p.blendMode = BlendMode.PLUS
                else p.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
            } else {
                if (Build.VERSION.SDK_INT >= 29) p.blendMode = null else p.xfermode = null
                p.alpha = 90
            }
        }
    }

    private fun advance() {
        val now = SystemClock.uptimeMillis()
        val rec = recording
        if (rec != null) {
            var idx = ((now - recStart) * 60 / 1000).toInt()
            if (idx >= rec.size) {
                if (recLoop) { recStart = now; idx = 0 } else idx = rec.size - 1
            }
            val f = rec[idx]
            for (i in 0 until BARS) hRef[i] = max(REST_H, f[i].toFloat())
            return
        }
        if (lastTick == 0L) lastTick = now
        var n = 0
        while (now - lastTick >= TICK_MS && n < 3) { step(); lastTick += TICK_MS; n++ }
        if (now - lastTick >= TICK_MS) lastTick = now
    }

    private fun step() {      // 录屏里数据约 30Hz 更新，每次更新后保持两帧
        for (i in 0 until BARS) {
            val t = target[i]
            level[i] += (t - level[i]) * (if (t > level[i]) attack else release)
            hRef[i] = REST_H + (MAX_H - REST_H) * level[i]
        }
    }

    private fun barColor(h: Float): Int {
        val k = ((h - 6f) / 46f).coerceIn(0f, 1f)
        return Color.rgb(
            lerp(Color.red(lowColor).toFloat(), Color.red(highColor).toFloat(), k).toInt(),
            lerp(Color.green(lowColor).toFloat(), Color.green(highColor).toFloat(), k).toInt(),
            lerp(Color.blue(lowColor).toFloat(), Color.blue(highColor).toFloat(), k).toInt()
        )
    }

    private fun radius(sigma: Float) = (sigma - 0.5f) / 0.57735f     // Skia: σ = 0.57735·r + 0.5
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun ease(x: Float) = x * x * (3f - 2f * x)

    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f; var ci = 0f
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    companion object {
        const val BARS = 65
        private const val REF_W = 720f     // 录屏宽度
        private const val REF_H = 260f     // 频谱区域高度（含光晕），随面板宽度按比例放大
        private const val SPAN = 576f      // 首尾两根条的中心距，约占720基准宽度的80%
        private const val BAR_W = 3f
        private const val REST_H = 2f
        private const val MAX_H = 108f
        private const val CURSOR_H = 26f
        private const val TICK_MS = 33L
        private const val FFT_N = 1024
        private const val F_MIN = 80f
        private const val F_MAX = 6000f

        fun parseRecording(json: String): Array<IntArray> {
            val a = JSONArray(json)
            return Array(a.length()) { f ->
                val row = a.getJSONArray(f)
                IntArray(row.length()) { row.getInt(it) }
            }
        }
    }
}