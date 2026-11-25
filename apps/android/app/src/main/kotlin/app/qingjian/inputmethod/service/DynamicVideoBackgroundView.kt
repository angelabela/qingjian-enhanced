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
import app.qingjian.inputmethod.core.AppLog

/** Muted looping MP4 background, released whenever the IME surface detaches. */
internal class DynamicVideoBackgroundView(context: Context, private val videoUri: Uri, alpha: Float, blurRadiusDp: Int = 0) : TextureView(context), TextureView.SurfaceTextureListener {
    private var player: MediaPlayer? = null
    private var output: Surface? = null
    private var prepared = false
    private var pausedForVisibility = false
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
                    // TextureView 默认按源视频比例缩放，窄屏/横屏视频会在面板右侧或底部留下空白。
                    // 让解码器按目标 Surface 做居中裁切填充，保证整个输入法窗口都有视频。
                    ready.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                    if (isShown) ready.start() else pausedForVisibility = true
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

    /** 重置 TextureView 的额外变换，由 MediaPlayer 的裁切填充模式覆盖整个 Surface。 */
    private fun applyVideoTransform() {
        if (width <= 0 || height <= 0 || videoWidth <= 0 || videoHeight <= 0) return
        // TextureView 的 Matrix 作用于纹理而非 View 布局；旧的按坐标 FILL 矩阵在部分比例下
        // 会被 SurfaceTexture 再次缩放，导致右侧/底部出现未覆盖区域。使用解码器 crop-fill。
        setTransform(Matrix())
        val scale = maxOf(width.toFloat() / videoWidth, height.toFloat() / videoHeight)
        val renderedWidth = videoWidth * scale
        val renderedHeight = videoHeight * scale
        AppLog.info("[VIDEO_BG_DIAG] crop-fill view=${width}x${height} source=${videoWidth}x${videoHeight} scale=$scale rendered=${renderedWidth}x${renderedHeight}")
    }
    override fun onVisibilityChanged(changedView: android.view.View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        val media = player ?: return
        if (!prepared) return
        if (!isShown) {
            if (runCatching { media.isPlaying }.getOrDefault(false)) {
                runCatching { media.pause() }
                pausedForVisibility = true
            }
        } else if (pausedForVisibility && isAvailable) {
            runCatching { media.start() }
            pausedForVisibility = false
        }
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { releasePlayer(); return true }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        val now = SystemClock.elapsedRealtime()
        // 亮度采样只用于主题适配；降低采样频率，且隐藏/脱离窗口时不再读取纹理。
        if (!isAttachedToWindow || !isShown || now - lastLuminanceSampleAt < 900L) return
        lastLuminanceSampleAt = now
        val sample = runCatching { getBitmap(8, 8) }.getOrNull() ?: return
        try {
            var total = 0f
            var count = 0
            // 使用整个 8×8 纹理采样网格，避免只取顶部局部颜色导致全键盘误判。
            for (y in 0 until sample.height) for (x in 0 until sample.width) {
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
        pausedForVisibility = false
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
