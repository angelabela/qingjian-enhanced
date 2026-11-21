package app.qingjian.inputmethod.keyboard

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import app.qingjian.inputmethod.keyboard.layout.Key
import app.qingjian.inputmethod.settings.Settings

/**
 * 按键声音。系统模式优先沿用 Android 的系统按键音与音量；系统按键音关闭时回退到短音。
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
        val systemEffectsEnabled = runCatching {
            android.provider.Settings.System.getInt(
                context.contentResolver,
                android.provider.Settings.System.SOUND_EFFECTS_ENABLED,
                1,
            ) != 0
        }.getOrDefault(true)
        if (systemEffectsEnabled) {
            runCatching { context.getSystemService(AudioManager::class.java)?.playSoundEffect(effect) }
        } else {
            // App 内已明确开启按键声音时，不让系统全局「按键音」关闭导致完全静音。
            playTone(ToneGenerator.TONE_PROP_BEEP, 24, 60)
        }
    }

    /** 输入法服务销毁时释放共享音源，避免 ToneGenerator 长期占用系统音频资源。 */
    fun release() {
        synchronized(this) {
            tones?.release()
            tones = null
            toneVolume = -1
        }
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
