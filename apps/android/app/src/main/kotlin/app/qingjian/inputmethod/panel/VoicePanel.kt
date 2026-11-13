package app.qingjian.inputmethod.panel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import app.qingjian.inputmethod.keyboard.Haptics
import app.qingjian.inputmethod.panel.base.Ink
import app.qingjian.inputmethod.panel.base.PanelIcons.Icon
import app.qingjian.inputmethod.theme.Palette
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Dreamy violet voice surface.
 *
 * Recognition and gesture handling remain owned by VoiceInput. This view is only a renderer:
 * three slow holographic halos react to the rolling microphone level without changing the
 * keyboard, recognition lifecycle, or the existing cancel/finish touch targets.
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
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val location = IntArray(2)

    var holding = false
        set(value) { field = value; invalidate() }

    var recognizing = false
        set(value) { field = value; invalidate() }

    var target = Target.NONE
        private set

    private val levels = FloatArray(BARS)
    private var attached = false
    private var smoothedLevel = 0f
    private var ambientShader: Shader? = null
    private var bloomShader: Shader? = null
    private var coreShader: Shader? = null
    private var ringShaders: Array<Shader?> = arrayOfNulls(3)

    init {
        // Keep the existing keyboard/theme surface intact; only the voice artwork is violet.
        setBackgroundColor(palette.board)
        isFocusable = true
    }

    fun addLevel(level: Float) {
        val normalized = level.coerceIn(0f, 1f)
        System.arraycopy(levels, 1, levels, 0, BARS - 1)
        levels[BARS - 1] = normalized
        smoothedLevel += (normalized - smoothedLevel) * 0.42f
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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        postOnAnimationDelayed(frameTick, FRAME_MS)
    }

    override fun onDetachedFromWindow() {
        attached = false
        removeCallbacks(frameTick)
        super.onDetachedFromWindow()
    }

    private val frameTick = Runnable {
        if (attached) {
            invalidate()
            postOnAnimationDelayed(frameTick, FRAME_MS)
        }
    }


    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val fw = w.toFloat()
        val fh = h.toFloat()
        val cx = fw * 0.5f
        val cy = fh * 0.36f
        ambientShader = RadialGradient(
            cx, cy, fw * 0.78f,
            intArrayOf(withAlpha(VIOLET, 0x38), withAlpha(MAGENTA, 0x1C),
                withAlpha(palette.board, 0)),
            floatArrayOf(0f, 0.56f, 1f), Shader.TileMode.CLAMP
        )
        val base = ink.dp(39f)
        bloomShader = RadialGradient(
            cx, fh * 0.34f, base * 3.35f,
            intArrayOf(withAlpha(PINK, 0x58), withAlpha(VIOLET, 0x32),
                withAlpha(PURPLE, 0x12), withAlpha(palette.board, 0)),
            floatArrayOf(0f, 0.38f, 0.72f, 1f), Shader.TileMode.CLAMP
        )
        coreShader = RadialGradient(
            cx - base * 0.28f, fh * 0.34f - base * 0.38f, base * 1.75f,
            intArrayOf(0xFFFFF0FF.toInt(), PINK, VIOLET, PURPLE),
            floatArrayOf(0f, 0.34f, 0.72f, 1f), Shader.TileMode.CLAMP
        )
        ringShaders = Array(3) { i ->
            val maxRadius = base * (1.30f + i * 0.43f) + ink.dp(16f)
            LinearGradient(
                cx - maxRadius, fh * 0.34f - maxRadius,
                cx + maxRadius, fh * 0.34f + maxRadius,
                intArrayOf(LAVENDER, PINK, VIOLET),
                null, Shader.TileMode.CLAMP
            )
        }
    }

    private val radius get() = width * 1.0f
    private val arcTop get() = height * 0.5f
    private val centreY get() = arcTop + radius
    private val thickness get() = height * 0.18f

    private fun targetAt(x: Float, y: Float): Target {
        val distance = hypot(x - width / 2f, y - centreY)
        if (distance < radius - thickness / 2 - ink.dp(24f) ||
            distance > radius + thickness / 2 + ink.dp(40f)) return Target.NONE
        val angle = Math.toDegrees(kotlin.math.atan2(
            (x - width / 2f).toDouble(), (centreY - y).toDouble()
        )).toFloat()
        return when {
            angle <= -PAD_FROM + 2 && angle >= -PAD_TO - 20 -> Target.CANCEL
            angle >= PAD_FROM - 2 && angle <= PAD_TO + 20 -> Target.ACTION
            else -> Target.NONE
        }
    }

    private fun hint() = when {
        recognizing -> "正在识别…"
        !holding -> "轻触话筒开始说话"
        target == Target.CANCEL -> "松手撤回"
        target == Target.ACTION -> "松手$actionLabel"
        else -> "正在聆听 · 松手结束"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val now = SystemClock.uptimeMillis()
        val phase = now / 7200f * (Math.PI * 2.0).toFloat()
        val audio = if (recognizing) 0f else smoothedLevel
        drawAmbient(canvas, w, h, now)
        drawGestureArcs(canvas)
        drawHolographicMicrophone(canvas, w, h, phase, audio, now)
        ink.text(canvas, hint(), 13f, palette.gloss, w / 2f, h * 0.70f, bold = true)

        val buttonY = h * 0.84f
        val buttonRadius = ink.dp(if (holding) 22f else 27f)
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = withAlpha(palette.surface, 0xF0)
        canvas.drawCircle(w / 2f, buttonY, buttonRadius + ink.dp(7f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ink.dp(1.4f)
        paint.color = withAlpha(VIOLET, 0xC0)
        canvas.drawCircle(w / 2f, buttonY, buttonRadius + ink.dp(7f), paint)
        paint.style = Paint.Style.FILL
        if (!holding && !recognizing) {
            ink.icon(canvas, Icon.MIC, w / 2f, buttonY, 24f, LAVENDER)
        } else if (recognizing) {
            drawRecognizingMark(canvas, w / 2f, buttonY)
        } else {
            paint.color = LAVENDER
            canvas.drawRoundRect(
                w / 2f - ink.dp(7f), buttonY - ink.dp(7f),
                w / 2f + ink.dp(7f), buttonY + ink.dp(7f),
                ink.dp(3f), ink.dp(3f), paint
            )
        }

        val rowY = h - ink.dp(22f)
        drawBottomAction(canvas, ink.dp(42f), rowY, "收起", Icon.HIDE)
        drawBottomAction(canvas, w - ink.dp(42f), rowY, "键盘", Icon.KEYBOARD)
    }

    private fun drawAmbient(canvas: Canvas, w: Float, h: Float, now: Long) {
        glow.shader = ambientShader
        canvas.drawRect(0f, 0f, w, h, glow)
        glow.shader = null

        // Sparse, slow-drifting dust; fixed seeds avoid flicker between frames.
        for (i in 0 until 22) {
            val drift = (now / (5200f + i * 110f)) % 1f
            val x = w * (0.08f + ((i * 43) % 84) / 100f) +
                sin(drift * 6.283f + i) * ink.dp(3f)
            val y = h * (0.10f + ((i * 29) % 48) / 100f) - drift * ink.dp(9f)
            val twinkle = 0.35f + 0.65f * abs(sin(i * 1.37f + now / 1700f))
            paint.color = withAlpha(if (i % 3 == 0) PINK else LAVENDER,
                (105 * twinkle).toInt())
            canvas.drawCircle(x, y, ink.dp(if (i % 4 == 0) 1.8f else 1.1f), paint)
        }
    }

    private fun drawGestureArcs(canvas: Canvas) {
        val cx = width / 2f
        rect.set(cx - radius, centreY - radius, cx + radius, centreY + radius)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ink.dp(7f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = if (target == Target.CANCEL) withAlpha(PINK, 0xD0)
            else withAlpha(LAVENDER, 0x30)
        canvas.drawArc(rect, -90f - PAD_TO, PAD_TO - PAD_FROM, false, paint)
        paint.color = if (target == Target.ACTION) withAlpha(LAVENDER, 0xD0)
            else withAlpha(LAVENDER, 0x30)
        canvas.drawArc(rect, -90f + PAD_FROM, PAD_TO - PAD_FROM, false, paint)
        paint.style = Paint.Style.FILL
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawHolographicMicrophone(
        canvas: Canvas, w: Float, h: Float, phase: Float, audio: Float, now: Long
    ) {
        val cx = w / 2f
        val cy = h * 0.34f
        val base = ink.dp(39f)
        val breath = (sin(phase) + 1f) * 0.5f
        val response = (audio * 0.9f).coerceIn(0f, 0.9f)
        val pulse = breath * 0.16f + response
        val rotation = now / 11500f * 360f

        // Soft volumetric bloom behind the three independent halo layers.
        glow.shader = bloomShader
        canvas.drawCircle(cx, cy, base * 3.35f, glow)
        glow.shader = null

        // Three concentric rings move at different speeds and respond gently to the mic level.
        for (i in 0 until 3) {
            val ringPhase = phase * (if (i == 1) -0.72f else 0.52f + i * 0.13f) + i * 1.9f
            val ringBreath = (sin(ringPhase) + 1f) * 0.5f
            val ringRadius = base * (1.30f + i * 0.43f) + ink.dp(5f) * ringBreath +
                ink.dp(11f) * response
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = ink.dp(if (i == 0) 2.4f else 1.6f)
            paint.shader = ringShaders[i]
            canvas.drawCircle(cx, cy, ringRadius, paint)
            paint.shader = null
            paint.style = Paint.Style.FILL

            // A short luminous arc gives the ring a holographic, rotating highlight.
            rect.set(cx - ringRadius, cy - ringRadius, cx + ringRadius, cy + ringRadius)
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = ink.dp(3.2f - i * 0.45f)
            paint.color = withAlpha(if (i == 1) PINK else LAVENDER,
                (105 + ringBreath * 95).toInt())
            canvas.drawArc(rect, rotation * (if (i == 1) -1f else 1f) + i * 83f,
                52f + response * 38f, false, paint)
            paint.style = Paint.Style.FILL
            paint.strokeCap = Paint.Cap.BUTT
        }

        // A delicate dotted orbit, rather than the old horizontal equalizer bars.
        for (i in 0 until 12) {
            val angle = phase * 0.36f + i * (Math.PI * 2.0 / 12.0).toFloat()
            val orbit = base * (2.12f + 0.10f * sin(phase + i))
            val x = cx + cos(angle) * orbit
            val y = cy + sin(angle) * orbit * 0.82f
            val alpha = (105 + 120 * (0.5f + 0.5f * sin(angle + phase))).toInt()
            paint.color = withAlpha(if (i % 3 == 0) PINK else LAVENDER, alpha)
            canvas.drawCircle(x, y, ink.dp(if (i % 3 == 0) 2.3f else 1.4f), paint)
        }

        // Glass-like violet core with a bright lavender centre.
        glow.shader = coreShader
        canvas.drawCircle(cx, cy, base * (1f + pulse * 0.06f), glow)
        glow.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ink.dp(1.1f)
        paint.color = withAlpha(0xFFFFFFFF.toInt(), 0xB0)
        canvas.drawCircle(cx, cy, base * (1f + pulse * 0.06f), paint)
        paint.style = Paint.Style.FILL
        ink.icon(canvas, Icon.MIC, cx, cy, 30f, 0xFFFFFFFF.toInt())
    }

    private fun drawRecognizingMark(canvas: Canvas, cx: Float, cy: Float) {
        paint.color = LAVENDER
        for (i in 0 until 3) {
            val x = cx + (i - 1) * ink.dp(7f)
            val bar = ink.dp(8f + i * 2f)
            rect.set(x - ink.dp(2f), cy - bar, x + ink.dp(2f), cy + bar)
            canvas.drawRoundRect(rect, ink.dp(2f), ink.dp(2f), paint)
        }
    }

    private fun drawBottomAction(canvas: Canvas, cx: Float, cy: Float, label: String, icon: Icon) {
        val r = ink.dp(16f)
        paint.color = withAlpha(palette.surface, 0xD8)
        canvas.drawCircle(cx, cy - ink.dp(7f), r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ink.dp(1f)
        paint.color = withAlpha(LAVENDER, 0x60)
        canvas.drawCircle(cx, cy - ink.dp(7f), r, paint)
        paint.style = Paint.Style.FILL
        ink.icon(canvas, icon, cx, cy - ink.dp(7f), 17f, palette.gloss)
        ink.text(canvas, label, 10f, palette.gloss, cx, cy + ink.dp(14f))
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_UP || holding || recognizing) return true
        val x = event.x
        val y = event.y
        val rowY = height - ink.dp(22f)
        val buttonY = height * 0.84f
        val near = ink.dp(32f)
        val tapped = when {
            targetAt(x, y) == Target.CANCEL -> { actions.onCancel(); true }
            targetAt(x, y) == Target.ACTION -> { actions.onFinish(act = true, hide = false); true }
            hypot(x - width / 2f, y - buttonY) < near -> { actions.onFinish(act = false, hide = false); true }
            y > rowY - near && x < ink.dp(80f) -> { actions.onFinish(act = false, hide = true); true }
            y > rowY - near && x > width - ink.dp(80f) -> { actions.onFinish(act = false, hide = false); true }
            else -> false
        }
        if (tapped) Haptics.tap(context)
        return true
    }

    private fun withAlpha(color: Int, alpha: Int) = (color and 0xFFFFFF) or (alpha shl 24)

    private companion object {
        const val BARS = 40
        const val FRAME_MS = 32L
        const val PAD_FROM = 4f
        const val PAD_TO = 28f
        const val VIOLET = 0xFF9B6CFF.toInt()
        const val PURPLE = 0xFF5D28B8.toInt()
        const val LAVENDER = 0xFFE4C7FF.toInt()
        const val PINK = 0xFFFF8FE7.toInt()
        const val MAGENTA = 0xFFD94CFF.toInt()
    }
}
