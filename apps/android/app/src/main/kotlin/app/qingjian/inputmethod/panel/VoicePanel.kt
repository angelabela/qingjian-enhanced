package app.qingjian.inputmethod.panel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
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
 * Modern voice panel. Rendering is isolated from recognition state: this view only draws
 * a restrained animated orb, live waveform and touch targets.
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
    private val orbRect = RectF()
    private val path = Path()
    private val location = IntArray(2)
    private val density = resources.displayMetrics.density

    var holding = false
        set(value) { field = value; invalidate() }

    var recognizing = false
        set(value) { field = value; invalidate() }

    var target = Target.NONE
        private set

    private val levels = FloatArray(BARS)
    private var attached = false

    init {
        setBackgroundColor(palette.board)
        isFocusable = true
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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        attached = false
        removeCallbacks(frameTick)
        super.onDetachedFromWindow()
    }

    private val frameTick = Runnable {
        if (attached && !recognizing) {
            invalidate()
            postInvalidateDelayed(FRAME_MS)
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
        val phase = (now % 2600L) / 2600f * (Math.PI * 2.0).toFloat()
        drawBackdrop(canvas, w, h)
        drawGestureArcs(canvas)
        drawOrb(canvas, w, h, phase)
        drawWave(canvas, h * 0.69f, phase)
        ink.text(canvas, hint(), 13f, palette.gloss, w / 2f, h * 0.77f, bold = true)

        val buttonY = h * 0.88f
        val buttonRadius = ink.dp(if (holding) 22f else 27f)
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = withAlpha(palette.surface, 0xF0)
        canvas.drawCircle(w / 2f, buttonY, buttonRadius + ink.dp(7f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ink.dp(1.4f)
        paint.color = withAlpha(palette.brand, 0xA0)
        canvas.drawCircle(w / 2f, buttonY, buttonRadius + ink.dp(7f), paint)
        paint.style = Paint.Style.FILL
        if (!holding && !recognizing) {
            ink.icon(canvas, Icon.MIC, w / 2f, buttonY, 24f, palette.brand)
        } else if (recognizing) {
            drawRecognizingMark(canvas, w / 2f, buttonY)
        } else {
            paint.color = palette.brand
            canvas.drawRoundRect(
                w / 2f - ink.dp(7f), buttonY - ink.dp(7f),
                w / 2f + ink.dp(7f), buttonY + ink.dp(7f),
                ink.dp(3f), ink.dp(3f), paint
            )
        }

        val rowY = h - ink.dp(22f)
        drawBottomAction(canvas, ink.dp(42f), rowY, "收起", Icon.HIDE)
        drawBottomAction(canvas, w - ink.dp(42f), rowY, "键盘", Icon.KEYBOARD)
        if (attached && !recognizing) postInvalidateDelayed(FRAME_MS)
    }

    private fun drawBackdrop(canvas: Canvas, w: Float, h: Float) {
        glow.shader = RadialGradient(
            w * 0.5f, h * 0.42f, w * 0.72f,
            intArrayOf(withAlpha(palette.brand, if (palette.dark) 0x48 else 0x24),
                withAlpha(palette.accent, 0x12), withAlpha(palette.board, 0)),
            floatArrayOf(0f, 0.58f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, glow)
        glow.shader = null

        // Subtle ambient particles; deterministic positions keep frames stable.
        for (i in 0 until 14) {
            val x = w * (0.12f + ((i * 37) % 76) / 100f)
            val y = h * (0.12f + ((i * 23) % 50) / 100f)
            val twinkle = 0.35f + 0.65f * abs(sin(i * 1.7f + SystemClock.uptimeMillis() / 900f))
            paint.color = withAlpha(if (i % 2 == 0) palette.brand else palette.fresh,
                (75 * twinkle).toInt())
            canvas.drawCircle(x, y, ink.dp(if (i % 3 == 0) 1.8f else 1.1f), paint)
        }
    }

    private fun drawGestureArcs(canvas: Canvas) {
        val cx = width / 2f
        rect.set(cx - radius, centreY - radius, cx + radius, centreY + radius)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ink.dp(7f)
        paint.strokeCap = Paint.Cap.ROUND
        val leftActive = target == Target.CANCEL
        val rightActive = target == Target.ACTION
        paint.color = if (leftActive) withAlpha(palette.brand, 0xB0)
            else withAlpha(palette.functionalKey, 0x35)
        canvas.drawArc(rect, -90f - PAD_TO, PAD_TO - PAD_FROM, false, paint)
        paint.color = if (rightActive) withAlpha(palette.brand, 0xB0)
            else withAlpha(palette.functionalKey, 0x35)
        canvas.drawArc(rect, -90f + PAD_FROM, PAD_TO - PAD_FROM, false, paint)
        paint.style = Paint.Style.FILL
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawOrb(canvas: Canvas, w: Float, h: Float, phase: Float) {
        val cx = w / 2f
        val cy = h * 0.37f
        val base = ink.dp(42f)
        val pulse = if (recognizing) 0f else (sin(phase) + 1f) * 0.5f
        val outer = base + ink.dp(10f) + ink.dp(5f) * pulse

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ink.dp(1.2f)
        paint.color = withAlpha(palette.brand, (42 + pulse * 45).toInt())
        canvas.drawCircle(cx, cy, outer + ink.dp(13f), paint)
        paint.strokeWidth = ink.dp(2f)
        paint.color = withAlpha(palette.brand, (70 + pulse * 65).toInt())
        canvas.drawCircle(cx, cy, outer, paint)
        paint.style = Paint.Style.FILL

        // Orb glow and glass-like core.
        glow.shader = RadialGradient(
            cx, cy, outer + ink.dp(20f),
            intArrayOf(withAlpha(palette.brand, 0x66), withAlpha(palette.accent, 0x28),
                withAlpha(palette.board, 0)),
            floatArrayOf(0f, 0.58f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, outer + ink.dp(20f), glow)
        glow.shader = null

        orbRect.set(cx - base, cy - base, cx + base, cy + base)
        glow.shader = RadialGradient(
            cx - base * 0.28f, cy - base * 0.36f, base * 1.75f,
            intArrayOf(palette.fresh, palette.brand, palette.accent),
            floatArrayOf(0f, 0.58f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, base, glow)
        glow.shader = null

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ink.dp(1f)
        paint.color = withAlpha(palette.gloss, 0x65)
        canvas.drawCircle(cx, cy, base, paint)
        paint.style = Paint.Style.FILL
        ink.icon(canvas, Icon.MIC, cx, cy, 31f, palette.onBrand)

        // A few moving light points orbit the orb, without heavy bitmap effects.
        for (i in 0 until 3) {
            val a = phase + i * (Math.PI * 2.0 / 3.0).toFloat()
            val orbit = base + ink.dp(15f)
            val px = cx + cos(a) * orbit
            val py = cy + sin(a) * orbit * 0.48f
            paint.color = withAlpha(if (i % 2 == 0) palette.fresh else palette.gloss, 0xD0)
            canvas.drawCircle(px, py, ink.dp(2.2f), paint)
        }
    }

    private fun drawWave(canvas: Canvas, cy: Float, phase: Float) {
        val span = width * 0.72f
        val left = (width - span) / 2f
        val step = span / BARS
        val barWidth = ink.dp(2.5f)
        val maxHalf = ink.dp(14f)
        for (i in 0 until BARS) {
            val edge = 1f - abs(i - (BARS - 1) / 2f) / BARS
            val live = if (recognizing) 0f else levels[i]
            val idle = if (recognizing) 0.08f else 0.12f + 0.08f * abs(sin(phase + i * 0.22f))
            val level = (live * 0.86f + idle).coerceIn(0.04f, 1f)
            val half = barWidth / 2f + (maxHalf - barWidth / 2f) * level * edge
            val x = left + step * (i + 0.5f)
            paint.shader = LinearGradient(
                x, cy - half, x, cy + half,
                palette.fresh, palette.accent, Shader.TileMode.CLAMP
            )
            rect.set(x - barWidth / 2f, cy - half, x + barWidth / 2f, cy + half)
            canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, paint)
        }
        paint.shader = null
    }

    private fun drawRecognizingMark(canvas: Canvas, cx: Float, cy: Float) {
        paint.color = palette.brand
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
        paint.color = withAlpha(palette.gloss, 0x38)
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
        val buttonY = height * 0.88f
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
    }
}
