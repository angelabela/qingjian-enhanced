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

/** 动态视频背景设置直接嵌入常用功能子面板；设置写入后通知输入法重建背景层。 */
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
    private val rowHeight get() = ink.dp(66f)
    private val labels = listOf("导入或更换视频", "启用视频背景", "视频与原有光效", "视频磨砂效果", "背景光效样式", "视频透明度", "背景亮度", "模糊程度")
    private val effectStyles = listOf(
        0 to "极光流彩", 1 to "深海蓝潮", 2 to "日落熔金", 4 to "冰晶星雾",
        7 to "霓虹光幕", 8 to "星环脉冲",
        9 to "流体·极光", 10 to "流体·日落", 11 to "流体·糖果", 12 to "流体·深海", 13 to "流体·霓虹",
        14 to "晴空云海", 15 to "暮色云层", 16 to "月夜流云", 17 to "梦幻光斑", 18 to "光斑穿云",
    )
    private fun selectedEffectStyle(): Int = Settings.normalizeKeyboardBackgroundStyle(
        settingsPrefs.getString(Settings.KEY_BACKGROUND_STYLE, "0")?.toIntOrNull() ?: 0
    )
    private fun selectedEffectName(): String = effectStyles.firstOrNull { it.first == selectedEffectStyle() }?.second ?: "极光流彩"
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
        ink.text(canvas, "视频背景", 16f, palette.text, width * 0.5f, headerHeight * 0.5f)
        card.set(ink.dp(10f), headerHeight, width - ink.dp(10f), height - ink.dp(8f))
        ink.round(canvas, card, 16f, palette.surface)
        val top = card.top + ink.dp(8f)
        val viewportTop = top
        val viewportBottom = card.bottom - ink.dp(8f)
        scroll.max = (labels.size * rowHeight - (viewportBottom - viewportTop)).coerceAtLeast(0f)
        canvas.save()
        canvas.clipRect(card.left, viewportTop, card.right, viewportBottom)
        canvas.translate(0f, -scroll.offset)
        labels.forEachIndexed { index, label ->
            val y = top + index * rowHeight
            val value = when (index) {
                0 -> if (prefs.getString(DynamicBackgroundActivity.KEY_URI, null) == null) "尚未导入 · 点击选择" else "已导入视频 · 点击更换"
                1 -> if (prefs.getBoolean(DynamicBackgroundActivity.KEY_ENABLED, false)) "已开启" else "已关闭"
                2 -> modeLabels[prefs.getInt(DynamicBackgroundActivity.KEY_MODE, 2).coerceIn(0, 2)]
                3 -> if (prefs.getBoolean(DynamicBackgroundActivity.KEY_FROSTED, true)) "开启" else "关闭"
                4 -> "${selectedEffectName()} · ${effectStyles.indexOfFirst { it.first == selectedEffectStyle() } + 1}/${effectStyles.size}"
                5 -> "${prefs.getInt(DynamicBackgroundActivity.KEY_ALPHA, 72).coerceIn(0, 100)}%"
                6 -> "${prefs.getInt(DynamicBackgroundActivity.KEY_BRIGHTNESS, 100).coerceIn(0, 200)}%"
                7 -> "${prefs.getInt(DynamicBackgroundActivity.KEY_BLUR, 8).coerceIn(0, 30)} dp"
                else -> ""
            }
            ink.textStart(canvas, label, 13f, palette.text, card.left + ink.dp(16f), y + ink.dp(22f), card.right - ink.dp(16f))
            ink.text(canvas, value, 12f, palette.brand, card.right - ink.dp(72f), y + ink.dp(22f))
            if (index in 5..7) {
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
            } else {
                ink.line(canvas, card.left + ink.dp(16f), y + rowHeight, card.right - ink.dp(16f), y + rowHeight, palette.faint)
            }
        }
        canvas.restore()
    }

    override fun onBack() = actions.openPanel(PanelKind.FUNCTIONS)

    override fun onTap(x: Float, y: Float) {
        if (!card.contains(x, y)) return
        val contentY = y - card.top - ink.dp(8f) + scroll.offset
        val index = (contentY / rowHeight).toInt()
        when (index) {
            0 -> context.startActivity(Intent(context, DynamicBackgroundActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            1 -> update { putBoolean(DynamicBackgroundActivity.KEY_ENABLED, !prefs.getBoolean(DynamicBackgroundActivity.KEY_ENABLED, false)) }
            2 -> {
                val nextMode = (prefs.getInt(DynamicBackgroundActivity.KEY_MODE, 2) + 1) % 3
                prefs.edit().putInt(DynamicBackgroundActivity.KEY_MODE, nextMode).apply()
                Settings.preferences(context).edit()
                    .putBoolean(Settings.KEY_BACKGROUND_EFFECT, nextMode != 0)
                    .apply()
                notifyChanged()
            }
            3 -> update { putBoolean(DynamicBackgroundActivity.KEY_FROSTED, !prefs.getBoolean(DynamicBackgroundActivity.KEY_FROSTED, true)) }
            4 -> {
                val current = selectedEffectStyle()
                val currentIndex = effectStyles.indexOfFirst { it.first == current }.coerceAtLeast(0)
                val next = effectStyles[(currentIndex + 1) % effectStyles.size].first
                // 选中任一效果时自动启用光效，避免只改变编号却因总开关关闭而看不到预览。
                settingsPrefs.edit()
                    .putString(Settings.KEY_BACKGROUND_STYLE, next.toString())
                    .putBoolean(Settings.KEY_BACKGROUND_EFFECT, true)
                    .apply()
                // 若当前选的是“仅视频”，用户主动选择光效时切换到叠加模式，确保新样式能立即预览。
                if (prefs.getBoolean(DynamicBackgroundActivity.KEY_ENABLED, false) &&
                    prefs.getInt(DynamicBackgroundActivity.KEY_MODE, 2) == 0) {
                    prefs.edit().putInt(DynamicBackgroundActivity.KEY_MODE, 2).apply()
                }
                context.sendBroadcast(Intent(DynamicBackgroundActivity.ACTION_SETTINGS_CHANGED).setPackage(context.packageName))
                invalidate()
            }
            in 5..7 -> {
                // 滑块只允许从实际轨道/滑块附近启动；整行点击不再意外改变数值。
                val railY = card.top + ink.dp(8f) + index * rowHeight + ink.dp(49f) - scroll.offset
                val left = card.left + ink.dp(18f)
                val right = card.right - ink.dp(18f)
                if (kotlin.math.abs(y - railY) <= ink.dp(12f) && x in left..right) {
                    setSliderValue(index, x)
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
        if (event.actionMasked == MotionEvent.ACTION_DOWN && card.contains(event.x, event.y)) {
            val index = ((event.y - card.top - ink.dp(8f) + scroll.offset) / rowHeight).toInt()
            if (index in 5..7) {
                val railY = card.top + ink.dp(8f) + index * rowHeight + ink.dp(49f) - scroll.offset
                val left = card.left + ink.dp(18f)
                val right = card.right - ink.dp(18f)
                val max = when (index) { 6 -> 200f; 7 -> 30f; else -> 100f }
                val current = when (index) {
                    5 -> prefs.getInt(DynamicBackgroundActivity.KEY_ALPHA, 72)
                    6 -> prefs.getInt(DynamicBackgroundActivity.KEY_BRIGHTNESS, 100)
                    else -> prefs.getInt(DynamicBackgroundActivity.KEY_BLUR, 8)
                }.coerceIn(0, max.toInt())
                val knobX = left + (right - left) * (current / max)
                // 只在当前滑块圆点上按下才接管手势；轨道空白区和整行都留给面板滚动。
                val onThumb = kotlin.math.abs(event.y - railY) <= ink.dp(15f) &&
                    kotlin.math.abs(event.x - knobX) <= ink.dp(18f)
                if (onThumb) {
                    sliderTouchIndex = index
                    setSliderValue(index, event.x)
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
