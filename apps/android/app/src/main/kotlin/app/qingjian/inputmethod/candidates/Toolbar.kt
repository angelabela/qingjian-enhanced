package app.qingjian.inputmethod.candidates

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
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
    context: Context,
    private val palette: Palette,
    private val density: Float,
    private val barHeight: Float,
    private val tools: List<ToolbarTool>,
) {
    private val logo = context.getDrawable(R.drawable.ic_brand)
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
    private var liquidGlassShader: RuntimeShader? = null

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

    /**
     * iOS Liquid Glass 风格的图标底片：半透明材质保留后方动态背景，
     * RuntimeShader 负责柔和的边缘折射感、高光带与主题色融合；API 26–32 使用 Canvas 降级绘制。
     * 这里不套整棵 View 树的 RenderEffect，避免候选文字与键盘按键被一起扭曲。
     */
    private fun drawLiquidGlassIcon(canvas: Canvas, icon: Icon, cx: Float, cy: Float, size: Float) {
        val tileSize = size * 1.52f
        val left = cx - tileSize / 2f
        val top = cy - tileSize / 2f
        val radius = tileSize * 0.30f
        val brand = palette.brand
        val shader = if (Build.VERSION.SDK_INT >= 33) {
            liquidGlassShader ?: RuntimeShader(LIQUID_GLASS_AGSL).also { liquidGlassShader = it }
        } else null

        canvas.save()
        canvas.translate(left, top)
        if (shader != null) {
            shader.setFloatUniform("resolution", tileSize, tileSize)
            shader.setFloatUniform("time", (SystemClock.uptimeMillis() % 12_000L) / 12_000f)
            shader.setFloatUniform(
                "tint",
                Color.red(brand) / 255f,
                Color.green(brand) / 255f,
                Color.blue(brand) / 255f,
                1f,
            )
            glassPaint.style = Paint.Style.FILL
            glassPaint.shader = shader
            canvas.drawRoundRect(0f, 0f, tileSize, tileSize, radius, radius, glassPaint)
            glassPaint.shader = null
        } else {
            glassPaint.style = Paint.Style.FILL
            glassPaint.shader = LinearGradient(
                0f, 0f, tileSize, tileSize,
                intArrayOf(withAlpha(Color.WHITE, 68), withAlpha(brand, 30), withAlpha(Color.WHITE, 18)),
                floatArrayOf(0f, 0.58f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(0f, 0f, tileSize, tileSize, radius, radius, glassPaint)
            glassPaint.shader = null
        }
        glassPaint.style = Paint.Style.STROKE
        glassPaint.strokeWidth = 0.85f * density
        glassPaint.color = withAlpha(Color.WHITE, if (palette.dark) 126 else 166)
        canvas.drawRoundRect(0.5f * density, 0.5f * density, tileSize - 0.5f * density,
            tileSize - 0.5f * density, radius, radius, glassPaint)
        glassPaint.style = Paint.Style.FILL
        glassPaint.shader = LinearGradient(
            0f, 1f * density, tileSize * 0.88f, tileSize * 0.42f,
            withAlpha(Color.WHITE, if (palette.dark) 92 else 118),
            withAlpha(brand, 0),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(1.5f * density, 1.5f * density, tileSize - 1.5f * density,
            tileSize * 0.30f, radius * 0.72f, radius * 0.72f, glassPaint)
        glassPaint.shader = null
        canvas.restore()

        val hsv = FloatArray(3)
        Color.colorToHSV(brand, hsv)
        val highlight = Color.HSVToColor(floatArrayOf(hsv[0], (hsv[1] * 0.48f).coerceIn(0.12f, 0.55f), 1f))
        stroke.shader = LinearGradient(
            cx - size * 0.52f, cy - size * 0.52f, cx + size * 0.52f, cy + size * 0.52f,
            highlight, brand, Shader.TileMode.CLAMP,
        )
        stroke.color = brand
        PanelIcons.draw(canvas, icon, cx, cy, size, stroke)
        stroke.shader = null
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
        val slot = middleSlot(viewWidth)
        val divider = viewWidth - hideSlot
        canvas.save()
        canvas.clipRect(logoSlot, 0f, divider, barHeight)
        tools.forEachIndexed { i, tool ->
            val cx = logoSlot + slot * (i + 0.5f) - offset
            if (liquidGlassEnabled) drawLiquidGlassIcon(canvas, tool.icon, cx, cy, 26 * density)
            else {
                stroke.shader = null
                stroke.color = palette.gloss
                PanelIcons.draw(canvas, tool.icon, cx, cy, 26 * density, stroke)
            }
        }
        canvas.restore()
        line.strokeWidth = density
        line.color = palette.faint
        canvas.drawLine(logoSlot, cy - 12 * density, logoSlot, cy + 12 * density, line)
        canvas.drawLine(divider, cy - 12 * density, divider, cy + 12 * density, line)
        if (liquidGlassEnabled) drawLiquidGlassIcon(canvas, Icon.HIDE, viewWidth - hideSlot / 2, cy, 24 * density)
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

        private const val LIQUID_GLASS_AGSL = """
            uniform float2 resolution;
            uniform float time;
            uniform float4 tint;

            half4 main(float2 p) {
                float2 uv = p / resolution;
                float2 q = uv * 2.0 - 1.0;
                float edgeDistance = max(abs(q.x), abs(q.y));
                float edgeMask = 1.0 - smoothstep(0.88, 1.0, edgeDistance);
                float rim = smoothstep(0.56, 0.80, edgeDistance) *
                    (1.0 - smoothstep(0.82, 0.98, edgeDistance));
                float sweepPosition = fract(time * 0.11 + uv.y * 0.22);
                float sweep = exp(-pow((uv.x - sweepPosition) * 7.0, 2.0));
                float topGlow = 1.0 - smoothstep(0.0, 0.72, uv.y);
                float alpha = (0.13 + rim * 0.18 + sweep * 0.10 + topGlow * 0.035) * edgeMask;
                float mixAmount = clamp(rim * 0.42 + sweep * 0.52 + topGlow * 0.10, 0.0, 1.0);
                half3 baseColor = mix(tint.rgb, half3(1.0), mixAmount);
                return half4(baseColor * alpha, alpha);
            }
        """
    }
}
