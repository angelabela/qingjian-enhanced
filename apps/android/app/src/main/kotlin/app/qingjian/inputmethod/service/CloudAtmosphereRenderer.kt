package app.qingjian.inputmethod.service

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
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
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hazePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var palette = paletteFor(Theme.DAY)
    private var activeTheme: Theme? = null
    private var bakeVersion = 0
    private var time = 0.0
    private var lastWidth = 0
    private var lastHeight = 0

    fun draw(canvas: Canvas, width: Float, height: Float, seconds: Double, theme: Theme) {
        if (width <= 0f || height <= 0f) return
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
        canvas.drawRect(0f, 0f, width, height, glowPaint)
        canvas.drawRect(0f, 0f, width, height, corePaint)
        drawLayer(canvas, cirrus, width, height)
        drawLayer(canvas, near, width, height)
        if (palette.rayAlpha > 0f) {
            val sx = palette.sunX * width
            val sy = palette.sunY * height
            val len = hypot(width, height) * 1.2f
            for (k in 0..3) {
                val angle = Math.toRadians((palette.rayAngle + (k - 1.5f) * 14f).toDouble())
                val half = .035 + .015 * k
                val path = android.graphics.Path().apply {
                    moveTo(sx, sy)
                    lineTo(sx + len * cos(angle - half).toFloat(), sy + len * sin(angle - half).toFloat())
                    lineTo(sx + len * cos(angle + half).toFloat(), sy + len * sin(angle + half).toFloat())
                    close()
                }
                val breathe = .5 + .5 * sin(time * .35 + k * 1.7)
                rayPaint.alpha = (palette.rayAlpha * 255f * breathe).toInt().coerceIn(0, 255)
                rayPaint.shader = LinearGradient(
                    sx, sy, sx + len * cos(angle).toFloat(), sy + len * sin(angle).toFloat(),
                    palette.sun, palette.sun and 0x00FFFFFF, Shader.TileMode.CLAMP,
                )
                canvas.drawPath(path, rayPaint)
            }
        }
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
        glowPaint.shader = RadialGradient(sx, sy, max(w, h) * .75f,
            withAlpha(p.sun, (p.glow * .55f * 255f).toInt()), withAlpha(p.sun, 0), Shader.TileMode.CLAMP)
        corePaint.shader = RadialGradient(sx, sy, min(w, h) * .14f,
            withAlpha(p.sun, (p.glow * .9f * 255f).toInt()), withAlpha(p.sun, 0), Shader.TileMode.CLAMP)
        hazePaint.shader = LinearGradient(0f, h * .6f, 0f, h,
            withAlpha(p.haze, 0), withAlpha(p.haze, 150), Shader.TileMode.CLAMP)
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
            val nearBitmap = bakeCumulus(DENSITY_CUMULUS, p, 0f)
            val farBitmap = bakeCumulus(DENSITY_CUMULUS, p, .55f)
            val cirrusBitmap = bakeCirrus(DENSITY_CIRRUS, p)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (version != bakeVersion || activeTheme != theme) {
                    nearBitmap.recycle(); farBitmap.recycle(); cirrusBitmap.recycle()
                    return@post
                }
                fun tile(bitmap: Bitmap) = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                near.shader = tile(nearBitmap)
                far.shader = tile(farBitmap)
                cirrus.shader = tile(cirrusBitmap)
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

    private fun paletteFor(theme: Theme) = when (theme) {
        Theme.DAY -> Palette(0xFF3A7BD9.toInt(),0xFF7DB5F0.toInt(),0xFFDCEEFF.toInt(),0xFFEAF4FF.toInt(),
            0xFFFFF2CC.toInt(),.84f,.05f,.70f,ColorWhite,0xFF93AACB.toInt(),.6f,-.8f,.56f,.74f,1.5f,.55f,.16f,118f)
        Theme.SUNSET -> Palette(0xFF2A2C6E.toInt(),0xFFA8508A.toInt(),0xFFFFB07A.toInt(),0xFFFFC7A0.toInt(),
            0xFFFFD28A.toInt(),.22f,.82f,.95f,0xFFFFE2C8.toInt(),0xFF5B3A70.toInt(),-.7f,.7f,.52f,.72f,1.5f,.70f,.22f,-55f)
        Theme.NIGHT -> Palette(0xFF10182F.toInt(),0xFF26395F.toInt(),0xFF596B8D.toInt(),0xFF7F91B0.toInt(),
            0xFFB8C9F0.toInt(),.78f,.16f,.28f,0xFFDDE7FF.toInt(),0xFF17233F.toInt(),.3f,-.9f,.40f,.59f,1.55f,.82f,.04f,125f)
        Theme.BEAM -> Palette(0xFF254F86.toInt(),0xFF79A9D8.toInt(),0xFFDCEBFA.toInt(),0xFFEAF4FF.toInt(),
            0xFFFFF6D8.toInt(),.70f,.18f,.82f,ColorWhite,0xFF7189AA.toInt(),-.5f,-.8f,.50f,.70f,1.55f,.58f,.24f,112f)
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
