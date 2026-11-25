package app.qingjian.inputmethod.keyboard

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import app.qingjian.inputmethod.R
import app.qingjian.inputmethod.keyboard.layout.Key
import app.qingjian.inputmethod.settings.Settings

/** 系统模式沿用 Android 系统按键音；其他模式播放 APK 内置的 CC0 OGG 短音。 */
object KeySounds {
    @Volatile private var pool: SoundPool? = null
    private val loadedSamples = mutableSetOf<Int>()
    private var sampleIds: Map<Settings.KeySoundStyle, Int> = emptyMap()

    fun play(context: Context, key: Key) {
        val prefs = Settings.preferences(context)
        if (!prefs.getBoolean(Settings.KEY_KEY_SOUND, false)) return
        val style = Settings.KeySoundStyle.of(prefs.getString(Settings.KEY_KEY_SOUND_STYLE, null))
        if (style == Settings.KeySoundStyle.SYSTEM) playSystem(context, key) else playBundled(context, style)
    }

    private fun playSystem(context: Context, key: Key) {
        val effect = when (key) {
            is Key.Space -> AudioManager.FX_KEYPRESS_SPACEBAR
            is Key.Enter -> AudioManager.FX_KEYPRESS_RETURN
            Key.Backspace -> AudioManager.FX_KEYPRESS_DELETE
            else -> AudioManager.FX_KEYPRESS_STANDARD
        }
        val enabled = runCatching {
            android.provider.Settings.System.getInt(context.contentResolver, android.provider.Settings.System.SOUND_EFFECTS_ENABLED, 1) != 0
        }.getOrDefault(true)
        if (enabled) runCatching { context.getSystemService(AudioManager::class.java)?.playSoundEffect(effect) }
    }

    @Synchronized
    private fun playBundled(context: Context, style: Settings.KeySoundStyle) {
        val soundPool = ensurePool(context) ?: return
        val sample = sampleIds[style] ?: return
        if (sample in loadedSamples) soundPool.play(sample, 0.62f, 0.62f, 1, 0, 1f)
    }

    @Synchronized
    private fun ensurePool(context: Context): SoundPool? {
        pool?.let { return it }
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val created = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attributes).build()
        created.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) synchronized(this) { loadedSamples += sampleId }
        }
        pool = created
        sampleIds = mapOf(
            Settings.KeySoundStyle.CRISP to created.load(context, R.raw.key_sound_crisp, 1),
            Settings.KeySoundStyle.SOFT to created.load(context, R.raw.key_sound_soft, 1),
            Settings.KeySoundStyle.MECHANICAL to created.load(context, R.raw.key_sound_mechanical, 1),
            Settings.KeySoundStyle.BUBBLE to created.load(context, R.raw.key_sound_bubble, 1),
        )
        return created
    }

    @Synchronized
    fun release() {
        pool?.release()
        pool = null
        loadedSamples.clear()
        sampleIds = emptyMap()
    }
}
