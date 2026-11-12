package app.qingjian.inputmethod.panel

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import app.qingjian.inputmethod.keyboard.Haptics
import app.qingjian.inputmethod.panel.base.Ink
import app.qingjian.inputmethod.panel.base.PanelIcons.Icon
import app.qingjian.inputmethod.theme.Palette
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 透明语音输入界面：取消旧版光晕、弧形按钮和环形波纹，
 * 改为简洁的水平线性声波与匀速扫描指示。语音识别及手势回调保持不变。
 */
@SuppressLint("ViewConstructor")
class VoicePanel(
    context: Context,
    private val palette: Palette,
    private val actionLabel: String,
    private val actions: Actions,
) : View(context) {
    interface Actions {
        fun onCancel()
        fun onFinish(act: Boolean, hide: Boolean)
    }

    enum class Target { NONE, CANCEL, ACTION }

    private val ink = Ink(palette, resources.displayMetrics.density)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val location = IntArray(2)
    private val levels = FloatArray(BARS)
    private var scanPhase = 0f

    private val scanAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1500L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            scanPhase = it.animatedValue as Float
            invalidate()
        }
    }

    var holding = false
        set(value) {
            field = value
            updateAnimation()
            invalidate()
        }

    var recognizing = false
        set(value) {
            field = value
            updateAnimation()
            invalidate()
        }

    var target = Target.NONE
        private set

    init {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimation()
    }

    override fun onDetachedFromWindow() {
        scanAnimator.cancel()
        super.onDetachedFromWindow()
    }

    private fun updateAnimation() {
        if ((holding || recognizing) && isAttachedToWindow) {
            if (!scanAnimator.isStarted) scanAnimator.start()
        } else {
            scanAnimator.cancel()
            scanPhase = 0f
        }
    }

    fun addLevel(level: Float) {
        System.arraycopy(levels, 1, levels, 0, BARS - 1)
        levels[BARS - 1] = level.coerceIn(0f, 1f)
        invalidate()
    }

    fun track(screenX: Float, screenY: Float) {
        getLocationOnScreen(location)
        val next = targetAt(screenX - location[0], screenY - location[1])
        if (next == target) return
        target = next
        if (next != Target.NONE) Haptics.tap(context)
        invalidate()
    }

    private fun targetAt(x: Float, y: Float): Target {
        if (y < height * 0.56f || y > height * 0.88f) return Target.NONE
        return when {
            x < width * 0.34f -> Target.CANCEL
            x > width * 0.66f -> Target.ACTION
            else -> Target.NONE
        }
    }

    private fun hint() = when {
        recognizing -> "正在识别…"
        !holding -> "点击话筒开始语音输入"
        target == Target.CANCEL -> "松手撤回本次语音"
        target == Target.ACTION -> "松手$actionLabel"
        else -> "松手结束语音输入"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val centerY = h * 0.34f
        val span = w * 0.68f
        val left = (w - span) / 2f
        val step = span / BARS
        val barWidth = ink.dp(2.4f)
        val maxHalf = ink.dp(22f)

        // 水平声波：仅随真实音量变化，不再绘制旧版发光背景和弧形波纹。
        for (i in 0 until BARS) {
            val edge = 1f - abs(i - (BARS - 1) / 2f) / (BARS / 2f)
            val level = if (recognizing) 0.08f else levels[i]
            val half = ink.dp(2f) + (maxHalf - ink.dp(2f)) * level * edge.coerceAtLeast(0.12f)
            val x = left + step * (i + 0.5f)
            paint.color = withAlpha(palette.brand, (105 + 145 * edge).toInt().coerceIn(0, 255))
            rect.set(x - barWidth / 2, centerY - half, x + barWidth / 2, centerY + half)
            canvas.drawRoundRect(rect, barWidth, barWidth, paint)
        }

        // 细线性扫描条：录音/识别时匀速移动，静止时只保留淡淡的底线。
        val lineY = h * 0.49f
        val lineLeft = w * 0.22f
        val lineRight = w * 0.78f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ink.dp(1f)
        paint.color = withAlpha(palette.gloss, 0x55)
        canvas.drawLine(lineLeft, lineY, lineRight, lineY, paint)
        if (holding || recognizing) {
            val markerX = lineLeft + (lineRight - lineLeft) * scanPhase
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = ink.dp(2.5f)
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = palette.brand
            canvas.drawLine(markerX - ink.dp(13f), lineY, markerX + ink.dp(13f), lineY, paint)
            paint.strokeCap = Paint.Cap.BUTT
        }

        paint.style = Paint.Style.FILL
        ink.text(canvas, hint(), 13f, palette.gloss, w / 2f, h * 0.59f)

        // 中央话筒按钮保持轻量描边，不铺不透明底色。
        val buttonY = h * 0.75f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ink.dp(1.5f)
        paint.color = withAlpha(palette.brand, 0xB0)
        canvas.drawCircle(w / 2f, buttonY, ink.dp(holding || recognizing && !holding.let { false } && false).toFloat(), paint)
        paint.style = Paint.Style.FILL
        if (!holding && !recognizing) ink.icon(canvas, Icon.MIC, w / 2f, buttonY, 22f, palette.brand)
        else {
            paint.color = palette.brand
            canvas.drawCircle(w / 2f, buttonY, ink.dp(4f), paint)
        }

        // 两侧为线性操作区，保留原撤回、回车动作和底部收起入口。
        val actionY = h * 0.69f
        ink.text(canvas, "撤回", 14f, if (target == Target.CANCEL) palette.brand else palette.gloss, w * 0.16f, actionY, bold = target == Target.CANCEL)
        ink.text(canvas, actionLabel, 14f, if (target == Target.ACTION) palette.brand else palette.gloss, w * 0.84f, actionY, bold = target == Target.ACTION)
        val rowY = h - ink.dp(22f)
        ink.icon(canvas, Icon.HIDE, ink.dp(40f), rowY, 22f, palette.gloss)
        ink.icon(canvas, Icon.KEYBOARD, w - ink.dp(40f), rowY, 24f, palette.gloss)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_UP || holding || recognizing) return true
        val x = event.x
        val y = event.y
        val rowY = height - ink.dp(22f)
        val near = ink.dp(32f)
        val tapped = when {
            targetAt(x, y) == Target.CANCEL -> { actions.onCancel(); true }
            targetAt(x, y) == Target.ACTION -> { actions.onFinish(act = true, hide = false); true }
            hypot(x - width / 2f, y - height * 0.75f) < near -> { actions.onFinish(act = false, hide = false); true }
            y > rowY - near && x < ink.dp(80f) -> { actions.onFinish(act = false, hide = true); true }
            y > rowY - near && x > width - ink.dp(80f) -> { actions.onFinish(act = false, hide = false); true }
            else -> false
        }
        if (tapped) Haptics.tap(context)
        return true
    }

    private fun withAlpha(color: Int, alpha: Int) = (color and 0xFFFFFF) or (alpha shl 24)

    private companion object {
        const val BARS = 36
    }
}
