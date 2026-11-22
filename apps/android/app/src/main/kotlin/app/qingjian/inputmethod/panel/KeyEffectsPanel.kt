package app.qingjian.inputmethod.panel

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
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
    private fun top() = card.top + ink.dp(12f)
    private fun gridTop() = card.top + ink.dp(42f)
    private fun alphaTop() = gridTop() + ink.dp(112f)
    private fun alphaTrackY() = alphaTop() + ink.dp(40f)
    private fun frostedTop() = alphaTop() + ink.dp(60f)
    private fun marqueeTop() = frostedTop() + ink.dp(48f)
    private fun contentBottom() = marqueeTop() + ink.dp(48f)

    private fun notifyChanged() {
        context.sendBroadcast(Intent(DynamicBackgroundActivity.ACTION_SETTINGS_CHANGED).setPackage(context.packageName))
        invalidate()
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
        ink.textStart(canvas, "26 个字母按键依次流动发光，功能键保持原样", 10.5f, palette.gloss,
            card.left + ink.dp(14f), top() + ink.dp(14f), card.right - ink.dp(10f))
        ink.textStart(canvas, "字母按键预览", 11f, palette.text,
            card.left + ink.dp(14f), top() + ink.dp(30f), card.right - ink.dp(10f))
        drawLetterPreview(canvas)
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
        canvas.restore()
        if (marquee && isShown) postInvalidateDelayed(32L)
    }

    private fun drawLetterPreview(canvas: Canvas) {
        val gap = ink.dp(4f)
        val left = card.left + ink.dp(12f)
        val cellWidth = (card.width() - ink.dp(24f) - gap * 6f) / 7f
        val rowStep = ink.dp(27f)
        val phase = (SystemClock.uptimeMillis() % 2_600L).toFloat() / 100f
        ('A'..'Z').forEachIndexed { index, letter ->
            val x = left + (index % 7) * (cellWidth + gap)
            val y = gridTop() + (index / 7) * rowStep
            val bounds = RectF(x, y, x + cellWidth, y + ink.dp(22f))
            ink.round(canvas, bounds, 5f, palette.functionalKey)
            val trail = (phase - index + 26f) % 26f
            val strength = if (marquee && trail <= 4.8f) (1f - trail / 4.8f).coerceIn(0f, 1f) else 0f
            if (strength > 0f) {
                val hue = (172f + phase * (360f / 26f)) % 360f
                val color = Color.HSVToColor((48 + 207 * strength).toInt(), floatArrayOf(hue, 0.92f, 1f))
                ink.fill.style = Paint.Style.STROKE
                ink.fill.strokeWidth = ink.dp(0.8f + 1.2f * strength)
                ink.fill.color = color
                ink.fill.setShadowLayer(ink.dp(3f * strength), 0f, 0f, color)
                canvas.drawRoundRect(bounds, ink.dp(5f), ink.dp(5f), ink.fill)
                ink.fill.clearShadowLayer()
                ink.fill.style = Paint.Style.FILL
                ink.text(canvas, letter.toString(), 11f, color, bounds.centerX(), bounds.centerY())
            } else ink.text(canvas, letter.toString(), 11f, palette.text, bounds.centerX(), bounds.centerY())
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
        } else if (contentY >= marqueeTop() && contentY < contentBottom()) {
            prefs.edit().putBoolean(DynamicBackgroundActivity.KEY_KEYBOARD_MARQUEE, !marquee).apply()
            notifyChanged()
        }
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
    }
}
