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
    private val rowHeight get() = ink.dp(58f)
    private val effectCellHeight get() = ink.dp(62f)
    private val effectGridGap get() = ink.dp(8f)
    private val labels = listOf("导入或更换视频", "视频背景开关", "背景显示模式", "视频磨砂效果", "视频透明度", "背景亮度", "模糊程度")
    private val effectRows get() = (effectStyles.size + 2) / 3
    private fun settingsRowsTop(top: Float) = top + ink.dp(30f)
    private fun effectTitleY(top: Float) = settingsRowsTop(top) + rowHeight * 4 + ink.dp(20f)
    private fun effectGridTop(top: Float) = settingsRowsTop(top) + rowHeight * 4 + ink.dp(32f)
    private fun effectGridBottom(top: Float) = effectGridTop(top) + effectRows * effectCellHeight + (effectRows - 1) * effectGridGap
    private fun slidersTop(top: Float) = effectGridBottom(top) + ink.dp(38f)
    private fun sliderRowTop(top: Float, index: Int) = slidersTop(top) + (index - 5) * rowHeight
    private val effectStyles = listOf(
        0 to "极光流彩", 1 to "深海蓝潮", 2 to "日落熔金", 4 to "冰晶星雾",
        7 to "霓虹光幕", 8 to "星环脉冲",
        9 to "流体·极光", 10 to "流体·日落", 11 to "流体·糖果", 12 to "流体·深海", 13 to "流体·霓虹",
        14 to "晴空云海", 15 to "暮色云层", 16 to "月夜流云", 17 to "梦幻光斑", 18 to "光斑穿云",
    )
    private fun selectedEffectStyle(): Int = Settings.normalizeKeyboardBackgroundStyle(
        settingsPrefs.getString(Settings.KEY_BACKGROUND_STYLE, "0")?.toIntOrNull() ?: 0
    )
    private val modeLabels = listOf("仅视频", "仅原有光效", "视频 + 光效叠加")

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
        ink.text(canvas, "面板背景特效", 16f, palette.text, width * 0.5f, headerHeight * 0.5f)
        card.set(ink.dp(10f), headerHeight, width - ink.dp(10f), height - ink.dp(8f))
        ink.round(canvas, card, 16f, palette.surface)
        val top = card.top + ink.dp(8f)
        val viewportTop = top
        val viewportBottom = card.bottom - ink.dp(8f)
        val totalHeight = sliderRowTop(top, 7) + rowHeight - top
        scroll.max = (totalHeight - (viewportBottom - viewportTop)).coerceAtLeast(0f)
        canvas.save()
        canvas.clipRect(card.left, viewportTop, card.right, viewportBottom)
        canvas.translate(0f, -scroll.offset)

        // 视频背景设置区
        ink.textStart(canvas, "视频背景设置", 11f, palette.gloss, card.left + ink.dp(16f), top + ink.dp(18f), card.right - ink.dp(16f))
        labels.take(4).forEachIndexed { index, label ->
            val y = settingsRowsTop(top) + index * rowHeight
            val value = when (index) {
                0 -> if (prefs.getString(DynamicBackgroundActivity.KEY_URI, null) == null) "尚未导入 · 点击选择" else "已导入视频 · 点击更换"
                1 -> if (prefs.getBoolean(DynamicBackgroundActivity.KEY_ENABLED, false)) "已开启" else "已关闭"
                2 -> modeLabels[prefs.getInt(DynamicBackgroundActivity.KEY_MODE, 2).coerceIn(0, 2)]
                else -> if (prefs.getBoolean(DynamicBackgroundActivity.KEY_FROSTED, true)) "开启" else "关闭"
            }
            ink.textStart(canvas, label, 13f, palette.text, card.left + ink.dp(16f), y + ink.dp(22f), card.right - ink.dp(16f))
            ink.text(canvas, value, 12f, palette.brand, card.right - ink.dp(72f), y + ink.dp(22f))
            ink.line(canvas, card.left + ink.dp(16f), y + rowHeight, card.right - ink.dp(16f), y + rowHeight, palette.faint)
        }

        // 背景光效样式：固定三列，所有样式均在网格中展示；超出可视区域由整个内容区上下滚动。
        val gridTop = effectGridTop(top)
        ink.textStart(canvas, "背景光效样式", 13f, palette.text, card.left + ink.dp(16f), effectTitleY(top), card.right - ink.dp(16f))
        val gridLeft = card.left + ink.dp(12f)
        val gridRight = card.right - ink.dp(12f)
        val cellGap = effectGridGap
        val cellWidth = (gridRight - gridLeft - cellGap * 2) / 3f
        val selected = selectedEffectStyle()
        effectStyles.forEachIndexed { index, style ->
            val row = index / 3
            val col = index % 3
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
            ink.text(canvas, style.second, 10f, if (isSelected) palette.brand else palette.text, rect.centerX(), rect.centerY() - ink.dp(3f))
            ink.text(canvas, "效果 ${style.first}", 9f, palette.gloss, rect.centerX(), rect.centerY() + ink.dp(15f))
        }

        // 画面调节区：与光效网格分组，滑块可拖动调整。
        ink.textStart(canvas, "画面调节", 13f, palette.text, card.left + ink.dp(16f), effectGridBottom(top) + ink.dp(22f), card.right - ink.dp(16f))
        for (index in 5..7) {
            val label = labels[index - 1]
            val y = sliderRowTop(top, index)
            val value = when (index) {
                5 -> "${prefs.getInt(DynamicBackgroundActivity.KEY_ALPHA, 72).coerceIn(0, 100)}%"
                6 -> "${prefs.getInt(DynamicBackgroundActivity.KEY_BRIGHTNESS, 100).coerceIn(0, 200)}%"
                else -> "${prefs.getInt(DynamicBackgroundActivity.KEY_BLUR, 8).coerceIn(0, 30)} dp"
            }
            ink.textStart(canvas, label, 13f, palette.text, card.left + ink.dp(16f), y + ink.dp(22f), card.right - ink.dp(16f))
            ink.text(canvas, value, 12f, palette.brand, card.right - ink.dp(72f), y + ink.dp(22f))
            val left = card.left + ink.dp(18f)
            val right = card.right - ink.dp(18f)
            val cy = y + ink.dp(49f)
            val max = when (index) { 6 -> 200f; 7 -> 30f; else -> 100f }
            val current = when (index) {
                5 -> prefs.getInt(DynamicBackgroundActivity.KEY_ALPHA, 72)
                6 -> prefs.getInt(DynamicBackgroundActivity.KEY_BRIGHTNESS, 100)
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
            contentY >= settingsRowsTop(top) && relativeY < rowHeight * 4 -> {
                val index = (relativeY / rowHeight).toInt()
                when (index) {
                    0 -> context.startActivity(Intent(context, DynamicBackgroundActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    1 -> update { putBoolean(DynamicBackgroundActivity.KEY_ENABLED, !prefs.getBoolean(DynamicBackgroundActivity.KEY_ENABLED, false)) }
                    2 -> {
                        val nextMode = (prefs.getInt(DynamicBackgroundActivity.KEY_MODE, 2) + 1) % 3
                        prefs.edit().putInt(DynamicBackgroundActivity.KEY_MODE, nextMode).apply()
                        settingsPrefs.edit().putBoolean(Settings.KEY_BACKGROUND_EFFECT, nextMode != 0).apply()
                        notifyChanged()
                    }
                    3 -> update { putBoolean(DynamicBackgroundActivity.KEY_FROSTED, !prefs.getBoolean(DynamicBackgroundActivity.KEY_FROSTED, true)) }
                }
            }
            contentY >= effectGridTop(top) && contentY < effectGridBottom(top) + effectGridGap -> {
                val gridY = contentY - effectGridTop(top)
                val gridLeft = card.left + ink.dp(12f)
                val gridRight = card.right - ink.dp(12f)
                val cellGap = effectGridGap
                val cellWidth = (gridRight - gridLeft - cellGap * 2) / 3f
                val col = ((x - gridLeft) / (cellWidth + cellGap)).toInt()
                val row = (gridY / (effectCellHeight + cellGap)).toInt()
                if (col in 0..2 && row >= 0) {
                    val cellLeft = gridLeft + col * (cellWidth + cellGap)
                    val cellTop = row * (effectCellHeight + cellGap)
                    if (x >= cellLeft && x <= cellLeft + cellWidth &&
                        gridY >= cellTop && gridY <= cellTop + effectCellHeight) {
                        val style = effectStyles.getOrNull(row * 3 + col) ?: return
                        settingsPrefs.edit()
                            .putString(Settings.KEY_BACKGROUND_STYLE, style.first.toString())
                            .putBoolean(Settings.KEY_BACKGROUND_EFFECT, true)
                            .apply()
                        if (prefs.getBoolean(DynamicBackgroundActivity.KEY_ENABLED, false) &&
                            prefs.getInt(DynamicBackgroundActivity.KEY_MODE, 2) == 0) {
                            prefs.edit().putInt(DynamicBackgroundActivity.KEY_MODE, 2).apply()
                        }
                        notifyChanged()
                    }
                }
            }
            else -> {
                val sliderIndex = (5..7).firstOrNull { index ->
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
                5 -> putInt(DynamicBackgroundActivity.KEY_ALPHA, (value * 100).toInt())
                6 -> putInt(DynamicBackgroundActivity.KEY_BRIGHTNESS, (value * 200).toInt())
                7 -> putInt(DynamicBackgroundActivity.KEY_BLUR, (value * 30).toInt())
                else -> putInt(DynamicBackgroundActivity.KEY_BLUR, (value * 30).toInt())
            }
        }.apply()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val top = card.top + ink.dp(8f)
        val contentY = event.y + scroll.offset
        if (event.actionMasked == MotionEvent.ACTION_DOWN && card.contains(event.x, event.y)) {
            val sliderIndex = (5..7).firstOrNull { index ->
                val rowTop = sliderRowTop(top, index)
                contentY >= rowTop && contentY < rowTop + rowHeight
            }
            if (sliderIndex != null) {
                val railY = sliderRowTop(top, sliderIndex) + ink.dp(49f)
                val left = card.left + ink.dp(18f)
                val right = card.right - ink.dp(18f)
                val max = when (sliderIndex) { 6 -> 200f; 7 -> 30f; else -> 100f }
                val current = when (sliderIndex) {
                    5 -> prefs.getInt(DynamicBackgroundActivity.KEY_ALPHA, 72)
                    6 -> prefs.getInt(DynamicBackgroundActivity.KEY_BRIGHTNESS, 100)
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
        return super.onTouchEvent(event)
    }
}
