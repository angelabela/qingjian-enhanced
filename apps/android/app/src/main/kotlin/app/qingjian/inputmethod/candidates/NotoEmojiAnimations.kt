package app.qingjian.inputmethod.candidates

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import kotlin.math.roundToInt

/**
 * APK 内离线 Noto Emoji 动画。缓存以候选文字为键，最多保留两个 Drawable；
 * 输入停顿 300ms 后才启动，窗口隐藏/脱离时暂停，缓存淘汰时取消动画。
 */
internal class NotoEmojiAnimations(
    private val context: Context,
    private val callback: Drawable.Callback,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var typing = false
    private var paused = false
    private val resumeAfterTyping = Runnable {
        typing = false
        if (!paused) {
            drawables.snapshot().values.forEach { drawable ->
                drawable.resumeAnimation()
                callback.invalidateDrawable(drawable)
            }
        }
    }
    private val compositions = LruCache<String, LottieComposition>(24)
    private val drawables = object : LruCache<String, LottieDrawable>(16) {
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: LottieDrawable, newValue: LottieDrawable?) {
            oldValue.cancelAnimation()
            oldValue.callback = null
        }
    }

    fun onCandidatesChanged() {
        typing = true
        handler.removeCallbacks(resumeAfterTyping)
        handler.postDelayed(resumeAfterTyping, 300L)
        drawables.snapshot().values.forEach { it.pauseAnimation() }
    }

    fun pauseAll() {
        paused = true
        handler.removeCallbacks(resumeAfterTyping)
        drawables.snapshot().values.forEach { it.pauseAnimation() }
    }

    fun resumeAll() {
        paused = false
        if (!typing) drawables.snapshot().values.forEach { it.resumeAnimation() }
    }

    /** Long-press preview is an explicit user request; do not let the typing debounce suppress it. */
    fun resumeForLongPress() {
        handler.removeCallbacks(resumeAfterTyping)
        typing = false
        paused = false
        drawables.snapshot().values.forEach { it.resumeAnimation() }
    }

    fun draw(canvas: Canvas, text: String, rect: RectF): Boolean {
        if (paused || typing || rect.isEmpty) return false
        val drawable = drawables.get(text) ?: createDrawable(text) ?: return false
        drawable.callback = callback
        val sourceBounds = drawable.composition?.bounds ?: return false
        val sourceWidth = sourceBounds.width().toFloat()
        val sourceHeight = sourceBounds.height().toFloat()
        if (sourceWidth <= 0f || sourceHeight <= 0f) return false

        // 等比例 contain：保留 Noto 原始画布比例，禁止把方形/竖向图案横向拉伸。
        val scale = minOf(rect.width() / sourceWidth, rect.height() / sourceHeight)
        val drawWidth = sourceWidth * scale
        val drawHeight = sourceHeight * scale
        val left = (rect.centerX() - drawWidth / 2f).roundToInt()
        val top = (rect.centerY() - drawHeight / 2f).roundToInt()
        val right = (rect.centerX() + drawWidth / 2f).roundToInt()
        val bottom = (rect.centerY() + drawHeight / 2f).roundToInt()

        canvas.save()
        canvas.clipRect(rect)
        drawable.bounds = Rect(left, top, right.coerceAtLeast(left + 1), bottom.coerceAtLeast(top + 1))
        if (!drawable.isAnimating) {
            drawable.repeatCount = LottieDrawable.INFINITE
            drawable.playAnimation()
        }
        drawable.draw(canvas)
        canvas.restore()
        return true
    }

    private fun assetKeys(text: String): List<String> {
        val codepoints = text.codePoints().toArray()
        val exact = codepoints.joinToString("_") { it.toString(16) }
        val withoutVariationSelector = codepoints.filter { it != 0xFE0F }
            .joinToString("_") { it.toString(16) }
        val legacyHyphenated = codepoints.joinToString("-") { it.toString(16) }
        return listOf(exact, withoutVariationSelector, legacyHyphenated).distinct()
    }

    private fun createDrawable(text: String): LottieDrawable? {
        var composition: LottieComposition? = null
        for (key in assetKeys(text)) {
            composition = compositions.get(key)
            if (composition == null) {
                composition = try {
                    context.assets.open("noto-emoji/$key.json").use { input ->
                        LottieCompositionFactory.fromJsonInputStreamSync(input, key).value
                    }
                } catch (_: Exception) {
                    null
                }
                if (composition != null) compositions.put(key, composition)
            }
            if (composition != null) break
        }
        val parsed = composition ?: return null
        return LottieDrawable().apply {
            setComposition(parsed)
            repeatCount = LottieDrawable.INFINITE
            callback = this@NotoEmojiAnimations.callback
        }.also { drawables.put(text, it) }
    }
}
