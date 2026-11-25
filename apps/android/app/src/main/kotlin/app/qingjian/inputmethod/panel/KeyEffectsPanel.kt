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
    private var sliderTracking = false
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
    private fun backgroundTop() = borderTop() + ink.dp(56f)
    private fun contentBottom() = backgroundTop() + ink.dp(56f)

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
            val size = ink.dp(25f)
            val gap = ink.dp(9f)
            val startX = card.left + ink.dp(14f)
            val y = rowTop + ink.dp(28f) - offset
            colors.forEachIndexed { index, _ ->
                val x = startX + index * (size + gap)
                elements += AccessibilityElement(
                    "$label，第${index + 1}种${if (index == selected) "，已选中" else ""}",
                    RectF(x, y - ink.dp(3f), x + size, y + size + ink.dp(3f)),
                    selected = index == selected,
                )
            }
        }
        addColors(borderTop(), "按键边框效果", PRESET_COLORS, PRESET_COLORS.indexOf(borderColor))
        addColors(backgroundTop(), "按键背景效果", PRESET_COLORS, PRESET_COLORS.indexOf(backgroundColor and 0x00FFFFFF or 0xFF000000.toInt()))
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
        drawColorChoice(canvas, borderTop(), "按键边框效果", PRESET_COLORS, PRESET_COLORS.indexOf(borderColor))
        drawColorChoice(canvas, backgroundTop(), "按键背景效果", PRESET_COLORS, PRESET_COLORS.indexOf(backgroundColor and 0x00FFFFFF or 0xFF000000.toInt()))
        canvas.restore()
        if (marquee && isShown) postInvalidateDelayed(32L)
    }

    private fun drawColorChoice(canvas: Canvas, rowTop: Float, label: String, colors: IntArray, selected: Int) {
        ink.textStart(canvas, label, 13f, palette.text, card.left + ink.dp(14f), rowTop + ink.dp(18f), card.right - ink.dp(12f))
        val swatchSize = ink.dp(17f)
        val gap = ink.dp(6f)
        var x = card.left + ink.dp(14f)
        val y = rowTop + ink.dp(28f)
        colors.forEachIndexed { index, color ->
            ink.fill.color = color
            canvas.drawCircle(x + swatchSize / 2f, y + swatchSize / 2f, swatchSize / 2f, ink.fill)
            if (index == selected) {
                ink.fill.style = Paint.Style.STROKE
                ink.fill.strokeWidth = ink.dp(2f)
                ink.fill.color = palette.text
                canvas.drawCircle(x + swatchSize / 2f, y + swatchSize / 2f, swatchSize / 2f + ink.dp(2f), ink.fill)
                ink.fill.style = Paint.Style.FILL
            }
            x += swatchSize + gap
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
            selectColorAt(x, contentY, borderTop(), KEY_BORDER_INDEX, BORDER_COLORS)
        } else if (contentY >= backgroundTop() && contentY < contentBottom()) {
            selectColorAt(x, contentY, backgroundTop(), KEY_BACKGROUND_INDEX, BACKGROUND_COLORS)
        }
    }

    /** 按实际色块坐标命中，允许直接选择颜色。 */
    private fun selectColorAt(x: Float, contentY: Float, rowTop: Float, key: String, colors: IntArray) {
        val swatchSize = ink.dp(17f)
        val gap = ink.dp(6f)
        val startX = card.left + ink.dp(14f)
        val centerY = rowTop + ink.dp(28f) + swatchSize / 2f
        if (kotlin.math.abs(contentY - centerY) > ink.dp(22f)) return
        val index = colors.indices.minByOrNull { i ->
            kotlin.math.abs(x - (startX + i * (swatchSize + gap) + swatchSize / 2f))
        } ?: return
        val centerX = startX + index * (swatchSize + gap) + swatchSize / 2f
        if (kotlin.math.abs(x - centerX) > swatchSize * 0.9f) return
        val color = colors[index]
        val editor = prefs.edit()
        if (key == KEY_BORDER_INDEX) {
            editor.putInt(KEY_BORDER_COLOR, color).putInt(KEY_BORDER_INDEX, 0)
        } else {
            editor.putInt(KEY_BACKGROUND_COLOR, color and 0x00FFFFFF or 0x40000000).putInt(KEY_BACKGROUND_INDEX, 0)
        }
        editor.apply()
        notifyChanged()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && card.contains(event.x, event.y) &&
            kotlin.math.abs(event.y + scroll.offset - alphaTrackY()) <= ink.dp(15f)) {
            sliderTracking = true
            parent?.requestDisallowInterceptTouchEvent(true)
            setAlphaFromX(event.x)
            invalidate()
            return true
        }
        if (sliderTracking) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> { setAlphaFromX(event.x); invalidate() }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    setAlphaFromX(event.x)
                    sliderTracking = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    notifyChanged()
                }
            }
            return true
        }
        return super.onTouchEvent(event)
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
