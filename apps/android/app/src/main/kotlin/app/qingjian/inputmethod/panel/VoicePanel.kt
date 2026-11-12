package app.qingjian.inputmethod.panel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import app.qingjian.inputmethod.keyboard.Haptics
import app.qingjian.inputmethod.panel.base.Ink
import app.qingjian.inputmethod.panel.base.PanelIcons.Icon
import app.qingjian.inputmethod.theme.Palette
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 键盘内的透明语音反馈层：键盘始终可见，只在中央显示麦克风、柔和光晕和随实时音量变化的环形声波。
 * 触摸只接管麦克风与底部两个操作区，其余区域交还键盘。
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
    private val levels = FloatArray(BARS)
    private var phase = 0f
    private var touchTarget = Target.NONE

    var holding = false
        set(value) { field = value; invalidate() }

    var recognizing = false
        set(value) { field = value; invalidate() }

    var target = Target.NONE
        private set

    init {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        isClickable = false
    }

    fun addLevel(level: Float) {
        System.arraycopy(levels, 1, levels, 0, BARS - 1)
        levels[BARS - 1] = level.coerceIn(0f, 1f)
        phase = (phase + 0.17f) % (2f * PI.toFloat())
        invalidate()
    }

    fun track(screenX: Float, screenY: Float) {
        val location = IntArray(2)
        getLocationOnScreen(location)
        val next = targetAt(screenX - location[0], screenY - location[1])
        if (next == target) return
        target = next
        if (next != Target.NONE) Haptics.tap(context)
        invalidate()
    }

    private fun targetAt(x: Float, y: Float): Target {
        if (y < height * 0.77f || y > height * 0.96f) return Target.NONE
        return when {
            x < width * 0.34f -> Target.CANCEL
            x > width * 0.66f -> Target.ACTION
            else -> Target.NONE
        }
    }

    private fun micCenterY() = height * 0.53f
    private fun micRadius() = ink.dp(42f)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = micCenterY()
        val active = holding || recognizing
        val average = if (active) levels.average().toFloat() else 0.04f
        val pulse = if (active) average else 0.025f

        // 柔和的多层光晕，强弱随麦克风实时音量变化。
        for (i in 4 downTo 1) {
            paint.style = Paint.Style.FILL
            paint.color = withAlpha(palette.brand, (7 + pulse * 24).toInt().coerceIn(7, 31))
            canvas.drawCircle(cx, cy, micRadius() + ink.dp(i * 12f) + pulse * ink.dp(24f), paint)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        for (i in 0 until BARS) {
            val angle = (2.0 * PI * i / BARS + phase * 0.08).toFloat()
            val sample = if (active) levels[i] else 0.035f
            val wave = ink.dp(5f) + sample * ink.dp(24f)
            val inner = micRadius() + ink.dp(10f)
            val outer = inner + wave
            paint.strokeWidth = ink.dp(2.2f)
            paint.color = withAlpha(palette.brand, (95 + sample * 155).toInt().coerceIn(80, 250))
            canvas.drawLine(
                cx + cos(angle) * inner, cy + sin(angle) * inner,
                cx + cos(angle) * outer, cy + sin(angle) * outer, paint
            )
        }

        // 中央麦克风本体保持轻量透明，不绘制独立面板或大块底色。
        paint.style = Paint.Style.FILL
        paint.color = withAlpha(palette.surface, 0xD8)
        canvas.drawCircle(cx, cy, micRadius(), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ink.dp(1.4f)
        paint.color = withAlpha(palette.brand, 0xD0)
        canvas.drawCircle(cx, cy, micRadius(), paint)
        paint.style = Paint.Style.FILL
        ink.icon(canvas, Icon.MIC, cx, cy, 29f, palette.brand)

        val hint = when {
            recognizing -> "正在识别语音…"
            holding -> "正在聆听 · 松开结束"
            else -> "点击麦克风结束语音输入"
        }
        ink.text(canvas, hint, 13f, palette.gloss, cx, cy + ink.dp(68f))

        val actionY = height * 0.86f
        ink.text(canvas, "撤回", 13f,
            if (target == Target.CANCEL) palette.brand else palette.gloss, width * 0.17f, actionY,
            bold = target == Target.CANCEL)
        ink.text(canvas, actionLabel, 13f,
            if (target == Target.ACTION) palette.brand else palette.gloss, width * 0.83f, actionY,
            bold = target == Target.ACTION)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        val micHit = hypot(x - width / 2f, y - micCenterY()) <= micRadius() + ink.dp(18f)
        val action = targetAt(x, y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchTarget = when {
                    micHit -> Target.NONE
                    action != Target.NONE -> action
                    else -> return false
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> return touchTarget != Target.NONE
            MotionEvent.ACTION_UP -> {
                when {
                    touchTarget == Target.CANCEL || action == Target.CANCEL -> actions.onCancel()
                    touchTarget == Target.ACTION || action == Target.ACTION -> actions.onFinish(act = true, hide = false)
                    touchTarget == Target.NONE && micHit -> actions.onFinish(act = false, hide = false)
                    else -> return false
                }
                touchTarget = Target.NONE
                Haptics.tap(context)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                touchTarget = Target.NONE
                return true
            }
        }
        return touchTarget != Target.NONE
    }

    private fun withAlpha(color: Int, alpha: Int) = (color and 0xFFFFFF) or (alpha shl 24)

    private companion object {
        const val BARS = 48
    }
}
