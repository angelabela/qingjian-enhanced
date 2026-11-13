package app.qingjian.inputmethod.panel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import app.qingjian.inputmethod.keyboard.Haptics
import app.qingjian.inputmethod.panel.base.Ink
import app.qingjian.inputmethod.panel.base.PanelIcons.Icon
import app.qingjian.inputmethod.theme.Palette
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 键盘内的语音反馈层。语音启动时轻柔压低键盘的颜色与对比度，
 * 中央以 GPU 径向渐变、体积光雾和实时音量声波突出麦克风。
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

    var listening = false
        set(value) { field = value; invalidate() }

    var recognizing = false
        set(value) { field = value; invalidate() }

    var target = Target.NONE
        private set

    init {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        // 径向渐变由 GPU 绘制，避免软件模糊层造成持续录音时掉帧。
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        isClickable = false
    }

    fun addLevel(level: Float) {
        System.arraycopy(levels, 1, levels, 0, BARS - 1)
        levels[BARS - 1] = level.coerceIn(0f, 1f)
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
        val active = holding || listening || recognizing
        val level = if (active) levels.average().toFloat().coerceIn(0f, 1f) else 0.12f
        val cx = width / 2f
        val cy = height * 0.53f
        val energy = 0.78f + level * 0.28f
        val amplitude = minOf(height * 0.14f, ink.dp(48f)) * energy

        // 参考图的核心视觉：低振幅、多层、连续的青蓝—紫—洋红霓虹光波。
        val gradient = LinearGradient(
            0f, 0f, width.toFloat(), 0f,
            intArrayOf(
                0xFF00DDF5.toInt(), 0xFF079EFF.toInt(), 0xFF315DFF.toInt(),
                0xFF7652FF.toInt(), 0xFFB744FF.toInt(), 0xFFFF55D5.toInt()
            ),
            floatArrayOf(0f, 0.23f, 0.44f, 0.65f, 0.84f, 1f),
            Shader.TileMode.CLAMP
        )
        val wavePaint = paint
        wavePaint.shader = gradient
        wavePaint.colorFilter = null
        wavePaint.style = Paint.Style.STROKE
        wavePaint.strokeCap = Paint.Cap.ROUND
        wavePaint.strokeJoin = Paint.Join.ROUND

        val main = android.graphics.Path()
        val upper = android.graphics.Path()
        val lower = android.graphics.Path()
        val fine = android.graphics.Path()
        val steps = 260
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val x = t * width
            val envelope = 0.22f + 0.78f *
                kotlin.math.abs(sin((t * 2.0f * PI + phase * 0.10f).toFloat()))
            val carrier = sin((t * 6.0f * PI + phase * 0.42f).toFloat())
            val detail = sin((t * 12.0f * PI - phase * 0.27f).toFloat()) * 0.16f
            val yMain = cy + (carrier * 0.82f + detail) * envelope * amplitude
            val yUpper = cy + sin((t * 6.0f * PI + phase * 0.42f + 0.42f).toFloat()) *
                envelope * amplitude * 0.58f
            val yLower = cy + sin((t * 6.0f * PI + phase * 0.42f - 0.48f).toFloat()) *
                envelope * amplitude * 0.34f
            val yFine = cy + sin((t * 8.0f * PI - phase * 0.22f).toFloat()) *
                envelope * amplitude * 0.20f
            if (i == 0) {
                main.moveTo(x, yMain)
                upper.moveTo(x, yUpper)
                lower.moveTo(x, yLower)
                fine.moveTo(x, yFine)
            } else {
                main.lineTo(x, yMain)
                upper.lineTo(x, yUpper)
                lower.lineTo(x, yLower)
                fine.lineTo(x, yFine)
            }
        }

        // 柔和外发光、半透明彩带和清晰的细亮核心线，避免上一版的粗重波峰。
        wavePaint.strokeWidth = ink.dp(19f)
        wavePaint.alpha = 18
        canvas.drawPath(main, wavePaint)
        wavePaint.strokeWidth = ink.dp(12f)
        wavePaint.alpha = 28
        canvas.drawPath(upper, wavePaint)
        wavePaint.strokeWidth = ink.dp(6f)
        wavePaint.alpha = 58
        canvas.drawPath(main, wavePaint)
        wavePaint.strokeWidth = ink.dp(3.2f)
        wavePaint.alpha = 118
        canvas.drawPath(upper, wavePaint)
        canvas.drawPath(lower, wavePaint)
        wavePaint.strokeWidth = ink.dp(2.0f)
        wavePaint.alpha = 235
        canvas.drawPath(main, wavePaint)
        wavePaint.strokeWidth = ink.dp(1.1f)
        wavePaint.alpha = 180
        canvas.drawPath(fine, wavePaint)

        // 中轴细光线贯穿全宽，亮度低于波形核心线。
        wavePaint.strokeWidth = ink.dp(0.9f)
        wavePaint.alpha = 105
        canvas.drawLine(0f, cy, width.toFloat(), cy, wavePaint)
        wavePaint.shader = null
        wavePaint.alpha = 255
        wavePaint.style = Paint.Style.FILL
        wavePaint.colorFilter = null

        // 麦克风控制区保持原有触摸命中区域，但不再绘制白色圆盘遮挡光波。
        val actionY = height * 0.86f
        ink.text(canvas, "撤回", 13f,
            if (target == Target.CANCEL) palette.brand else palette.gloss, width * 0.17f, actionY,
            bold = target == Target.CANCEL)
        ink.text(canvas, actionLabel, 13f,
            if (target == Target.ACTION) palette.brand else palette.gloss, width * 0.83f, actionY,
            bold = target == Target.ACTION)

        if (active) {
            phase = (phase + 0.006f + level * 0.004f) % (2f * PI.toFloat())
            postInvalidateDelayed(16L)
        }
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
