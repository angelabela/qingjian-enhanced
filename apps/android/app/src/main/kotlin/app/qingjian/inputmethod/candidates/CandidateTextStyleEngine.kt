package app.qingjian.inputmethod.candidates

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
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
                preedit = Style(color, 0.72f, weight.weight),
                gloss = Style(color, 0.68f, 400),
                selected = Style(color, 1.08f, maxOf(600, weight.weight)),
            )
        }
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

    fun paint(role: Role): TextPaint = paints.getValue(role)

    /** 清除旧位图字形并按指定角色重绘；调用方需在已绘制 Rust 位图的 saveLayer 内使用。 */
    fun drawReplacing(canvas: Canvas, text: String, rect: RectF, role: Role, eraseSize: Float = baseTextSize) {
        if (text.isEmpty() || rect.isEmpty) return
        val paint = paint(role)
        val x = rect.centerX()
        val originalSize = paint.textSize
        // 词组可能比单字宽很多：在当前候选格内等比缩小文字，避免裁掉半个字。
        val availableWidth = (rect.width() - 8f * density).coerceAtLeast(1f)
        val measuredWidth = paint.measureText(text)
        if (measuredWidth > availableWidth && measuredWidth > 0f) {
            paint.textSize = (originalSize * availableWidth / measuredWidth).coerceAtLeast(originalSize * 0.62f)
        }
        val baseline = rect.centerY() - (paint.ascent() + paint.descent()) / 2f
        canvas.save()
        canvas.clipRect(rect)
        clearPaint.textSize = eraseSize
        clearPaint.textAlign = Paint.Align.CENTER
        clearPaint.typeface = paint.typeface
        clearPaint.style = Paint.Style.STROKE
        clearPaint.strokeWidth = 3.5f * density
        canvas.drawText(text, x, baseline, clearPaint)
        clearPaint.style = Paint.Style.FILL
        canvas.drawText(text, x, baseline, clearPaint)
        if (paint.textSize > 0f) {
            val style = config.style(role)
            style.strokeColor?.let { color ->
                val stroke = TextPaint(paint).apply {
                    this.color = color
                    this.style = Paint.Style.STROKE
                    strokeWidth = style.strokeWidthDp.coerceAtLeast(0f) * density
                    clearShadowLayer()
                }
                canvas.drawText(text, x, baseline, stroke)
            }
        }
        canvas.drawText(text, x, baseline, paint)
        paint.textSize = originalSize
        canvas.restore()
    }
}
