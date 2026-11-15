package app.qingjian.inputmethod.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import app.qingjian.inputmethod.core.Engine
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Rust 侧的识别方（云端、离线模型，Core 的 `Transcriber`）：这边用 [AudioRecord] 录单声道 16 kHz，
 * 一截截交给引擎，每 [POLL_MS] 取一次结果。「语音识别」没配好时 [available] 为 false，[VoiceInput] 不会选它。
 */
class EngineRecognizer(private val engine: Engine) : VoiceRecognizer {
    private val main = Handler(Looper.getMainLooper())
    private var listener: VoiceRecognizer.Listener? = null

    /** 录音线程看它停；主线程写。 */
    @Volatile
    private var recording = false

    override val available: Boolean get() = engine.voiceAvailable

    override val needsMicrophone = true

    // 入口先查过录音权限（VoiceInput），没有权限不会走到这里
    @SuppressLint("MissingPermission")
    override fun start(listener: VoiceRecognizer.Listener) {
        this.listener = listener
        engine.voiceStart(SAMPLE_RATE)
        recording = true
        thread(name = "qingjian-voice-record") {
            val size = maxOf(AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING), CHUNK * 2)
            val record = runCatching {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, CHANNEL, ENCODING, size)
            }.getOrNull()
            if (record?.state != AudioRecord.STATE_INITIALIZED) {
                record?.release()
                main.post { fail("麦克风打不开") }
                return@thread
            }
            record.startRecording()
            val buffer = ShortArray(CHUNK)
            while (recording) {
                val read = record.read(buffer, 0, buffer.size)
                if (read < 0) break
                if (read == 0) continue
                val chunk = buffer.copyOf(read)
                val spectrum = spectrum(chunk)
                // 句柄只在主线程用；说完之后晚到的那截不再交
                main.post {
                    if (!recording) return@post
                    engine.voiceFeed(chunk)
                    this.listener?.onSpectrum(spectrum)
                }
            }
            record.stop()
            record.release()
        }
        main.postDelayed(poll, POLL_MS)
    }

    override fun finish() {
        if (!recording) return
        recording = false
        engine.voiceFinish()
    }

    override fun cancel() {
        engine.voiceCancel()
        stop()
    }

    private val poll: Runnable = Runnable {
        val listener = listener ?: return@Runnable
        engine.pollVoice()?.let { (kind, text) ->
            when (kind) {
                "partial" -> listener.onPartial(text)
                "final" -> {
                    stop()
                    listener.onFinal(text)
                }
                else -> {
                    stop()
                    listener.onError(text)
                }
            }
        }
        if (this.listener != null) main.postDelayed(poll, POLL_MS)
    }

    private fun fail(message: String) {
        val listener = listener ?: return
        engine.voiceCancel()
        stop()
        listener.onError(message)
    }

    /** 收尾：录音线程跟着停（出错、服务端先结束时也得停，不然麦克风一直开着）。 */
    private fun stop() {
        recording = false
        main.removeCallbacks(poll)
        listener = null
    }

    /**
     * 对真实 16 kHz PCM16 样本做 1024 点 Hann 窗 FFT，输出 48 个对数频段。
     * 每个频段取 FFT bin 的 RMS 幅度，以 -72 dBFS 到 -18 dBFS 映射到 0..1。
     */
    private fun spectrum(samples: ShortArray): FloatArray {
        val real = DoubleArray(FFT_SIZE)
        val imaginary = DoubleArray(FFT_SIZE)
        val offset = (samples.size - FFT_SIZE).coerceAtLeast(0)
        val available = minOf(samples.size, FFT_SIZE)
        for (i in 0 until available) {
            val sample = samples[offset + i].toDouble() / Short.MAX_VALUE
            val window = 0.5 - 0.5 * cos(2.0 * PI * i / (FFT_SIZE - 1))
            real[i] = sample * window
        }

        // Radix-2 in-place Cooley–Tukey FFT.
        var j = 0
        for (i in 1 until FFT_SIZE) {
            var bit = FFT_SIZE shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val r = real[i]; real[i] = real[j]; real[j] = r
                val im = imaginary[i]; imaginary[i] = imaginary[j]; imaginary[j] = im
            }
        }
        var size = 2
        while (size <= FFT_SIZE) {
            val half = size shr 1
            val angle = -2.0 * PI / size
            val stepReal = cos(angle)
            val stepImaginary = kotlin.math.sin(angle)
            var baseIndex = 0
            while (baseIndex < FFT_SIZE) {
                var twiddleReal = 1.0
                var twiddleImaginary = 0.0
                for (k in 0 until half) {
                    val even = baseIndex + k
                    val odd = even + half
                    val oddReal = real[odd] * twiddleReal - imaginary[odd] * twiddleImaginary
                    val oddImaginary = real[odd] * twiddleImaginary + imaginary[odd] * twiddleReal
                    real[odd] = real[even] - oddReal
                    imaginary[odd] = imaginary[even] - oddImaginary
                    real[even] += oddReal
                    imaginary[even] += oddImaginary
                    val nextReal = twiddleReal * stepReal - twiddleImaginary * stepImaginary
                    twiddleImaginary = twiddleReal * stepImaginary + twiddleImaginary * stepReal
                    twiddleReal = nextReal
                }
                baseIndex += size
            }
            size = size shl 1
        }

        val result = FloatArray(SPECTRUM_BANDS)
        val binHz = SAMPLE_RATE.toDouble() / FFT_SIZE
        val minFrequency = 55.0
        val maxFrequency = 7_500.0
        for (band in 0 until SPECTRUM_BANDS) {
            val lowHz = minFrequency * (maxFrequency / minFrequency).pow(band.toDouble() / SPECTRUM_BANDS)
            val highHz = minFrequency * (maxFrequency / minFrequency).pow((band + 1.0) / SPECTRUM_BANDS)
            val firstBin = (lowHz / binHz).toInt().coerceAtLeast(1)
            val lastBin = (highHz / binHz).toInt().coerceAtMost(FFT_SIZE / 2)
            var power = 0.0
            var count = 0
            if (firstBin <= lastBin) {
                for (bin in firstBin..lastBin) {
                    val magnitude = sqrt(real[bin] * real[bin] + imaginary[bin] * imaginary[bin]) * (2.0 / FFT_SIZE)
                    power += magnitude * magnitude
                    count++
                }
            }
            val amplitude = if (count == 0) 0.0 else sqrt(power / count)
            val dbfs = if (amplitude > 1e-9) 20.0 * log10(amplitude) else FLOOR_DB
            result[band] = ((dbfs - FLOOR_DB) / (CEILING_DB - FLOOR_DB)).toFloat().coerceIn(0f, 1f)
        }
        return result
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** 一截 100 ms。 */
        const val CHUNK = SAMPLE_RATE / 10
        const val POLL_MS = 100L
        const val FFT_SIZE = 1024
        const val SPECTRUM_BANDS = 48
        const val FLOOR_DB = -72.0
        const val CEILING_DB = -18.0
    }
}
