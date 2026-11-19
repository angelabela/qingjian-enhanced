package app.qingjian.inputmethod.panel

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import app.qingjian.inputmethod.background.DynamicBackgroundActivity
import app.qingjian.inputmethod.panel.base.PanelIcons
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
    private val card = RectF()
    private val scroll = scrollable(false) { x, y -> card.contains(x, y) }
    private var sliderDragged = false
    private val rowHeight get() = ink.dp(66f)
    private val labels = listOf("启用视频背景", "视频与原有光效", "视频磨砂效果", "视频透明度", "背景亮度", "模糊程度", "导入或更换视频")
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
        drawHeader(canvas, null, PanelIcons.Icon.SETTINGS)
        ink.text(canvas, "动态视频背景", 16f, palette.text, width * 0.5f, headerHeight * 0.5f)
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
                0 -> if (prefs.getBoolean(DynamicBackgroundActivity.KEY_ENABLED, false)) "已开启" else "已关闭"
                1 -> modeLabels[prefs.getInt(DynamicBackgroundActivity.KEY_MODE, 2).coerceIn(0, 2)]
                2 -> if (prefs.getBoolean(DynamicBackgroundActivity.KEY_FROSTED, true)) "开启" else "关闭"
                3 -> "${prefs.getInt(DynamicBackgroundActivity.KEY_ALPHA, 72).coerceIn(0, 100)}%"
                4 -> "${prefs.getInt(DynamicBackgroundActivity.KEY_BRIGHTNESS, 100).coerceIn(0, 200)}%"
                5 -> "${prefs.getInt(DynamicBackgroundActivity.KEY_BLUR, 8).coerceIn(0, 30)} dp"
                else -> if (prefs.getString(DynamicBackgroundActivity.KEY_URI, null) == null) "尚未导入" else "已导入视频 · 点击更换"
            }
            ink.textStart(canvas, label, 13f, palette.text, card.left + ink.dp(16f), y + ink.dp(22f), card.right - ink.dp(16f))
            ink.text(canvas, value, 12f, palette.brand, card.right - ink.dp(72f), y + ink.dp(22f))
            if (index in 3..5) {
                val left = card.left + ink.dp(18f)
                val right = card.right - ink.dp(18f)
                val cy = y + ink.dp(49f)
                val max = if (index == 3) 100f else if (index == 4) 200f else 30f
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
            } else {
                ink.line(canvas, card.left + ink.dp(16f), y + rowHeight, card.right - ink.dp(16f), y + rowHeight, palette.faint)
            }
        }
        canvas.restore()
    }

    override fun onTap(x: Float, y: Float) {
        if (isRightIcon(x, y)) return
        if (!card.contains(x, y)) return
        val contentY = y - card.top - ink.dp(8f) + scroll.offset
        val index = (contentY / rowHeight).toInt()
        when (index) {
            0 -> update { putBoolean(DynamicBackgroundActivity.KEY_ENABLED, !prefs.getBoolean(DynamicBackgroundActivity.KEY_ENABLED, false)) }
            1 -> {
                val nextMode = (prefs.getInt(DynamicBackgroundActivity.KEY_MODE, 2) + 1) % 3
                prefs.edit().putInt(DynamicBackgroundActivity.KEY_MODE, nextMode).apply()
                // 选择仅光效或视频与光效叠加时自动打开原有键盘光影；仅视频时关闭它。
                Settings.preferences(context).edit()
                    .putBoolean(Settings.KEY_BACKGROUND_EFFECT, nextMode != 0)
                    .apply()
                notifyChanged()
            }
            2 -> update { putBoolean(DynamicBackgroundActivity.KEY_FROSTED, !prefs.getBoolean(DynamicBackgroundActivity.KEY_FROSTED, true)) }
            in 3..5 -> {
                val left = card.left + ink.dp(18f)
                val right = card.right - ink.dp(18f)
                val value = (((x - left) / (right - left).coerceAtLeast(1f)).coerceIn(0f, 1f))
                update {
                    when (index) {
                        3 -> putInt(DynamicBackgroundActivity.KEY_ALPHA, (value * 100).toInt())
                        4 -> putInt(DynamicBackgroundActivity.KEY_BRIGHTNESS, (value * 200).toInt())
                        else -> putInt(DynamicBackgroundActivity.KEY_BLUR, (value * 30).toInt())
                    }
                }
            }
            6 -> context.startActivity(Intent(context, DynamicBackgroundActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_MOVE && card.contains(event.x, event.y)) {
            val index = ((event.y - card.top - ink.dp(8f) + scroll.offset) / rowHeight).toInt()
            if (index in 3..5) {
                val left = card.left + ink.dp(18f)
                val right = card.right - ink.dp(18f)
                val value = ((event.x - left) / (right - left).coerceAtLeast(1f)).coerceIn(0f, 1f)
                prefs.edit().apply {
                    when (index) {
                        3 -> putInt(DynamicBackgroundActivity.KEY_ALPHA, (value * 100).toInt())
                        4 -> putInt(DynamicBackgroundActivity.KEY_BRIGHTNESS, (value * 200).toInt())
                        else -> putInt(DynamicBackgroundActivity.KEY_BLUR, (value * 30).toInt())
                    }
                }.apply()
                sliderDragged = true
                invalidate()
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_UP && sliderDragged) {
            sliderDragged = false
            context.sendBroadcast(Intent(DynamicBackgroundActivity.ACTION_SETTINGS_CHANGED).setPackage(context.packageName))
        }
        return handled
    }
}
