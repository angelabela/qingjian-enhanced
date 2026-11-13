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

    private var dimProgress = 0f
    private var ringEnergy = 0f
    private var ringScale = 1f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val active = holding || listening || recognizing
        val targetDim = if (active) 0.20f else 0f
        dimProgress += (targetDim - dimProgress) * 0.16f
        val rawLevel = if (active) (levels.average().toFloat() * 1.8f).coerceIn(0f, 1f) else 0f
        ringEnergy += (rawLevel - ringEnergy) * if (active) 0.22f else 0.10f
        val breath = 0.5f + 0.5f * sin(phase * 0.48f)
        val cx = width / 2f
        val cy = micCenterY()
        val density = resources.displayMetrics.density

        // 聆听时轻柔压暗键盘，停止后用同样平滑的曲线恢复。
        if (dimProgress > 0.002f) {
            paint.shader = null
            paint.style = Paint.Style.FILL
            paint.color = android.graphics.Color.BLACK
            paint.alpha = (dimProgress * 255f).toInt().coerceIn(0, 255)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.alpha = 255
        }

        val pulse = 1f + breath * 0.035f + ringEnergy * 0.12f
        val baseRadius = ink.dp(61f) * pulse * ringScale
        val amplitude = ink.dp(7f + ringEnergy * 15f)
        val ring = android.graphics.Path()
        val secondary = android.graphics.Path()
        val inner = android.graphics.Path()
        val segments = 220
        for (i in 0..segments) {
            val a = i.toFloat() / segments * (2f * PI.toFloat())
            val wave = sin(a * 7f - phase * 0.78f) * 0.55f +
                sin(a * 13f + phase * 0.42f) * 0.25f +
                sin(a * 3f - phase * 0.28f) * 0.20f
            val r = baseRadius + wave * amplitude
            val x = cx + cos(a) * r
            val y = cy + sin(a) * r
            val r2 = baseRadius - ink.dp(8f) + wave * amplitude * 0.48f
            val x2 = cx + cos(a + 0.12f) * r2
            val y2 = cy + sin(a + 0.12f) * r2
            val r3 = baseRadius + ink.dp(8f) + sin(a * 5f + phase * 0.35f) * amplitude * 0.34f
            val x3 = cx + cos(a) * r3
            val y3 = cy + sin(a) * r3
            if (i == 0) {
                ring.moveTo(x, y)
                secondary.moveTo(x2, y2)
                inner.moveTo(x3, y3)
            } else {
                ring.lineTo(x, y)
                secondary.lineTo(x2, y2)
                inner.lineTo(x3, y3)
            }
        }
        ring.close()
        secondary.close()
        inner.close()

        val colors = intArrayOf(
            0xFF00E5FF.toInt(), 0xFF168BFF.toInt(), 0xFF455BFF.toInt(),
            0xFF8B45FF.toInt(), 0xFFE044FF.toInt(), 0xFFFF4FB8.toInt(),
            0xFF00E5FF.toInt()
        )
        val positions = floatArrayOf(0f, 0.17f, 0.34f, 0.52f, 0.70f, 0.86f, 1f)
        paint.shader = android.graphics.SweepGradient(cx, cy, colors, positions)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND

        // 外层宽柔光 + 中层彩色光带 + 内层高亮细线，制造有层次的霓虹光环。
        paint.strokeWidth = ink.dp(18f)
        paint.alpha = (22 + breath * 12f + ringEnergy * 25f).toInt()
        canvas.drawPath(ring, paint)
        paint.strokeWidth = ink.dp(9f)
        paint.alpha = (42 + ringEnergy * 45f).toInt()
        canvas.drawPath(inner, paint)
        paint.strokeWidth = ink.dp(3.8f)
        paint.alpha = 210
        canvas.drawPath(ring, paint)
        paint.strokeWidth = ink.dp(1.5f)
        paint.alpha = 170
        canvas.drawPath(secondary, paint)
        paint.shader = null
        paint.alpha = 255
        paint.style = Paint.Style.FILL

        // 麦克风中心不使用白色大圆盘，让光环和键盘自然融合。
        val micColor = 0xFF72D9FF.toInt()
        paint.color = micColor
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ink.dp(4f)
        paint.strokeCap = Paint.Cap.ROUND
        val micTop = cy - ink.dp(15f)
        val micBottom = cy + ink.dp(5f)
        canvas.drawRoundRect(
            cx - ink.dp(7f), micTop, cx + ink.dp(7f), micBottom,
            ink.dp(7f), ink.dp(7f), paint
        )
        val micArc = RectF(cx - ink.dp(17f), cy - ink.dp(7f), cx + ink.dp(17f), cy + ink.dp(17f))
        canvas.drawArc(micArc, 0f, 180f, false, paint)
        canvas.drawLine(cx, cy + ink.dp(17f), cx, cy + ink.dp(25f), paint)
        canvas.drawLine(cx - ink.dp(8f), cy + ink.dp(25f), cx + ink.dp(8f), cy + ink.dp(25f), paint)
        paint.style = Paint.Style.FILL
        paint.color = android.graphics.Color.WHITE
        paint.alpha = 255

        val actionY = height * 0.86f
        ink.text(canvas, "撤回", 13f,
            if (target == Target.CANCEL) palette.brand else palette.gloss, width * 0.17f, actionY,
            bold = target == Target.CANCEL)
        ink.text(canvas, actionLabel, 13f,
            if (target == Target.ACTION) palette.brand else palette.gloss, width * 0.83f, actionY,
            bold = target == Target.ACTION)

        // 慢速连续呼吸；音量只增加光环的振幅和亮度，不让整个界面剧烈跳动。
        phase = (phase + if (active) 0.035f else 0.020f) % (2f * PI.toFloat())
        val targetScale = if (active) 1.035f else 1f
        ringScale += (targetScale - ringScale) * 0.12f
        if (active || dimProgress > 0.002f || kotlin.math.abs(ringScale - 1f) > 0.002f) {
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
