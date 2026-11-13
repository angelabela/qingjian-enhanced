package app.qingjian.inputmethod.panel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
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
        val cx = width / 2f
        val cy = micCenterY()
        val active = holding || listening || recognizing
        val average = if (active) levels.average().toFloat() else 0.025f
        val pulse = if (active) average.coerceIn(0f, 1f) else 0.025f
        val ambient = ((sin(phase * 1.35f) + 1f) * 0.5f)
        val energy = maxOf(pulse, if (active) ambient * 0.34f else 0.025f)
        val radius = micRadius()

        // 横向霓虹声波：以参考图为目标，青蓝渐变过渡到紫罗兰与洋红。
        // 多层宽线叠加模拟柔光，细线保留清晰的玻璃质感；振幅随实时音量变化。
        val wavePaint = paint
        wavePaint.shader = LinearGradient(
            0f, 0f, width.toFloat(), 0f,
            intArrayOf(
                0xFF00D9F5.toInt(), 0xFF078CFF.toInt(), 0xFF315BFF.toInt(),
                0xFF7652FF.toInt(), 0xFFB83DFF.toInt(), 0xFFFF4BCF.toInt()
            ),
            floatArrayOf(0f, 0.22f, 0.43f, 0.63f, 0.82f, 1f),
            Shader.TileMode.CLAMP
        )
        wavePaint.style = Paint.Style.STROKE
        wavePaint.strokeCap = Paint.Cap.ROUND
        wavePaint.strokeJoin = Paint.Join.ROUND
        val waveAmplitude = maxOf(height * 0.30f, ink.dp(34f)) * (0.72f + energy * 0.48f)
        val wavePath = android.graphics.Path()
        val secondaryPath = android.graphics.Path()
        val tertiaryPath = android.graphics.Path()
        val steps = 180
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val x = t * width
            val envelope = 0.18f + 0.82f * kotlin.math.abs(sin((t * 3.0f * PI + phase * 0.16f).toFloat()))
            val base = sin((t * 6.0f * PI + phase * 0.72f).toFloat()) * envelope
            val detail = sin((t * 12.0f * PI - phase * 0.48f).toFloat()) * envelope * 0.34f
            val broad = sin((t * 4.0f * PI + phase * 0.42f).toFloat()) * envelope * 0.76f
            val y1 = cy + (base + detail) * waveAmplitude
            val y2 = cy + broad * waveAmplitude * 0.72f
            val y3 = cy - (base * 0.52f + detail * 0.24f) * waveAmplitude
            if (i == 0) {
                wavePath.moveTo(x, y1)
                secondaryPath.moveTo(x, y2)
                tertiaryPath.moveTo(x, y3)
            } else {
                wavePath.lineTo(x, y1)
                secondaryPath.lineTo(x, y2)
                tertiaryPath.lineTo(x, y3)
            }
        }

        // 宽幅低透明辉光层。
        wavePaint.strokeWidth = ink.dp(24f)
        wavePaint.colorFilter = null
        wavePaint.alpha = 24
        canvas.drawPath(secondaryPath, wavePaint)
        wavePaint.strokeWidth = ink.dp(15f)
        wavePaint.alpha = 42
        canvas.drawPath(wavePath, wavePaint)
        // 中层彩色光带。
        wavePaint.strokeWidth = ink.dp(7f)
        wavePaint.alpha = 105
        canvas.drawPath(secondaryPath, wavePaint)
        wavePaint.strokeWidth = ink.dp(5f)
        wavePaint.alpha = 150
        canvas.drawPath(wavePath, wavePaint)
        // 高亮核心线与细节波形。
        wavePaint.strokeWidth = ink.dp(2.2f)
        wavePaint.alpha = 245
        canvas.drawPath(wavePath, wavePaint)
        wavePaint.strokeWidth = ink.dp(1.35f)
        wavePaint.alpha = 190
        canvas.drawPath(tertiaryPath, wavePaint)
        // 中轴光线，形成参考图中贯穿全屏的亮线。
        wavePaint.strokeWidth = ink.dp(1.1f)
        wavePaint.alpha = 215
        canvas.drawLine(0f, cy, width.toFloat(), cy, wavePaint)
        wavePaint.alpha = 255
        wavePaint.shader = null
        wavePaint.colorFilter = null
        wavePaint.style = Paint.Style.FILL

        // 少量高亮粒子沿波形缓慢漂移，避免视觉过于静止。
        for (i in 0 until 16) {
            val t = (i / 15f)
            val x = t * width
            val y = cy + sin((t * 6.0f * PI + phase * 0.72f).toFloat()) *
                waveAmplitude * (0.18f + 0.82f * kotlin.math.abs(sin((t * 3.0f * PI).toFloat())))
            wavePaint.color = withAlpha(
                if (t < 0.5f) 0xFF29CFFF.toInt() else 0xFFE45BFF.toInt(),
                (80 + energy * 150).toInt().coerceIn(80, 230)
            )
            canvas.drawCircle(x, y, ink.dp(if (i % 3 == 0) 1.6f else 0.9f), wavePaint)
        }

        // 中央麦克风本体保持清晰、明亮，与柔化后的键盘形成主次层级。
        paint.style = Paint.Style.FILL
        paint.color = withAlpha(palette.surface, 0xF4)
        canvas.drawCircle(cx, cy, radius * (1.02f + ambient * 0.035f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ink.dp(1.7f)
        paint.color = withAlpha(palette.brand, 0xF4)
        canvas.drawCircle(cx, cy, radius * (1.02f + ambient * 0.035f), paint)

        // 麦克风本体跟随声波做轻微缩放与渐隐渐现，保持图标清楚但有呼吸感。
        val micAlpha = (184 + ambient * 71f).toInt().coerceIn(184, 255)
        val micScale = 0.965f + ambient * 0.075f
        val save = canvas.save()
        canvas.scale(micScale, micScale, cx, cy)
        val layer = canvas.saveLayerAlpha(
            cx - ink.dp(25f), cy - ink.dp(28f),
            cx + ink.dp(25f), cy + ink.dp(28f), micAlpha
        )
        ink.icon(canvas, Icon.MIC, cx, cy, 29f, palette.brand)
        canvas.restoreToCount(layer)
        canvas.restoreToCount(save)

        val actionY = height * 0.86f
        ink.text(canvas, "撤回", 13f,
            if (target == Target.CANCEL) palette.brand else palette.gloss, width * 0.17f, actionY,
            bold = target == Target.CANCEL)
        ink.text(canvas, actionLabel, 13f,
            if (target == Target.ACTION) palette.brand else palette.gloss, width * 0.83f, actionY,
            bold = target == Target.ACTION)

        if (active) {
            phase = (phase + 0.009f + pulse * 0.010f) % (2f * PI.toFloat())
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
