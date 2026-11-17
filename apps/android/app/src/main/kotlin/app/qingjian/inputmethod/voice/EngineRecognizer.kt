package app.qingjian.inputmethod.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
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

    /** 每个频带独立估计环境底噪；语音峰值只慢速抬高估计，低能量段较快回落。 */
    private val noiseFloorDb = DoubleArray(SPECTRUM_BANDS) { -58.0 }
    private val pcmNoiseSuppressor = PcmNoiseSuppressor()

    /** 录音线程看它停；主线程写。 */
    @Volatile
    private var recording = false

    /** 取消或重启后使已排队的旧音频回调失效；finish 则保留当前会话的队列顺序。 */
    private val feedLock = Any()

    @Volatile
    private var sessionId = 0L

    @Volatile
    private var cancelled = false

    override val available: Boolean get() = engine.voiceAvailable

    override val needsMicrophone = true

    // 入口先查过录音权限（VoiceInput），没有权限不会走到这里
    @SuppressLint("MissingPermission")
    override fun start(listener: VoiceRecognizer.Listener) {
        this.listener = listener
        pcmNoiseSuppressor.reset()
        engine.voiceStart(SAMPLE_RATE)
        val currentSession = synchronized(feedLock) {
            sessionId += 1
            cancelled = false
            recording = true
            sessionId
        }
        thread(name = "qingjian-voice-record") {
            val size = maxOf(AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING), CHUNK * 2)
            val record = runCatching {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, CHANNEL, ENCODING, size)
            }.getOrNull()
            if (record?.state != AudioRecord.STATE_INITIALIZED) {
                record?.release()
                main.post { fail("麦克风无法启动，可能正被其他应用占用，请关闭其他录音或通话应用后重试") }
                return@thread
            }
            var platformNoiseSuppressor: NoiseSuppressor? = null
            try {
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    error("AudioRecord 未进入录音状态")
                }
                // 先启用 Android 音频链路的硬件/系统降噪；不可用时仍执行下方 PCM 软件处理。
                platformNoiseSuppressor = runCatching {
                    if (NoiseSuppressor.isAvailable()) {
                        NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true }
                    } else null
                }.getOrNull()
            } catch (_: Throwable) {
                record.release()
                main.post {
                    fail("麦克风正在被占用或暂不可用，请结束其他应用的录音、通话或语音输入后重试")
                }
                return@thread
            }
            val buffer = ShortArray(CHUNK)
            while (recording) {
                val read = record.read(buffer, 0, buffer.size)
                if (read < 0) {
                    val message = when (read) {
                        AudioRecord.ERROR_DEAD_OBJECT -> "麦克风连接已中断，请重新尝试语音输入"
                        AudioRecord.ERROR_INVALID_OPERATION -> "麦克风当前不可用，可能正被其他应用占用，请关闭其他录音或通话应用后重试"
                        AudioRecord.ERROR_BAD_VALUE -> "录音参数无效，请重新启动语音输入"
                        AudioRecord.ERROR -> "麦克风读取失败，请检查麦克风权限或占用情况"
                        else -> "麦克风录音失败（$read），请重试"
                    }
                    main.post { if (recording) fail(message) }
                    break
                }
                if (read == 0) continue
                val captured = buffer.copyOf(read)
                // 对实际送入 ASR 的 PCM 做自适应降噪，频谱动画也显示处理后的音频。
                val chunk = pcmNoiseSuppressor.process(captured)
                val spectrum = spectrum(chunk)
                // 句柄只在主线程用；说完之后晚到的那截不再交
                synchronized(feedLock) {
                    // 与 finish() 共用锁：先获得锁的音频块先入主线程队列；
                    // finish() 获锁后会关闭采集，之后返回的 read 不再排到 Finish 之后。
                    if (!recording || sessionId != currentSession || cancelled) return@synchronized
                    main.post {
                        if (sessionId != currentSession || cancelled) return@post
                        engine.voiceFeed(chunk)
                        this.listener?.onSpectrum(spectrum)
                    }
                }
            }
            runCatching { if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop() }
            runCatching { platformNoiseSuppressor?.release() }
            record.release()
        }
        main.postDelayed(poll, POLL_MS)
    }

    override fun finish() {
        if (!recording) return
        val currentSession = synchronized(feedLock) {
            if (!recording) return
            recording = false
            sessionId
        }
        // 所有已接收的音频块都已先入队；finish 之后完成的 read 会被丢弃。
        main.post {
            if (sessionId == currentSession && !cancelled) engine.voiceFinish()
        }
    }

    override fun cancel() {
        synchronized(feedLock) {
            cancelled = true
            sessionId += 1
            recording = false
        }
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
        synchronized(feedLock) {
            recording = false
            cancelled = true
            sessionId += 1
        }
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
            // 自适应噪声门：低于估计底噪的能量不显示；高于底噪约 8 dB 才逐渐进入频谱。
            // 底噪下降跟得快、上升跟得慢，避免把持续说话误学成噪声。
            val previousFloor = noiseFloorDb[band]
            val floorBlend = if (dbfs < previousFloor) 0.055 else 0.0015
            val floor = (previousFloor + (dbfs - previousFloor) * floorBlend)
                .coerceIn(-72.0, -34.0)
            noiseFloorDb[band] = floor
            val gateDb = (floor + NOISE_GATE_DB).coerceIn(-64.0, -32.0)
            result[band] = if (dbfs <= gateDb) 0f
                else ((dbfs - gateDb) / (CEILING_DB - gateDb)).toFloat().coerceIn(0f, 1f)
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
        const val NOISE_GATE_DB = 8.0
    }
}
