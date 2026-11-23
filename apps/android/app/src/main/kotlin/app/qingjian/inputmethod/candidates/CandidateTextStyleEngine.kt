package app.qingjian.inputmethod.candidates

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.text.TextPaint
import app.qingjian.inputmethod.settings.Settings

/**
 * 候选栏文字样式引擎。
 *
 * 五种语义角色各自持有独立配置：首选词、普通候选、拼音、释义、选中候选。
 * Rust 只提供字符串、布局矩形和命中区域；所有文字样式由 Android Canvas 在此处绘制。
 * 后续设置页只需生成 Config，不需要修改 Rust/JNI 或候选布局。
 */
class CandidateTextStyleEngine(
    private val density: Float,
    private val baseTextSize: Float,
    private val defaultColor: Int,
    private val defaultWeight: Settings.FontWeight,
    val config: Config = Config.defaults(defaultColor, defaultWeight),
) {
    enum class Role { FIRST_CANDIDATE, CANDIDATE, PREEDIT, GLOSS, SELECTED }

    data class Style(
        val color: Int,
        val sizeScale: Float = 1f,
        val weight: Int = 400,
        val typefaceFamily: String = "sans-serif",
        val strokeColor: Int? = null,
        val strokeWidthDp: Float = 0f,
        val shadowColor: Int? = null,
        val shadowRadiusDp: Float = 0f,
        val shadowDxDp: Float = 0f,
        val shadowDyDp: Float = 0f,
        val alpha: Int = 255,
    )

    data class Config(
        val firstCandidate: Style,
        val candidate: Style,
        val preedit: Style,
        val gloss: Style,
        val selected: Style,
        val preset: Settings.TextEffectPreset = Settings.TextEffectPreset.STANDARD,
    ) {
        fun style(role: Role): Style = when (role) {
            Role.FIRST_CANDIDATE -> firstCandidate
            Role.CANDIDATE -> candidate
            Role.PREEDIT -> preedit
            Role.GLOSS -> gloss
            Role.SELECTED -> selected
        }

        companion object {
            fun defaults(color: Int, weight: Settings.FontWeight) = Config(
                firstCandidate = Style(color, 1.08f, weight.weight),
                candidate = Style(color, 1f, weight.weight),
                preedit = Style(color, 0.87f, maxOf(600, weight.weight), typefaceFamily = "sans-serif-medium"),
                gloss = Style(color, 0.62f, 400),
                selected = Style(color, 1.08f, maxOf(600, weight.weight)),
            )

            fun forPreset(color: Int, weight: Settings.FontWeight, preset: Settings.TextEffectPreset): Config {
                val base = defaults(color, weight)
                fun styled(style: Style): Style = when (preset) {
                    Settings.TextEffectPreset.STANDARD -> style
                    Settings.TextEffectPreset.BOLD_DEPTH -> style.copy(
                        sizeScale = style.sizeScale * 1.04f, weight = maxOf(700, style.weight),
                        shadowColor = 0x66000000, shadowRadiusDp = 1.2f, shadowDyDp = 1f,
                    )
                    Settings.TextEffectPreset.SOFT_GLOW -> style.copy(
                        shadowColor = color, shadowRadiusDp = 5f, shadowDxDp = 0f, shadowDyDp = 0f,
                    )
                    Settings.TextEffectPreset.NEON_OUTLINE -> style.copy(
                        strokeColor = color, strokeWidthDp = 1.15f,
                        shadowColor = color, shadowRadiusDp = 3f,
                    )
                    Settings.TextEffectPreset.CONTRAST_OUTLINE -> style.copy(
                        strokeColor = android.graphics.Color.WHITE,
                        strokeWidthDp = 1.35f,
                    )
                    Settings.TextEffectPreset.COLOR_GRADIENT -> style
                }
                return Config(styled(base.firstCandidate), styled(base.candidate), styled(base.preedit),
                    styled(base.gloss), styled(base.selected), preset)
            }
        }
    }

    private var adaptiveTextColor: Int? = null
    private var transitionAlpha: Int = 255

    /** 候选文字淡入透明度；不影响候选格背景与分隔线。 */
    fun setTransitionAlpha(alpha: Int) {
        transitionAlpha = alpha.coerceIn(0, 255)
    }
    private val paints = Role.entries.associateWith { role ->
        TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { applyStyle(this, config.style(role)) }
    }
    private val clearPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.TRANSPARENT
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private fun applyStyle(paint: TextPaint, style: Style) {
        paint.color = style.color
        // 绘制坐标统一使用候选格中心；测量与绘制必须共享同一对齐方式。
        paint.textAlign = Paint.Align.CENTER
        paint.alpha = style.alpha.coerceIn(0, 255)
        paint.textSize = baseTextSize * style.sizeScale.coerceIn(0.5f, 2.5f)
        paint.typeface = android.graphics.Typeface.create(style.typefaceFamily, if (style.weight >= 600) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        paint.isFakeBoldText = style.weight >= 600
        paint.style = Paint.Style.FILL
        paint.clearShadowLayer()
        style.shadowColor?.takeIf { style.shadowRadiusDp > 0f }?.let {
            paint.setShadowLayer(style.shadowRadiusDp * density, style.shadowDxDp * density, style.shadowDyDp * density, it)
        }
    }

    fun setAdaptiveTextColor(color: Int?) {
        adaptiveTextColor = color
        paints.forEach { (role, paint) -> paint.color = color ?: config.style(role).color }
    }

    fun paint(role: Role): TextPaint = paints.getValue(role)

    private fun relativeLuminance(color: Int): Float {
        fun channel(value: Int): Float {
            val c = value / 255f
            return if (c <= 0.04045f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        }
        return 0.2126f * channel(android.graphics.Color.red(color)) +
            0.7152f * channel(android.graphics.Color.green(color)) +
            0.0722f * channel(android.graphics.Color.blue(color))
    }

    /** 清除旧位图字形并按指定角色重绘；调用方需在已绘制 Rust 位图的 saveLayer 内使用。 */
    fun drawReplacing(canvas: Canvas, text: String, rect: RectF, role: Role, eraseUnderlay: Boolean = true, alphaMultiplier: Int = 255) {
        if (text.isEmpty() || rect.isEmpty) return
        val paint = paint(role)
        val x = rect.centerX()
        val originalSize = paint.textSize
        val originalAlpha = paint.alpha
        paint.alpha = (originalAlpha * transitionAlpha * alphaMultiplier.coerceIn(0, 255) / 65_025).coerceIn(0, 255)
        // 词组可能比单字宽很多：在当前候选格内等比缩小文字，避免裁掉半个字。
        // 文字必须完整落在候选格内：同时按宽度和高度缩放，不再设置 62% 的硬下限，
        // 否则较长词组即使缩小后仍会被 clipRect 从左右两侧裁掉。
        val availableWidth = (rect.width() - 2f * density).coerceAtLeast(1f)
        val availableHeight = (rect.height() - 2f * density + if (role == Role.PREEDIT) 6f * density else 0f).coerceAtLeast(1f)
        val measuredWidth = paint.measureText(text)
        val isCandidateRole = role == Role.FIRST_CANDIDATE || role == Role.CANDIDATE || role == Role.SELECTED
        // 常用语/整句补全过长时保留正常字号并在候选格内省略，避免整段文字被压缩成极小字号。
        val displayText = if (isCandidateRole && measuredWidth > availableWidth) {
            TextUtils.ellipsize(text, paint, availableWidth, TextUtils.TruncateAt.END).toString()
        } else text
        val displayWidth = paint.measureText(displayText)
        val measuredHeight = (paint.descent() - paint.ascent()).coerceAtLeast(1f)
        val fitScale = if (role == Role.PREEDIT) {
            // 拼音逐字定位时，字符边界可能很窄；必须同时按宽、高 fit。
            // 旧逻辑只按高度放大，并把裁剪框扩到相邻字母区域，容易造成字母重叠。
            minOf(
                1.12f,
                availableWidth / displayWidth.coerceAtLeast(1f),
                availableHeight / measuredHeight,
            )
        } else minOf(
            1f,
            availableWidth / measuredWidth.coerceAtLeast(1f),
            availableHeight / measuredHeight,
        )
        // 不设置缩放下限：极长词组也必须完整落入候选格，不能再被硬裁切。
        paint.textSize = originalSize * fitScale
        val baseline = rect.centerY() - (paint.ascent() + paint.descent()) / 2f
        canvas.save()
        val clipRect = if (role == Role.PREEDIT) {
            // 保持每个字母的真实边界，不向左右扩展侵入相邻字母。
            RectF(rect.left, rect.top - 2f * density, rect.right, rect.bottom + 2f * density)
        } else rect
        canvas.clipRect(clipRect)
        // 仅拼音行仍保留 Rust 字形，需要擦除后覆盖；候选词和注释已在 Rust 主题层关闭文字绘制，
        // 不再用 CLEAR 擦背景，避免把高亮底色擦出透明洞。
        if (eraseUnderlay) {
            clearPaint.textSize = when (role) {
                Role.FIRST_CANDIDATE, Role.CANDIDATE, Role.SELECTED -> baseTextSize * 0.95f
                // Rust strip_top 使用 annotation 字号（12dp × 1.25），约为基础候选字号的 0.75。
                Role.PREEDIT -> baseTextSize * 1.12f
                Role.GLOSS -> baseTextSize * 0.575f
            }
            clearPaint.textAlign = Paint.Align.CENTER
            clearPaint.typeface = android.graphics.Typeface.create(
                "sans-serif",
                if (defaultWeight.weight >= 600) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL,
            )
            clearPaint.isFakeBoldText = defaultWeight.weight >= 600
            clearPaint.style = Paint.Style.STROKE
            clearPaint.strokeWidth = 4f * density
            val eraseBaseline = rect.centerY() - (clearPaint.ascent() + clearPaint.descent()) / 2f
            canvas.drawText(text, x, eraseBaseline, clearPaint)
            clearPaint.style = Paint.Style.FILL
            canvas.drawText(text, x, eraseBaseline, clearPaint)
        }
        if (paint.textSize > 0f) {
            val style = config.style(role)
            val strokeColor = if (config.preset == Settings.TextEffectPreset.CONTRAST_OUTLINE) {
                if (relativeLuminance(adaptiveTextColor ?: style.color) > 0.5f) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            } else style.strokeColor
            strokeColor?.let { color ->
                val stroke = TextPaint(paint).apply {
                    this.color = color
                    this.style = Paint.Style.STROKE
                    strokeWidth = style.strokeWidthDp.coerceAtLeast(0f) * density
                    clearShadowLayer()
                }
                canvas.drawText(displayText, x, baseline, stroke)
            }
        }
        if (config.preset == Settings.TextEffectPreset.COLOR_GRADIENT && adaptiveTextColor == null) {
            paint.shader = android.graphics.LinearGradient(
                rect.left, rect.top, rect.right, rect.bottom,
                intArrayOf(0xFF45D7FF.toInt(), 0xFF8B65FF.toInt(), 0xFFFF6FB5.toInt()),
                null, android.graphics.Shader.TileMode.CLAMP,
            )
        }
        canvas.drawText(displayText, x, baseline, paint)
        paint.shader = null
        paint.textSize = originalSize
        paint.alpha = originalAlpha
        canvas.restore()
    }
}
