package app.qingjian.inputmethod.service
import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView

/** Muted looping MP4 background, released whenever the IME surface detaches. */
internal class DynamicVideoBackgroundView(context: Context, private val videoUri: Uri, alpha: Float) : TextureView(context), TextureView.SurfaceTextureListener {
    private var player: MediaPlayer? = null
    private var output: Surface? = null
    private var prepared = false
    init { isOpaque = false; this.alpha = alpha.coerceIn(0f, 1f); surfaceTextureListener = this }
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
                if (player !== ready) return@setOnPreparedListener
                prepared = true
                runCatching { ready.start() }
            }
            media.setOnErrorListener { _, _, _ -> releasePlayer(); true }
            media.prepareAsync()
        } catch (_: Exception) { releasePlayer() }
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
