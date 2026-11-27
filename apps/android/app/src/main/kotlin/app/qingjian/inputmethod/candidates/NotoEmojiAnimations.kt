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
    private val drawables = object : LruCache<String, LottieDrawable>(2) {
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: LottieDrawable, newValue: LottieDrawable?) {
            oldValue.cancelAnimation()
            oldValue.callback = null
        }
    }

    fun onCandidatesChanged() {
        typing = true
        handler.removeCallbacks(resumeAfterTyping)
        handler.postDelayed(resumeAfterTyping, 300L)
        pauseAll()
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

    fun draw(canvas: Canvas, text: String, rect: RectF): Boolean {
        if (paused || typing || rect.isEmpty) return false
        val drawable = drawables.get(text) ?: createDrawable(text) ?: return false
        drawable.callback = callback
        val left = rect.left.toInt()
        val top = rect.top.toInt()
        drawable.bounds = Rect(left, top, rect.right.toInt(), rect.bottom.toInt())
        if (!drawable.isAnimating) {
            drawable.repeatCount = LottieDrawable.INFINITE
            drawable.playAnimation()
        }
        drawable.draw(canvas)
        return true
    }

    private fun createDrawable(text: String): LottieDrawable? {
        val codepoints = text.codePoints().toArray().joinToString("-") { it.toString(16) }
        val composition = compositions.get(codepoints) ?: run {
            val assetPath = "noto-emoji/$codepoints.json"
            val parsed = try {
                context.assets.open(assetPath).use { input ->
                    LottieCompositionFactory.fromJsonInputStreamSync(input, codepoints).value
                }
            } catch (_: Exception) { null } ?: return null
            compositions.put(codepoints, parsed)
            parsed
        }
        return LottieDrawable().apply {
            setComposition(composition)
            repeatCount = LottieDrawable.INFINITE
            callback = this@NotoEmojiAnimations.callback
        }.also { drawables.put(text, it) }
    }
}
