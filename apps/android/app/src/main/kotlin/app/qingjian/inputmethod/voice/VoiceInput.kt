package app.qingjian.inputmethod.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.qingjian.inputmethod.R
import app.qingjian.inputmethod.core.Engine
import app.qingjian.inputmethod.panel.VoicePanel

/**
 * 语音输入的唯一入口：长按空格、工具栏话筒、全面屏底栏的语音键都走 [start]。
 * 挑识别方式、查录音权限、开关语音面板；听出来的字边说边以编辑中的文字放进输入框，说完经 Core 落定。
 * 识别本身在 [VoiceRecognizer] 的实现里。
 */
class VoiceInput(private val context: Context, private val engine: Engine, private val host: Host) :
    VoiceRecognizer.Listener, VoicePanel.Actions {
    interface Host {
        /** 盖上语音面板，点它的各块接回 [actions]；键盘没建起来时为 null。 */
        fun openVoicePanel(actions: VoicePanel.Actions): VoicePanel?

        fun closeVoicePanel()

        /** 开始说话前：组句落下，识别出的字接在后面。 */
        fun prepareVoice()

        /** 说话途中听出来的字：放进输入框、处在编辑中；空串撤掉。 */
        fun composeVoice(text: String)

        /** 最终结果上屏（经 Core），替换掉编辑中的那段。 */
        fun commitVoice(text: String)

        /** 执行输入框的回车动作（发送、搜索）；没有动作时什么都不做。 */
        fun performVoiceAction()

        fun hideKeyboard()

        fun toast(message: String)

        /** 密码、私密或直接输入框禁止将音频发送到云端。 */
        fun canStartVoice(): Boolean

        /** 语音识别文本的云端保守校正；失败时调用方回退到原始识别文本。 */
        fun correctVoiceText(text: String, callback: (Result<String>) -> Unit)

        /** 将异步校正应用到刚提交的语音事务；若光标上下文已变化则安全放弃。 */
        fun refineCommittedVoice(original: String, corrected: String): Boolean

        fun undoVoice(): Boolean
        fun redoVoice(): Boolean

        /** 还没选识别服务：提示去「语音识别」页配。 */
        fun voiceNeedsSetup()
    }

    /** 最终结果上屏之后还要做的事。 */
    private enum class After { NOTHING, ACTION, HIDE }

    private var recognizer: VoiceRecognizer? = null
    private var panel: VoicePanel? = null
    private var after = After.NOTHING
    /** 使输入框切换/取消后的迟到 AI 回复失效。 */
    private var generation = 0L
    private var pendingCorrection = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingPartialText: String? = null
    private var lastPartialText = ""
    private var lastPartialAt = 0L
    private val partialFlush = Runnable {
        val text = pendingPartialText ?: return@Runnable
        pendingPartialText = null
        if (recognizer != null) {
            runCatching { host.composeVoice(text) }
                .onFailure { android.util.Log.w("QingjianVoice", "Unable to update composing text", it) }
            lastPartialText = text
            lastPartialAt = SystemClock.uptimeMillis()
        }
    }

    val active: Boolean get() = recognizer != null || pendingCorrection

    /** 「语音识别」配好了就用 Rust 侧的识别方，否则是 [UnavailableRecognizer]。 */
    private fun pick(): VoiceRecognizer = when {
        engine.voiceAvailable -> EngineRecognizer(engine)
        else -> UnavailableRecognizer
    }

    /** 开始说话；[holding] 是按着空格说。返回开没开起来：不能用、要先申请权限都是 false。 */
    fun start(holding: Boolean): Boolean {
        android.util.Log.i("QingjianVoice", "start requested holding=$holding active=${active}")
        if (active) return true
        if (!host.canStartVoice()) {
            host.toast("当前输入框属于密码、私密或直接输入模式，已禁止云端语音输入")
            return false
        }
        generation += 1
        val picked = pick()
        if (!picked.available) {
            host.voiceNeedsSetup()
            return false
        }
        if (picked.needsMicrophone && !MicrophonePermissionActivity.granted(context)) {
            // 弹系统的权限框；给了就直接开始（按着空格的那次手指早抬起了，按点按算）
            MicrophonePermissionActivity.request(context) { granted ->
                if (granted) start(holding = false) else host.toast(context.getString(R.string.voice_no_permission))
            }
            return false
        }
        host.prepareVoice()
        val panel = try {
            host.openVoicePanel(this)
        } catch (error: Throwable) {
            android.util.Log.e("QingjianVoice", "Failed to create voice panel", error)
            host.toast("语音界面启动失败，请稍后重试")
            return false
        } ?: return false
        panel.holding = holding
        panel.listening = true
        this.panel = panel
        after = After.NOTHING
        recognizer = picked
        return try {
            picked.start(this)
            true
        } catch (error: Throwable) {
            android.util.Log.e("QingjianVoice", "Failed to start voice recognizer", error)
            runCatching { picked.cancel() }
            recognizer = null
            this.panel = null
            host.closeVoicePanel()
            host.toast("语音识别启动失败，请检查识别服务和麦克风权限")
            false
        }
    }

    /** 按着空格说时手指挪到了屏幕上的这一点。 */
    fun drag(screenX: Float, screenY: Float) {
        panel?.takeIf { it.holding }?.track(screenX, screenY)
    }

    /** 按着空格的手指抬起：松在「撤回输入」上撤回，松在右边那块上上屏并执行回车动作，别处只是说完。[cancel] 是手指被系统收走。 */
    fun release(cancel: Boolean) {
        val activePanel = panel?.takeIf { it.holding } ?: return
        val target = activePanel.target
        activePanel.holding = false
        if (cancel || target == VoicePanel.Target.CANCEL) {
            // 长按空格期间滑到「撤销」再松手：丢弃本次语音临时内容，不触碰此前已输入的文字。
            onCancel()
            return
        }
        if (target == VoicePanel.Target.ACTION) {
            // 滑到「完成」并松手，执行与点击完成按钮相同的收尾逻辑。
            onClose()
            return
        }
        // 空格按住期间的面板只在按压生命周期内存在：抬手立即关闭界面并停止采集，
        // 识别器完成收尾后仍可通过 onFinal 将最终文本提交到原输入框。
        activePanel.listening = false
        activePanel.recognizing = true
        panel = null
        host.closeVoicePanel()
        onFinish(act = false, hide = false)
    }

    override fun onUndo() {
        // 录音/实时转写尚未完成时，撤销整段本次语音；识别完成后才撤销上一条语音事务。
        if (recognizer != null || panel?.holding == true) onCancel() else host.undoVoice()
    }

    override fun onRedo() { host.redoVoice() }

    override fun onClose() {
        val activeRecognizer = recognizer ?: run {
            if (pendingCorrection) onCancel() else host.closeVoicePanel()
            return
        }
        after = After.NOTHING
        panel?.listening = false
        panel?.recognizing = false
        panel = null
        host.closeVoicePanel()
        runCatching { activeRecognizer.finish() }
            .onFailure {
                android.util.Log.e("QingjianVoice", "Failed to finish voice input", it)
                host.composeVoice("")
                end()
            }
    }

    override fun onFinish(act: Boolean, hide: Boolean) {
        android.util.Log.i("QingjianVoice", "finish requested act=$act hide=$hide recognizerActive=${recognizer != null}")
        val recognizer = recognizer ?: return
        after = when {
            act -> After.ACTION
            hide -> After.HIDE
            else -> After.NOTHING
        }
        panel?.listening = false
        panel?.recognizing = true
        recognizer.finish()
    }

    /** 不要这一段（撤回、返回键、换输入框、收起键盘）：编辑中的字撤掉。 */
    override fun onCancel() {
        android.util.Log.i("QingjianVoice", "cancel requested recognizerActive=${recognizer != null} pendingCorrection=$pendingCorrection")
        generation += 1
        mainHandler.removeCallbacks(partialFlush)
        pendingPartialText = null
        lastPartialText = ""
        lastPartialAt = 0L
        val activeRecognizer = recognizer
        val hadPendingVoice = activeRecognizer != null || pendingCorrection
        activeRecognizer?.cancel()
        pendingCorrection = false
        if (hadPendingVoice) host.composeVoice("")
        end()
    }

    /** 外面要停（返回键、换输入框、键盘重建）：同撤回。 */
    fun cancel() = onCancel()

    override fun onPartial(text: String) {
        if (!active) return
        val normalized = VoiceTextNormalizer.normalize(text)
        if (normalized.isBlank() || normalized == lastPartialText) return
        pendingPartialText = normalized
        val elapsed = SystemClock.uptimeMillis() - lastPartialAt
        val delay = (PARTIAL_UPDATE_INTERVAL_MS - elapsed).coerceAtLeast(0L)
        mainHandler.removeCallbacks(partialFlush)
        if (delay == 0L) partialFlush.run() else mainHandler.postDelayed(partialFlush, delay)
    }

    override fun onFinal(text: String) {
        mainHandler.removeCallbacks(partialFlush)
        pendingPartialText = null
        lastPartialText = ""
        lastPartialAt = 0L
        android.util.Log.i("QingjianVoice", "final result received length=${text.length}")
        val then = after
        val requestGeneration = generation
        val completedPanel = panel
        recognizer = null
        after = After.NOTHING
        panel = completedPanel
        val original = VoiceTextNormalizer.normalize(text)
        if (original.isBlank()) {
            android.util.Log.w("QingjianVoice", "Recognizer returned an empty final result; closing the completed voice session")
            runCatching { host.composeVoice("") }
            end()
            return
        }

        // 先立即提交 ASR 原文，避免用户等待整段云端校正；校正随后尝试原位修订同一条撤销记录。
        // 若用户已移动光标、继续输入或切换输入框，宿主会因上下文不匹配而安全放弃修订。
        pendingCorrection = false
        try {
            val commitStartedAt = SystemClock.elapsedRealtime()
            host.commitVoice(original)
            val commitElapsed = SystemClock.elapsedRealtime() - commitStartedAt
            android.util.Log.i("QingjianVoice", "final commit completed elapsedMs=$commitElapsed length=${original.length}")
            if (then == After.ACTION) host.performVoiceAction()
            if (then == After.HIDE) host.hideKeyboard()
        } catch (error: Throwable) {
            android.util.Log.e("QingjianVoice", "Failed to commit final voice result", error)
            runCatching { host.composeVoice("") }
            end()
            host.toast("语音结果提交失败，请重试")
            return
        }

        runCatching {
            host.correctVoiceText(original) { result ->
                if (generation != requestGeneration) return@correctVoiceText
                val corrected = result.getOrNull()
                    ?.let(VoiceTextNormalizer::normalize)
                    ?.takeIf { it.isNotBlank() && it.length <= maxOf(original.length * 2, original.length + 40) }
                    ?: original
                if (result.isFailure) {
                    android.util.Log.w("QingjianVoice", "Cloud voice correction unavailable; keeping immediate ASR text")
                } else if (corrected != original) {
                    runCatching { host.refineCommittedVoice(original, corrected) }
                        .onFailure { android.util.Log.w("QingjianVoice", "Unable to apply corrected voice text", it) }
                }
            }
        }.onFailure {
            android.util.Log.w("QingjianVoice", "Unable to start cloud voice correction; keeping ASR text", it)
        }
        // 识别结束后保留面板，让用户能立即撤销/重做刚提交的整段语音。
        panel?.listening = false
        panel?.recognizing = false
    }

    override fun onError(message: String) {
        android.util.Log.e("QingjianVoice", "recognition error: $message")
        host.composeVoice("")
        end()
        host.toast(message)
    }

    override fun onSpectrum(bands: FloatArray) {
        panel?.addSpectrum(bands)
    }

    override fun onLevel(level: Float) {
        panel?.addSpectrum(FloatArray(48) { level.coerceIn(0f, 1f) })
    }

    private companion object {
        const val PARTIAL_UPDATE_INTERVAL_MS = 180L
    }

    private fun end() {
        mainHandler.removeCallbacks(partialFlush)
        pendingPartialText = null
        lastPartialText = ""
        lastPartialAt = 0L
        recognizer = null
        panel = null
        after = After.NOTHING
        host.closeVoicePanel()
    }
}
