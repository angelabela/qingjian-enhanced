package app.qingjian.inputmethod.panel

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import app.qingjian.inputmethod.background.DynamicBackgroundActivity
import app.qingjian.inputmethod.panel.base.PanelView
import app.qingjian.inputmethod.settings.Settings

/** 面板背景特效统一设置页：整合视频背景、动态光效与画面调节。 */
@SuppressLint("ViewConstructor")
class DynamicBackgroundPanel(
    context: Context,
    palette: app.qingjian.inputmethod.theme.Palette,
    actions: PanelActions,
    headerHeight: Float,
) : PanelView(context, palette, actions, headerHeight) {
    private val prefs = context.getSharedPreferences(DynamicBackgroundActivity.PREFS, Context.MODE_PRIVATE)
    private val settingsPrefs = Settings.preferences(context)
    private val card = RectF()
    private val scroll = scrollable(false) { x, y -> card.contains(x, y) }
    private var sliderTouchIndex = -1
    private var restoreScrollPending = true
    private val scrollStateKey = "dynamic_background_panel_scroll_offset"
    private val rowHeight get() = ink.dp(58f)
    private val effectCellHeight get() = ink.dp(48f)
    private val effectGridGap get() = ink.dp(6f)
    private val labels = listOf("导入或更换视频", "背景显示模式", "背景磨砂效果", "背景透明度", "背景亮度", "模糊程度")
    private val effectRows get() = (effectStyles.size + 3) / 4
    private fun settingsRowsTop(top: Float) = top + ink.dp(30f)
    private fun effectTitleY(top: Float) = settingsRowsTop(top) + rowHeight * 3 + ink.dp(20f)
    private fun effectGridTop(top: Float) = settingsRowsTop(top) + rowHeight * 3 + ink.dp(32f)
    private fun effectGridBottom(top: Float) = effectGridTop(top) + effectRows * effectCellHeight + (effectRows - 1) * effectGridGap
    private fun adaptiveIconRowTop(top: Float) = effectGridBottom(top) + ink.dp(34f)
    private fun slidersTop(top: Float) = adaptiveIconRowTop(top) + rowHeight + ink.dp(20f)
    private fun sliderRowTop(top: Float, index: Int) = slidersTop(top) + (index - 3) * rowHeight
    private val effectStyles = listOf(
        0 to "极光流彩", 1 to "深海蓝潮", 2 to "日落熔金", 4 to "冰晶星雾",
        7 to "霓虹光幕", 8 to "星环脉冲",
        9 to "流体·极光", 10 to "流体·日落", 11 to "流体·糖果", 12 to "流体·深海", 13 to "流体·霓虹",
        14 to "晴空云海", 15 to "暮色云层", 16 to "月夜流云", 17 to "梦幻光斑", 18 to "光斑穿云",
    )
    private fun selectedEffectStyle(): Int = Settings.normalizeKeyboardBackgroundStyle(
        settingsPrefs.getString(Settings.KEY_BACKGROUND_STYLE, "0")?.toIntOrNull() ?: 0
    )
    private val modeLabels = listOf("视频", "光效", "常规")
    private fun modeChipGap() = ink.dp(4f)
    private fun modeChipWidth(): Float = minOf(ink.dp(43f), (card.width() - ink.dp(180f) - modeChipGap() * 2f).coerceAtLeast(ink.dp(28f)) / 3f)
    private fun modeChipLeft(): Float = card.right - ink.dp(12f) - modeChipWidth() * 3f - modeChipGap() * 2f

    private fun notifyChanged() {
        context.sendBroadcast(Intent(DynamicBackgroundActivity.ACTION_SETTINGS_CHANGED).setPackage(context.packageName))
        invalidate()
    }

    private fun update(block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        notifyChanged()
    }

    override fun onDraw(canvas: Canvas) {
        drawHeader(canvas, null)
        ink.text(canvas, "背景特效", 16f, palette.text, width * 0.5f, headerHeight * 0.5f)
        card.set(ink.dp(10f), headerHeight, width - ink.dp(10f), height - ink.dp(8f))
        ink.round(canvas, card, 16f, palette.surface)
        val top = card.top + ink.dp(8f)
        val viewportTop = top
        val viewportBottom = card.bottom - ink.dp(8f)
        val totalHeight = sliderRowTop(top, 5) + rowHeight - top
        scroll.max = (totalHeight - (viewportBottom - viewportTop)).coerceAtLeast(0f)
        if (restoreScrollPending) {
            scroll.drag(prefs.getFloat(scrollStateKey, 0f))
            restoreScrollPending = false
        }
        canvas.save()
        canvas.clipRect(card.left, viewportTop, card.right, viewportBottom)
        canvas.translate(0f, -scroll.offset)

        // 背景显示模式与视频导入；不再提供独立的视频开关。
        ink.textStart(canvas, "背景设置", 11f, palette.gloss, card.left + ink.dp(16f), top + ink.dp(18f), card.right - ink.dp(16f))
        labels.take(3).forEachIndexed { index, label ->
            val y = settingsRowsTop(top) + index * rowHeight
            val value = when (index) {
                0 -> if (prefs.getString(DynamicBackgroundActivity.KEY_URI, null) == null) "尚未导入 · 点击选择" else "已导入视频 · 点击更换"
                1 -> modeLabels[DynamicBackgroundActivity.displayMode(context)]
                else -> if (prefs.getBoolean(DynamicBackgroundActivity.KEY_FROSTED, true)) "开启" else "关闭"
            }
            ink.textStart(canvas, label, 13f, palette.text, card.left + ink.dp(16f), y + ink.dp(22f), card.right - ink.dp(16f))
            if (index == 1) {
                val chipWidth = modeChipWidth()
                val gap = modeChipGap()
                val left = modeChipLeft()
                val selectedMode = DynamicBackgroundActivity.displayMode(context)
                modeLabels.forEachIndexed { mode, modeLabel ->
                    val bounds = RectF(left + mode * (chipWidth + gap), y + ink.dp(7f),
                        left + mode * (chipWidth + gap) + chipWidth, y + ink.dp(39f))
                    val selected = selectedMode == mode
                    ink.round(canvas, bounds, 8f, if (selected) palette.brand else palette.functionalKey)
                    ink.text(canvas, modeLabel, 10.5f, if (selected) palette.onBrand else palette.text, bounds.centerX(), bounds.centerY() + ink.dp(3f))
                }
            } else {
                ink.text(canvas, value, 12f, palette.brand, card.right - ink.dp(54f), y + ink.dp(22f))
            }
            ink.line(canvas, card.left + ink.dp(16f), y + rowHeight, card.right - ink.dp(16f), y + rowHeight, palette.faint)
        }

        // 背景光效样式：固定四列紧凑网格；超出可视区域由整个内容区上下滚动。
        val gridTop = effectGridTop(top)
        ink.textStart(canvas, "背景光效样式", 13f, palette.text, card.left + ink.dp(16f), effectTitleY(top), card.right - ink.dp(16f))
        val gridLeft = card.left + ink.dp(12f)
        val gridRight = card.right - ink.dp(12f)
        val cellGap = effectGridGap
        val cellWidth = (gridRight - gridLeft - cellGap * 3) / 4f
        val selected = selectedEffectStyle()
        effectStyles.forEachIndexed { index, style ->
            val row = index / 4
            val col = index % 4
            val left = gridLeft + col * (cellWidth + cellGap)
            val cellTop = gridTop + row * (effectCellHeight + cellGap)
            val rect = RectF(left, cellTop, left + cellWidth, cellTop + effectCellHeight)
            val isSelected = style.first == selected
            val selectedFill = (palette.brand and 0x00FFFFFF) or (0x38 shl 24)
            ink.round(canvas, rect, 10f, if (isSelected) selectedFill else palette.functionalKey)
            if (isSelected) {
                ink.fill.style = android.graphics.Paint.Style.STROKE
                ink.fill.color = palette.brand
                ink.fill.strokeWidth = ink.dp(1.5f)
                canvas.drawRoundRect(rect, ink.dp(10f), ink.dp(10f), ink.fill)
                ink.fill.style = android.graphics.Paint.Style.FILL
            }
            ink.text(canvas, style.second, 10f, if (isSelected) palette.brand else palette.text, rect.centerX(), rect.centerY() + ink.dp(4f))
        }

        // 液态玻璃图标：半透明玻璃底片、高光与主题色图标；关闭后恢复普通主题图标。
        val adaptiveTop = adaptiveIconRowTop(top)
        val glassEnabled = settingsPrefs.getBoolean(Settings.KEY_LIQUID_GLASS_ICONS, true)
        ink.textStart(canvas, "液态玻璃图标特效", 13f, palette.text, card.left + ink.dp(16f), adaptiveTop + ink.dp(22f), card.right - ink.dp(104f))
        ink.text(canvas, if (glassEnabled) "已开启" else "已关闭", 12f,
            if (glassEnabled) palette.brand else palette.gloss, card.right - ink.dp(54f), adaptiveTop + ink.dp(22f))
        ink.line(canvas, card.left + ink.dp(16f), adaptiveTop + rowHeight, card.right - ink.dp(16f), adaptiveTop + rowHeight, palette.faint)

        // 画面调节区：与光效网格分组，滑块可拖动调整。
        ink.textStart(canvas, "画面调节", 13f, palette.text, card.left + ink.dp(16f), slidersTop(top) - ink.dp(4f), card.right - ink.dp(16f))
        for (index in 3..5) {
            val label = labels[index]
            val y = sliderRowTop(top, index)
            val value = when (index) {
                3 -> "${prefs.getInt(DynamicBackgroundActivity.KEY_ALPHA, 72).coerceIn(0, 100)}%"
                4 -> "${prefs.getInt(DynamicBackgroundActivity.KEY_BRIGHTNESS, 100).coerceIn(0, 200)}%"
                else -> "${prefs.getInt(DynamicBackgroundActivity.KEY_BLUR, 8).coerceIn(0, 30)} dp"
            }
            ink.textStart(canvas, label, 13f, palette.text, card.left + ink.dp(16f), y + ink.dp(22f), card.right - ink.dp(16f))
            ink.text(canvas, value, 12f, palette.brand, card.right - ink.dp(72f), y + ink.dp(22f))
            val left = card.left + ink.dp(18f)
            val right = card.right - ink.dp(18f)
            val cy = y + ink.dp(49f)
            val max = when (index) { 4 -> 200f; 5 -> 30f; else -> 100f }
            val current = when (index) {
                3 -> prefs.getInt(DynamicBackgroundActivity.KEY_ALPHA, 72)
                4 -> prefs.getInt(DynamicBackgroundActivity.KEY_BRIGHTNESS, 100)
                else -> prefs.getInt(DynamicBackgroundActivity.KEY_BLUR, 8)
            }.coerceIn(0, max.toInt())
            ink.fill.color = palette.functionalKey
            ink.fill.strokeWidth = ink.dp(4f)
            ink.fill.strokeCap = android.graphics.Paint.Cap.ROUND
            canvas.drawLine(left, cy, right, cy, ink.fill)
            ink.fill.color = palette.brand
            canvas.drawLine(left, cy, left + (right - left) * (current / max), cy, ink.fill)
            canvas.drawCircle(left + (right - left) * (current / max), cy, ink.dp(6f), ink.fill)
        }
        canvas.restore()
    }

    override fun onBack() = actions.openPanel(PanelKind.FUNCTIONS)

    override fun onTap(x: Float, y: Float) {
        if (!card.contains(x, y)) return
        val top = card.top + ink.dp(8f)
        val contentY = y + scroll.offset
        val relativeY = contentY - settingsRowsTop(top)
        when {
            contentY >= settingsRowsTop(top) && relativeY < rowHeight * 3 -> {
                val index = (relativeY / rowHeight).toInt()
                when (index) {
                    0 -> context.startActivity(Intent(context, DynamicBackgroundActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    1 -> {
                        val chipWidth = modeChipWidth()
                        val gap = modeChipGap()
                        val left = modeChipLeft()
                        val mode = ((x - left) / (chipWidth + gap)).toInt()
                        if (mode in modeLabels.indices && x <= left + mode * (chipWidth + gap) + chipWidth) {
                            prefs.edit().putInt(DynamicBackgroundActivity.KEY_DISPLAY_MODE, mode).apply()
                            settingsPrefs.edit().putBoolean(Settings.KEY_BACKGROUND_EFFECT, mode == DynamicBackgroundActivity.MODE_EFFECT).apply()
                            notifyChanged()
                        }
                    }
                    2 -> update { putBoolean(DynamicBackgroundActivity.KEY_FROSTED, !prefs.getBoolean(DynamicBackgroundActivity.KEY_FROSTED, true)) }
                }
            }
            contentY >= effectGridTop(top) && contentY < effectGridBottom(top) + effectGridGap -> {
                val gridY = contentY - effectGridTop(top)
                val gridLeft = card.left + ink.dp(12f)
                val gridRight = card.right - ink.dp(12f)
                val cellGap = effectGridGap
                val cellWidth = (gridRight - gridLeft - cellGap * 3) / 4f
                val col = ((x - gridLeft) / (cellWidth + cellGap)).toInt()
                val row = (gridY / (effectCellHeight + cellGap)).toInt()
                if (col in 0..3 && row >= 0) {
                    val cellLeft = gridLeft + col * (cellWidth + cellGap)
                    val cellTop = row * (effectCellHeight + cellGap)
                    if (x >= cellLeft && x <= cellLeft + cellWidth &&
                        gridY >= cellTop && gridY <= cellTop + effectCellHeight) {
                        val style = effectStyles.getOrNull(row * 4 + col) ?: return
                        settingsPrefs.edit()
                            .putString(Settings.KEY_BACKGROUND_STYLE, style.first.toString())
                            .putBoolean(Settings.KEY_BACKGROUND_EFFECT, true)
                            .apply()
                        prefs.edit().putInt(DynamicBackgroundActivity.KEY_DISPLAY_MODE, DynamicBackgroundActivity.MODE_EFFECT).apply()
                        notifyChanged()
                    }
                }
            }
            contentY >= adaptiveIconRowTop(top) && contentY < adaptiveIconRowTop(top) + rowHeight -> {
                val enabled = settingsPrefs.getBoolean(Settings.KEY_LIQUID_GLASS_ICONS, true)
                settingsPrefs.edit().putBoolean(Settings.KEY_LIQUID_GLASS_ICONS, !enabled).apply()
                notifyChanged()
            }
            else -> {
                val sliderIndex = (3..5).firstOrNull { index ->
                    val rowTop = sliderRowTop(top, index)
                    contentY >= rowTop && contentY < rowTop + rowHeight
                } ?: return
                val railY = sliderRowTop(top, sliderIndex) + ink.dp(49f)
                val left = card.left + ink.dp(18f)
                val right = card.right - ink.dp(18f)
                if (kotlin.math.abs(contentY - railY) <= ink.dp(12f) && x in left..right) {
                    setSliderValue(sliderIndex, x)
                    notifyChanged()
                }
            }
        }
    }

    private fun setSliderValue(index: Int, x: Float) {
        val left = card.left + ink.dp(18f)
        val right = card.right - ink.dp(18f)
        val value = ((x - left) / (right - left).coerceAtLeast(1f)).coerceIn(0f, 1f)
        prefs.edit().apply {
            when (index) {
                3 -> putInt(DynamicBackgroundActivity.KEY_ALPHA, (value * 100).toInt())
                4 -> putInt(DynamicBackgroundActivity.KEY_BRIGHTNESS, (value * 200).toInt())
                5 -> putInt(DynamicBackgroundActivity.KEY_BLUR, (value * 30).toInt())
            }
        }.apply()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val top = card.top + ink.dp(8f)
        val contentY = event.y + scroll.offset
        if (event.actionMasked == MotionEvent.ACTION_DOWN && card.contains(event.x, event.y)) {
            val sliderIndex = (3..5).firstOrNull { index ->
                val rowTop = sliderRowTop(top, index)
                contentY >= rowTop && contentY < rowTop + rowHeight
            }
            if (sliderIndex != null) {
                val railY = sliderRowTop(top, sliderIndex) + ink.dp(49f)
                val left = card.left + ink.dp(18f)
                val right = card.right - ink.dp(18f)
                val max = when (sliderIndex) { 4 -> 200f; 5 -> 30f; else -> 100f }
                val current = when (sliderIndex) {
                    3 -> prefs.getInt(DynamicBackgroundActivity.KEY_ALPHA, 72)
                    4 -> prefs.getInt(DynamicBackgroundActivity.KEY_BRIGHTNESS, 100)
                    else -> prefs.getInt(DynamicBackgroundActivity.KEY_BLUR, 8)
                }.coerceIn(0, max.toInt())
                val knobX = left + (right - left) * (current / max)
                val onThumb = kotlin.math.abs(contentY - railY) <= ink.dp(15f) &&
                    kotlin.math.abs(event.x - knobX) <= ink.dp(18f)
                if (onThumb) {
                    sliderTouchIndex = sliderIndex
                    setSliderValue(sliderTouchIndex, event.x)
                    invalidate()
                    return true
                }
            }
        }
        if (sliderTouchIndex >= 0) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    setSliderValue(sliderTouchIndex, event.x)
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    setSliderValue(sliderTouchIndex, event.x)
                    sliderTouchIndex = -1
                    context.sendBroadcast(Intent(DynamicBackgroundActivity.ACTION_SETTINGS_CHANGED).setPackage(context.packageName))
                    invalidate()
                }
            }
            return true
        }
        val handled = super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_MOVE ||
            event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL) {
            prefs.edit().putFloat(scrollStateKey, scroll.offset).apply()
        }
        return handled
    }
}
