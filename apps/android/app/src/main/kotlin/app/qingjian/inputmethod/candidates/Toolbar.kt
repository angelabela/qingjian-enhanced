package app.qingjian.inputmethod.candidates

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.graphics.RuntimeShader
import android.text.TextPaint
import android.text.TextUtils
import app.qingjian.inputmethod.R
import app.qingjian.inputmethod.panel.base.PanelIcons
import app.qingjian.inputmethod.panel.base.PanelIcons.Icon
import app.qingjian.inputmethod.settings.Settings
import app.qingjian.inputmethod.settings.ToolbarTool
import app.qingjian.inputmethod.theme.Palette
import kotlin.math.cos
import kotlin.math.sin

/**
 * 不在组句时候选条的样子：左边 logo 进功能面板、右边收起键盘，两头固定、各隔一道竖线，宽度一样；
 * 中间是用户定制的 [tools]，一屏最多放 [VISIBLE_SLOTS] 个，多出来的横着滑出来，不满时平分中间那段。
 */
class Toolbar(
    private val context: Context,
    private val palette: Palette,
    private val density: Float,
    private val barHeight: Float,
    private val tools: List<ToolbarTool>,
) {
    private val logo = context.getDrawable(R.drawable.ic_launcher_foreground)
    private val logoSlot = 62f * density
    private val hideSlot = 62f * density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint().apply { color = palette.faint }
    private val noticeText = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var adaptiveTargetColors: IntArray? = null
    private var adaptiveStartColors: IntArray? = null
    private var adaptiveTransitionStartedAt = 0L
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glassEffectPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val backdropMatrix = Matrix()
    // 每个图标独立持有 RuntimeShader/Backdrop BitmapShader，避免同一 Shader 在一帧内
    // 被多个图标连续改写 uniform 与纹理矩阵，导致 GPU 延迟执行时出现串帧/闪烁。
    private val liquidGlassShaders = HashMap<Pair<Icon, Int>, RuntimeShader>()
    private var liquidGlassShaderUnavailable = false
    private var backdropShaderBitmap: Bitmap? = null
    private val backdropShaders = HashMap<Pair<Icon, Int>, BitmapShader>()
    private var backdropProvider: (() -> Bitmap?)? = null
    private val iconMaskCache = HashMap<Pair<Icon, Int>, Bitmap>()
    private val iconMaskShaderCache = HashMap<Pair<Icon, Int>, BitmapShader>()

    fun setBackdropProvider(provider: (() -> Bitmap?)?) {
        backdropProvider = provider
    }

    private fun iconMask(icon: Icon, renderSize: Int, iconSize: Float): Bitmap {
        val key = icon to renderSize
        return iconMaskCache.getOrPut(key) {
            Bitmap.createBitmap(renderSize, renderSize, Bitmap.Config.ARGB_8888).also { bitmap ->
                val maskCanvas = Canvas(bitmap)
                val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                }
                PanelIcons.draw(maskCanvas, icon, renderSize / 2f, renderSize / 2f, iconSize, maskPaint)
            }
        }
    }

    /** 中间那排滑过去的距离（像素）。 */
    var offset = 0f
        private set

    private fun middleWidth(viewWidth: Int) = viewWidth - logoSlot - hideSlot

    private fun middleSlot(viewWidth: Int) = middleWidth(viewWidth) / tools.size.coerceIn(1, VISIBLE_SLOTS)

    fun maxOffset(viewWidth: Int) = (middleSlot(viewWidth) * tools.size - middleWidth(viewWidth)).coerceAtLeast(0f)

    fun scrollTo(value: Float, viewWidth: Int) {
        offset = value.coerceIn(0f, maxOffset(viewWidth))
    }

    /** 为每个工具图标分别采样背景 RGB；中间图标随横向滚动更新采样位置。 */
    fun sampleXs(viewWidth: Int): FloatArray {
        val slot = middleSlot(viewWidth)
        return FloatArray(tools.size + 1) { index ->
            if (index < tools.size) logoSlot + slot * (index + 0.5f) - offset
            else viewWidth - hideSlot / 2f
        }
    }

    /** 将背景色转换成有足够对比度的彩色前景，不再黑白二选一。 */
    private fun adaptiveIconColor(background: Int): Int {
        val backgroundLuminance = relativeLuminance(background)
        val hsv = FloatArray(3)
        Color.colorToHSV(background, hsv)
        val brandHsv = FloatArray(3)
        Color.colorToHSV(palette.brand, brandHsv)
        val baseHue = if (hsv[1] < 0.12f) brandHsv[0] else hsv[0]
        val saturation = (hsv[1] * 0.82f).coerceIn(0.62f, 0.88f)
        val values = floatArrayOf(0.98f, 0.84f, 0.46f, 0.30f, 0.22f)
        val hueOffsets = floatArrayOf(0f, 18f, -18f, 150f, 190f)
        var best = Color.WHITE
        var bestScore = -1f
        for (value in values) for (hueOffset in hueOffsets) {
            val candidate = Color.HSVToColor(
                floatArrayOf((baseHue + hueOffset + 360f) % 360f, saturation, value),
            )
            val contrast = contrastRatio(backgroundLuminance, relativeLuminance(candidate))
            if (contrast > bestScore) {
                bestScore = contrast
                best = candidate
            }
        }
        return best
    }

    private fun relativeLuminance(color: Int): Float {
        fun linear(channel: Int): Double {
            val value = channel / 255.0
            return if (value <= 0.04045) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
        }
        return (0.2126 * linear(Color.red(color)) + 0.7152 * linear(Color.green(color)) +
            0.0722 * linear(Color.blue(color))).toFloat()
    }

    private fun contrastRatio(first: Float, second: Float): Float {
        val lighter = maxOf(first, second)
        val darker = minOf(first, second)
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    /** 新采样色从当前显示色继续缓动，避免背景每次采样都重置动画造成跳色。 */
    fun setAdaptiveColors(backgroundColors: IntArray?) {
        if (backgroundColors == null) {
            adaptiveTargetColors = null
            adaptiveStartColors = null
            return
        }
        val next = IntArray(tools.size + 1) { index ->
            adaptiveIconColor(backgroundColors.getOrElse(index) { palette.board })
        }
        if (adaptiveTargetColors?.contentEquals(next) == true) return
        val now = SystemClock.uptimeMillis()
        adaptiveStartColors = animatedColors(now) ?: next.copyOf()
        adaptiveTargetColors = next
        adaptiveTransitionStartedAt = now
    }

    /** 视频层暂时只提供整帧亮度，使用主题品牌色相生成彩色前景并沿用同一缓动。 */
    fun setAdaptiveLuminance(luminance: Float, count: Int) {
        val hsv = FloatArray(3)
        Color.colorToHSV(palette.brand, hsv)
        hsv[1] = hsv[1].coerceAtLeast(0.62f)
        hsv[2] = if (luminance.coerceIn(0f, 1f) < 0.46f) 0.98f else 0.30f
        setAdaptiveColors(IntArray(count) { Color.HSVToColor(hsv) })
    }

    private fun animatedColors(now: Long): IntArray? {
        val target = adaptiveTargetColors ?: return null
        val start = adaptiveStartColors ?: return target
        val progress = ((now - adaptiveTransitionStartedAt).toFloat() / COLOR_TRANSITION_MS).coerceIn(0f, 1f)
        val eased = progress * progress * (3f - 2f * progress)
        val colors = IntArray(target.size) { index ->
            val from = start.getOrElse(index) { target[index] }
            val to = target[index]
            Color.rgb(
                (Color.red(from) + (Color.red(to) - Color.red(from)) * eased).toInt(),
                (Color.green(from) + (Color.green(to) - Color.green(from)) * eased).toInt(),
                (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * eased).toInt(),
            )
        }
        if (progress >= 1f) adaptiveStartColors = null
        return colors
    }

    private fun iconMaskShader(icon: Icon, renderSize: Int, iconSize: Float): BitmapShader {
        val key = icon to renderSize
        return iconMaskShaderCache.getOrPut(key) {
            BitmapShader(iconMask(icon, renderSize, iconSize), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
    }

    /**
     * 玻璃材质直接作用于图标笔画，不绘制圆角方框。
     * Refraction / dispersion 核心思路移植自 Kyant0/AndroidLiquidGlass (Backdrop)：
     * https://github.com/Kyant0/AndroidLiquidGlass
     * Copyright 2025 Kyant, Apache-2.0。此处改为 View/Canvas 字形 alpha mask，
     * 并由输入法当前背景纹理作为 backdrop shader，不依赖 Compose Backdrop 容器。
     */
    private fun drawLiquidGlassIcon(
        canvas: Canvas,
        icon: Icon,
        cx: Float,
        cy: Float,
        size: Float,
        backdrop: Bitmap?,
        viewWidth: Int,
    ) {
        val brand = palette.brand
        fun drawStableGlassStroke() {
            // 软件 Canvas、纹理暂不可用或 Shader 初始化失败时，使用稳定的双层玻璃描边。
            glassPaint.style = Paint.Style.STROKE
            glassPaint.strokeCap = Paint.Cap.ROUND
            glassPaint.strokeJoin = Paint.Join.ROUND
            glassPaint.color = withAlpha(brand, 224)
            PanelIcons.draw(canvas, icon, cx, cy, size, glassPaint)
            glassPaint.color = withAlpha(Color.WHITE, 104)
            canvas.save()
            canvas.translate(-0.35f * density, -0.45f * density)
            PanelIcons.draw(canvas, icon, cx, cy, size, glassPaint)
            canvas.restore()
        }
        if (Build.VERSION.SDK_INT < 33 || !canvas.isHardwareAccelerated ||
            liquidGlassShaderUnavailable || backdrop == null || backdrop.isRecycled) {
            drawStableGlassStroke()
            return
        }

        try {
            val padding = 5.5f * density
            val renderSize = kotlin.math.ceil(size + padding * 2f).toInt().coerceAtLeast(8)
            iconMask(icon, renderSize, size)
            if (backdropShaderBitmap !== backdrop) {
                backdropShaderBitmap = backdrop
                backdropShaders.clear()
                liquidGlassShaders.clear()
            }
            val shaderKey = icon to renderSize
            val shader = liquidGlassShaders.getOrPut(shaderKey) { RuntimeShader(LIQUID_GLASS_ICON_AGSL) }
            val sourceScale = backdrop.width.toFloat() / viewWidth.coerceAtLeast(1)
            val sourceShader = backdropShaders.getOrPut(shaderKey) {
                BitmapShader(backdrop, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }

            backdropMatrix.setScale(sourceScale, sourceScale)
            backdropMatrix.postTranslate((cx - renderSize / 2f) * sourceScale, (cy - renderSize / 2f) * sourceScale)
            sourceShader.setLocalMatrix(backdropMatrix)
            shader.setInputShader("content", iconMaskShader(icon, renderSize, size))
            shader.setInputShader("backdrop", sourceShader)
            shader.setFloatUniform("size", renderSize.toFloat(), renderSize.toFloat())
            shader.setFloatUniform("refractionHeight", 5.5f * density)
            shader.setFloatUniform("refractionAmount", -4.8f * density)
            shader.setFloatUniform("depthEffect", 0.16f)
            shader.setFloatUniform("chromaticAberration", 0.72f)
            shader.setFloatUniform(
                "tint",
                Color.red(brand) / 255f,
                Color.green(brand) / 255f,
                Color.blue(brand) / 255f,
                1f,
            )
            glassEffectPaint.color = Color.WHITE
            glassEffectPaint.shader = shader
            val left = cx - renderSize / 2f
            val top = cy - renderSize / 2f
            val save = canvas.save()
            try {
                canvas.translate(left, top)
                canvas.drawRect(0f, 0f, renderSize.toFloat(), renderSize.toFloat(), glassEffectPaint)
            } finally {
                canvas.restoreToCount(save)
            }
        } catch (_: RuntimeException) {
            // 图形驱动/系统不支持当前 AGSL 时，停用故障 Shader 并保持图标可见，不让工具栏闪烁或崩溃。
            liquidGlassShaderUnavailable = true
            liquidGlassShaders.clear()
            backdropShaders.clear()
            drawStableGlassStroke()
        } finally {
            glassEffectPaint.shader = null
        }
    }

    fun onLogo(x: Float) = x < logoSlot

    fun onHide(x: Float, viewWidth: Int) = x > viewWidth - hideSlot

    /** 中间那排 [x] 处的工具；落在空处（全移除了）为 null。 */
    fun toolAt(x: Float, viewWidth: Int): ToolbarTool? {
        if (tools.isEmpty()) return null
        return tools[((x - logoSlot + offset) / middleSlot(viewWidth)).toInt().coerceIn(0, tools.size - 1)]
    }

    /** 工具栏位置上临时放的一句提示（功能要先去设置里配好）：左边提示图标，中间一行字，右边 ›，整条可点。 */
    fun drawNotice(canvas: Canvas, viewWidth: Int, message: String) {
        val cy = barHeight / 2
        stroke.color = palette.brand
        PanelIcons.draw(canvas, Icon.INFO, 30 * density, cy, 22 * density, stroke)
        noticeText.color = palette.text
        noticeText.textSize = 13 * density
        val left = 52 * density
        val right = viewWidth - 44 * density
        val shown = TextUtils.ellipsize(message, noticeText, right - left, TextUtils.TruncateAt.END).toString()
        canvas.drawText(shown, left, cy - (noticeText.descent() + noticeText.ascent()) / 2, noticeText)
        stroke.color = palette.gloss
        PanelIcons.draw(canvas, Icon.CHEVRON, viewWidth - 26 * density, cy, 20 * density, stroke)
    }

    fun draw(canvas: Canvas, viewWidth: Int) {
        val cy = barHeight / 2
        // 改为轻柔的呼吸悬浮：缩放与上下浮动错相循环，不再旋转图标，也不加辉光。
        val logoSize = (30 * density).toInt()
        val logoCentre = (logoSlot / 2).toFloat()
        val phase = (SystemClock.uptimeMillis() % LOGO_CYCLE_MS).toFloat() / LOGO_CYCLE_MS
        val scale = 0.92f + 0.08f * (0.5f - 0.5f * cos(phase * 2f * Math.PI).toFloat())
        val floatY = sin(phase * 2f * Math.PI).toFloat() * 1.4f * density
        canvas.save()
        canvas.translate(logoCentre, cy + floatY)
        canvas.scale(scale, scale)
        canvas.translate(-logoCentre, -cy)
        logo?.alpha = 255
        logo?.setBounds(
            (logoCentre - logoSize / 2).toInt(), (cy - logoSize / 2).toInt(),
            (logoCentre + logoSize / 2).toInt(), (cy + logoSize / 2).toInt(),
        )
        logo?.draw(canvas)
        canvas.restore()

        val liquidGlassEnabled = Settings.preferences(context).getBoolean(Settings.KEY_LIQUID_GLASS_ICONS, true)
        val backdrop = if (liquidGlassEnabled && Build.VERSION.SDK_INT >= 33) backdropProvider?.invoke() else null
        val slot = middleSlot(viewWidth)
        val divider = viewWidth - hideSlot
        canvas.save()
        canvas.clipRect(logoSlot, 0f, divider, barHeight)
        tools.forEachIndexed { i, tool ->
            val cx = logoSlot + slot * (i + 0.5f) - offset
            if (liquidGlassEnabled) drawLiquidGlassIcon(canvas, tool.icon, cx, cy, 26 * density, backdrop, viewWidth)
            else {
                stroke.shader = null
                stroke.color = palette.gloss
                PanelIcons.draw(canvas, tool.icon, cx, cy, 26 * density, stroke)
            }
        }
        canvas.restore()
        if (liquidGlassEnabled) drawLiquidGlassIcon(canvas, Icon.HIDE, viewWidth - hideSlot / 2, cy, 24 * density, backdrop, viewWidth)
        else {
            stroke.shader = null
            stroke.color = palette.gloss
            PanelIcons.draw(canvas, Icon.HIDE, viewWidth - hideSlot / 2, cy, 24 * density, stroke)
        }
    }

    private companion object {
        const val VISIBLE_SLOTS = 5
        const val LOGO_CYCLE_MS = 3_600L
        const val COLOR_TRANSITION_MS = 460f

        private const val LIQUID_GLASS_ICON_AGSL = """
            uniform shader content;
            uniform shader backdrop;
            uniform float2 size;
            uniform float refractionHeight;
            uniform float refractionAmount;
            uniform float depthEffect;
            uniform float chromaticAberration;
            uniform float4 tint;

            float circleMap(float x) {
                return 1.0 - sqrt(max(1.0 - x * x, 0.0));
            }

            half4 main(float2 coord) {
                half4 mask = content.eval(coord);
                if (mask.a <= 0.001) return half4(0.0);

                float2 texel = float2(1.0, 1.0);
                float gx = content.eval(coord + float2(texel.x, 0.0)).a -
                    content.eval(coord - float2(texel.x, 0.0)).a;
                float gy = content.eval(coord + float2(0.0, texel.y)).a -
                    content.eval(coord - float2(0.0, texel.y)).a;
                float2 gradient = float2(gx, gy);
                float gradientLength = length(gradient);
                float2 halfSize = size * 0.5;
                float2 centeredCoord = coord - halfSize;

                // Backdrop 的 lens() 用圆角矩形 SDF；这里以图标 alpha-mask 的梯度估算 glyph SDF，
                // 保留同一 circleMap、法线折射和色散采样流程，折射边缘跟随真实图标轮廓。
                float sd = (0.5 - mask.a) / max(gradientLength, 0.0001);
                half4 original = backdrop.eval(coord);
                if (-sd >= refractionHeight) {
                    half3 baseColor = mix(original.rgb, tint.rgb, 0.16);
                    return half4(baseColor * mask.a, mask.a);
                }
                sd = min(sd, 0.0);

                float d = circleMap(clamp(1.0 - -sd / refractionHeight, 0.0, 1.0)) * refractionAmount;
                float2 normal = normalize(-gradient + depthEffect * normalize(centeredCoord + float2(0.0001, 0.0001)));
                float2 refractedCoord = coord + d * normal;
                float dispersionIntensity = chromaticAberration *
                    ((centeredCoord.x * centeredCoord.y) / max(halfSize.x * halfSize.y, 0.0001));
                float2 dispersedCoord = d * normal * dispersionIntensity;

                half4 color = half4(0.0);
                half4 red = backdrop.eval(refractedCoord + dispersedCoord);
                color.r += red.r / 3.5;
                color.a += red.a / 7.0;

                half4 orange = backdrop.eval(refractedCoord + dispersedCoord * (2.0 / 3.0));
                color.r += orange.r / 3.5;
                color.g += orange.g / 7.0;
                color.a += orange.a / 7.0;

                half4 yellow = backdrop.eval(refractedCoord + dispersedCoord * (1.0 / 3.0));
                color.r += yellow.r / 3.5;
                color.g += yellow.g / 3.5;
                color.a += yellow.a / 7.0;

                half4 green = backdrop.eval(refractedCoord);
                color.g += green.g / 3.5;
                color.a += green.a / 7.0;

                half4 cyan = backdrop.eval(refractedCoord - dispersedCoord * (1.0 / 3.0));
                color.g += cyan.g / 3.5;
                color.b += cyan.b / 3.0;
                color.a += cyan.a / 7.0;

                half4 blue = backdrop.eval(refractedCoord - dispersedCoord * (2.0 / 3.0));
                color.b += blue.b / 3.0;
                color.a += blue.a / 7.0;

                half4 purple = backdrop.eval(refractedCoord - dispersedCoord);
                color.r += purple.r / 7.0;
                color.b += purple.b / 3.0;
                color.a += purple.a / 7.0;

                float edge = clamp(gradientLength * 1.65, 0.0, 1.0);
                float facing = max(dot(normal, normalize(float2(-0.72, -0.68))), 0.0);
                float highlight = edge * (0.12 + facing * 0.36);
                color.rgb = mix(color.rgb, tint.rgb, 0.16);
                color.rgb += half3(highlight);
                float alpha = mask.a * 0.94;
                return half4(color.rgb * alpha, alpha);
            }
        """
    }
}
