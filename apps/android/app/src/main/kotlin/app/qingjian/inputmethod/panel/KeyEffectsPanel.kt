package app.qingjian.inputmethod.panel

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import app.qingjian.inputmethod.background.DynamicBackgroundActivity
import app.qingjian.inputmethod.panel.base.PanelView

/** 独立的按键外观页：按键透明度、磨砂与 26 字母跑马灯集中管理。 */
@SuppressLint("ViewConstructor")
class KeyEffectsPanel(context: Context, palette: app.qingjian.inputmethod.theme.Palette, actions: PanelActions, headerHeight: Float)
    : PanelView(context, palette, actions, headerHeight) {
    private val prefs = context.getSharedPreferences(DynamicBackgroundActivity.PREFS, Context.MODE_PRIVATE)
    private val card = RectF()
    private val scroll = scrollable(false) { x, y -> card.contains(x, y) }
    private var activeSlider = -1
    private val alpha get() = prefs.getInt(KEY_ALPHA, 100).coerceIn(0, 100)
    private val frosted get() = prefs.getBoolean(KEY_FROSTED, false)
    private val marquee get() = prefs.getBoolean(DynamicBackgroundActivity.KEY_KEYBOARD_MARQUEE, false)
    private val edgeGlow get() = prefs.getBoolean(KEY_EDGE_GLOW, false)
    private val threeDimensional get() = prefs.getBoolean(KEY_3D, false)
    private val borderColor get() = prefs.getInt(KEY_BORDER_COLOR, 0).takeIf { it != 0 }
        ?: BORDER_COLORS[prefs.getInt(KEY_BORDER_INDEX, 0).coerceIn(0, BORDER_COLORS.lastIndex)]
    private val backgroundColor get() = prefs.getInt(KEY_BACKGROUND_COLOR, 0).takeIf { it != 0 }
        ?: BACKGROUND_COLORS[prefs.getInt(KEY_BACKGROUND_INDEX, 0).coerceIn(0, BACKGROUND_COLORS.lastIndex)]
    private fun top() = card.top + ink.dp(12f)
    private fun alphaTop() = top() + ink.dp(4f)
    private fun alphaTrackY() = alphaTop() + ink.dp(40f)
    private fun frostedTop() = alphaTop() + ink.dp(60f)
    private fun marqueeTop() = frostedTop() + ink.dp(48f)
    private fun edgeGlowTop() = marqueeTop() + ink.dp(48f)
    private fun threeDimensionalTop() = edgeGlowTop() + ink.dp(48f)
    private fun borderTop() = threeDimensionalTop() + ink.dp(48f)
    private fun borderTrackY() = borderTop() + ink.dp(36f)
    private fun backgroundTop() = borderTop() + ink.dp(82f)
    private fun backgroundTrackY() = backgroundTop() + ink.dp(36f)
    private fun restoreTop() = backgroundTop() + ink.dp(82f)
    private fun contentBottom() = restoreTop() + ink.dp(48f)

    private fun notifyChanged() {
        context.sendBroadcast(Intent(DynamicBackgroundActivity.ACTION_SETTINGS_CHANGED).setPackage(context.packageName))
        invalidate()
    }

    override fun accessibilityElements(): List<AccessibilityElement> {
        if (card.isEmpty) return emptyList()
        val offset = scroll.offset
        val left = card.left + ink.dp(8f)
        val right = card.right - ink.dp(8f)
        val elements = mutableListOf<AccessibilityElement>()
        val trackLeft = card.left + ink.dp(18f)
        val trackRight = card.right - ink.dp(18f)
        val trackY = alphaTrackY() - offset
        elements += AccessibilityElement(
            label = "按键透明度，$alpha%",
            bounds = RectF(trackLeft, trackY - ink.dp(14f), trackRight, trackY + ink.dp(14f)),
            rangeMin = 0f,
            rangeMax = 100f,
            rangeCurrent = alpha.toFloat(),
            onSetProgress = { value ->
                prefs.edit().putInt(KEY_ALPHA, value.toInt().coerceIn(0, 100)).apply()
                notifyChanged()
            },
        )
        listOf(
            Triple(frostedTop(), "按键磨砂效果", frosted),
            Triple(marqueeTop(), "字母按键跑马灯", marquee),
            Triple(edgeGlowTop(), "边缘发光效果", edgeGlow),
            Triple(threeDimensionalTop(), "立体按键效果", threeDimensional),
        ).forEach { (rowTop, label, enabled) ->
            elements += AccessibilityElement(
                "$label，${if (enabled) "已开启" else "已关闭"}",
                RectF(left, rowTop - offset, right, rowTop + ink.dp(46f) - offset),
                selected = enabled,
                checked = enabled,
            )
        }
        fun addColors(rowTop: Float, label: String, colors: IntArray, selected: Int) {
            val size = ink.dp(19f)
            val gap = ink.dp(5f)
            val startX = card.left + ink.dp(14f)
            val y = rowTop + ink.dp(52f) - offset
            colors.forEachIndexed { index, _ ->
                val x = startX + index * (size + gap)
                elements += AccessibilityElement(
                    "$label，第${index + 1}种${if (index == selected) "，已选中" else ""}",
                    RectF(x, y - ink.dp(3f), x + size, y + size + ink.dp(3f)),
                    selected = index == selected,
                    checked = index == selected,
                    role = "android.widget.RadioButton",
                )
            }
        }
        elements += AccessibilityElement(
            "按键边框 RGB 色相，${hueOf(borderColor).toInt()}度",
            RectF(trackLeft, borderTrackY() - offset - ink.dp(12f), trackRight, borderTrackY() - offset + ink.dp(12f)),
            rangeMin = 0f, rangeMax = 360f, rangeCurrent = hueOf(borderColor),
            onSetProgress = { setColorHue(1, it) },
        )
        elements += AccessibilityElement(
            "按键背景 RGB 色相，${hueOf(backgroundColor).toInt()}度",
            RectF(trackLeft, backgroundTrackY() - offset - ink.dp(12f), trackRight, backgroundTrackY() - offset + ink.dp(12f)),
            rangeMin = 0f, rangeMax = 360f, rangeCurrent = hueOf(backgroundColor),
            onSetProgress = { setColorHue(2, it) },
        )
        addColors(borderTop(), "按键边框效果", PRESET_COLORS, PRESET_COLORS.indexOf(borderColor))
        addColors(backgroundTop(), "按键背景效果", PRESET_COLORS, PRESET_COLORS.indexOf(backgroundColor and 0x00FFFFFF or 0xFF000000.toInt()))
        elements += AccessibilityElement(
            "恢复按键边框和背景默认主题色",
            RectF(card.left + ink.dp(14f), restoreTop() - offset + ink.dp(4f), card.right - ink.dp(14f), restoreTop() - offset + ink.dp(40f)),
        )
        val viewport = RectF(card.left, card.top + ink.dp(6f), card.right, card.bottom - ink.dp(6f))
        accessibilityScrollElement("按键效果设置列表，可滚动", viewport, scroll)?.let(elements::add)
        return elements.mapNotNull { element ->
            val visible = RectF(element.bounds)
            if (!visible.intersect(viewport)) null else element.copy(bounds = visible)
        }
    }

    override fun onDraw(canvas: Canvas) {
        drawHeader(canvas, null, app.qingjian.inputmethod.panel.base.PanelIcons.Icon.SETTINGS)
        ink.text(canvas, "按键效果", 16f, palette.text, width * 0.5f, headerHeight * 0.5f)
        card.set(ink.dp(10f), headerHeight, width - ink.dp(10f), height - ink.dp(8f))
        ink.round(canvas, card, 16f, palette.surface)
        val viewportBottom = card.bottom - ink.dp(6f)
        scroll.max = (contentBottom() - viewportBottom).coerceAtLeast(0f)
        canvas.save()
        canvas.clipRect(card.left, card.top + ink.dp(6f), card.right, viewportBottom)
        canvas.translate(0f, -scroll.offset)
        ink.textStart(canvas, "按键透明度", 13f, palette.text, card.left + ink.dp(14f), alphaTop() + ink.dp(17f), card.right - ink.dp(68f))
        ink.text(canvas, "$alpha%", 12f, palette.brand, card.right - ink.dp(28f), alphaTop() + ink.dp(17f))
        val left = card.left + ink.dp(18f)
        val right = card.right - ink.dp(18f)
        val trackY = alphaTrackY()
        ink.fill.color = palette.functionalKey
        ink.fill.strokeWidth = ink.dp(4f)
        ink.fill.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(left, trackY, right, trackY, ink.fill)
        ink.fill.color = palette.brand
        val progressX = left + (right - left) * alpha / 100f
        canvas.drawLine(left, trackY, progressX, trackY, ink.fill)
        canvas.drawCircle(progressX, trackY, ink.dp(6f), ink.fill)
        drawToggle(canvas, frostedTop(), "按键磨砂效果", frosted, "开启后键帽增加柔和玻璃雾面")
        drawToggle(canvas, marqueeTop(), "字母按键跑马灯", marquee, "A-Z 依次流动，带彩虹渐变光尾")
        drawToggle(canvas, edgeGlowTop(), "边缘发光效果", edgeGlow, "按键轮廓增加柔和外扩光晕")
        drawToggle(canvas, threeDimensionalTop(), "立体按键效果", threeDimensional, "增加底部厚度阴影与顶部高光")
        drawColorChoice(canvas, borderTop(), "按键边框效果 RGB", borderColor, isBackground = false)
        drawColorChoice(canvas, backgroundTop(), "按键背景效果 RGB", backgroundColor, isBackground = true)
        val restoreBounds = RectF(card.left + ink.dp(14f), restoreTop() + ink.dp(4f), card.right - ink.dp(14f), restoreTop() + ink.dp(40f))
        ink.round(canvas, restoreBounds, 10f, palette.functionalKey)
        ink.text(canvas, "恢复默认主题色", 12f, palette.text, restoreBounds.centerX(), restoreBounds.centerY())
        canvas.restore()
        if (marquee && isShown) postInvalidateDelayed(32L)
    }

    private fun drawColorChoice(canvas: Canvas, rowTop: Float, label: String, selectedColor: Int, isBackground: Boolean) {
        ink.textStart(canvas, label, 12.5f, palette.text, card.left + ink.dp(14f), rowTop + ink.dp(17f), card.right - ink.dp(12f))
        val left = card.left + ink.dp(18f)
        val right = card.right - ink.dp(18f)
        val trackY = rowTop + ink.dp(36f)
        val track = RectF(left, trackY - ink.dp(4f), right, trackY + ink.dp(4f))
        val rainbow = intArrayOf(
            0xFFFF3030.toInt(), 0xFFFFD430.toInt(), 0xFF55E85A.toInt(),
            0xFF25DDE8.toInt(), 0xFF397BFF.toInt(), 0xFFB34DFF.toInt(), 0xFFFF3030.toInt(),
        )
        ink.fill.shader = android.graphics.LinearGradient(track.left, 0f, track.right, 0f, rainbow, null, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRoundRect(track, ink.dp(4f), ink.dp(4f), ink.fill)
        ink.fill.shader = null
        val markerX = left + (right - left) * (hueOf(selectedColor) / 360f)
        ink.fill.color = palette.surface
        canvas.drawCircle(markerX, trackY, ink.dp(6f), ink.fill)
        ink.fill.color = selectedColor
        canvas.drawCircle(markerX, trackY, ink.dp(4f), ink.fill)

        val swatchWidth = ink.dp(19f)
        val swatchHeight = ink.dp(12f)
        val gap = ink.dp(5f)
        var x = card.left + ink.dp(14f)
        val y = rowTop + ink.dp(52f)
        PRESET_COLORS.forEach { color ->
            val bounds = RectF(x, y, x + swatchWidth, y + swatchHeight)
            ink.fill.color = if (isBackground) (color and 0x00FFFFFF) or 0x55000000 else color
            canvas.drawRoundRect(bounds, ink.dp(3f), ink.dp(3f), ink.fill)
            val selected = (selectedColor and 0x00FFFFFF) == (color and 0x00FFFFFF)
            if (selected) {
                ink.fill.style = Paint.Style.STROKE
                ink.fill.strokeWidth = ink.dp(1.5f)
                ink.fill.color = palette.text
                canvas.drawRoundRect(RectF(bounds).apply { inset(-ink.dp(1.5f), -ink.dp(1.5f)) }, ink.dp(4f), ink.dp(4f), ink.fill)
                ink.fill.style = Paint.Style.FILL
            }
            x += swatchWidth + gap
        }
    }

    private fun drawToggle(canvas: Canvas, rowTop: Float, label: String, enabled: Boolean, note: String) {
        val cy = rowTop + ink.dp(23f)
        ink.textStart(canvas, label, 13f, palette.text, card.left + ink.dp(14f), cy - ink.dp(5f), card.right - ink.dp(78f))
        ink.textStart(canvas, note, 9.5f, palette.gloss, card.left + ink.dp(14f), cy + ink.dp(11f), card.right - ink.dp(78f))
        val cx = card.right - ink.dp(38f)
        val bounds = RectF(cx - ink.dp(20f), cy - ink.dp(12f), cx + ink.dp(20f), cy + ink.dp(12f))
        ink.round(canvas, bounds, 12f, if (enabled) palette.brand else palette.functionalKey)
        ink.fill.color = palette.surface
        canvas.drawCircle(if (enabled) cx + ink.dp(8f) else cx - ink.dp(8f), cy, ink.dp(9f), ink.fill)
    }

    override fun onTap(x: Float, y: Float) {
        if (isRightIcon(x, y)) { actions.openSettings(); return }
        if (!card.contains(x, y)) return
        val contentY = y + scroll.offset
        if (contentY >= frostedTop() && contentY < marqueeTop()) {
            prefs.edit().putBoolean(KEY_FROSTED, !frosted).apply()
            notifyChanged()
        } else if (contentY >= marqueeTop() && contentY < edgeGlowTop()) {
            prefs.edit().putBoolean(DynamicBackgroundActivity.KEY_KEYBOARD_MARQUEE, !marquee).apply()
            notifyChanged()
        } else if (contentY >= edgeGlowTop() && contentY < threeDimensionalTop()) {
            prefs.edit().putBoolean(KEY_EDGE_GLOW, !edgeGlow).apply()
            notifyChanged()
        } else if (contentY >= threeDimensionalTop() && contentY < borderTop()) {
            prefs.edit().putBoolean(KEY_3D, !threeDimensional).apply()
            notifyChanged()
        } else if (contentY >= borderTop() && contentY < backgroundTop()) {
            selectColorAt(x, contentY, borderTop(), KEY_BORDER_COLOR, isBackground = false)
        } else if (contentY >= backgroundTop() && contentY < restoreTop()) {
            selectColorAt(x, contentY, backgroundTop(), KEY_BACKGROUND_COLOR, isBackground = true)
        } else if (contentY >= restoreTop() && contentY < contentBottom()) {
            restoreThemeColors()
        }
    }

    /** 恢复边框与背景色的主题默认值；不改透明度、磨砂和其他按键效果。 */
    private fun restoreThemeColors() {
        prefs.edit()
            .remove(KEY_BORDER_COLOR)
            .remove(KEY_BACKGROUND_COLOR)
            .putInt(KEY_BORDER_INDEX, 0)
            .putInt(KEY_BACKGROUND_INDEX, 0)
            .apply()
        notifyChanged()
    }

    /** 点击 11 个固定预设色，背景色保留半透明以透出主题/视频背景。 */
    private fun selectColorAt(x: Float, contentY: Float, rowTop: Float, key: String, isBackground: Boolean) {
        val swatchWidth = ink.dp(19f)
        val swatchHeight = ink.dp(12f)
        val gap = ink.dp(5f)
        val startX = card.left + ink.dp(14f)
        val topY = rowTop + ink.dp(52f)
        val centerY = topY + swatchHeight / 2f
        if (kotlin.math.abs(contentY - centerY) > ink.dp(12f)) return
        val index = PRESET_COLORS.indices.minByOrNull { i ->
            kotlin.math.abs(x - (startX + i * (swatchWidth + gap) + swatchWidth / 2f))
        } ?: return
        val centerX = startX + index * (swatchWidth + gap) + swatchWidth / 2f
        if (kotlin.math.abs(x - centerX) > swatchWidth * 0.65f) return
        val color = PRESET_COLORS[index]
        val stored = if (isBackground) (color and 0x00FFFFFF) or 0x55000000 else color
        prefs.edit().putInt(key, stored)
            .putInt(if (isBackground) KEY_BACKGROUND_INDEX else KEY_BORDER_INDEX, 0)
            .apply()
        notifyChanged()
    }

    private fun hueOf(color: Int): Float {
        if (color == 0) return 210f
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color, hsv)
        return hsv[0]
    }

    private fun setColorHue(slider: Int, hue: Float) {
        val color = android.graphics.Color.HSVToColor(floatArrayOf(hue.coerceIn(0f, 360f), 0.86f, 1f))
        val key = if (slider == 1) KEY_BORDER_COLOR else KEY_BACKGROUND_COLOR
        val stored = if (slider == 2) (color and 0x00FFFFFF) or 0x55000000 else color
        prefs.edit().putInt(key, stored)
            .putInt(if (slider == 1) KEY_BORDER_INDEX else KEY_BACKGROUND_INDEX, 0)
            .apply()
        notifyChanged()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val contentY = event.y + scroll.offset
        if (event.actionMasked == MotionEvent.ACTION_DOWN && card.contains(event.x, event.y)) {
            activeSlider = when {
                kotlin.math.abs(contentY - alphaTrackY()) <= ink.dp(15f) -> 0
                kotlin.math.abs(contentY - borderTrackY()) <= ink.dp(12f) -> 1
                kotlin.math.abs(contentY - backgroundTrackY()) <= ink.dp(12f) -> 2
                else -> -1
            }
            if (activeSlider >= 0) {
                parent?.requestDisallowInterceptTouchEvent(true)
                updateSliderFromX(event.x, activeSlider)
                invalidate()
                return true
            }
        }
        if (activeSlider >= 0) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> { updateSliderFromX(event.x, activeSlider); invalidate() }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    updateSliderFromX(event.x, activeSlider)
                    activeSlider = -1
                    parent?.requestDisallowInterceptTouchEvent(false)
                    notifyChanged()
                }
            }
            return true
        }
        return super.onTouchEvent(event)
    }

    private fun updateSliderFromX(x: Float, slider: Int) {
        if (slider == 0) setAlphaFromX(x) else {
            val left = card.left + ink.dp(18f)
            val right = card.right - ink.dp(18f)
            val fraction = ((x - left) / (right - left).coerceAtLeast(1f)).coerceIn(0f, 1f)
            setColorHue(slider, fraction * 360f)
        }
    }

    private fun setAlphaFromX(x: Float) {
        val left = card.left + ink.dp(18f)
        val right = card.right - ink.dp(18f)
        val fraction = ((x - left) / (right - left).coerceAtLeast(1f)).coerceIn(0f, 1f)
        prefs.edit().putInt(KEY_ALPHA, (fraction * 100).toInt()).apply()
    }

    override fun onBack() = actions.openPanel(PanelKind.FUNCTIONS)
    private companion object {
        const val KEY_ALPHA = "keyboard_key_alpha"
        const val KEY_FROSTED = "keyboard_key_frosted"
        const val KEY_EDGE_GLOW = "keyboard_key_edge_glow"
        const val KEY_3D = "keyboard_key_3d"
        const val KEY_BORDER_INDEX = "keyboard_key_border_index"
        const val KEY_BACKGROUND_INDEX = "keyboard_key_background_index"
        const val KEY_BORDER_COLOR = "keyboard_key_border_color"
        const val KEY_BACKGROUND_COLOR = "keyboard_key_background_color"
        val PRESET_COLORS = intArrayOf(
            0xFFD4042D.toInt(), 0xFF9D1F2F.toInt(), 0xFFEB6127.toInt(),
            0xFFDB7A0E.toInt(), 0xFF5A92E5.toInt(), 0xFF5E8034.toInt(),
            0xFF6FBA2C.toInt(), 0xFF41B5C2.toInt(), 0xFFFACA2E.toInt(),
            0xFFEB6D98.toInt(), 0xFF722169.toInt(),
        )
        val BORDER_COLORS = intArrayOf(0, 0xFF5B8CFF.toInt(), 0xFF28D7C5.toInt(), 0xFFFFB45B.toInt(), 0xFFFF6B9A.toInt())
        val BACKGROUND_COLORS = intArrayOf(0, 0x403B82F6, 0x4030CDB8, 0x40FFB45B, 0x40A78BFA)
    }
}
