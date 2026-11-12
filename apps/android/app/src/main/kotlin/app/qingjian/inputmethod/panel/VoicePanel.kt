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

        // 明显降低键盘存在感：比旧版更高的不透明度，并叠加轻微冷灰雾面。
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = android.graphics.Color.argb(88, 104, 116, 132)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.color = android.graphics.Color.argb(18, 12, 20, 32)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        // 体积光：中心亮、边缘柔和衰减，避免廉价的实线圆环感。
        val glowRadius = radius * (3.15f + energy * 1.15f)
        paint.shader = RadialGradient(
            cx, cy, glowRadius,
            intArrayOf(
                withAlpha(palette.brand, (104 + energy * 105).toInt().coerceIn(104, 209)),
                withAlpha(palette.brand, (62 + energy * 54).toInt().coerceIn(62, 116)),
                withAlpha(palette.brand, 0)
            ),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, glowRadius, paint)
        paint.shader = null

        // 两层不同尺度的柔光核心，随真实输入音量呼吸。
        paint.style = Paint.Style.FILL
        paint.color = withAlpha(palette.brand, (28 + energy * 58).toInt().coerceIn(28, 86))
        canvas.drawCircle(cx, cy, radius * (1.8f + energy * 0.48f), paint)
        paint.color = withAlpha(palette.brand, (20 + energy * 34).toInt().coerceIn(20, 54))
        canvas.drawCircle(cx, cy, radius * (2.55f + energy * 0.62f), paint)

        // 柔和扩散的双层呼吸波，不画成生硬的固定圆环。
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ink.dp(1.2f)
        paint.color = withAlpha(palette.brand, (24 + ambient * 36).toInt().coerceIn(24, 60))
        canvas.drawCircle(cx, cy, radius * (1.55f + ambient * 0.42f), paint)
        paint.strokeWidth = ink.dp(0.8f)
        paint.color = withAlpha(palette.brand, (16 + ambient * 24).toInt().coerceIn(16, 40))
        canvas.drawCircle(cx, cy, radius * (2.0f + ((ambient + 0.5f) % 1f) * 0.55f), paint)

        // 环形声波：每根光束按对应时间片的音量改变长度与亮度。
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        for (i in 0 until BARS) {
            val angle = (2.0 * PI * i / BARS + phase).toFloat()
            val sample = if (active) maxOf(levels[i], ambient * 0.12f) else 0.025f
            val wave = ink.dp(5f) + sample * ink.dp(38f)
            val inner = radius + ink.dp(9f)
            val outer = inner + wave
            paint.strokeWidth = ink.dp(if (i % 3 == 0) 2.5f else 1.7f)
            paint.color = withAlpha(palette.brand, (86 + sample * 169).toInt().coerceIn(86, 255))
            canvas.drawLine(
                cx + cos(angle) * inner, cy + sin(angle) * inner,
                cx + cos(angle) * outer, cy + sin(angle) * outer, paint
            )
        }

        // 稀疏微粒沿光晕外围缓慢漂移，营造悬浮空间感；不使用随机数，帧间稳定。
        paint.style = Paint.Style.FILL
        for (i in 0 until 18) {
            val angle = (i * 2.39996f + phase * (if (i % 2 == 0) 0.32f else -0.22f))
            val orbit = radius * (2.05f + 0.42f * sin(i * 1.71f + phase * 0.7f))
            val particleSize = ink.dp(0.8f + (i % 3) * 0.45f)
            paint.color = withAlpha(palette.brand, (58 + energy * 132).toInt().coerceIn(58, 190))
            canvas.drawCircle(
                cx + cos(angle) * orbit,
                cy + sin(angle) * orbit,
                particleSize,
                paint
            )
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
            phase = (phase + 0.024f + pulse * 0.026f) % (2f * PI.toFloat())
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
