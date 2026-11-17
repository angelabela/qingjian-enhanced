package app.qingjian.inputmethod.panel

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import app.qingjian.inputmethod.keyboard.Haptics
import app.qingjian.inputmethod.panel.base.Ink
import app.qingjian.inputmethod.theme.Palette
import kotlin.math.exp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

/** 语音面板：较轻的磨砂背景、随机渐变双声波与真实音量响应光晕。 */
@SuppressLint("ViewConstructor")
class VoicePanel(
    context: Context,
    private val palette: Palette,
    private val actionLabel: String,
    private val actions: Actions,
    private val blurredKeyboard: Bitmap? = null,
    private val animationStyle: Int = 0,
) : FrameLayout(context) {

    interface Actions {
        fun onCancel()
        fun onFinish(act: Boolean, hide: Boolean)
        /** Close the panel immediately while allowing the recognizer to finish its final result. */
        fun onClose()
        fun onUndo()
        fun onRedo()
    }

    enum class Target { NONE, CANCEL, ACTION }

    private val ink = Ink(palette, resources.displayMetrics.density)
    private val backdrop = ImageView(context)
    private val scrim = View(context)
    private val overlay = PhysicsWaveView(context).apply {
        style = PhysicsWaveView.Style.entries.getOrElse(animationStyle) { PhysicsWaveView.Style.SPRING_BARS }
        color = palette.brand
    }
    private val finishButton = TextView(context)
    private val undoButton = TextView(context)
    private val spectrumBands = FloatArray(SAMPLE_COUNT)
    private val smoothedBands = FloatArray(SAMPLE_COUNT)
    private var lastFrameNanos = 0L
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
            overlay.listening = value
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
        // 降低模糊半径，保留更多键盘细节；背景遮罩稍加深，减弱过透的感觉。
        if (Build.VERSION.SDK_INT >= 31 && blurredKeyboard != null) {
            val radius = 12f * resources.displayMetrics.density
            backdrop.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
        }
        addView(backdrop, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // 语音面板自身采用液态玻璃质感：半透明冷色渐层、柔和高光和细亮边。
        val glassRadius = 26f * resources.displayMetrics.density
        scrim.background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                0x55F5FBFF,
                (palette.board and 0x00FFFFFF) or 0x55405070,
                (palette.surface and 0x00FFFFFF) or 0x66405070,
                0x33202A48,
            ),
        ).apply {
            cornerRadius = glassRadius
            setStroke((1f * resources.displayMetrics.density).toInt().coerceAtLeast(1), 0x77FFFFFF)
        }
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val density = resources.displayMetrics.density
        fun styleActionButton(view: TextView, label: String, action: () -> Unit) {
            view.text = label
            view.textSize = 14f
            view.setTextColor(palette.text)
            view.gravity = android.view.Gravity.CENTER
            view.isClickable = true
            view.isFocusable = true
            view.includeFontPadding = false
            view.setPadding(0, 0, 0, 0)
            view.background = android.graphics.drawable.GradientDrawable().apply {
                setColor((palette.surface and 0x00FFFFFF) or 0xE0202938.toInt())
                cornerRadius = 22f * density
                setStroke((1f * density).toInt().coerceAtLeast(1), 0x88FFFFFF.toInt())
            }
            view.setOnClickListener {
                // 语音面板与键盘按键共用 App 内「按键震动」开关和强度档位。
                Haptics.tap(context)
                action()
            }
        }
        styleActionButton(undoButton, "撤销", actions::onUndo)
        styleActionButton(finishButton, "完成", actions::onClose)
        // 两个按钮使用相同的固定尺寸与间距，成组居中，避免分别贴在屏幕两侧。
        val actionRow = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            addView(undoButton, android.widget.LinearLayout.LayoutParams((104f * density).toInt(), (44f * density).toInt()))
            addView(finishButton, android.widget.LinearLayout.LayoutParams((104f * density).toInt(), (44f * density).toInt()).apply {
                marginStart = (14f * density).toInt()
            })
        }
        addView(actionRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = (18f * density).toInt()
        })
    }

    fun addSpectrum(bands: FloatArray) {
        synchronized(spectrumBands) {
            for (i in 0 until SAMPLE_COUNT) spectrumBands[i] = bands.getOrElse(i) { 0f }.coerceIn(0f, 1f)
        }
        val fiveBands = FloatArray(5) { band ->
            val start = band * SAMPLE_COUNT / 5
            val end = (band + 1) * SAMPLE_COUNT / 5
            var total = 0f
            for (i in start until end) total += spectrumBands[i]
            total / (end - start).coerceAtLeast(1)
        }
        overlay.update(fiveBands)
    }

    fun track(screenX: Float, screenY: Float) {
        val next = targetAt(screenX, screenY)
        if (next == target) return
        target = next
        undoButton.isSelected = next == Target.CANCEL
        finishButton.isSelected = next == Target.ACTION
        undoButton.alpha = if (undoButton.isSelected) 1f else 0.86f
        finishButton.alpha = if (finishButton.isSelected) 1f else 0.86f
        undoButton.scaleX = if (undoButton.isSelected) 1.06f else 1f
        undoButton.scaleY = if (undoButton.isSelected) 1.06f else 1f
        finishButton.scaleX = if (finishButton.isSelected) 1.06f else 1f
        finishButton.scaleY = if (finishButton.isSelected) 1.06f else 1f
        if (next != Target.NONE) Haptics.tap(context)
        overlay.invalidate()
    }

    private fun targetAt(screenX: Float, screenY: Float): Target {
        // 按钮的 left/top 是 actionRow 局部坐标，不能直接与面板局部坐标比较。
        // 分别换算成屏幕坐标后命中，覆盖撤销与完成两个按钮的真实区域。
        fun contains(view: View): Boolean {
            if (view.width <= 0 || view.height <= 0) return false
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            return screenX >= location[0] && screenX < location[0] + view.width &&
                screenY >= location[1] && screenY < location[1] + view.height
        }
        return when {
            contains(undoButton) -> Target.CANCEL
            contains(finishButton) -> Target.ACTION
            else -> Target.NONE
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = super.dispatchTouchEvent(event)

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (Build.VERSION.SDK_INT >= 31) backdrop.setRenderEffect(null)
        backdrop.setImageDrawable(null)
        blurredKeyboard?.takeIf { !it.isRecycled }?.recycle()
    }

    private inner class OverlayView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private val d = resources.displayMetrics.density
        // 每次新建语音面板都重新抽取色相；上下两条线保持协调但不再固定为同一套颜色。
        private val hueSeed = kotlin.random.Random.Default.nextFloat() * 360f
        private val lowerHueSeed = (hueSeed + 62f + kotlin.random.Random.Default.nextFloat() * 78f) % 360f
        init {
            // 仅对两条声波的光晕层做软件模糊，核心线条仍保持清晰。
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }

        /** 固定线条位置，只让渐变色相本身平滑循环变化；不移动 Shader 或线条。 */
        private fun gradientColors(baseHue: Float, hueShift: Float): IntArray {
            val stops = listOf(0f, 34f, 76f)
            val colors = stops.map { offset ->
                Color.HSVToColor(floatArrayOf((baseHue + hueShift + offset) % 360f, 0.82f, 1f))
            }
            fun alpha(color: Int, value: Int) = (color and 0x00FFFFFF) or (value shl 24)
            return intArrayOf(alpha(colors[0], 0), alpha(colors[1], 238), alpha(colors[2], 220), alpha(colors[0], 0))
        }

        private fun drawWaveLine(canvas: Canvas, cx: Float, cy: Float, hueShift: Float, maxAmplitude: Float) {
            fun drawLine(offset: Float, hue: Float, alpha: Int, blur: Float) {
                path.reset()
                for (i in smoothedBands.indices) {
                    val x = width * 0.08f + width * 0.84f * i / (smoothedBands.size - 1).coerceAtLeast(1)
                    val energy = smoothedBands[i].coerceIn(0f, 1f)
                    val y = cy + offset * d + (energy - 0.22f) * maxAmplitude * 1.55f
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
                paint.strokeWidth = if (blur > 0f) 5f * d else 2.2f * d
                paint.color = Color.HSVToColor(floatArrayOf((hue + hueShift) % 360f, 0.78f, 1f))
                paint.alpha = alpha
                paint.maskFilter = if (blur > 0f) BlurMaskFilter(blur * d, BlurMaskFilter.Blur.NORMAL) else null
                canvas.drawPath(path, paint)
                paint.maskFilter = null
            }
            drawLine(-13f, hueSeed, 90, 7f)
            drawLine(-13f, hueSeed, 245, 0f)
            drawLine(13f, lowerHueSeed, 75, 7f)
            drawLine(13f, lowerHueSeed, 220, 0f)
        }

        private fun drawCircularSpectrum(canvas: Canvas, cx: Float, cy: Float, hueShift: Float) {
            val radius = minOf(width, height) * 0.19f
            val barCount = smoothedBands.size
            val step = (2.0 * PI / barCount).toFloat()
            for (i in 0 until barCount) {
                val angle = -PI.toFloat() / 2f + i * step
                val energy = smoothedBands[i].coerceIn(0f, 1f)
                val inner = radius
                val outer = inner + 4f * d + energy * 42f * d
                val x1 = cx + cos(angle.toDouble()).toFloat() * inner
                val y1 = cy + sin(angle.toDouble()).toFloat() * inner
                val x2 = cx + cos(angle.toDouble()).toFloat() * outer
                val y2 = cy + sin(angle.toDouble()).toFloat() * outer
                val color = Color.HSVToColor(floatArrayOf((hueSeed + hueShift + i * 3.4f) % 360f, 0.76f, 1f))
                paint.color = color
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = 2.4f * d
                paint.alpha = 70
                paint.maskFilter = BlurMaskFilter(5f * d, BlurMaskFilter.Blur.NORMAL)
                canvas.drawLine(x1, y1, x2, y2, paint)
                paint.maskFilter = null
                paint.alpha = 245
                canvas.drawLine(x1, y1, x2, y2, paint)
            }
        }

        private fun drawTwinRibbon(canvas: Canvas, cx: Float, cy: Float, hueShift: Float, maxAmplitude: Float) {
            for (lineIndex in 0..1) {
                path.reset()
                for (i in smoothedBands.indices) {
                    val x = width * 0.09f + width * 0.82f * i / (smoothedBands.size - 1).coerceAtLeast(1)
                    val energy = smoothedBands[i].coerceIn(0f, 1f)
                    val direction = if (lineIndex == 0) -1f else 1f
                    val y = cy + direction * (10f * d + energy * maxAmplitude * 0.72f)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                val hue = if (lineIndex == 0) hueSeed else lowerHueSeed
                paint.color = Color.HSVToColor(floatArrayOf((hue + hueShift) % 360f, 0.8f, 1f))
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 8f * d
                paint.strokeCap = Paint.Cap.ROUND
                paint.alpha = 65
                paint.maskFilter = BlurMaskFilter(8f * d, BlurMaskFilter.Blur.NORMAL)
                canvas.drawPath(path, paint)
                paint.maskFilter = null
                paint.strokeWidth = 2.6f * d
                paint.alpha = 235
                canvas.drawPath(path, paint)
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width <= 0 || height <= 0) return
            val cx = width * 0.5f
            val cy = height * 0.475f
            val frameBands = FloatArray(SAMPLE_COUNT)
            synchronized(spectrumBands) {
                spectrumBands.copyInto(frameBands)
            }

            val frameNanos = System.nanoTime()
            val deltaSeconds = if (lastFrameNanos == 0L) 1.0 / 60.0
                else ((frameNanos - lastFrameNanos) / 1_000_000_000.0).coerceIn(0.0, 0.05)
            lastFrameNanos = frameNanos
            // 频谱采用快起慢落的包络平滑，保留瞬态，又避免柱高抖动。
            for (i in smoothedBands.indices) {
                val attack = (1.0 - exp(-deltaSeconds / 0.045)).toFloat()
                val release = (1.0 - exp(-deltaSeconds / 0.19)).toFloat()
                val blend = if (frameBands[i] > smoothedBands[i]) attack else release
                smoothedBands[i] += (frameBands[i] - smoothedBands[i]) * blend
            }

            val now = frameNanos / 1_000_000_000.0
            val halfWidth = width * 0.43f
            val maxAmplitude = 54f * d

            // 48 根柱逐一对应真实 FFT 对数频带；不再叠加假音量包络或人为波纹。

            val barCount = SAMPLE_COUNT
            val gap = 3.2f * d
            val barWidth = ((halfWidth * 2f) - gap * (barCount - 1)) / barCount
            val hueShift = (now * 32.0).toFloat() % 360f
            when (animationStyle) {
                1 -> drawWaveLine(canvas, cx, cy, hueShift, maxAmplitude)
                2 -> drawCircularSpectrum(canvas, cx, cy, hueShift)
                3 -> drawTwinRibbon(canvas, cx, cy, hueShift, maxAmplitude)
                else -> {
                    for (i in 0 until barCount) {
                        val x = cx - halfWidth + i * (barWidth + gap)
                        val bandEnergy = smoothedBands[i].coerceIn(0f, 1f)
                        val heightBand = (2.2f * d + bandEnergy * maxAmplitude).coerceAtLeast(2f * d)
                        val color = Color.HSVToColor(floatArrayOf((hueSeed + hueShift + i * 2.8f) % 360f, 0.78f, 1f))
                        paint.shader = null
                        paint.color = color
                        paint.style = Paint.Style.STROKE
                        paint.strokeCap = Paint.Cap.ROUND
                        paint.strokeWidth = barWidth.coerceAtLeast(1.2f * d)
                        paint.alpha = 75
                        paint.maskFilter = BlurMaskFilter(5f * d, BlurMaskFilter.Blur.NORMAL)
                        canvas.drawLine(x + barWidth / 2f, cy - heightBand, x + barWidth / 2f, cy + heightBand, paint)
                        paint.maskFilter = null
                        paint.alpha = 245
                        paint.strokeWidth = (barWidth * 0.72f).coerceAtLeast(1.1f * d)
                        canvas.drawLine(x + barWidth / 2f, cy - heightBand, x + barWidth / 2f, cy + heightBand, paint)
                    }
                }
            }
            paint.alpha = 255

            // 顶部双线的颜色持续平滑变化；面板离屏后由 View 生命周期自然停止绘制。
            if (isAttachedToWindow && visibility == View.VISIBLE) postInvalidateOnAnimation()
        }
    }

    companion object {
        const val SAMPLE_COUNT = 48
        const val PREF_STYLE = "voice_animation_style"
        val ANIMATION_STYLE_LABELS = listOf("弹簧柱", "重力弹跳球", "峰值保持", "弹性丝带", "呼吸光球", "每次启动随机")

        /** 输入法 Service 不是 Activity，不能直接挂 AlertDialog；交给独立 Activity 显示设置页。 */
        fun showAnimationPicker(context: Context) {
            context.startActivity(
                Intent(context, VoiceAnimationSettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }

        fun resolveAnimationStyle(context: Context): Int {
            val selected = context.getSharedPreferences("voice", Context.MODE_PRIVATE)
                .getInt(PREF_STYLE, 0)
            return if (selected == -1) kotlin.random.Random.nextInt(5) else selected.coerceIn(0, 4)
        }
    }
}
