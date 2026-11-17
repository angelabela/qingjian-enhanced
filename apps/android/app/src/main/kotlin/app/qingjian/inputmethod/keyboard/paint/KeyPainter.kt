package app.qingjian.inputmethod.keyboard.paint

import android.graphics.Canvas
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
    private val gestureHint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

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

    /** 所有手势提示统一贴角：上滑在左上，下滑在右下，方向与动作一一对应。 */
    fun drawGestureHints(canvas: Canvas, slot: KeySlot, upLabel: String?, downLabel: String?) {
        if (upLabel == null && downLabel == null) return
        val b = slot.bounds
        val inset = 8f * density
        val onBrand = slot.key is Key.Enter
        if (upLabel != null) drawGestureHint(canvas, b.left + inset, b.top + inset, upLabel, upward = true, rightAligned = false, onBrand = onBrand)
        if (downLabel != null) drawGestureHint(canvas, b.right - inset, b.bottom - inset, downLabel, upward = false, rightAligned = true, onBrand = onBrand)
    }

    private fun drawGestureHint(
        canvas: Canvas,
        x: Float,
        y: Float,
        label: String,
        upward: Boolean,
        rightAligned: Boolean,
        onBrand: Boolean,
    ) {
        val baseline = y + 3f * density
        val textX = if (rightAligned) x - 5.5f * density else x + 5.5f * density
        ink.textAlign = if (rightAligned) Paint.Align.RIGHT else Paint.Align.LEFT
        ink.typeface = fontTypeface
        ink.textSize = 9f * density
        // 所有按键统一只显示文字：无手势图标、无粗体、无阴影。
        ink.color = if (onBrand) labelColorForBrand() else secondaryColor
        ink.typeface = fontTypeface
        ink.clearShadowLayer()
        canvas.drawText(label, textX, baseline, ink)
        ink.textAlign = Paint.Align.CENTER
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
        fill.shader = null
        fill.color = palette.keyShadow
        canvas.drawRoundRect(
            bounds.left, bounds.top + metrics.shadowOffset,
            bounds.right, bounds.bottom + metrics.shadowOffset,
            metrics.radius, metrics.radius, fill,
        )
        if (keySkin == Settings.KeySkin.ORIGINAL) {
            fill.color = color
            canvas.drawRoundRect(bounds, metrics.radius, metrics.radius, fill)
            return
        }
        // 液态玻璃键帽：半透明主题色渐层、顶部宽高光、底部反射与细描边。
        // 保留原 bounds 和圆角，不改变按键命中区域或键盘布局。
        val top = withAlpha(lighten(color, 1.28f), 0xB8)
        val bottom = withAlpha(darken(color, 0.86f), 0xE0)
        fill.shader = LinearGradient(bounds.left, bounds.top, bounds.left, bounds.bottom, top, bottom, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(bounds, metrics.radius, metrics.radius, fill)
        fill.shader = null

        val inset = 1.2f * density
        val innerRadius = (metrics.radius - inset).coerceAtLeast(1f)
        // 顶部玻璃反光，透明度从上到下自然减弱。
        fill.shader = LinearGradient(
            bounds.left, bounds.top + inset, bounds.left, bounds.top + bounds.height() * 0.48f,
            intArrayOf(0x72FFFFFF, 0x28FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP,
        )
        val shine = RectF(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.top + bounds.height() * 0.48f)
        canvas.drawRoundRect(shine, innerRadius, innerRadius, fill)
        fill.shader = null

        // 底部轻微反射与玻璃边缘高光，避免塑料感。
        fill.shader = LinearGradient(bounds.left, bounds.bottom - bounds.height() * 0.24f, bounds.left, bounds.bottom,
            0x00FFFFFF, 0x30FFFFFF, Shader.TileMode.CLAMP)
        val reflection = RectF(bounds.left + inset, bounds.bottom - bounds.height() * 0.24f, bounds.right - inset, bounds.bottom - inset)
        canvas.drawRoundRect(reflection, innerRadius, innerRadius, fill)
        fill.shader = null
        fill.style = Paint.Style.STROKE
        fill.strokeWidth = 0.8f * density
        fill.color = 0x88FFFFFF.toInt()
        canvas.drawRoundRect(bounds.left + inset / 2, bounds.top + inset / 2, bounds.right - inset / 2, bounds.bottom - inset / 2,
            (metrics.radius - inset / 2).coerceAtLeast(1f), (metrics.radius - inset / 2).coerceAtLeast(1f), fill)
        fill.style = Paint.Style.FILL
        fill.color = color
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
        canvas.drawText(value, cx, cy - (ink.descent() + ink.ascent()) / 2, ink)
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
        val EDIT_GESTURE_KEYS = setOf('z', 'x', 'c', 'v')
        const val PRESSED_BRAND_SHADE = 0x33000000

        const val HINT_MAX_SP = 11f

        /** 锁定大写的小点：半径与离键右上角的距离。 */
        const val LOCK_DOT_DP = 3.5f
        const val LOCK_DOT_INSET_DP = 9f
    }
}
