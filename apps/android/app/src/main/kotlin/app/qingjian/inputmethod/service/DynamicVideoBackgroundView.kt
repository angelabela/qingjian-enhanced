package app.qingjian.inputmethod.service
import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import android.os.Build
import android.graphics.RenderEffect
import android.graphics.Shader

/** Muted looping MP4 background, released whenever the IME surface detaches. */
internal class DynamicVideoBackgroundView(context: Context, private val videoUri: Uri, alpha: Float, blurRadiusDp: Int = 0) : TextureView(context), TextureView.SurfaceTextureListener {
    private var player: MediaPlayer? = null
    private var output: Surface? = null
    private var prepared = false
    init {
        isOpaque = false
        this.alpha = alpha.coerceIn(0f, 1f)
        if (Build.VERSION.SDK_INT >= 31 && blurRadiusDp > 0) {
            val radius = blurRadiusDp * resources.displayMetrics.density
            setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
        }
        surfaceTextureListener = this
    }
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        releasePlayer()
        val target = Surface(surface)
        output = target
        val media = MediaPlayer()
        player = media
        try {
            media.setSurface(target)
            media.setDataSource(context, videoUri)
            media.isLooping = true
            media.setVolume(0f, 0f)
            media.setOnPreparedListener { ready ->
                if (player !== ready || !isAvailable || output?.isValid != true) {
                    releasePlayer()
                    return@setOnPreparedListener
                }
                prepared = true
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
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { releasePlayer(); return true }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    override fun onDetachedFromWindow() { releasePlayer(); super.onDetachedFromWindow() }
    private fun releasePlayer() {
        val wasPrepared = prepared
        prepared = false
        player?.let { media ->
            runCatching { media.setOnPreparedListener(null); media.setOnErrorListener(null); if (wasPrepared && media.isPlaying) media.stop(); media.release() }
        }
        player = null
        output?.release()
        output = null
    }
}
