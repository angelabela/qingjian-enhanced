package app.qingjian.inputmethod.panel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import app.qingjian.inputmethod.keyboard.Haptics
import app.qingjian.inputmethod.panel.base.Ink
import app.qingjian.inputmethod.theme.Palette
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/** 语音面板：半透明毛玻璃键盘底图 + 随麦克风音量变化的实时波形。 */
@SuppressLint("ViewConstructor")
class VoicePanel(
    context: Context,
    private val palette: Palette,
    private val actionLabel: String,
    private val actions: Actions,
    private val blurredKeyboard: Bitmap? = null,
) : FrameLayout(context) {

    interface Actions {
        fun onCancel()
        fun onFinish(act: Boolean, hide: Boolean)
    }

    enum class Target { NONE, CANCEL, ACTION }

    private val ink = Ink(palette, resources.displayMetrics.density)
    private val backdrop = ImageView(context)
    private val scrim = View(context)
    private val overlay = OverlayView(context)
    private val levels = FloatArray(SAMPLE_COUNT)
    private var sampleCursor = 0
    private var touchTarget = Target.NONE
    var target = Target.NONE
        private set

    var holding = false
        set(value) {
            field = value
            overlay.invalidate()
        }

    var listening = false
        set(value) {
            field = value
            overlay.invalidate()
        }

    var recognizing = false
        set(value) {
            field = value
            overlay.invalidate()
        }

    init {
        clipChildren = true
        clipToPadding = true

        backdrop.scaleType = ImageView.ScaleType.FIT_XY
        blurredKeyboard?.let(backdrop::setImageBitmap)
        if (Build.VERSION.SDK_INT >= 31 && blurredKeyboard != null) {
            val radius = 22f * resources.displayMetrics.density
            backdrop.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
        }
        addView(backdrop, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // 深蓝黑色染色层保留键盘轮廓与键位明暗，但压低对比度，形成毛玻璃观感。
        scrim.setBackgroundColor(0xA90A1024.toInt())
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun addLevel(level: Float) {
        synchronized(levels) {
            levels[sampleCursor] = level.coerceIn(0f, 1f)
            sampleCursor = (sampleCursor + 1) % SAMPLE_COUNT
        }
        overlay.postInvalidateOnAnimation()
    }

    fun track(screenX: Float, screenY: Float) {
        val location = IntArray(2)
        getLocationOnScreen(location)
        val next = targetAt(screenX - location[0], screenY - location[1])
        if (next == target) return
        target = next
        if (next != Target.NONE) Haptics.tap(context)
        overlay.invalidate()
    }

    private fun targetAt(x: Float, y: Float): Target {
        if (y < height * 0.77f || y > height * 0.96f) return Target.NONE
        return when {
            x < width * 0.34f -> Target.CANCEL
            x > width * 0.66f -> Target.ACTION
            else -> Target.NONE
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        val density = resources.displayMetrics.density
        val micRadius = 34f * density
        val micHit = kotlin.math.hypot(x - width / 2f, y - height * 0.475f) <= micRadius + 18f * density
        val action = targetAt(x, y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchTarget = when {
                    micHit -> Target.NONE
                    action != Target.NONE -> action
                    else -> return true
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> return true
            MotionEvent.ACTION_UP -> {
                when {
                    touchTarget == Target.CANCEL || action == Target.CANCEL -> actions.onCancel()
                    touchTarget == Target.ACTION || action == Target.ACTION -> actions.onFinish(act = true, hide = false)
                    touchTarget == Target.NONE && micHit -> actions.onFinish(act = false, hide = false)
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
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (Build.VERSION.SDK_INT >= 31) backdrop.setRenderEffect(null)
        backdrop.setImageDrawable(null)
        blurredKeyboard?.takeIf { !it.isRecycled }?.recycle()
    }

    private inner class OverlayView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private val micRect = RectF()
        private val d = resources.displayMetrics.density
        private val samples = FloatArray(SAMPLE_COUNT)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width <= 0 || height <= 0) return
            val cx = width * 0.5f
            val cy = height * 0.475f
            synchronized(levels) { for (i in samples.indices) samples[i] = levels[(sampleCursor + i) % SAMPLE_COUNT] }

            // 两条柔和的声波轨迹；波峰直接来自实时音量采样，不再绘制球体或圆形扩散环。
            val now = System.nanoTime() / 1_000_000_000.0
            val halfWidth = width * 0.43f
            for (lane in 0..1) {
                val baseY = cy + (lane - 0.5f) * 30f * d
                path.reset()
                for (i in 0 until SAMPLE_COUNT) {
                    val x = cx - halfWidth + (2f * halfWidth * i / (SAMPLE_COUNT - 1))
                    val sample = samples[i]
                    val wave = (sin(now * 2.1 + i * 0.31 + lane * 0.9) * 0.16f).toFloat()
                    val amplitude = (sample * 58f * d + abs(wave) * 4f * d).coerceAtLeast(2f * d)
                    val y = baseY + (if (i % 2 == 0) -1f else 1f) * amplitude
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
                paint.strokeWidth = if (lane == 0) 2.4f * d else 1.35f * d
                paint.shader = LinearGradient(
                    cx - halfWidth, baseY, cx + halfWidth, baseY,
                    if (lane == 0) intArrayOf(0x0037DFFF, 0xE85FE8FF.toInt(), 0xD99D72FF.toInt(), 0x0037DFFF)
                    else intArrayOf(0x002F8FFF, 0xB84F8DFF.toInt(), 0xB66E75FF.toInt(), 0x002F8FFF),
                    null, Shader.TileMode.CLAMP,
                )
                canvas.drawPath(path, paint)
            }
            paint.shader = null

            // 中央麦克风图标保持清晰，不显示识别文字或「未检测到声音」提示。
            val r = 12f * d
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.8f * d
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = 0xFFF1F7FF.toInt()
            canvas.drawRoundRect(cx - r * 0.34f, cy - r * 0.78f, cx + r * 0.34f, cy + r * 0.18f, r * 0.34f, r * 0.34f, paint)
            micRect.set(cx - r * 0.68f, cy - r * 0.28f, cx + r * 0.68f, cy + r * 0.62f)
            canvas.drawArc(micRect, 0f, 180f, false, paint)
            canvas.drawLine(cx, cy + r * 0.62f, cx, cy + r * 0.98f, paint)
            canvas.drawLine(cx - r * 0.38f, cy + r * 0.98f, cx + r * 0.38f, cy + r * 0.98f, paint)

            val actionY = height * 0.91f
            ink.text(canvas, "撤回", 13f, if (target == Target.CANCEL) palette.brand else 0xFFE0E9F5.toInt(),
                width * 0.17f, actionY, bold = target == Target.CANCEL)
            ink.text(canvas, actionLabel, 13f, if (target == Target.ACTION) palette.brand else 0xFFE0E9F5.toInt(),
                width * 0.83f, actionY, bold = target == Target.ACTION)

            if (listening || recognizing) postInvalidateOnAnimation()
        }
    }

    private companion object {
        const val SAMPLE_COUNT = 48
    }
}
