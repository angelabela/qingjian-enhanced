package app.qingjian.inputmethod.service
import android.content.Context
import android.graphics.SurfaceTexture
import android.graphics.Bitmap
import android.os.SystemClock
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import android.os.Build
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Matrix
import android.graphics.RectF
import app.qingjian.inputmethod.core.AppLog

/** Muted looping MP4 background, released whenever the IME surface detaches. */
internal class DynamicVideoBackgroundView(context: Context, private val videoUri: Uri, alpha: Float, blurRadiusDp: Int = 0) : TextureView(context), TextureView.SurfaceTextureListener {
    private var player: MediaPlayer? = null
    private var output: Surface? = null
    private var prepared = false
    private var videoWidth = 0
    private var videoHeight = 0
    var onAverageLuminance: ((Float) -> Unit)? = null
    private var lastLuminanceSampleAt = 0L
    private var currentBlurRadiusDp = blurRadiusDp

    init {
        isOpaque = false
        this.alpha = alpha.coerceIn(0f, 1f)
        applyBlur(currentBlurRadiusDp)
        surfaceTextureListener = this
    }

    /** Update visual effects without destroying the TextureView/Surface or restarting playback. */
    fun updateAppearance(alpha: Float, blurRadiusDp: Int) {
        this.alpha = alpha.coerceIn(0f, 1f)
        val nextBlur = blurRadiusDp.coerceIn(0, 30)
        if (currentBlurRadiusDp != nextBlur) {
            currentBlurRadiusDp = nextBlur
            applyBlur(nextBlur)
        }
        invalidate()
    }

    private fun applyBlur(blurRadiusDp: Int) {
        if (Build.VERSION.SDK_INT >= 31) {
            val effect = if (blurRadiusDp > 0) {
                val radius = blurRadiusDp * resources.displayMetrics.density
                RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
            } else null
            setRenderEffect(effect)
        }
    }
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        AppLog.info("[VIDEO_BG_DIAG] surface available view=${this.width}x${this.height} texture=${width}x${height} uriScheme=${videoUri.scheme}")
        releasePlayer()
        val target = Surface(surface)
        output = target
        val media = MediaPlayer()
        player = media
        try {
            media.setSurface(target)
            media.setDataSource(context, videoUri)
            media.setOnVideoSizeChangedListener { _, width, height ->
                videoWidth = width
                videoHeight = height
                AppLog.info("[VIDEO_BG_DIAG] source size=${width}x${height} view=${this@DynamicVideoBackgroundView.width}x${this@DynamicVideoBackgroundView.height}")
                applyVideoTransform()
            }
            media.isLooping = true
            media.setVolume(0f, 0f)
            media.setOnPreparedListener { ready ->
                if (player !== ready || !isAvailable || output?.isValid != true) {
                    releasePlayer()
                    return@setOnPreparedListener
                }
                prepared = true
                AppLog.info("[VIDEO_BG_DIAG] player prepared view=${this@DynamicVideoBackgroundView.width}x${this@DynamicVideoBackgroundView.height} source=${videoWidth}x${videoHeight}")
                runCatching {
                    ready.setVolume(0f, 0f)
                    ready.start()
                    invalidate()
                }.onFailure {
                    android.util.Log.w("QingjianVideoBg", "Unable to start prepared background video", it)
                    releasePlayer()
                }
            }
            media.setOnInfoListener { ready, what, _ ->
                if (player === ready && what == MediaPlayer.MEDIA_INFO_BUFFERING_END && prepared && !ready.isPlaying) {
                    runCatching { ready.start() }
                }
                false
            }
            media.setOnErrorListener { _, what, extra ->
                AppLog.warn("[VIDEO_BG_DIAG] MediaPlayer error what=$what extra=$extra")
                android.util.Log.w("QingjianVideoBg", "MediaPlayer error what=$what extra=$extra")
                releasePlayer()
                true
            }
            media.prepareAsync()
        } catch (error: Exception) {
            android.util.Log.w("QingjianVideoBg", "Unable to prepare background video", error)
            releasePlayer()
        }
    }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        AppLog.info("[VIDEO_BG_DIAG] texture resized=${width}x${height} view=${this.width}x${this.height}")
        applyVideoTransform()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        AppLog.info("[VIDEO_BG_DIAG] view size=${w}x${h} previous=${oldw}x${oldh} attached=$isAttachedToWindow")
        applyVideoTransform()
    }

    /** 完整显示视频源画面并铺满整个输入法面板；按面板宽高分别缩放，不裁切、不留黑边。 */
    private fun applyVideoTransform() {
        if (width <= 0 || height <= 0 || videoWidth <= 0 || videoHeight <= 0) return
        val source = RectF(0f, 0f, videoWidth.toFloat(), videoHeight.toFloat())
        val destination = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val matrix = Matrix().apply {
            // FILL 使用独立的横纵缩放比例，保证视频每一帧完整映射到面板区域。
            setRectToRect(source, destination, Matrix.ScaleToFit.FILL)
        }
        setTransform(matrix)
        val scaleX = width.toFloat() / videoWidth
        val scaleY = height.toFloat() / videoHeight
        AppLog.info("[VIDEO_BG_DIAG] full-frame fill view=${width}x${height} source=${videoWidth}x${videoHeight} scaleX=$scaleX scaleY=$scaleY crop=false")
    }
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { releasePlayer(); return true }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        val now = SystemClock.elapsedRealtime()
        // 亮度采样只用于主题适配；降低采样频率，且隐藏/脱离窗口时不再读取纹理。
        if (!isAttachedToWindow || !isShown || now - lastLuminanceSampleAt < 1_500L) return
        lastLuminanceSampleAt = now
        val sample = runCatching { getBitmap(8, 8) }.getOrNull() ?: return
        try {
            var total = 0f
            var count = 0
            for (y in 0 until minOf(sample.height, 3)) for (x in 0 until sample.width) {
                val pixel = sample.getPixel(x, y)
                val r = android.graphics.Color.red(pixel) / 255f
                val g = android.graphics.Color.green(pixel) / 255f
                val b = android.graphics.Color.blue(pixel) / 255f
                // 感知亮度近似值；采样缩小到 8×8，避免逐帧读取整张视频位图。
                total += 0.2126f * r + 0.7152f * g + 0.0722f * b
                count++
            }
            if (count > 0) onAverageLuminance?.invoke(total / count)
        } finally {
            sample.recycle()
        }
    }
    override fun onDetachedFromWindow() { releasePlayer(); super.onDetachedFromWindow() }
    private fun releasePlayer() {
        val wasPrepared = prepared
        prepared = false
        player?.let { media ->
            runCatching { media.setOnPreparedListener(null); media.setOnErrorListener(null); if (wasPrepared && media.isPlaying) media.stop(); media.release() }
        }
        player = null
        videoWidth = 0
        videoHeight = 0
        output?.release()
        output = null
    }
}
