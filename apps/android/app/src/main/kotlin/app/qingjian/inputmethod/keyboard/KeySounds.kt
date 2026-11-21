package app.qingjian.inputmethod.keyboard

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import app.qingjian.inputmethod.keyboard.layout.Key
import app.qingjian.inputmethod.settings.Settings

/**
 * 按键声音。系统模式沿用 Android 的系统按键音开关与音量；
 * 预设模式使用短促合成音，不加载音频文件、不创建每次点击的播放器。
 */
object KeySounds {
    @Volatile private var tones: ToneGenerator? = null
    @Volatile private var toneVolume: Int = -1

    fun play(context: Context, key: Key) {
        val prefs = Settings.preferences(context)
        if (!prefs.getBoolean(Settings.KEY_KEY_SOUND, false)) return
        when (Settings.KeySoundStyle.of(prefs.getString(Settings.KEY_KEY_SOUND_STYLE, null))) {
            Settings.KeySoundStyle.SYSTEM -> playSystem(context, key)
            Settings.KeySoundStyle.CRISP -> playTone(ToneGenerator.TONE_PROP_BEEP, 28, 70)
            Settings.KeySoundStyle.SOFT -> playTone(ToneGenerator.TONE_PROP_ACK, 24, 42)
            Settings.KeySoundStyle.MECHANICAL -> playTone(ToneGenerator.TONE_DTMF_1, 18, 55)
            Settings.KeySoundStyle.BUBBLE -> playTone(ToneGenerator.TONE_PROP_PROMPT, 34, 48)
        }
    }

    private fun playSystem(context: Context, key: Key) {
        val effect = when (key) {
            is Key.Space -> AudioManager.FX_KEYPRESS_SPACEBAR
            is Key.Enter -> AudioManager.FX_KEYPRESS_RETURN
            Key.Backspace -> AudioManager.FX_KEYPRESS_DELETE
            else -> AudioManager.FX_KEYPRESS_STANDARD
        }
        runCatching { context.getSystemService(AudioManager::class.java)?.playSoundEffect(effect) }
    }

    private fun playTone(tone: Int, durationMs: Int, volume: Int) {
        runCatching {
            synchronized(this) {
                var generator = tones
                if (generator == null || toneVolume != volume) {
                    generator?.release()
                    generator = ToneGenerator(AudioManager.STREAM_SYSTEM, volume)
                    tones = generator
                    toneVolume = volume
                }
                generator.startTone(tone, durationMs)
            }
        }
    }
}
