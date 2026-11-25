package app.qingjian.inputmethod.service

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ComposeShader
import android.graphics.PorterDuff
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Canvas/BitmapShader cloud renderer adapted from the supplied CloudAtmosphereView.
 * Texture generation runs off the UI thread; animation only translates cached seamless tiles.
 */
internal class CloudAtmosphereRenderer(
    private val density: Float,
    private val invalidate: () -> Unit,
) {
    enum class Theme { DAY, SUNSET, NIGHT, BEAM }

    private data class Palette(
        val top: Int, val mid: Int, val horizon: Int, val haze: Int, val sun: Int,
        val sunX: Float, val sunY: Float, val glow: Float, val lit: Int, val shadow: Int,
        val lightX: Float, val lightY: Float, val lo: Float, val hi: Float,
        val contrast: Float, val coreDark: Float, val rayAlpha: Float, val rayAngle: Float,
        val sunRadius: Float = .038f, val rayWidth: Float = .14f,
        val rayLength: Float = .90f, val rayCount: Int = 5, val raySpread: Float = 30f,
    )

    private data class Layer(
        val scale: Float, val sy: Float, val speed: Float, val y: Float, val alpha: Int,
        val paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        var shader: BitmapShader? = null,
    )

    private val far = Layer(1.15f, .50f, 3f, .38f, 235)
    private val cirrus = Layer(2.2f, 1f, 5f, .05f, 150)
    private val near = Layer(2.6f, 1f, 12f, 0f, 245)
    private val matrix = Matrix()
    private val skyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sunDiscPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sunCorePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hazePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private data class RayBeam(val angle: Float, val halfWidth: Float, val length: Float, val paint: Paint)
    private val rayBeams = ArrayList<RayBeam>()
    private var palette = paletteFor(Theme.DAY)
    private var activeTheme: Theme? = null
    @Volatile private var bakeVersion = 0
    @Volatile private var active = true
    // Keep explicit references so replacing a theme releases the previous texture set.
    private var cachedNearBitmap: Bitmap? = null
    private var cachedFarBitmap: Bitmap? = null
    private var cachedCirrusBitmap: Bitmap? = null
    private var time = 0.0
    private var lastWidth = 0
    private var lastHeight = 0

    /** Pause texture generation while the owning drawable is hidden; release cached references safely. */
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        bakeVersion++
        activeTheme = null
        if (!value) {
            near.shader = null
            far.shader = null
            cirrus.shader = null
            near.paint.shader = null
            far.paint.shader = null
            cirrus.paint.shader = null
            // Drop references rather than force-recycling bitmaps that a hardware Canvas may still be uploading.
            cachedNearBitmap = null
            cachedFarBitmap = null
            cachedCirrusBitmap = null
        } else {
            invalidate()
        }
    }

    fun draw(canvas: Canvas, width: Float, height: Float, seconds: Double, theme: Theme) {
        if (!active || width <= 0f || height <= 0f) return
        time = seconds
        if (activeTheme != theme) {
            activeTheme = theme
            palette = paletteFor(theme)
            buildSky(width, height)
            bakeAsync(theme, palette)
        } else if (lastWidth != width.toInt() || lastHeight != height.toInt()) {
            buildSky(width, height)
        }
        canvas.drawRect(0f, 0f, width, height, skyPaint)
        drawLayer(canvas, far, width, height)
        canvas.drawRect(0f, 0f, width, height * .55f, farFadePaint)

        // 太阳的泛光与光盘先画在云层后方，近景云会自然遮挡光线。
        canvas.drawRect(0f, 0f, width, height, glowPaint)
        val sunX = palette.sunX * width
        val sunY = palette.sunY * height
        val sunRadius = min(width, height) * palette.sunRadius
        canvas.drawCircle(sunX, sunY, sunRadius * 1.8f, sunDiscPaint)
        canvas.drawCircle(sunX, sunY, sunRadius * .72f, sunCorePaint)

        drawLayer(canvas, cirrus, width, height)
        // 体积光束使用横向羽化 + 纵向衰减的双渐变，不再绘制硬边三角形。
        rayBeams.forEachIndexed { index, beam ->
            val breathe = .78f + .22f * sin((time * .32 + index * 1.13).toFloat())
            beam.paint.alpha = (palette.rayAlpha * 255f * breathe).toInt().coerceIn(0, 255)
            canvas.save()
            canvas.translate(sunX, sunY)
            canvas.rotate(beam.angle)
            canvas.drawRect(-beam.halfWidth, 0f, beam.halfWidth, beam.length, beam.paint)
            canvas.restore()
        }
        // 近景云层最后绘制，遮住部分光束，形成光从云隙中穿出的层次。
        drawLayer(canvas, near, width, height)
        canvas.drawRect(0f, height * .60f, width, height, hazePaint)
    }

    private val farFadePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun buildSky(w: Float, h: Float) {
        lastWidth = w.toInt()
        lastHeight = h.toInt()
        val p = palette
        val sx = p.sunX * w
        val sy = p.sunY * h
        skyPaint.shader = LinearGradient(0f, 0f, 0f, h,
            intArrayOf(p.top, p.mid, p.horizon), floatArrayOf(0f, .7f, 1f), Shader.TileMode.CLAMP)
        val fadeEnd = mix(p.top, p.mid, .55f / .7f)
        farFadePaint.shader = LinearGradient(0f, 0f, 0f, h * .55f, p.top, fadeEnd and 0x00FFFFFF, Shader.TileMode.CLAMP)
        val haloRadius = max(w, h) * .58f
        glowPaint.shader = RadialGradient(
            sx, sy, haloRadius,
            intArrayOf(
                withAlpha(p.sun, (p.glow * .30f * 255f).toInt()),
                withAlpha(p.sun, (p.glow * .13f * 255f).toInt()),
                withAlpha(p.sun, 0),
            ),
            floatArrayOf(0f, .28f, 1f), Shader.TileMode.CLAMP,
        )
        val sunRadius = min(w, h) * p.sunRadius
        sunDiscPaint.shader = RadialGradient(
            sx, sy, sunRadius * 1.8f,
            intArrayOf(0xFFFFFFFF.toInt(), p.sun, withAlpha(p.sun, 0)),
            floatArrayOf(0f, .52f, 1f), Shader.TileMode.CLAMP,
        )
        sunCorePaint.shader = RadialGradient(
            sx, sy, sunRadius * .85f,
            intArrayOf(0xFFFFFFFF.toInt(), p.sun, withAlpha(p.sun, 0)),
            floatArrayOf(0f, .62f, 1f), Shader.TileMode.CLAMP,
        )
        hazePaint.shader = LinearGradient(0f, h * .6f, 0f, h,
            withAlpha(p.haze, 0), withAlpha(p.haze, 150), Shader.TileMode.CLAMP)

        rayBeams.clear()
        if (p.rayAlpha > 0f && p.rayCount > 0) {
            val beamLength = hypot(w, h) * p.rayLength
            val halfBase = min(w, h) * p.rayWidth
            for (index in 0 until p.rayCount) {
                val centered = index - (p.rayCount - 1) / 2f
                val angle = p.rayAngle + centered * p.raySpread / max(1, p.rayCount - 1)
                val halfWidth = halfBase * if (index % 2 == 0) 1f else .62f
                val crossFade = LinearGradient(
                    -halfWidth, 0f, halfWidth, 0f,
                    intArrayOf(0x00FFFFFF, withAlpha(p.sun, 118), 0x00FFFFFF),
                    floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP,
                )
                val lengthFade = LinearGradient(
                    0f, 0f, 0f, beamLength,
                    intArrayOf(withAlpha(p.sun, 92), withAlpha(p.sun, 52), 0x00FFFFFF),
                    floatArrayOf(0f, .38f, 1f), Shader.TileMode.CLAMP,
                )
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = ComposeShader(crossFade, lengthFade, PorterDuff.Mode.MULTIPLY)
                }
                rayBeams += RayBeam(angle, halfWidth, beamLength, paint)
            }
        }
        layoutLayers(h)
    }

    private fun drawLayer(canvas: Canvas, layer: Layer, w: Float, h: Float) {
        val shader = layer.shader ?: return
        val tile = h * layer.scale
        val ox = ((time * layer.speed * density) % tile).toFloat()
        val oy = h * layer.y
        canvas.save()
        canvas.translate(ox, oy)
        canvas.drawRect(-ox, -oy, w - ox, h - oy, layer.paint)
        canvas.restore()
    }

    private fun bakeAsync(theme: Theme, p: Palette) {
        val version = ++bakeVersion
        Thread({
            var nearBitmap: Bitmap? = null
            var farBitmap: Bitmap? = null
            var cirrusBitmap: Bitmap? = null
            try {
                val near = bakeCumulus(DENSITY_CUMULUS, p, 0f)
                nearBitmap = near
                if (!active || version != bakeVersion) {
                    near.recycle()
                    return@Thread
                }
                val far = bakeCumulus(DENSITY_CUMULUS, p, .55f)
                farBitmap = far
                if (!active || version != bakeVersion) {
                    near.recycle()
                    far.recycle()
                    return@Thread
                }
                val cirrus = bakeCirrus(DENSITY_CIRRUS, p)
                cirrusBitmap = cirrus
                if (!active || version != bakeVersion) {
                    near.recycle(); far.recycle(); cirrus.recycle()
                    return@Thread
                }
            } catch (error: RuntimeException) {
                nearBitmap?.recycle()
                farBitmap?.recycle()
                cirrusBitmap?.recycle()
                return@Thread
            }
            val readyNear = nearBitmap ?: return@Thread
            val readyFar = farBitmap ?: run { readyNear.recycle(); return@Thread }
            val readyCirrus = cirrusBitmap ?: run { readyNear.recycle(); readyFar.recycle(); return@Thread }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (!active || version != bakeVersion || activeTheme != theme) {
                    readyNear.recycle(); readyFar.recycle(); readyCirrus.recycle()
                    return@post
                }
                fun tile(bitmap: Bitmap) = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                // Replace all shaders together on the main thread. Old BitmapShader references are
                // left to GC after the renderer drops them; do not recycle during hardware rendering.
                near.shader = tile(readyNear)
                far.shader = tile(readyFar)
                cirrus.shader = tile(readyCirrus)
                cachedNearBitmap = readyNear
                cachedFarBitmap = readyFar
                cachedCirrusBitmap = readyCirrus
                layoutLayers(lastHeight.toFloat())
                invalidate()
            }
        }, "cloud-atmosphere-bake").start()
    }

    private fun layoutLayers(h: Float) {
        if (h <= 0f) return
        listOf(far, cirrus, near).forEach { layer ->
            val shader = layer.shader ?: return@forEach
            val scale = h * layer.scale / TEX
            matrix.setScale(scale, scale * layer.sy)
            shader.setLocalMatrix(matrix)
            layer.paint.shader = shader
            layer.paint.alpha = layer.alpha
        }
    }

    private fun bakeCumulus(d: FloatArray, p: Palette, farAmount: Float): Bitmap {
        val lit = mix(p.lit, p.haze, farAmount)
        val shadow = mix(p.shadow, p.haze, farAmount)
        val ax = (p.lightX * 5f).roundToInt()
        val ay = (p.lightY * 5f).roundToInt()
        val bx = (p.lightX * 16f).roundToInt()
        val by = (p.lightY * 16f).roundToInt()
        val pixels = IntArray(TEX * TEX)
        for (y in 0 until TEX) for (x in 0 until TEX) {
            val c = contrast(d[y * TEX + x], p.contrast)
            val mask = smoothstep(p.lo, p.hi, c)
            if (mask <= .003f) continue
            val c1 = contrast(at(d, x + ax, y + ay), p.contrast)
            val c2 = contrast(at(d, x + bx, y + by), p.contrast)
            val shade = (.58f + (c - c1) * 3.2f + (c - c2) * 1.6f).coerceIn(0f, 1f)
            val depth = smoothstep(p.lo + .06f, p.hi + .20f, c)
            var color = mix(shadow, lit, shade)
            color = mix(color, shadow, depth * p.coreDark * (1f - shade * .6f))
            color = mix(color, lit, (1f - depth) * mask * .45f)
            pixels[y * TEX + x] = ((mask * 255f + .5f).toInt() shl 24) or (color and 0x00FFFFFF)
        }
        return Bitmap.createBitmap(pixels, TEX, TEX, Bitmap.Config.ARGB_8888)
    }

    private fun bakeCirrus(d: FloatArray, p: Palette): Bitmap {
        val color = mix(p.lit, p.haze, .2f) and 0x00FFFFFF
        val pixels = IntArray(TEX * TEX)
        for (i in pixels.indices) {
            val c = contrast(d[i], 1.8f)
            val mask = smoothstep(.54f, .80f, c) * .8f
            pixels[i] = ((mask * 255f + .5f).toInt() shl 24) or color
        }
        return Bitmap.createBitmap(pixels, TEX, TEX, Bitmap.Config.ARGB_8888)
    }

    private fun paletteFor(theme: Theme): Palette = when (theme) {
        // 晴空云海：高位偏右的冷白太阳，光束细而克制。
        Theme.DAY -> Palette(
            0xFF3A7BD9.toInt(), 0xFF7DB5F0.toInt(), 0xFFDCEEFF.toInt(), 0xFFEAF4FF.toInt(),
            0xFFFFF2CC.toInt(), .84f, .10f, .78f, ColorWhite, 0xFF93AACB.toInt(),
            .6f, -.8f, .56f, .74f, 1.5f, .55f, .13f, 118f,
            sunRadius = .036f, rayWidth = .105f, rayLength = .88f, rayCount = 5, raySpread = 34f,
        )
        // 暮色云层：贴近地平线的暖色太阳，云底受橙金色反射，光束向下铺开。
        Theme.SUNSET -> Palette(
            0xFF2A2C6E.toInt(), 0xFFA8508A.toInt(), 0xFFFFB07A.toInt(), 0xFFFFC7A0.toInt(),
            0xFFFFD28A.toInt(), .22f, .79f, .98f, 0xFFFFE2C8.toInt(), 0xFF5B3A70.toInt(),
            -.7f, .7f, .52f, .72f, 1.5f, .70f, .20f, -55f,
            sunRadius = .062f, rayWidth = .16f, rayLength = .70f, rayCount = 6, raySpread = 42f,
        )
        // 月夜流云：冷月柔和漫反射，不画太阳直射光束。
        Theme.NIGHT -> Palette(
            0xFF10182F.toInt(), 0xFF26395F.toInt(), 0xFF596B8D.toInt(), 0xFF7F91B0.toInt(),
            0xFFB8C9F0.toInt(), .78f, .16f, .42f, 0xFFDDE7FF.toInt(), 0xFF17233F.toInt(),
            .3f, -.9f, .40f, .59f, 1.55f, .82f, 0f, 125f,
            sunRadius = .032f, rayWidth = .08f, rayLength = .5f, rayCount = 0, raySpread = 0f,
        )
        // 光斑穿云：偏侧上方的金白光源，多束宽窄不同的柔光从云隙穿出。
        Theme.BEAM -> Palette(
            0xFF254F86.toInt(), 0xFF79A9D8.toInt(), 0xFFDCEBFA.toInt(), 0xFFEAF4FF.toInt(),
            0xFFFFF6D8.toInt(), .70f, .20f, .92f, ColorWhite, 0xFF7189AA.toInt(),
            -.5f, -.8f, .50f, .70f, 1.55f, .58f, .27f, 112f,
            sunRadius = .044f, rayWidth = .19f, rayLength = 1.02f, rayCount = 7, raySpread = 58f,
        )
    }

    private fun smoothstep(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
    private fun contrast(v: Float, k: Float) = .5f + (v - .5f) * k
    private fun withAlpha(color: Int, alpha: Int) = (alpha.coerceIn(0,255) shl 24) or (color and 0x00FFFFFF)
    private fun mix(a: Int, b: Int, amount: Float): Int {
        val t = amount.coerceIn(0f,1f)
        fun ch(s: Int) = (((a shr s) and 255) * (1f-t) + ((b shr s) and 255) * t + .5f).toInt()
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
    private fun at(data: FloatArray, x: Int, y: Int) = data[Math.floorMod(y,TEX) * TEX + Math.floorMod(x,TEX)]

    companion object {
        private const val TEX = 512
        private const val ColorWhite = 0xFFFFFFFF.toInt()
        private val DENSITY_CUMULUS by lazy { fbm(3,3,6,7L) }
        private val DENSITY_CIRRUS by lazy { fbm(2,7,5,99L) }

        private fun fbm(cx0: Int, cy0: Int, octaves: Int, seed: Long): FloatArray {
            val density = FloatArray(TEX * TEX)
            val ix = IntArray(TEX)
            val tx = FloatArray(TEX)
            var amplitude = .5f
            var total = 0f
            for (octave in 0 until octaves) {
                val cx = cx0 shl octave
                val cy = cy0 shl octave
                val random = Random(seed + octave)
                val lattice = FloatArray(cx * cy) { random.nextFloat() }
                for (x in 0 until TEX) {
                    val fx = x * cx / TEX.toFloat()
                    val i = fx.toInt()
                    val t = fx - i
                    ix[x] = i
                    tx[x] = t * t * (3f - 2f * t)
                }
                for (y in 0 until TEX) {
                    val fy = y * cy / TEX.toFloat()
                    val j = fy.toInt()
                    val t = fy - j
                    val ty = t * t * (3f - 2f * t)
                    val row0 = j * cx
                    val row1 = ((j + 1) % cy) * cx
                    for (x in 0 until TEX) {
                        val i0 = ix[x]
                        val i1 = (i0 + 1) % cx
                        val a = lattice[row0 + i0]
                        val b = lattice[row0 + i1]
                        val c = lattice[row1 + i0]
                        val d = lattice[row1 + i1]
                        val top = a + (b-a) * tx[x]
                        val bottom = c + (d-c) * tx[x]
                        density[y * TEX + x] += amplitude * (top + (bottom-top) * ty)
                    }
                }
                total += amplitude
                amplitude *= .5f
            }
            for (i in density.indices) density[i] /= total
            return density
        }
    }
}
