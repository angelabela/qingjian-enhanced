package app.qingjian.inputmethod.keyboard.paint

import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import app.qingjian.inputmethod.keyboard.ShiftState
import app.qingjian.inputmethod.keyboard.SideColumn
import app.qingjian.inputmethod.keyboard.layout.Key
import app.qingjian.inputmethod.keyboard.layout.KeySlot
import app.qingjian.inputmethod.keyboard.layout.KeyboardMetrics
import app.qingjian.inputmethod.theme.Palette
import app.qingjian.inputmethod.settings.Settings

/** 画键与按键气泡。 */
class KeyPainter(
    private val palette: Palette,
    private val metrics: KeyboardMetrics,
    /** 键面字号档的倍数（键盘设置「键面字号」），键上的主字与气泡一起缩放。 */
    private val textScale: Float,
    private val keySkin: Settings.KeySkin = Settings.KeySkin.ORIGINAL,
    private val fontWeight: Settings.FontWeight = Settings.FontWeight.NORMAL,
    private var keyBackgroundAlpha: Float = 1f,
    private var keyBackgroundFrosted: Boolean = false,
    private val textEffectPreset: Settings.TextEffectPreset = Settings.TextEffectPreset.STANDARD,
) {
    private val density = metrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fontTypeface = fontWeight.typeface
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = fontTypeface
        // 对中粗、粗体再叠加轻微合成加粗，低端字体没有可变字重时也能明显区分。
        isFakeBoldText = fontWeight.weight >= 600
    }
    private val bubble = RectF()
    private var marqueeEnabled = false
    private val gestureHint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun setKeySurface(alpha: Float, frosted: Boolean) {
        keyBackgroundAlpha = alpha.coerceIn(0f, 1f)
        keyBackgroundFrosted = frosted
    }

    fun setMarqueeEnabled(enabled: Boolean) { marqueeEnabled = enabled }

    var shift = ShiftState.OFF
    var chinese = true

    /** 中英键上的字（当前语言）与角上的小字（按一下切到的那种）。 */
    var languageLabel = "中"
    var languageHint = ""

    /** 回车键上的字（发送、搜索…）；null 画回车图标。 */
    var enterLabel: String? = null

    fun drawKey(canvas: Canvas, slot: KeySlot, down: Boolean) {
        val key = slot.key
        val bounds = slot.bounds
        val brand = key is Key.Enter
        drawPlate(
            canvas, bounds,
            keyFillColor(key, down, brand),
        )
        if (marqueeEnabled && key is Key.Letter) key.label.firstOrNull()?.let { drawLetterMarquee(canvas, bounds, it) }
        if (brand && down) {
            fill.color = PRESSED_BRAND_SHADE
            canvas.drawRoundRect(bounds, metrics.radius, metrics.radius, fill)
        }

        // 锁定大写：在亮着的底色上再加右上角一个品牌色小点，和「只大写一次」分开
        if (shift == ShiftState.LOCKED && (key == Key.Shift || (key as? Key.Letter)?.flickShift == true)) {
            fill.color = if (keySkin == Settings.KeySkin.ORIGINAL) palette.brand else keySkin.pressed
            canvas.drawCircle(bounds.right - LOCK_DOT_INSET_DP * density, bounds.top + LOCK_DOT_INSET_DP * density, LOCK_DOT_DP * density, fill)
        }

        // 角标先画会把 ink 换成浅色，正文的颜色另存一份
        val labelColor = if (keySkin == Settings.KeySkin.ORIGINAL) { if (brand) palette.onBrand else palette.text } else keySkin.foreground
        ink.color = labelColor
        val label = label(key)
        when {
            key is Key.Space ->
                KeyIcons.drawMicrophone(canvas, bounds.centerX(), bounds.centerY() - 1f * density, 20f * density, ink)
            label.isEmpty() ->
                KeyIcons.draw(canvas, key, bounds.centerX(), bounds.centerY(), 24 * density, ink, shift)
            // 兼作大写的 `;`：上方小 Shift 图标（上滑出它），中间 `;`，下方韵母
            key is Key.Letter && key.flickShift -> {
                ink.color = secondaryColor
                KeyIcons.draw(canvas, Key.Shift, bounds.centerX(), bounds.top + bounds.height() * 0.22f, 13 * density, ink, shift)
                text(canvas, label, 19f, labelColor, bounds.centerX(), bounds.top + bounds.height() * 0.5f)
                fitted(canvas, key.finals, 9f, secondaryColor, bounds.centerX(), bounds.top + bounds.height() * 0.8f, bounds.width() * 0.92f)
            }

            // 双拼：上方翘舌声母（没有就印角标），中间字母，下方韵母
            key is Key.Letter && (key.initial.isNotEmpty() || key.finals.isNotEmpty()) -> {
                val top = key.initial.ifEmpty { key.hint }
                val topColor = if (keySkin == Settings.KeySkin.ORIGINAL && key.initial.isNotEmpty()) palette.brand else secondaryColor
                text(canvas, top, 10f, topColor, bounds.centerX(), bounds.top + bounds.height() * 0.2f)
                text(canvas, label, 19f, labelColor, bounds.centerX(), bounds.top + bounds.height() * 0.5f)
                fitted(canvas, key.finals, 9f, secondaryColor, bounds.centerX(), bounds.top + bounds.height() * 0.8f, bounds.width() * 0.92f)
            }

            key is Key.Letter && key.hint.isNotEmpty() -> {
                text(canvas, key.hint, 11f, secondaryColor, bounds.centerX(), bounds.top + bounds.height() * 0.24f)
                text(canvas, label, 21f, labelColor, bounds.centerX(), bounds.top + bounds.height() * belowHint)
            }

            // 逗号键同时承载上滑句号：逗号与字母主字对齐，句号提示由 drawGestureHints 放在上方。
            key is Key.Text && key.choices.isNotEmpty() -> {
                text(canvas, label, 21f, labelColor, bounds.centerX(), bounds.top + bounds.height() * belowHint)
            }

            // 中英键：角上写按一下会切到哪种语言，长按换
            key == Key.Language && languageHint.isNotEmpty() -> {
                text(canvas, languageHint, 10f, secondaryColor, bounds.right - bounds.width() * 0.22f, bounds.top + bounds.height() * 0.24f)
                text(canvas, label, 17f, labelColor, bounds.centerX(), bounds.centerY())
            }

            key is Key.Digit -> {
                text(canvas, key.digit.toString(), 11f, secondaryColor, bounds.centerX(), bounds.top + bounds.height() * 0.24f)
                text(canvas, label, 18f, labelColor, bounds.centerX(), bounds.top + bounds.height() * belowHint)
            }

            else -> {
                val size = when (key) {
                    is Key.Letter -> 22f
                    is Key.Zhuyin -> 20f
                    is Key.Text -> 20f
                    is Key.Space -> 13f
                    else -> 15f
                }
                text(canvas, label, size, if (key is Key.Space) secondaryColor else labelColor, bounds.centerX(), bounds.centerY() - if (key is Key.Space) 4f * density else 0f)
            }
        }


    }

    /** 手势提示直接显示在键帽内：上滑靠上、下滑靠下；优先完整文字，确实放不下才换语义图标。 */
    /** A-Z 字母按顺序追逐发光，带渐隐光尾；功能键不参与。 */
    private fun drawLetterMarquee(canvas: Canvas, bounds: RectF, letter: Char) {
        // 按真实 QWERTY 键位依次追逐：先 QWERTY 顶排，再 ASDF...、ZXCV...
        val index = QWERTY_ORDER.indexOf(letter.lowercaseChar())
        if (index < 0) return
        val now = SystemClock.uptimeMillis()
        // 2.6s -> 6.5s：速度降低 60%（保留原有亮度/拖尾观感）。
        val phase = (now % MARQUEE_CYCLE_MS).toFloat() / MARQUEE_STEP_MS
        val trail = (phase - index + 26f) % 26f
        if (trail > 4.8f) return
        val travelStrength = (1f - trail / 4.8f).coerceIn(0f, 1f)
        // 呼吸光：缓慢明暗起伏，不改变字母追逐顺序。
        val breathePhase = (now % BREATH_CYCLE_MS).toFloat() / BREATH_CYCLE_MS
        val breathe = 0.68f + 0.32f * (0.5f + 0.5f * kotlin.math.sin(breathePhase * (2f * Math.PI).toFloat()))
        val strength = travelStrength * breathe
        val hue = (172f + phase * (360f / 26f)) % 360f
        val color = Color.HSVToColor((48 + 207 * strength).toInt(), floatArrayOf(hue, 0.92f, 1f))
        val inset = 0.8f * density
        val outline = RectF(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset)
        fill.shader = null
        fill.style = Paint.Style.STROKE
        fill.strokeWidth = (0.7f + 1.5f * strength) * density
        fill.color = color
        fill.setShadowLayer(4.5f * density * strength, 0f, 0f, color)
        canvas.drawRoundRect(outline, (metrics.radius - inset).coerceAtLeast(1f), (metrics.radius - inset).coerceAtLeast(1f), fill)
        fill.clearShadowLayer()
        fill.style = Paint.Style.FILL
    }

    fun drawGestureHints(canvas: Canvas, slot: KeySlot, upLabel: String?, downLabel: String?) {
        if (upLabel == null && downLabel == null) return
        val b = slot.bounds
        val onBrand = slot.key is Key.Enter
        val hintWidth = (b.width() - 8f * density).coerceAtLeast(10f * density)
        if (upLabel != null) {
            drawGestureHint(
                canvas, b.centerX(),
                if (upLabel == "。" || upLabel == ".") b.top + b.height() * 0.24f else b.top + 11f * density,
                upLabel, upward = true, onBrand = onBrand, maxLabelWidth = hintWidth,
            )
        }
        if (downLabel != null) {
            drawGestureHint(
                canvas, b.centerX(), b.bottom - 4f * density, downLabel,
                upward = false, onBrand = onBrand, maxLabelWidth = hintWidth,
            )
        }
    }

    private fun drawGestureHint(
        canvas: Canvas,
        x: Float,
        baseline: Float,
        label: String,
        upward: Boolean,
        onBrand: Boolean,
        maxLabelWidth: Float,
    ) {
        ink.typeface = fontTypeface
        ink.textAlign = Paint.Align.CENTER
        ink.textSize = 8.5f * density
        val punctuationOnly = upward && (label == "。" || label == ".")
        val arrow = if (upward) "↑" else "↓"
        val fullText = "$arrow$label"
        val useText = ink.measureText(fullText) <= maxLabelWidth
        val symbolText = "$arrow${gestureHintSymbol(label, upward)}"
        val shown = when {
            punctuationOnly -> label
            useText -> fullText
            ink.measureText(symbolText) <= maxLabelWidth -> symbolText
            else -> arrow
        }
        ink.textSize = (if (punctuationOnly) 12f else if (useText) 8.5f else 11f) * density
        // 只有完整动作文字超出键帽可用宽度时才退化为图标。
        ink.color = if (onBrand) labelColorForBrand() else secondaryColor
        ink.clearShadowLayer()
        canvas.drawText(shown, x, baseline, ink)
        ink.textAlign = Paint.Align.CENTER
    }

    private fun gestureHintSymbol(label: String, upward: Boolean): String = when (label) {
        "清空输入", "删除一个字" -> "⌫"
        "撤销清空", "撤销" -> "↶"
        "重做" -> "↷"
        "打开剪贴板" -> "▤"
        "全选" -> "▣"
        "剪切" -> "✂"
        "复制" -> "▢"
        "粘贴" -> "▣"
        "打开符号面板" -> "⌘"
        "选择皮肤" -> "◈"
        "执行回车" -> "↵"
        "切换中英文" -> "文"
        "打开扫码" -> "▦"
        "AI 对话一键生成" -> "✧"
        "AI 情景表达一键生成" -> "✦"
        else -> if (upward) "↑" else "↓"
    }

    /** 按下的键上方放大显示 [text]（缺省是键面的字，上滑时是要出的那个）；顶排的气泡会画到候选条上。 */
    fun drawBubble(canvas: Canvas, slot: KeySlot, viewWidth: Int, text: String = label(slot.key)) {
        val bounds = slot.bounds
        val grow = 7f * density
        bubble.set(
            (bounds.left - grow).coerceAtLeast(metrics.gapX),
            bounds.top - metrics.rowHeight - 2 * density,
            (bounds.right + grow).coerceAtMost(viewWidth - metrics.gapX),
            bounds.top - 4 * density,
        )
        drawPlate(canvas, bubble, if (keySkin == Settings.KeySkin.ORIGINAL) palette.surface else keySkin.base)
        text(canvas, text, 30f, if (keySkin == Settings.KeySkin.ORIGINAL) palette.text else keySkin.foreground, bubble.centerX(), bubble.centerY())
    }

    /** 拼音列：一块圆角面板，项目竖排。 */
    fun drawSide(canvas: Canvas, side: SideColumn) {
        fill.color = palette.functionalKey
        canvas.drawRoundRect(side.bounds, metrics.radius, metrics.radius, fill)
        canvas.save()
        canvas.clipRect(side.bounds)
        side.visibleItems { item, cy -> text(canvas, item, 17f, palette.text, side.bounds.centerX(), cy) }
        canvas.restore()
    }

    private fun label(key: Key) = when (key) {
        // 字母键始终跟随 Shift 显示大小写；中文拼音状态默认小写。
        is Key.Letter -> if (shift.on) key.label.uppercase() else key.label.lowercase()
        Key.Language -> languageLabel
        is Key.Enter -> enterLabel.orEmpty()
        else -> key.label
    }

    /** 空格键剪贴板手势只显示文字，不再绘制剪贴板和箭头图标。 */
    private fun drawClipboardSwipeHint(canvas: Canvas, bounds: RectF) {
        ink.typeface = fontTypeface
        ink.textSize = 8f * density
        ink.color = secondaryColor
        ink.clearShadowLayer()
        canvas.drawText("剪贴板", bounds.centerX(), bounds.bottom - 5f * density, ink)
        ink.textAlign = Paint.Align.CENTER
    }

    /** 键帽颜色跟随皮肤变化；轮廓、边界与圆角半径始终沿用原来的 bounds。 */
    private fun keyFillColor(key: Key, down: Boolean, brand: Boolean): Int {
        if (keySkin == Settings.KeySkin.ORIGINAL) return when {
            brand -> palette.brand
            down || ((key == Key.Shift || (key as? Key.Letter)?.flickShift == true) && shift.on) -> palette.accent
            key.functional -> palette.functionalKey
            else -> palette.surface
        }
        return when {
            down || ((key == Key.Shift || (key as? Key.Letter)?.flickShift == true) && shift.on) -> keySkin.pressed
            brand -> keySkin.pressed
            key.functional -> keySkin.functional
            else -> keySkin.base
        }
    }

    private val secondaryColor: Int
        get() = if (keySkin == Settings.KeySkin.ORIGINAL) palette.gloss else keySkin.secondary

    private fun labelColorForBrand(): Int =
        if (keySkin == Settings.KeySkin.ORIGINAL) palette.onBrand else keySkin.foreground

    /** 带底影与顶部柔和高光的圆角键帽；不改变原有按键形状。 */
    private fun drawPlate(canvas: Canvas, bounds: RectF, color: Int) {
        // Paint.color 会覆盖 Paint.alpha；之前先设置 alpha、再设置 color，导致滑块值
        // 被颜色赋值重置为 255，按键透明度看起来完全没有变化。
        fill.shader = null
        fill.style = Paint.Style.FILL
        fill.color = scaleAlpha(palette.keyShadow, keyBackgroundAlpha)
        canvas.drawRoundRect(
            bounds.left, bounds.top + metrics.shadowOffset,
            bounds.right, bounds.bottom + metrics.shadowOffset,
            metrics.radius, metrics.radius, fill,
        )

        if (keySkin == Settings.KeySkin.ORIGINAL) {
            fill.color = scaleAlpha(color, keyBackgroundAlpha)
            canvas.drawRoundRect(bounds, metrics.radius, metrics.radius, fill)
            if (keyBackgroundFrosted) {
                // 玻璃雾面层使用真正带 alpha 的颜色，不依赖 Paint.alpha 的残留状态。
                fill.color = scaleAlpha(0x50FFFFFF, keyBackgroundAlpha)
                canvas.drawRoundRect(bounds, metrics.radius, metrics.radius, fill)
                fill.color = scaleAlpha(0x30FFFFFF, keyBackgroundAlpha)
                val inset = 1f * density
                val highlight = RectF(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.top + bounds.height() * 0.42f)
                canvas.drawRoundRect(highlight, (metrics.radius - inset).coerceAtLeast(1f), (metrics.radius - inset).coerceAtLeast(1f), fill)
            }
            return
        }

        // 液态玻璃键帽：渐层、高光、反射和描边都乘以同一透明度，避免只有底色变化。
        val top = scaleAlpha(withAlpha(lighten(color, 1.28f), 0xB8), keyBackgroundAlpha)
        val bottom = scaleAlpha(withAlpha(darken(color, 0.86f), 0xE0), keyBackgroundAlpha)
        fill.shader = LinearGradient(bounds.left, bounds.top, bounds.left, bounds.bottom, top, bottom, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(bounds, metrics.radius, metrics.radius, fill)
        fill.shader = null

        val inset = 1.2f * density
        val innerRadius = (metrics.radius - inset).coerceAtLeast(1f)
        fill.shader = LinearGradient(
            bounds.left, bounds.top + inset, bounds.left, bounds.top + bounds.height() * 0.48f,
            intArrayOf(
                scaleAlpha(0x72FFFFFF, keyBackgroundAlpha),
                scaleAlpha(0x28FFFFFF, keyBackgroundAlpha),
                scaleAlpha(0x00FFFFFF, keyBackgroundAlpha),
            ), null, Shader.TileMode.CLAMP,
        )
        val shine = RectF(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.top + bounds.height() * 0.48f)
        canvas.drawRoundRect(shine, innerRadius, innerRadius, fill)
        fill.shader = null

        fill.shader = LinearGradient(
            bounds.left, bounds.bottom - bounds.height() * 0.24f, bounds.left, bounds.bottom,
            scaleAlpha(0x00FFFFFF, keyBackgroundAlpha),
            scaleAlpha(0x30FFFFFF, keyBackgroundAlpha),
            Shader.TileMode.CLAMP,
        )
        val reflection = RectF(bounds.left + inset, bounds.bottom - bounds.height() * 0.24f, bounds.right - inset, bounds.bottom - inset)
        canvas.drawRoundRect(reflection, innerRadius, innerRadius, fill)
        fill.shader = null

        fill.style = Paint.Style.STROKE
        fill.strokeWidth = 0.8f * density
        fill.color = scaleAlpha(0x88FFFFFF.toInt(), keyBackgroundAlpha)
        canvas.drawRoundRect(
            bounds.left + inset / 2, bounds.top + inset / 2,
            bounds.right - inset / 2, bounds.bottom - inset / 2,
            (metrics.radius - inset / 2).coerceAtLeast(1f),
            (metrics.radius - inset / 2).coerceAtLeast(1f), fill,
        )
        fill.style = Paint.Style.FILL

        if (keyBackgroundFrosted) {
            fill.color = scaleAlpha(0x50FFFFFF, keyBackgroundAlpha)
            canvas.drawRoundRect(bounds, metrics.radius, metrics.radius, fill)
        }
    }

    private fun scaleAlpha(color: Int, factor: Float): Int {
        val original = (color ushr 24) and 0xFF
        val alpha = (original * factor.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)

    private fun lighten(color: Int, factor: Float): Int = tint(color, factor)
    private fun darken(color: Int, factor: Float): Int = tint(color, factor)

    private fun tint(color: Int, factor: Float): Int {
        fun channel(shift: Int) = (((color ushr shift) and 0xFF) * factor).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun text(canvas: Canvas, value: String, sp: Float, color: Int, cx: Float, cy: Float) {
        ink.textSize = sp * density * scaleOf(sp)
        ink.color = color
        ink.shader = null
        ink.style = Paint.Style.FILL
        ink.clearShadowLayer()
        ink.isFakeBoldText = fontWeight.weight >= 600
        val baseline = cy - (ink.descent() + ink.ascent()) / 2
        when (textEffectPreset) {
            Settings.TextEffectPreset.STANDARD -> canvas.drawText(value, cx, baseline, ink)
            Settings.TextEffectPreset.BOLD_DEPTH -> {
                ink.isFakeBoldText = true
                ink.setShadowLayer(1.2f * density, 0f, 1f * density, 0x66000000)
                canvas.drawText(value, cx, baseline, ink)
            }
            Settings.TextEffectPreset.SOFT_GLOW -> {
                ink.setShadowLayer(5f * density, 0f, 0f, color)
                canvas.drawText(value, cx, baseline, ink)
            }
            Settings.TextEffectPreset.NEON_OUTLINE -> {
                ink.setShadowLayer(3f * density, 0f, 0f, color)
                ink.style = Paint.Style.STROKE
                ink.strokeWidth = 1.15f * density
                canvas.drawText(value, cx, baseline, ink)
                ink.style = Paint.Style.FILL
                canvas.drawText(value, cx, baseline, ink)
            }
            Settings.TextEffectPreset.CONTRAST_OUTLINE -> {
                val luminance = (0.2126f * android.graphics.Color.red(color) +
                    0.7152f * android.graphics.Color.green(color) +
                    0.0722f * android.graphics.Color.blue(color)) / 255f
                ink.color = if (luminance > 0.5f) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                ink.style = Paint.Style.STROKE
                ink.strokeWidth = 1.35f * density
                canvas.drawText(value, cx, baseline, ink)
                ink.color = color
                ink.style = Paint.Style.FILL
                canvas.drawText(value, cx, baseline, ink)
            }
            Settings.TextEffectPreset.COLOR_GRADIENT -> {
                val measured = ink.measureText(value).coerceAtLeast(1f)
                ink.shader = LinearGradient(cx - measured / 2f, baseline - ink.textSize,
                    cx + measured / 2f, baseline, intArrayOf(0xFF45D7FF.toInt(), 0xFF8B65FF.toInt(), 0xFFFF6FB5.toInt()),
                    null, Shader.TileMode.CLAMP)
                canvas.drawText(value, cx, baseline, ink)
                ink.shader = null
            }
        }
        ink.style = Paint.Style.FILL
        ink.shader = null
        ink.clearShadowLayer()
        ink.isFakeBoldText = fontWeight.weight >= 600
    }

    /** 同 [text]，放不下 [maxWidth] 就缩小字号（双拼韵母一个键可能有两三个）。 */
    private fun fitted(canvas: Canvas, value: String, sp: Float, color: Int, cx: Float, cy: Float, maxWidth: Float) {
        ink.textSize = sp * density * scaleOf(sp)
        val width = ink.measureText(value)
        text(canvas, value, if (width > maxWidth) sp * maxWidth / width else sp, color, cx, cy)
    }

    /** 带角标的键上主字的纵向位置（占键高的比例）：字放大时往下让一点，不顶到角标。 */
    private val belowHint = 0.6f + (textScale - 1f).coerceAtLeast(0f) * 0.15f

    /** 键面字号只放大键上的主字；角标、双拼韵母这类小字（[HINT_MAX_SP] 以下）不跟，放大了会和主字挤在一起。 */
    private fun scaleOf(sp: Float) = if (sp > HINT_MAX_SP) textScale else 1f

    private companion object {
        const val MARQUEE_CYCLE_MS = 6_500L
        const val MARQUEE_STEP_MS = 250f
        const val BREATH_CYCLE_MS = 2_400L
        const val QWERTY_ORDER = "qwertyuiopasdfghjklzxcvbnm"
        val EDIT_GESTURE_KEYS = setOf('z', 'x', 'c', 'v')
        const val PRESSED_BRAND_SHADE = 0x33000000

        const val HINT_MAX_SP = 11f

        /** 锁定大写的小点：半径与离键右上角的距离。 */
        const val LOCK_DOT_DP = 3.5f
        const val LOCK_DOT_INSET_DP = 9f
    }
}
