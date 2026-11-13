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
import kotlin.math.exp
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
    private val micHighlightRect = RectF()
    private val micStandRect = RectF()
    private val orbitRects = Array(3) { RectF() }
    private val orbitTailRects = Array(3) { RectF() }
    private val location = IntArray(2)
    private val levels = FloatArray(BARS)
    private val ringColors = intArrayOf(
        0xFF36E9FF.toInt(), 0xFF398EFF.toInt(), 0xFF735CFF.toInt(),
        0xFFC05CFF.toInt(), 0xFF36E9FF.toInt()
    )
    private var backgroundShader: Shader? = null
    private var ambientShader: Shader? = null
    private var ringShader: Shader? = null
    private var orbitShader: Shader? = null
    private var sphereShader: Shader? = null
    private var edgeShader: Shader? = null
    private var phase = 0f
    private var lastFrameNanos = 0L
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


    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val fw = w.toFloat()
        val fh = h.toFloat()
        val cx = fw / 2f
        val cy = fh * 0.53f
        val sphereR = minOf(ink.dp(82f), minOf(fw, fh) * 0.205f) * 1.2f

        backgroundShader = LinearGradient(
            0f, 0f, fw, fh,
            intArrayOf(0xD90A1534.toInt(), 0xD90D1233.toInt(), 0xDD21113F.toInt()),
            null, Shader.TileMode.CLAMP
        )
        ambientShader = RadialGradient(
            cx, cy, sphereR * 2.5f,
            intArrayOf(0x665C65FF, 0x332E4CCB, 0x00201850),
            null, Shader.TileMode.CLAMP
        )
        ringShader = android.graphics.SweepGradient(
            cx, cy, ringColors, floatArrayOf(0f, 0.25f, 0.53f, 0.78f, 1f)
        )
        orbitShader = android.graphics.SweepGradient(
            cx, cy,
            intArrayOf(0x002FE8FF, 0xAA42CFFF.toInt(), 0xDD8B70FF.toInt(),
                0x8845CFFF.toInt(), 0x002FE8FF),
            floatArrayOf(0f, 0.22f, 0.48f, 0.76f, 1f)
        )
        sphereShader = RadialGradient(
            cx - sphereR * 0.34f, cy - sphereR * 0.40f, sphereR * 1.65f,
            intArrayOf(0xFFB9FFFF.toInt(), 0xFF39DFFF.toInt(), 0xFF258BEF.toInt(),
                0xFF5549D9.toInt(), 0xFF29144F.toInt()),
            floatArrayOf(0f, 0.24f, 0.52f, 0.79f, 1f), Shader.TileMode.CLAMP
        )
        edgeShader = RadialGradient(
            cx, cy, sphereR,
            intArrayOf(0x00FFFFFF, 0x22BFFFFF, 0xAA9CEBFF.toInt()),
            floatArrayOf(0.78f, 0.94f, 1f), Shader.TileMode.CLAMP
        )
    }

    private fun micCenterY() = height * 0.53f
    private fun micRadius() = ink.dp(42f)

    private var dimProgress = 0f
    private var ringEnergy = 0f
    private var sphereScale = 1f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val active = holding || listening || recognizing
        // Drive animation from frame time, not draw count: dropped frames must not
        // change animation speed or create uneven phase steps on different refresh rates.
        val frameNanos = System.nanoTime()
        val deltaSeconds = if (lastFrameNanos == 0L) {
            1f / 60f
        } else {
            ((frameNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
        }
        lastFrameNanos = frameNanos
        phase = (phase + deltaSeconds * if (active) 1.56f else 0.72f) % (2f * PI.toFloat())
        val weightedLevel = levels.indices.fold(0f) { total, index ->
            val weight = (index + 1).toFloat() / BARS
            total + levels[index] * weight
        }
        val recentLevel = weightedLevel / (BARS * 0.505f)
        val inputLevel = if (active) recentLevel.coerceIn(0f, 1f) else 0f
        val ringSmoothing = 1f - exp(-(if (active) 16.5f else 4.7f) * deltaSeconds)
        ringEnergy += (inputLevel - ringEnergy) * ringSmoothing
        val targetDim = if (active) 0.30f else 0.12f
        val dimSmoothing = 1f - exp(-6.3f * deltaSeconds)
        dimProgress += (targetDim - dimProgress) * dimSmoothing

        val cx = width / 2f
        val cy = micCenterY()
        val minSide = minOf(width, height).toFloat()
        val radius = minOf(ink.dp(82f), minSide * 0.205f)
        val breath = 0.5f + 0.5f * sin(phase * 0.42f)
        val pulse = 1f + breath * 0.025f + ringEnergy * 0.115f
        val sphereSmoothing = 1f - exp(-10.5f * deltaSeconds)
        sphereScale += ((1f + ringEnergy * 0.10f) - sphereScale) * sphereSmoothing
        val sphereR = radius * pulse * sphereScale

        // 深蓝至暗紫的宇宙底色，键盘轮廓仍隐约可见。
        paint.shader = backgroundShader
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null

        // 星点位置固定，亮度缓慢闪烁，避免随机跳动。
        for (i in 0 until 34) {
            val sx = ((i * 73 + 29) % 997) / 997f * width
            val sy = ((i * 137 + 61) % 991) / 991f * height
            val twinkle = 0.35f + 0.65f * (0.5f + 0.5f *
                sin(phase * 0.32f + i * 1.71f))
            paint.color = if (i % 4 == 0) 0xFF9ADFFF.toInt() else 0xFFB9B9FF.toInt()
            paint.alpha = (35 + twinkle * 105f).toInt()
            val starR = ink.dp(if (i % 7 == 0) 1.8f else 1.0f) * (1f + ringEnergy * 0.25f)
            canvas.drawCircle(sx, sy, starR, paint)
        }
        paint.alpha = 255

        // 柔和的背景能量雾。
        paint.shader = ambientShader
        canvas.drawCircle(cx, cy, sphereR * 2.5f, paint)
        paint.shader = null

        // 多层同心声波环，随音量改变半径、亮度和脉动幅度。
        for (i in 0..3) {
            val rr = sphereR * (1.18f + i * 0.19f) +
                sin(phase * 0.55f + i * 0.9f) * ink.dp(2.5f + ringEnergy * 5f)
            paint.shader = ringShader
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = ink.dp(if (i == 0) 2.2f else 1.15f)
            paint.alpha = (if (i == 0) 125 else 55) + (ringEnergy * 70f).toInt()
            canvas.drawCircle(cx, cy, rr, paint)
        }
        paint.shader = null

        // 倾斜的土星轨道：宽柔光、主光带和偏移拖尾共同形成空间纵深。
        for (i in 0..2) {
            val orbitW = sphereR * (2.95f + i * 0.36f)
            val orbitH = sphereR * (0.72f + i * 0.11f)
            val rotation = -24f + i * 17f + phase * (if (active) 2.8f else 1.2f) *
                (if (i % 2 == 0) 1f else -0.72f)
            val oval = orbitRects[i]
            oval.set(cx - orbitW / 2f, cy - orbitH / 2f, cx + orbitW / 2f, cy + orbitH / 2f)
            canvas.save()
            canvas.rotate(rotation, cx, cy)
            paint.shader = orbitShader
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = ink.dp(13f + ringEnergy * 3f)
            paint.alpha = 24
            canvas.drawOval(oval, paint)
            paint.strokeWidth = ink.dp(2.1f)
            paint.alpha = (72 + ringEnergy * 75f).toInt()
            canvas.drawOval(oval, paint)
            paint.strokeWidth = ink.dp(1f)
            paint.alpha = 105
            val tail = orbitTailRects[i]
            tail.set(oval.left + orbitW * 0.08f, oval.top, oval.right, oval.bottom)
            canvas.drawArc(tail, 198f, 112f, false, paint)
            canvas.restore()
        }
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.alpha = 255

        // 能量球：左上亮青、右下深紫的三维径向渐变。
        paint.shader = sphereShader
        canvas.drawCircle(cx, cy, sphereR, paint)
        paint.shader = null

        // 球体边缘内发光与顶部高光。
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ink.dp(5f)
        paint.shader = edgeShader
        canvas.drawCircle(cx, cy, sphereR - ink.dp(2f), paint)
        paint.shader = null
        paint.strokeWidth = ink.dp(1.2f)
        paint.color = 0xBFFFFFFF.toInt()
        micHighlightRect.set(cx - sphereR * 0.86f, cy - sphereR * 0.86f,
            cx + sphereR * 0.86f, cy + sphereR * 0.86f)
        canvas.drawArc(micHighlightRect, 205f, 105f, false, paint)
        paint.style = Paint.Style.FILL

        // 纯白极简麦克风：话筒、U 型支架和底座，形状保持稳定不随音量变形。
        paint.shader = null
        paint.color = android.graphics.Color.WHITE
        paint.alpha = 255
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ink.dp(4.2f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        val micTop = cy - sphereR * 0.34f
        val micBottom = cy + sphereR * 0.12f
        canvas.drawRoundRect(
            cx - sphereR * 0.105f, micTop, cx + sphereR * 0.105f, micBottom,
            sphereR * 0.105f, sphereR * 0.105f, paint
        )
        micStandRect.set(cx - sphereR * 0.26f, cy - sphereR * 0.12f,
            cx + sphereR * 0.26f, cy + sphereR * 0.30f)
        canvas.drawArc(micStandRect, 0f, 180f, false, paint)
        canvas.drawLine(cx, cy + sphereR * 0.30f, cx, cy + sphereR * 0.43f, paint)
        canvas.drawLine(cx - sphereR * 0.17f, cy + sphereR * 0.43f,
            cx + sphereR * 0.17f, cy + sphereR * 0.43f, paint)
        paint.style = Paint.Style.FILL
        paint.color = android.graphics.Color.WHITE
        paint.alpha = 255

        val actionY = height * 0.91f
        ink.text(canvas, "撤回", 13f,
            if (target == Target.CANCEL) palette.brand else 0xFFD6D9FF.toInt(), width * 0.17f, actionY,
            bold = target == Target.CANCEL)
        ink.text(canvas, actionLabel, 13f,
            if (target == Target.ACTION) palette.brand else 0xFFD6D9FF.toInt(), width * 0.83f, actionY,
            bold = target == Target.ACTION)

        if (active || dimProgress > 0.122f || kotlin.math.abs(sphereScale - 1f) > 0.002f) {
            postInvalidateOnAnimation()
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
