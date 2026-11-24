package app.qingjian.inputmethod.service

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Outline
import android.graphics.Shader
import android.graphics.LinearGradient
import android.graphics.RadialGradient
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import app.qingjian.inputmethod.candidates.CandidateBarView
import app.qingjian.inputmethod.mascot.FloatingMascotService
import app.qingjian.inputmethod.candidates.CandidateGrid
import app.qingjian.inputmethod.candidates.CloudSuggestionsView
import app.qingjian.inputmethod.cloud.AiChatGenerator
import app.qingjian.inputmethod.cloud.AiExpressionGroup
import app.qingjian.inputmethod.candidates.StripSource
import app.qingjian.inputmethod.core.frame.Candidate
import app.qingjian.inputmethod.core.AppLog
import app.qingjian.inputmethod.core.store.ClipboardStore
import app.qingjian.inputmethod.keyboard.BottomActionsView
import app.qingjian.inputmethod.keyboard.KeyboardView
import app.qingjian.inputmethod.panel.AiChatPanel
import app.qingjian.inputmethod.panel.AiExpressionPanel
import app.qingjian.inputmethod.panel.CatalogPanel
import app.qingjian.inputmethod.panel.ContextToolsPanel
import app.qingjian.inputmethod.panel.FunctionPanel
import app.qingjian.inputmethod.panel.DynamicBackgroundPanel
import app.qingjian.inputmethod.panel.KeyEffectsPanel
import app.qingjian.inputmethod.background.DynamicBackgroundActivity
import app.qingjian.inputmethod.panel.KeyboardPickerPanel
import app.qingjian.inputmethod.panel.PanelActions
import app.qingjian.inputmethod.panel.PanelKind
import app.qingjian.inputmethod.panel.ToolbarPanel
import app.qingjian.inputmethod.panel.VoicePanel
import app.qingjian.inputmethod.panel.clipboard.ClipboardPanel
import app.qingjian.inputmethod.panel.clipboard.PiecesPanel
import app.qingjian.inputmethod.panel.editor.FloatingEditor
import app.qingjian.inputmethod.settings.BottomAction
import app.qingjian.inputmethod.settings.Settings
import app.qingjian.inputmethod.theme.Palette
import androidx.core.content.ContextCompat

/** 各动态背景的代表性亮度，用于候选文字和全局背景衬层的自适应对比度。 */
private fun backgroundEffectLuminance(style: Int): Float = when (style) {
    9 -> 0.20f
    10 -> 0.19f
    11 -> 0.88f
    12 -> 0.16f
    13 -> 0.15f
    14 -> 0.68f
    15 -> 0.43f
    16 -> 0.12f
    17 -> 0.17f
    18 -> 0.53f
    1, 4, 7, 8 -> 0.24f
    2 -> 0.46f
    else -> 0.22f
}

private fun backgroundEffectTextColor(style: Int): Int =
    if (backgroundEffectLuminance(style) >= 0.56f) Color.BLACK else Color.WHITE

/**
 * 覆盖整块输入法窗口的流动光影背景。键盘、候选工具栏和底部操作条共用同一层，
 * 避免只在 KeyboardView 自己的画布里绘制而导致上下两排仍露出纯主题色。
 */
private class FlowingKeyboardBackgroundDrawable(
    private val palette: Palette,
    private val style: Int,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val motionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val frost = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x18FFFFFF }
    private val matrix = Matrix()
    private var shader: LinearGradient? = null
    private var shaderWidth = 0
    private var shaderHeight = 0
    var onFrame: (() -> Unit)? = null
    private var sampleBitmap: Bitmap? = null
    private val frame: Runnable = object : Runnable {
        override fun run() {
            invalidateSelf()
            onFrame?.invoke()
            scheduleSelf(this, SystemClock.uptimeMillis() + FRAME_DELAY_MS)
        }
    }

    /**
     * 将同一份动态背景以 1/8 分辨率绘制到小位图，再读取工具栏各图标附近的局部亮度。
     * 不调用 PixelCopy、不截图整个窗口；低频复用小位图，避免影响输入响应。
     */
    fun sampleLuminances(xPositions: FloatArray, y: Float): FloatArray {
        val bounds = bounds
        if (bounds.isEmpty || xPositions.isEmpty()) return FloatArray(xPositions.size) { 0.5f }
        val sampleWidth = (bounds.width() / 8).coerceAtLeast(1)
        val sampleHeight = (bounds.height() / 8).coerceAtLeast(1)
        val targetBitmap = sampleBitmap?.takeIf { it.width == sampleWidth && it.height == sampleHeight }
            ?: Bitmap.createBitmap(sampleWidth, sampleHeight, Bitmap.Config.ARGB_8888).also {
                sampleBitmap?.recycle()
                sampleBitmap = it
            }
        targetBitmap.eraseColor(Color.TRANSPARENT)
        val sx = sampleWidth.toFloat() / bounds.width()
        val sy = sampleHeight.toFloat() / bounds.height()
        val sampleCanvas = Canvas(targetBitmap)
        sampleCanvas.scale(sx, sy)
        sampleCanvas.translate(-bounds.left.toFloat(), -bounds.top.toFloat())
        draw(sampleCanvas)

        fun linear(channel: Int): Double {
            val value = channel / 255.0
            return if (value <= 0.04045) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
        }
        return FloatArray(xPositions.size) { index ->
            val px = ((xPositions[index] - bounds.left) * sx).toInt().coerceIn(0, sampleWidth - 1)
            val py = ((y - bounds.top) * sy).toInt().coerceIn(0, sampleHeight - 1)
            var total = 0.0
            var count = 0
            for (dy in -2..2) for (dx in -2..2) {
                val xx = (px + dx).coerceIn(0, sampleWidth - 1)
                val yy = (py + dy).coerceIn(0, sampleHeight - 1)
                val pixel = targetBitmap.getPixel(xx, yy)
                total += 0.2126 * linear(Color.red(pixel)) +
                    0.7152 * linear(Color.green(pixel)) +
                    0.0722 * linear(Color.blue(pixel))
                count++
            }
            (total / count).toFloat().coerceIn(0f, 1f)
        }
    }

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        super.onBoundsChange(bounds)
        val w = bounds.width()
        val h = bounds.height()
        if (w <= 0 || h <= 0) {
            shader = null
            return
        }
        shaderWidth = w
        shaderHeight = h
        shader = LinearGradient(
            -w.toFloat(), 0f, w.toFloat(), h.toFloat(),
            colorsForStyle(style),
            FloatArray(colorsForStyle(style).size) { index ->
                index.toFloat() / (colorsForStyle(style).size - 1).coerceAtLeast(1)
            },
            Shader.TileMode.MIRROR,
        )
        unscheduleSelf(frame)
        scheduleSelf(frame, SystemClock.uptimeMillis() + FRAME_DELAY_MS)
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        val activeShader = shader ?: return
        if (bounds.isEmpty || bounds.width() != shaderWidth || bounds.height() != shaderHeight) return
        // 多个不同周期叠加，连续运动；比上一版明显加快，同时避免短周期机械回跳。
        val now = SystemClock.uptimeMillis()
        fun phase(period: Long, shift: Double = 0.0): Double =
            (now % period).toDouble() / period.toDouble() * (Math.PI * 2.0) + shift
        val p = phase(BACKGROUND_CYCLE_MS)
        val driftX = kotlin.math.sin(p) * 0.66 +
            kotlin.math.sin(phase(117_000L, 1.7)) * 0.23 +
            kotlin.math.sin(phase(49_000L, 0.4)) * 0.09
        val driftY = kotlin.math.sin(phase(96_000L, 2.1)) * 0.16
        val offsetX = (driftX * shaderWidth * 1.18).toFloat()
        val offsetY = (driftY * shaderHeight * 1.18).toFloat()
        matrix.setTranslate(offsetX, offsetY)
        activeShader.setLocalMatrix(matrix)
        paint.shader = activeShader
        paint.alpha = 190
        canvas.drawColor(palette.board)
        canvas.drawRect(bounds, paint)
        paint.shader = null
        canvas.drawRect(bounds, frost)

        // 第1套保留原有极光流彩；其余三套使用不同的运动构图，而非只替换色板。
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        val t = now / 1000.0
        motionPaint.style = Paint.Style.STROKE
        when (style) {
            1 -> { // 深海蓝潮
                for (layer in 0 until 4) {
                    val path = Path()
                    val baseY = h * (0.40f + layer * 0.16f)
                    for (step in 0..48) {
                        val x = w * step / 48f
                        val wave = kotlin.math.sin(x / w * Math.PI * 3.2 + t * (0.72 + layer * 0.09) + layer).toFloat()
                        val swell = kotlin.math.sin(x / w * Math.PI * 1.15 - t * 0.38 + layer * 0.8).toFloat()
                        val y = baseY + wave * h * 0.045f + swell * h * 0.025f
                        if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    motionPaint.color = when (layer % 2) { 0 -> 0x6654E7F2; 1 -> 0x5540A8FF; else -> 0x4478F4D8 }
                    motionPaint.strokeWidth = (2.5f + layer * 1.1f) * (h / 240f).coerceAtLeast(0.7f)
                    motionPaint.style = Paint.Style.STROKE
                    canvas.drawPath(path, motionPaint)
                }
            }
            2 -> { // 日落熔金
                val sunX = w * (0.70f + kotlin.math.sin(t * 0.19).toFloat() * 0.08f)
                val sunY = h * (0.43f + kotlin.math.sin(t * 0.23).toFloat() * 0.055f)
                drawRadialGlow(canvas, sunX, sunY, h * 0.30f, 0x66FFD16A.toInt())
                motionPaint.style = Paint.Style.FILL
                motionPaint.color = 0x66FFB45E
                canvas.drawCircle(sunX, sunY, h * 0.17f, motionPaint)
                motionPaint.color = 0x99FFE0A0.toInt()
                canvas.drawCircle(sunX, sunY, h * 0.105f, motionPaint)
                motionPaint.style = Paint.Style.STROKE
                for (ring in 0..2) {
                    motionPaint.color = (0x36 + ring * 0x0A shl 24) or 0x00FFB86B
                    motionPaint.strokeWidth = 1.5f * (ring + 1)
                    canvas.drawCircle(sunX, sunY, h * (0.21f + ring * 0.075f), motionPaint)
                }
                motionPaint.style = Paint.Style.FILL
                motionPaint.color = 0x30FF8C58
                canvas.drawRect(0f, h * 0.72f, w, h, motionPaint)
            }
            4 -> { // 冰晶星雾
                for (crystal in 0 until 9) {
                    val seed = crystal * 2.399
                    val cx = (w * (0.08 + ((crystal * 37) % 83) / 100.0) + kotlin.math.sin(t * 0.16 + seed) * w * 0.025).toFloat()
                    val cy = (h * (0.12 + ((crystal * 29) % 76) / 100.0) + kotlin.math.cos(t * 0.13 + seed) * h * 0.035).toFloat()
                    val radius = h * (0.025f + (crystal % 3) * 0.012f)
                    motionPaint.color = 0x779DDCFF
                    motionPaint.strokeWidth = 1.2f
                    motionPaint.style = Paint.Style.STROKE
                    val points = ArrayList<Pair<Float, Float>>(6)
                    for (side in 0 until 6) {
                        val a = side * Math.PI / 3.0 + t * 0.12 * if (crystal % 2 == 0) 1 else -1
                        points.add(Pair(cx + kotlin.math.cos(a).toFloat() * radius, cy + kotlin.math.sin(a).toFloat() * radius))
                    }
                    val crystalPath = Path()
                    points.forEachIndexed { index, point ->
                        if (index == 0) crystalPath.moveTo(point.first, point.second) else crystalPath.lineTo(point.first, point.second)
                    }
                    crystalPath.close()
                    canvas.drawPath(crystalPath, motionPaint)
                    for (side in 0 until 3) canvas.drawLine(cx, cy, points[side].first, points[side].second, motionPaint)
                    val twinkle = (0.5 + 0.5 * kotlin.math.sin(t * 1.2 + seed)).toFloat()
                    motionPaint.style = Paint.Style.FILL
                    motionPaint.color = (0x55 + (twinkle * 0x66).toInt() shl 24) or 0x00E7F7FF
                    canvas.drawCircle(cx, cy, radius * (0.12f + twinkle * 0.12f), motionPaint)
                }
            }
            7 -> { // 霓虹光幕
                motionPaint.style = Paint.Style.STROKE
                for (band in 0 until 8) {
                    val path = Path()
                    val phase = t * (0.22 + band * 0.012) + band * 0.83
                    for (step in 0..32) {
                        val u = step / 32f
                        val x = w * u + kotlin.math.sin(u * Math.PI * 2.0 + phase).toFloat() * w * 0.035f
                        val y = h * (0.08f + band * 0.12f) + kotlin.math.sin(u * Math.PI * 2.0 + phase * 0.72).toFloat() * h * 0.075f
                        if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    motionPaint.color = when (band % 3) { 0 -> 0x6654E7F2; 1 -> 0x665F8FFF; else -> 0x66D76BFF }
                    motionPaint.strokeWidth = (3f + band % 3) * (h / 240f).coerceAtLeast(0.7f)
                    canvas.drawPath(path, motionPaint)
                }
            }
            8 -> { // 星环脉冲
                motionPaint.style = Paint.Style.STROKE
                val centers = arrayOf(Pair(w * 0.24f, h * 0.48f), Pair(w * 0.73f, h * 0.56f))
                centers.forEachIndexed { centerIndex, center ->
                    for (ring in 0 until 5) {
                        val phase = ((t * 0.16 + ring * 0.2 + centerIndex * 0.31) % 1.0).toFloat()
                        val radius = h * (0.06f + phase * 0.58f)
                        val alpha = ((1f - phase) * 105f).toInt().coerceIn(0, 105)
                        val rgb = if ((ring + centerIndex) % 2 == 0) 0x005CEBFF else 0x00D17BFF
                        motionPaint.color = (alpha shl 24) or rgb
                        motionPaint.strokeWidth = (1.2f + (1f - phase) * 2.2f) * (h / 240f).coerceAtLeast(0.7f)
                        canvas.drawCircle(center.first, center.second, radius, motionPaint)
                    }
                }
                motionPaint.style = Paint.Style.FILL
                val pulse = (0.5 + 0.5 * kotlin.math.sin(t * 1.15)).toFloat()
                motionPaint.color = 0x6677DFFF
                canvas.drawCircle(w * 0.24f, h * 0.48f, h * (0.035f + pulse * 0.018f), motionPaint)
                motionPaint.color = 0x66D17BFF
                canvas.drawCircle(w * 0.73f, h * 0.56f, h * (0.035f + (1f - pulse) * 0.018f), motionPaint)
            }
            in 9..18 -> {
                val save = canvas.save()
                canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
                canvas.clipRect(0f, 0f, w, h)
                drawImportedEffect(canvas, w, h, t, style)
                canvas.restoreToCount(save)
            }
        }
        motionPaint.style = Paint.Style.FILL
    }

    /** 将 FluidGradient.kt 的 5 套流体主题与 SkyScenes 的 5 个云层/光斑场景移植为原生 Canvas 绘制。 */
    private fun drawImportedEffect(canvas: Canvas, w: Float, h: Float, t: Double, style: Int) {
        fun background(top: Int, bottom: Int) {
            paint.shader = LinearGradient(0f, 0f, 0f, h, top, bottom, Shader.TileMode.CLAMP)
            paint.alpha = 255
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
        }
        fun fluid(bg: Int, colors: IntArray, period: Double, screen: Boolean, grain: Float) {
            canvas.drawColor(bg)
            val paths = arrayOf(
                floatArrayOf(.10f,.10f,.65f,.40f,.55f,1.00f,1.25f,1.0f),
                floatArrayOf(.90f,.35f,.20f,.55f,.55f,1.00f,.80f,1.3f),
                floatArrayOf(.30f,.95f,.75f,.60f,.55f,1.00f,1.30f,.9f),
                floatArrayOf(.85f,.90f,.30f,.55f,.40f,1.00f,.70f,1.1f)
            )
            paths.forEachIndexed { i, p ->
                val phase = t * Math.PI * 2.0 / period * p[7]
                val f = (0.5 - 0.5 * kotlin.math.cos(phase)).toFloat()
                val cx = (p[0] + (p[2] - p[0]) * f) * w
                val cy = (p[1] + (p[3] - p[1]) * f) * h
                val radius = p[4] * (maxOf(w, h)) * (p[5] + (p[6] - p[5]) * f)
                val color = colors[i]
                paint.shader = RadialGradient(cx, cy, radius,
                    intArrayOf(color, (color and 0x00FFFFFF) or 0x66000000, color and 0x00FFFFFF),
                    floatArrayOf(0f, .52f, 1f), Shader.TileMode.CLAMP)
                paint.xfermode = if (screen) PorterDuffXfermode(PorterDuff.Mode.SCREEN) else null
                canvas.drawCircle(cx, cy, radius, paint)
                paint.shader = null
                paint.xfermode = null
            }
            if (grain > 0f) {
                motionPaint.style = Paint.Style.FILL
                for (i in 0 until 90) {
                    val x = ((i * 73) % 997) / 997f * w
                    val y = ((i * 191) % 991) / 991f * h
                    val twinkle = (0.45 + 0.55 * kotlin.math.sin(t * 1.2 + i * 1.7)).toFloat()
                    motionPaint.color = Color.argb((grain * 150f * twinkle).toInt().coerceIn(1, 35), 255, 255, 255)
                    canvas.drawCircle(x, y, if (i % 5 == 0) 1.25f else .65f, motionPaint)
                }
            }
        }
        fun glow(x: Float, y: Float, radius: Float, color: Int, alpha: Int = 150) {
            drawRadialGlow(canvas, x, y, radius, (color and 0x00FFFFFF) or (alpha shl 24))
        }
        fun cloud(x: Float, y: Float, scale: Float, tint: Int, shade: Int, alpha: Int) {
            val base = scale * h
            motionPaint.style = Paint.Style.FILL
            motionPaint.color = (tint and 0x00FFFFFF) or ((alpha * .50f).toInt() shl 24)
            canvas.drawCircle(x - base * .28f, y, base * .20f, motionPaint)
            canvas.drawCircle(x, y - base * .10f, base * .27f, motionPaint)
            canvas.drawCircle(x + base * .30f, y, base * .22f, motionPaint)
            canvas.drawRoundRect(x - base * .48f, y - base * .03f, x + base * .48f, y + base * .20f, base * .12f, base * .12f, motionPaint)
            motionPaint.color = (shade and 0x00FFFFFF) or ((alpha * .20f).toInt() shl 24)
            canvas.drawOval(x - base * .42f, y + base * .07f, x + base * .42f, y + base * .24f, motionPaint)
        }
        fun cloudLayers(tint: Int, shade: Int, dusk: Boolean = false) {
            for (layer in 0..2) {
                val count = if (layer == 2) 4 else 5
                for (i in 0 until count) {
                    val seed = i * 1.73 + layer * 2.4
                    val speed = .006 + layer * .004
                    val drift = ((t * speed) % 1.25).toFloat()
                    val x = ((i.toFloat() / count + drift) % 1.25f - .12f) * w
                    val y = h * (when (layer) { 0 -> .17f; 1 -> .43f; else -> .77f } +
                        kotlin.math.sin(t * .10 + seed).toFloat() * .025f)
                    val scale = when (layer) { 0 -> .15f; 1 -> .22f; else -> .31f }
                    cloud(x, y, scale, tint, shade, if (layer == 0) 80 else if (layer == 1) 125 else 165)
                }
            }
        }
        fun bokeh(count: Int, colors: IntArray, speed: Float, radiusFactor: Float) {
            for (i in 0 until count) {
                val seed = i * 2.399
                val x = (((i * 73) % 101) / 101f * w + kotlin.math.sin(t * .10 + seed).toFloat() * w * .035f +
                    kotlin.math.sin(t * .018 + seed).toFloat() * w * .04f)
                val drift = ((t * speed + (i % 9) * .13) % 1.0).toFloat()
                val y = (1f - ((((i * 47) % 103) / 103f + drift) % 1f)) * h
                val r = h * radiusFactor * (0.45f + (i % 7) * .12f)
                val color = colors[i % colors.size]
                val pulse = (0.55 + 0.45 * kotlin.math.sin(t * 1.3 + seed)).toFloat()
                motionPaint.style = Paint.Style.FILL
                motionPaint.color = (color and 0x00FFFFFF) or ((90f * pulse).toInt() shl 24)
                canvas.drawCircle(x, y, r, motionPaint)
                motionPaint.style = Paint.Style.STROKE
                motionPaint.strokeWidth = (1f + r * .035f)
                motionPaint.color = (color and 0x00FFFFFF) or ((145f * pulse).toInt() shl 24)
                canvas.drawCircle(x, y, r * .72f, motionPaint)
            }
            motionPaint.style = Paint.Style.FILL
        }
        // 每个效果从干净的 Paint 状态开始，避免前一帧/旧样式遗留 alpha、Shader 或 PorterDuff 状态。
        paint.alpha = 255
        paint.colorFilter = null
        paint.xfermode = null
        paint.shader = null
        motionPaint.alpha = 255
        when (style) {
            9 -> fluid(0xFF050A1F.toInt(), intArrayOf(0xFF00E5A8.toInt(),0xFF2B6BFF.toInt(),0xFF8A3FFC.toInt(),0xFF00C2FF.toInt()), 24.0, true, .10f)
            10 -> fluid(0xFF2A0A2E.toInt(), intArrayOf(0xFFFF7A45.toInt(),0xFFFF3D81.toInt(),0xFFFFC14D.toInt(),0xFF7B2FF7.toInt()), 26.0, false, .10f)
            11 -> fluid(0xFFFDF3FF.toInt(), intArrayOf(0xFFFFB3D9.toInt(),0xFFB8C8FF.toInt(),0xFFC8FFE9.toInt(),0xFFFFE3A8.toInt()), 30.0, false, .06f)
            12 -> fluid(0xFF021A2B.toInt(), intArrayOf(0xFF0A6FB5.toInt(),0xFF14C2C9.toInt(),0xFF052F5F.toInt(),0xFF7BE0FF.toInt()), 34.0, false, .08f)
            13 -> fluid(0xFF0A0010.toInt(), intArrayOf(0xFFFF00C8.toInt(),0xFF00F0FF.toInt(),0xFF6A00FF.toInt(),0xFFFFEE00.toInt()), 12.0, true, .16f)
            14 -> {
                background(0xFF3A7BD5.toInt(), 0xFFBFE6FF.toInt())
                glow(w * .80f, h * .14f, maxOf(w,h) * .45f, 0xFFFFF8D2.toInt(), 215)
                cloudLayers(0xFFFFFFFF.toInt(), 0xFF6E91BE.toInt())
            }
            15 -> {
                paint.shader = LinearGradient(0f,0f,0f,h,intArrayOf(0xFF2B1055.toInt(),0xFFC2477A.toInt(),0xFFFF8A5C.toInt(),0xFFFFD08A.toInt()),floatArrayOf(0f,.5f,.8f,1f),Shader.TileMode.CLAMP)
                canvas.drawRect(0f,0f,w,h,paint); paint.shader=null
                glow(w*.5f,h*.86f,maxOf(w,h)*.6f,0xFFFFC878.toInt(),185)
                cloudLayers(0xFFFFAF9B.toInt(),0xFF551E55.toInt(),true)
            }
            16 -> {
                background(0xFF03050F.toInt(),0xFF1B2C58.toInt())
                for (i in 0 until 70) {
                    val seed=i*2.399
                    val x=((i*47)%101)/101f*w
                    val y=((i*31)%103)/103f*h*.60f
                    val twinkle=(.3+.5*(.5+.5*kotlin.math.sin(t*1.5+seed))).toFloat()
                    motionPaint.color=Color.argb((twinkle*255).toInt(),255,255,255)
                    canvas.drawCircle(x,y,(.5f+(i%4)*.35f),motionPaint)
                }
                val mx=w*.72f; val my=h*.22f
                glow(mx,my,maxOf(w,h)*.40f,0xFFBED2FF.toInt(),90)
                motionPaint.color=0xFFF6F8FF.toInt()
                canvas.drawCircle(mx,my,minOf(w,h)*.06f,motionPaint)
                cloudLayers(0xFF96AAD7.toInt(),0xFF0A0F28.toInt())
            }
            17 -> {
                background(0xFF10062B.toInt(),0xFF3B1260.toInt())
                bokeh(60,intArrayOf(0xFFFF78C8.toInt(),0xFF78C8FF.toInt(),0xFFFFD278.toInt(),0xFFAA82FF.toInt(),0xFF78FFDC.toInt()),.12f,.045f)
            }
            18 -> {
                paint.shader=LinearGradient(0f,0f,0f,h,intArrayOf(0xFF2A1A5E.toInt(),0xFFD76D77.toInt(),0xFFFFB88C.toInt()),floatArrayOf(0f,.55f,1f),Shader.TileMode.CLAMP)
                canvas.drawRect(0f,0f,w,h,paint); paint.shader=null
                bokeh(45,intArrayOf(0xFFFFE6A0.toInt(),0xFFFFA0BE.toInt(),0xFFFFFFFF.toInt(),0xFFBEAAFF.toInt()),.10f,.035f)
                cloudLayers(0xFFFFE2EC.toInt(),0xFF7D3C73.toInt())
                bokeh(22,intArrayOf(0xFFFFF0C8.toInt(),0xFFFFC8DC.toInt()),.16f,.018f)
            }
        }
    }

    private fun drawRadialGlow(canvas: Canvas, cx: Float, cy: Float, radius: Float, color: Int) {
        val centerColor = (color and 0x00FFFFFF) or 0x99000000.toInt()
        val midColor = (color and 0x00FFFFFF) or 0x44000000
        paint.shader = RadialGradient(cx, cy, radius, intArrayOf(centerColor, midColor, color and 0x00FFFFFF),
            floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
        paint.xfermode = null
        canvas.drawCircle(cx, cy, radius, paint)
        paint.shader = null
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

    private companion object {
        const val FRAME_DELAY_MS = 24L
        const val BACKGROUND_CYCLE_MS = 75_000L

        private fun colorsForStyle(style: Int): IntArray {
            return when (style) {
                1 -> intArrayOf(0xC811B8A6.toInt(),0xC81D8FE8.toInt(),0xC835C6D8.toInt(),0xC8126F9A.toInt(),0xC811B8A6.toInt())
                2 -> intArrayOf(0xC8FF8A4C.toInt(),0xC8F04F8B.toInt(),0xC8B45BE8.toInt(),0xC8FFD166.toInt(),0xC8FF8A4C.toInt())
                4 -> intArrayOf(0xC8D8F3FF.toInt(),0xC887B8E8.toInt(),0xC8B7A6F6.toInt(),0xC8F4E9FF.toInt(),0xC8D8F3FF.toInt())
                7 -> intArrayOf(0xC8184268.toInt(),0xC8276F9B.toInt(),0xC83B3C91.toInt(),0xC877318D.toInt(),0xC8184268.toInt())
                8 -> intArrayOf(0xC825285B.toInt(),0xC845367E.toInt(),0xC8336E91.toInt(),0xC844387E.toInt(),0xC825285B.toInt())
                9 -> intArrayOf(0xC8050A1F.toInt(),0xC800E5A8.toInt(),0xC82B6BFF.toInt(),0xC88A3FFC.toInt(),0xC8050A1F.toInt())
                10 -> intArrayOf(0xC82A0A2E.toInt(),0xC8FF7A45.toInt(),0xC8FF3D81.toInt(),0xC8FFC14D.toInt(),0xC82A0A2E.toInt())
                11 -> intArrayOf(0xC8FDF3FF.toInt(),0xC8FFB3D9.toInt(),0xC8B8C8FF.toInt(),0xC8C8FFE9.toInt(),0xC8FDF3FF.toInt())
                12 -> intArrayOf(0xC8021A2B.toInt(),0xC80A6FB5.toInt(),0xC814C2C9.toInt(),0xC8052F5F.toInt(),0xC8021A2B.toInt())
                13 -> intArrayOf(0xC80A0010.toInt(),0xC8FF00C8.toInt(),0xC800F0FF.toInt(),0xC86A00FF.toInt(),0xC80A0010.toInt())
                14 -> intArrayOf(0xC83A7BD5.toInt(),0xC8BFE6FF.toInt(),0xC8E8F7FF.toInt(),0xC83A7BD5.toInt())
                15 -> intArrayOf(0xC82B1055.toInt(),0xC8C2477A.toInt(),0xC8FF8A5C.toInt(),0xC8FFD08A.toInt())
                16 -> intArrayOf(0xC803050F.toInt(),0xC81B2C58.toInt(),0xC803050F.toInt())
                17 -> intArrayOf(0xC810062B.toInt(),0xC83B1260.toInt(),0xC810062B.toInt())
                18 -> intArrayOf(0xC82A1A5E.toInt(),0xC8D76D77.toInt(),0xC8FFB88C.toInt(),0xC82A1A5E.toInt())
                else -> intArrayOf(0xC82DE2C5.toInt(),0xC83E8BFF.toInt(),0xC89B62FF.toInt(),0xC8FF62B5.toInt(),0xC8FFB347.toInt(),0xC82DE2C5.toInt())
            }
        }
    }
}

/**
 * 输入法的整块视图：候选条 + 键盘，全面屏适配开着且角上放了键时底下再接一条操作条，否则只让出导航栏的高度（游戏键盘里两样都不要）。
 * 面板（[PanelKind]）盖在候选条与键盘那一整块上，高度跟键盘走；底下的键盘留着占位，关面板就露出来。
 */
class InputPanel(
    private val context: Context,
    val settings: Settings,
    barListener: CandidateBarView.Listener,
    stripSource: StripSource,
    keyboardListener: KeyboardView.Listener,
    private val actions: PanelActions,
    onBottomAction: (BottomAction) -> Unit,
    onCloudSelect: (Int) -> Unit,
    private val onGenerateAiExpression: (String, (Result<List<AiExpressionGroup>>) -> Unit) -> Unit,
    private val aiChatGenerator: AiChatGenerator,
    /** 游戏键盘里浮着用（[GameFrame]）：不接底下的操作条，也不让导航栏。 */
    private val floating: Boolean = false,
) {
    // 游戏键盘一律深色：浮在游戏画面上，浅色太扎眼
    private val palette = Palette.of(settings.theme, floating || settings.appearance.dark(context))
    private val dynamicBackgroundPrefs = context.getSharedPreferences("dynamic_background", Context.MODE_PRIVATE)
    private val videoBackgroundEnabled = dynamicBackgroundPrefs.getBoolean("video_enabled", false)
    private val videoBackgroundMode = dynamicBackgroundPrefs.getInt("video_mode", 2).coerceIn(0, 2)
    private val videoBackgroundFrosted = dynamicBackgroundPrefs.getBoolean("video_frosted", true)
    private val videoBackgroundUri = if (videoBackgroundEnabled && videoBackgroundMode != 1) dynamicBackgroundPrefs.getString("video_uri", null) else null
    private val backgroundEffectActive = settings.keyboardBackgroundEffect && (videoBackgroundUri == null || videoBackgroundMode != 0)
    private var transparentBackground = backgroundEffectActive || videoBackgroundUri != null
    private val videoBackgroundAlpha = dynamicBackgroundPrefs.getInt("video_alpha", 72).coerceIn(0, 100) / 100f
    private val videoBackgroundBrightness = dynamicBackgroundPrefs.getInt("video_brightness", 100).coerceIn(0, 200)
    private val videoBackgroundBlur = if (videoBackgroundFrosted) dynamicBackgroundPrefs.getInt("video_blur", 8).coerceIn(0, 30) else 0

    val candidateTextStyleConfig = app.qingjian.inputmethod.candidates.CandidateTextStyleEngine.Config.forPreset(
        palette.text, settings.fontWeight, settings.candidateTextPreset,
    )
    // 候选栏与拼音位图始终透出统一面板背景，避免单独绘制第二层底色。
    val bar = CandidateBarView(context, palette, settings.candidateFontSize / 20f, stripSource, settings.toolbar, barListener,
        settings.fontWeight, true, candidateTextStyleConfig).apply {
        setBackgroundColor(Color.TRANSPARENT)
        // 首帧尚未取得视频亮度时先按主题给出可读颜色；光效模式按配色组选择黑/白高对比文字。
        setAdaptiveTextColor(
            when {
                videoBackgroundUri != null -> if (palette.dark) Color.WHITE else Color.BLACK
                backgroundEffectActive -> backgroundEffectTextColor(settings.keyboardBackgroundStyle)
                else -> null
            },
        )
    }
    private val cloudSuggestions = CloudSuggestionsView(context, palette, onCloudSelect)
    /** Tips 行只占位、不绘制背景；玻璃效果由 Tips 自身的独立窗口提供。 */
    private val cloudTipsSlot = FrameLayout(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
        visibility = View.INVISIBLE
        addView(
            cloudSuggestions,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                0,
                Gravity.CENTER,
            ),
        )
        cloudSuggestions.setAnchorView(this)
    }
    // 视频背景启用时自动降低键帽不透明度，让视频能够透过键帽显示；用户设置的透明度比例仍然生效。
    private val keyBackgroundAlpha = dynamicBackgroundPrefs.getInt("keyboard_key_alpha", 100).coerceIn(0, 100) / 100f * if (videoBackgroundUri != null) 0.48f else 1f
    private val keyBackgroundFrosted = dynamicBackgroundPrefs.getBoolean("keyboard_key_frosted", false)
    val keyboard = KeyboardView(context, palette, settings, keyboardListener, transparentBackground = transparentBackground,
        keyBackgroundAlpha = keyBackgroundAlpha, keyBackgroundFrosted = keyBackgroundFrosted,
        keyMarqueeEnabled = dynamicBackgroundPrefs.getBoolean(DynamicBackgroundActivity.KEY_KEYBOARD_MARQUEE, false))

    /** 开着的面板；null 是键盘。 */
    var openPanel: PanelKind? = null
        private set

    private var panelView: View? = null
    private var chatPanel: AiChatPanel? = null
    private var voiceView: VoicePanel? = null

    /** AI 对话面板接管当前输入连接，让底部实体键盘可以直接编辑面板字段。 */
    val aiChatEditor: AiChatPanel? get() = chatPanel

    /** 快捷翻译等浮动编辑条使用与键盘相同的动态光效/视频背景透明策略。 */
    val editorBackgroundTransparent: Boolean get() = transparentBackground

    private val bottomInset = navigationBarHeight(context)
    private val hasBottomActions = !floating && settings.fullscreenAdapt &&
        (settings.bottomLeft != BottomAction.NONE || settings.bottomRight != BottomAction.NONE)
    private val bottomActions = if (hasBottomActions) {
        BottomActionsView(
            context, palette, bottomInset, settings.bottomLeft, settings.bottomRight, onBottomAction,
            transparentBackground = transparentBackground,
        )
    } else null

    // 云联想使用独立悬浮 Tips，不加入垂直布局、不挤占候选栏和键盘高度。
    private val main = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
        setBackgroundColor(Color.TRANSPARENT)
        addView(bar)
        addView(keyboard)
        bottomActions?.let { addView(it) }
    }

    private val videoBottomExtension = if (!floating && (!settings.fullscreenAdapt ||
        (settings.bottomLeft == BottomAction.NONE && settings.bottomRight == BottomAction.NONE))) bottomInset else 0

    private var activeFlowBackground: FlowingKeyboardBackgroundDrawable? = null
    private var currentVideoBackgroundActive = videoBackgroundUri != null
    private var lastToolbarColorSampleAt = 0L

    private val stack = Stack(
        context, main, videoBackgroundUri, videoBackgroundAlpha, videoBackgroundBrightness, videoBackgroundBlur,
        videoBottomExtension,
        palette.dark,
    ) { luminance -> bar.setVideoBackgroundLuminance(luminance) }.apply {
        setBackgroundColor(Color.TRANSPARENT)
        clipChildren = false
        clipToPadding = false
    }


    init {
        if (backgroundEffectActive) {
            stack.updateBackgroundContrast(backgroundEffectLuminance(settings.keyboardBackgroundStyle))
        }
    }

    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = when {
            backgroundEffectActive -> createFlowingBackground(settings.keyboardBackgroundStyle)
            videoBackgroundUri != null -> android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            else -> android.graphics.drawable.ColorDrawable(palette.board)
        }
        clipChildren = false
        clipToPadding = false
        // 输入法面板顶部左右圆角，底部保持方正；裁切范围覆盖候选栏、键盘与底部操作条。
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val radius = 26f * context.resources.displayMetrics.density
                val path = Path().apply {
                    moveTo(radius, 0f)
                    lineTo(view.width - radius, 0f)
                    quadTo(view.width.toFloat(), 0f, view.width.toFloat(), radius)
                    lineTo(view.width.toFloat(), view.height.toFloat())
                    lineTo(0f, view.height.toFloat())
                    lineTo(0f, radius)
                    quadTo(0f, 0f, radius, 0f)
                    close()
                }
                outline.setConvexPath(path)
            }
        }
        // Tips 行本身透明且高度固定；Tips 内容在这一行水平居中，不贴靠右侧。
        addView(
            cloudTipsSlot,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            },
        )
        addView(stack)
        val inset = bottomInset
        if (floating) return@apply
        // 两个角都不放键时不要那条操作条，只让出导航栏，不留一截空白（#889）
        val corners = settings.bottomLeft != BottomAction.NONE || settings.bottomRight != BottomAction.NONE
        if (!settings.fullscreenAdapt || !corners) setPadding(0, 0, 0, inset)
    }

    private fun createFlowingBackground(style: Int): FlowingKeyboardBackgroundDrawable =
        FlowingKeyboardBackgroundDrawable(palette, style).also { drawable ->
            activeFlowBackground = drawable
            drawable.onFrame = { updateAdaptiveToolbarColors() }
        }

    /** 每 160ms 低分辨率采样一次；同一工具栏每个图标独立计算黑白对比色。 */
    private fun updateAdaptiveToolbarColors(force: Boolean = false) {
        val enabled = Settings.preferences(context).getBoolean(Settings.KEY_ADAPTIVE_TOOLBAR_ICONS, true)
        if (!enabled) {
            bar.setAdaptiveToolbarLuminances(null)
            return
        }
        if (currentVideoBackgroundActive) return // 视频帧通过现有亮度回调提供保护色
        val drawable = activeFlowBackground ?: run {
            bar.setAdaptiveToolbarLuminances(null)
            return
        }
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastToolbarColorSampleAt < 160L) return
        if (bar.width <= 0 || bar.height <= 0) return
        lastToolbarColorSampleAt = now
        val xs = bar.adaptiveToolbarSampleXs()
        val luminances = drawable.sampleLuminances(xs, bar.height / 2f)
        bar.setAdaptiveToolbarLuminances(luminances)
    }

    /** 只替换视频/亮度层，保留键盘、候选栏和当前打开的子面板实例。 */
    fun refreshDynamicBackground() {
        val prefs = context.getSharedPreferences("dynamic_background", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("video_enabled", false)
        val mode = prefs.getInt("video_mode", 2).coerceIn(0, 2)
        val uri = if (enabled && mode != 1) prefs.getString("video_uri", null) else null
        currentVideoBackgroundActive = uri != null
        val alpha = prefs.getInt("video_alpha", 72).coerceIn(0, 100) / 100f
        val brightness = prefs.getInt("video_brightness", 100).coerceIn(0, 200)
        val blur = if (prefs.getBoolean("video_frosted", true)) prefs.getInt("video_blur", 8).coerceIn(0, 30) else 0
        stack.updateVideoBackground(uri, alpha, brightness, blur)
        val keyAlpha = prefs.getInt("keyboard_key_alpha", 100).coerceIn(0, 100) / 100f * if (uri != null) 0.48f else 1f
        val keyFrosted = prefs.getBoolean("keyboard_key_frosted", false)
        keyboard.setKeySurface(keyAlpha, keyFrosted)
        keyboard.setMarqueeEnabled(prefs.getBoolean(DynamicBackgroundActivity.KEY_KEYBOARD_MARQUEE, false))
        // 边缘发光、立体键帽和自定义颜色在同一 Painter 中立即刷新，不依赖关闭/重开面板。
        keyboard.refreshKeyEffects()
        val settingsPrefs = Settings.preferences(context)
        val effectEnabled = settingsPrefs.getBoolean(Settings.KEY_BACKGROUND_EFFECT, settings.keyboardBackgroundEffect) &&
            (!enabled || mode != 0)
        val style = Settings.normalizeKeyboardBackgroundStyle(settingsPrefs.getString(Settings.KEY_BACKGROUND_STYLE, settings.keyboardBackgroundStyle.toString())?.toIntOrNull() ?: settings.keyboardBackgroundStyle)
        // 视频亮度由纹理采样持续更新；纯光效则按当前效果的色彩分布设置初始对比度。
        stack.updateBackgroundContrast(if (uri == null && effectEnabled) backgroundEffectLuminance(style) else null)
        transparentBackground = effectEnabled || uri != null
        // 候选文字与背景模式同步：视频先用主题对比色并随后按视频帧亮度微调；光效按色组切换；
        // 两种动态背景都关闭时清除旧的自适应颜色，恢复当前候选文字样式配置。
        bar.setAdaptiveTextColor(
            when {
                uri != null -> if (palette.dark) Color.WHITE else Color.BLACK
                effectEnabled -> backgroundEffectTextColor(style)
                else -> null
            },
        )
        // 候选栏始终透明；静态主题底色由 root 绘制，开启视频时则透出同一纹理层。
        bar.setTransparentBackground(true, palette.board)
        keyboard.setTransparentBackground(transparentBackground, palette.board)
        bottomActions?.setTransparentBackground(transparentBackground)
        when (val active = panelView) {
            is app.qingjian.inputmethod.panel.base.PanelView -> active.useAnimatedBackground(transparentBackground)
            is AiChatPanel -> active.setTransparentBackground(transparentBackground)
            is AiExpressionPanel -> active.setTransparentBackground(transparentBackground)
        }
        root.background = when {
            effectEnabled -> createFlowingBackground(style)
            uri != null -> {
                activeFlowBackground = null
                android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            }
            else -> {
                activeFlowBackground = null
                android.graphics.drawable.ColorDrawable(palette.board)
            }
        }
        if (uri != null || !effectEnabled) bar.setAdaptiveToolbarLuminances(null)
        else updateAdaptiveToolbarColors(force = true)
        root.invalidate()
    }

    /** 云联想只进入小精灵原有圆角气泡；清除旧版独立悬浮 Tips 窗口。 */
    fun showCloudSuggestions(candidates: List<Pair<Int, String>>) {
        smartSuggestionItems = emptyList()
        smartSuggestionSelection = null
        CloudSuggestionsView.hideAllFloatingTips()
        cloudSuggestions.show(emptyList())
        val prefs = context.getSharedPreferences(FloatingMascotService.PREFS, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(FloatingMascotService.KEY_ENABLED, false) &&
            android.provider.Settings.canDrawOverlays(context)
        if (!enabled) return
        val intent = Intent(context, FloatingMascotService::class.java).apply {
            action = if (candidates.isEmpty()) FloatingMascotService.ACTION_HIDE_TIPS else FloatingMascotService.ACTION_SHOW_TIPS
            putStringArrayListExtra(FloatingMascotService.EXTRA_TIP_LABELS, ArrayList(candidates.map { it.second }))
            putIntegerArrayListExtra(FloatingMascotService.EXTRA_TIP_INDEXES, ArrayList(candidates.map { it.first }))
            putExtra(FloatingMascotService.ACTION_COLOR_SURFACE, palette.surface)
            putExtra(FloatingMascotService.ACTION_COLOR_TEXT, palette.text)
            putExtra(FloatingMascotService.ACTION_COLOR_CLOUD, palette.cloud)
            putExtra(FloatingMascotService.ACTION_COLOR_ACCENT, palette.accent)
            putExtra(FloatingMascotService.ACTION_COLOR_BORDER, palette.faint)
        }
        runCatching { ContextCompat.startForegroundService(context, intent) }
            .onFailure { android.util.Log.w("QingjianMascot", "Unable to update cloud tips bubble", it) }
    }

    /** 将云 Tips 锚定到当前编辑器光标上方；保留服务层的既有定位接口。 */
    fun updateCloudTipsAnchor(screenX: Float, screenY: Float) =
        cloudSuggestions.updateEditorAnchor(screenX, screenY)

    private var smartSuggestionItems: List<String> = emptyList()
    private var smartSuggestionSelection: ((String) -> Unit)? = null

    /** 设备信息建议也只放进小精灵气泡，不再创建独立 Tips 悬浮窗。 */
    fun showSmartSuggestions(candidates: List<String>, onSelect: (String) -> Unit) {
        CloudSuggestionsView.hideAllFloatingTips()
        cloudSuggestions.show(emptyList())
        val prefs = context.getSharedPreferences(FloatingMascotService.PREFS, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(FloatingMascotService.KEY_ENABLED, false) &&
            android.provider.Settings.canDrawOverlays(context)
        smartSuggestionItems = if (enabled) candidates.take(3) else emptyList()
        smartSuggestionSelection = if (smartSuggestionItems.isNotEmpty()) onSelect else null
        if (!enabled) return
        val intent = Intent(context, FloatingMascotService::class.java).apply {
            action = if (smartSuggestionItems.isEmpty()) FloatingMascotService.ACTION_HIDE_TIPS else FloatingMascotService.ACTION_SHOW_TIPS
            putStringArrayListExtra(FloatingMascotService.EXTRA_TIP_LABELS, ArrayList(smartSuggestionItems))
            putIntegerArrayListExtra(FloatingMascotService.EXTRA_TIP_INDEXES, ArrayList(smartSuggestionItems.indices.toList()))
            putExtra(FloatingMascotService.ACTION_COLOR_SURFACE, palette.surface)
            putExtra(FloatingMascotService.ACTION_COLOR_TEXT, palette.text)
            putExtra(FloatingMascotService.ACTION_COLOR_CLOUD, palette.cloud)
            putExtra(FloatingMascotService.ACTION_COLOR_ACCENT, palette.accent)
            putExtra(FloatingMascotService.ACTION_COLOR_BORDER, palette.faint)
        }
        runCatching { ContextCompat.startForegroundService(context, intent) }
            .onFailure { android.util.Log.w("QingjianMascot", "Unable to update smart suggestion bubble", it) }
    }

    fun selectSmartSuggestion(index: Int): Boolean {
        val value = smartSuggestionItems.getOrNull(index) ?: return false
        val select = smartSuggestionSelection ?: return false
        smartSuggestionItems = emptyList()
        smartSuggestionSelection = null
        select(value)
        return true
    }

    /** 打开 [kind]；拆词要带上那段文字 [text]。 */
    fun open(kind: PanelKind, text: String = "", hasSelection: Boolean = false) {
        close()
        val header = bar.barHeight
        val panel = when (kind) {
            PanelKind.FUNCTIONS -> FunctionPanel(context, palette, actions, header, settings)
            PanelKind.DYNAMIC_BACKGROUND -> DynamicBackgroundPanel(context, palette, actions, header)
            PanelKind.KEY_EFFECTS -> KeyEffectsPanel(context, palette, actions, header)
            PanelKind.KEYBOARDS -> KeyboardPickerPanel(context, palette, actions, header, settings.layout)
            PanelKind.TOOLBAR -> ToolbarPanel(context, palette, actions, header, settings.toolbar)
            PanelKind.SYMBOLS, PanelKind.EMOJI -> CatalogPanel(
                context, palette, actions, header, kind,
                leftThumb = settings.arrangement == Settings.Arrangement.LEFT_THUMB,
            )
            PanelKind.CANDIDATES -> CandidateGrid(context, palette, actions, header, settings.textSize.scale)
            PanelKind.CONTEXT_TOOLS -> ContextToolsPanel(
                context, palette, header, text, hasSelection, aiChatGenerator,
                actions::insertAtCursor, actions::replaceSelectedText, actions::copyToClipboard, actions::closePanel,
            )
            PanelKind.AI_CHAT -> AiChatPanel(
                context, palette, header, text, aiChatGenerator, actions::replaceInputText, actions::undoInputReplacement, actions::closePanel, transparentBackground,
            )
            PanelKind.AI_EXPRESSION -> AiExpressionPanel(
                context, palette, header, text, onGenerateAiExpression, actions::replaceInputText, actions::undoInputReplacement, actions::closePanel, transparentBackground,
            )
            PanelKind.CLIPBOARD, PanelKind.PHRASES -> ClipboardPanel(context, palette, actions, header, phrasesFirst = kind == PanelKind.PHRASES)
            PanelKind.PIECES -> PiecesPanel(context, palette, actions, header, text)
            // 语音面板带着回调，由 [openVoice] 盖上
            PanelKind.VOICE -> return
        }
        cover(kind, panel)
    }

    /** 生成跨 Android 版本一致的磨砂底图：先缩小再高质量放大，避免依赖仅部分设备生效的 RenderEffect。 */
    private fun blurVoiceBackdrop(source: Bitmap, strength: Int): Bitmap {
        val width = source.width
        val height = source.height
        val scale = 1.2f + (strength.coerceIn(1, 50) - 1) / 49f * 4.8f
        val reduced = Bitmap.createScaledBitmap(
            source,
            (width / scale).toInt().coerceAtLeast(1),
            (height / scale).toInt().coerceAtLeast(1),
            true,
        )
        val blurred = Bitmap.createScaledBitmap(reduced, width, height, true)
        if (reduced !== source && !reduced.isRecycled) reduced.recycle()
        if (!source.isRecycled) source.recycle()
        return blurred
    }

    /** 盖上语音面板：右边那块写 [actionLabel]，点各块接回 [actions]。 */
    fun openVoice(actionLabel: String, actions: VoicePanel.Actions): VoicePanel {
        close()
        // 先把当前键盘画成一张底图，再由 VoicePanel 做背景模糊和深色染色；
        // 不直接改键盘视图本身，语音结束后原键盘可立即恢复交互。
        val backdrop = runCatching {
            val w = stack.width
            val h = stack.height
            if (w <= 0 || h <= 0) null else Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmap ->
                stack.draw(Canvas(bitmap))
            }
        }.getOrNull()
        val voicePrefs = context.getSharedPreferences("voice", Context.MODE_PRIVATE)
        val glassStrength = voicePrefs.getInt(VoicePanel.PREF_GLASS_STRENGTH, VoicePanel.DEFAULT_GLASS_STRENGTH).coerceIn(1, 50)
        val frostedBackdrop = backdrop?.let { bitmap ->
            runCatching { blurVoiceBackdrop(bitmap, glassStrength) }.getOrElse {
                if (!bitmap.isRecycled) bitmap.recycle()
                null
            }
        }
        return VoicePanel(context, palette, actionLabel, actions, frostedBackdrop, VoicePanel.resolveAnimationStyle(context), transparentBackground, glassStrength).also { voice ->
            applyFontWeight(voice)
            voiceView = voice
            stack.addView(voice, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            openPanel = PanelKind.VOICE
            overlay?.bringToFront()
            voice.bringToFront()
        }
    }

    private fun cover(kind: PanelKind, panel: View) {
        applyFontWeight(panel)
        (panel as? app.qingjian.inputmethod.panel.base.PanelView)?.useAnimatedBackground(transparentBackground)
        // AI 对话与 AI 情境表达使用完全相同的面板容器和测量高度，
        // 不再额外搬动候选栏或创建覆盖整块输入法窗口的特殊层。
        if (kind == PanelKind.AI_CHAT && panel is AiChatPanel) {
            chatPanel = panel
        }
        stack.addView(
            panel,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        main.visibility = View.INVISIBLE
        panelView = panel
        openPanel = kind
        overlay?.bringToFront()
    }

    /** 一直盖在最上面的一层（同步的边缘光）。放进 [stack] 而不是在外面再包一层：输入法的视图外面多包一层，键盘就贴到屏幕顶上去了。 */
    fun addOverlay(view: View) {
        overlay = view
        stack.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private var overlay: View? = null

    val view: View get() = root

    /** 编辑条（常用语编辑框、快捷翻译）摆在最上面（键盘照常可打）；[editor] 为 null 收起。 */
    fun showEditor(editor: View?) {
        // 编辑条必须放在 Stack 的 main 内容层中：视频背景先绘制，编辑条再叠在其上；
        // 之前放到 root 外层会让编辑条与视频背景脱离，始终露出默认纯色底。
        main.getChildAt(0)?.takeIf { it is FloatingEditor }?.let(main::removeView)
        editor?.let {
            applyFontWeight(it)
            main.addView(
                it,
                0,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    /** 刷新剪贴板管理面板；临时预览由 ClipboardWatcher 单独更新。 */
    fun refreshClipboard() {
        if (openPanel == PanelKind.CLIPBOARD) open(PanelKind.CLIPBOARD)
    }

    fun refreshFunctions() {
        (panelView as? FunctionPanel)?.refreshState()
    }

    /** 候选展开开着时换上这一帧的拼音与候选。 */
    fun showCandidates(preedit: String, candidates: List<Candidate>, singleChars: Boolean) {
        (panelView as? CandidateGrid)?.show(preedit, candidates, singleChars)
    }

    /**
     * 将字体粗细统一应用到面板内所有 TextView/EditText/Button，递归覆盖各功能面板的文本控件。
     * 保留控件原有的粗体语义；键盘键面与候选条由各自绘制器使用同一设置。
     */
    private fun applyFontWeight(view: View) {
        val selected = settings.fontWeight.typeface
        if (view is TextView) {
            view.typeface = Typeface.create(selected, if (view.typeface?.isBold == true) Typeface.BOLD else Typeface.NORMAL)
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) applyFontWeight(view.getChildAt(index))
        }
    }

    /** 回到键盘；没开面板时什么都不做。 */
    fun close() {
        panelView?.let(stack::removeView)
        voiceView?.let(stack::removeView)
        panelView = null
        voiceView = null
        chatPanel = null
        openPanel = null
        main.visibility = View.VISIBLE
    }

    /** 先量键盘那一块，面板一律量成同样高；MATCH_PARENT 在输入法窗口里会撑满整屏。 */
    private class ChatOverlay(
        context: Context,
        panel: View,
        candidateBar: View,
        candidateHeight: Int,
    ) : LinearLayout(context) {
        init {
            orientation = VERTICAL
            addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
            addView(candidateBar, LayoutParams(LayoutParams.MATCH_PARENT, candidateHeight))
        }
    }

    private class Stack(
        context: Context,
        private val main: View,
        videoUri: String?,
        videoAlpha: Float,
        videoBrightness: Int,
        videoBlur: Int,
        private val videoBottomExtension: Int,
        private val contrastTextIsLight: Boolean,
        private val onVideoLuminance: (Float) -> Unit,
    ) : FrameLayout(context) {
        private val contrastVeil = View(context).apply { setBackgroundColor(Color.BLACK); alpha = 0f }
        private var lastVideoMeasureSignature: String? = null

        /** 视频/光效与内容之间的自适应衬层，统一保护文字、按键字母和图标的对比度。 */
        fun updateBackgroundContrast(luminance: Float?) {
            if (luminance == null) {
                contrastVeil.alpha = 0f
                contrastVeil.invalidate()
                return
            }
            val level = luminance.coerceIn(0f, 1f)
            val veilColor = if (contrastTextIsLight) Color.BLACK else Color.WHITE
            val amount = if (contrastTextIsLight) {
                (0.12f + (level - 0.34f).coerceAtLeast(0f) * 0.48f).coerceIn(0.12f, 0.44f)
            } else {
                (0.12f + (0.62f - level).coerceAtLeast(0f) * 0.48f).coerceIn(0.12f, 0.44f)
            }
            contrastVeil.setBackgroundColor(veilColor)
            contrastVeil.alpha = amount
            contrastVeil.invalidate()
        }
        private var currentVideoUri: String? = videoUri?.takeIf { it.isNotBlank() }
        private var videoBackground: DynamicVideoBackgroundView? = currentVideoUri?.let {
            DynamicVideoBackgroundView(context, Uri.parse(it), videoAlpha, videoBlur).apply {
                onAverageLuminance = { luminance ->
                    onVideoLuminance(luminance)
                    updateBackgroundContrast(luminance)
                }
            }
        }
        private var videoTint: View? = makeVideoTint(context, currentVideoUri, videoBrightness)

        fun updateVideoBackground(uri: String?, alpha: Float, brightness: Int, blur: Int) {
            val normalizedUri = uri?.takeIf { it.isNotBlank() }
            if (normalizedUri != currentVideoUri) {
                videoBackground?.let { removeView(it) }
                currentVideoUri = normalizedUri
                videoBackground = normalizedUri?.let {
                    DynamicVideoBackgroundView(context, Uri.parse(it), alpha, blur).apply {
                        onAverageLuminance = { luminance ->
                    onVideoLuminance(luminance)
                    updateBackgroundContrast(luminance)
                }
                    }
                }
                videoBackground?.let {
                    addView(it, 0, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Gravity.TOP,
                    ))
                }
                AppLog.info("[VIDEO_BG_DIAG] video source changed enabled=${normalizedUri != null} viewCreated=${videoBackground != null}")
            } else {
                videoBackground?.updateAppearance(alpha, blur)
            }
            videoTint?.let(::removeView)
            videoTint = makeVideoTint(context, normalizedUri, brightness)
            videoTint?.let {
                addView(it, if (videoBackground != null) 1 else 0, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.TOP,
                ))
            }
            requestLayout()
            invalidate()
        }

        private fun makeVideoTint(context: Context, uri: String?, brightness: Int): View? = uri?.let {
            View(context).apply {
                val amount = (kotlin.math.abs(brightness - 100) / 100f * 0.55f).coerceIn(0f, 0.55f)
                setBackgroundColor(if (brightness < 100) Color.BLACK else Color.WHITE)
                alpha = amount
            }
        }

        init {
            videoBackground?.let {
                addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP))
            }
            videoTint?.let {
                addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP))
            }
            // 对比度衬层位于视频/光效上方、所有文字与图标下方。
            addView(contrastVeil, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.TOP,
            ))
            // The keyboard content must be a child of the overlay container.
            addView(main, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            ))
        }

        private fun Int?.orZero() = this ?: 0

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            measureChild(main, widthMeasureSpec, heightMeasureSpec)
            val contentWidth = MeasureSpec.makeMeasureSpec(main.measuredWidth, MeasureSpec.EXACTLY)
            // 视频层延伸到 root 为导航栏预留的底部 inset；宽度严格跟随实际面板内容宽度。
            val videoHeight = MeasureSpec.makeMeasureSpec(
                main.measuredHeight + videoBottomExtension,
                MeasureSpec.EXACTLY,
            )
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child === main) continue
                if (child === videoBackground || child === videoTint) {
                    child.measure(contentWidth, videoHeight)
                    continue
                }
                if (child is CloudSuggestionsView) {
                    // 悬浮 Tips 自己测量高度，不参与候选栏/键盘的垂直排版。
                    measureChild(child, widthMeasureSpec, heightMeasureSpec)
                    continue
                }
                val childHeight = if (child is ChatOverlay) {
                    (main.measuredHeight - (main as? LinearLayout)?.getChildAt(2)?.measuredHeight.orZero()).coerceAtLeast(0)
                } else main.measuredHeight
                child.measure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY))
            }
            setMeasuredDimension(main.measuredWidth, main.measuredHeight)
            val signature = "stack=${measuredWidth}x${measuredHeight} main=${main.measuredWidth}x${main.measuredHeight} video=${videoBackground?.measuredWidth ?: 0}x${videoBackground?.measuredHeight ?: 0} tint=${videoTint?.measuredWidth ?: 0}x${videoTint?.measuredHeight ?: 0} bottomExtension=$videoBottomExtension"
            if (signature != lastVideoMeasureSignature) {
                lastVideoMeasureSignature = signature
                AppLog.info("[VIDEO_BG_DIAG] $signature")
            }
        }
    }

    /**
     * 导航栏高度变了（换了手势 / 三键导航）：输入法进程不一定重建视图，留着旧的底边，三键的导航栏会盖住最下一排键。
     * 服务每次弹键盘时问一次，变了就重建。
     */
    fun stale(): Boolean = navigationBarHeight(context) != bottomInset

    /** 系统导航栏 / 手势条的高度。输入法窗口自己收不到这份 inset，从窗口度量里取。 */
    private fun navigationBarHeight(context: Context): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return context.getSystemService(WindowManager::class.java).currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom
        }
        val id = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }
}
