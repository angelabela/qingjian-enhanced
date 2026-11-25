package app.qingjian.inputmethod.voice

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 录音 PCM16 单声道自适应噪声门。
 *
 * 每 10 ms 估算一次 RMS 与噪声底，按噪声底以上的余量计算软门限增益，
 * 逐样本乘到真正送往 ASR 的 PCM 上。它不是频谱 UI 的显示滤波。
 * Android NoiseSuppressor 若可用会先作用于 AudioRecord 会话，本类再做温和门限。
 */
internal class PcmNoiseSuppressor {
    private var noiseFloorDb = -48.0
    private var gain = 1.0

    fun reset() {
        noiseFloorDb = -48.0
        gain = 1.0
    }

    fun process(input: ShortArray): ShortArray {
        if (input.isEmpty()) return input
        val output = ShortArray(input.size)
        var frameStart = 0
        while (frameStart < input.size) {
            val frameEnd = minOf(input.size, frameStart + FRAME_SAMPLES)
            var power = 0.0
            for (i in frameStart until frameEnd) {
                val normalized = input[i].toDouble() / 32768.0
                power += normalized * normalized
            }
            val rms = sqrt(power / (frameEnd - frameStart).coerceAtLeast(1))
            val levelDb = if (rms > 1e-9) 20.0 * ln(rms) / LN_10 else -100.0

            // 仅在接近当前底噪的帧更新估计，避免把正常语音峰值学成噪声。
            val floorRate = if (levelDb < noiseFloorDb + 12.0) 0.10 else 0.001
            noiseFloorDb = (noiseFloorDb + (levelDb - noiseFloorDb) * floorRate)
                .coerceIn(-78.0, -24.0)

            val margin = levelDb - noiseFloorDb
            val targetGain = when {
                margin <= GATE_START_DB -> 0.16
                margin >= GATE_FULL_DB -> 1.0
                else -> 0.16 + (margin - GATE_START_DB) /
                    (GATE_FULL_DB - GATE_START_DB) * 0.84
            }

            // 开门快、关门慢，降低门限抽吸感并尽量保留弱音节尾音。
            val smoothing = if (targetGain > gain) 0.42 else 0.12
            for (i in frameStart until frameEnd) {
                gain += (targetGain - gain) * smoothing
                output[i] = (input[i] * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            frameStart = frameEnd
        }
        return output
    }

    private companion object {
        const val FRAME_SAMPLES = 160 // 16 kHz × 10 ms
        const val GATE_START_DB = 5.0
        const val GATE_FULL_DB = 15.0
        const val LN_10 = 2.302585092994046
    }
}
