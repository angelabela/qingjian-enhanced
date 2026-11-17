package app.qingjian.inputmethod.voice

import android.content.Context
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

    val active: Boolean get() = recognizer != null || pendingCorrection

    /** 「语音识别」配好了就用 Rust 侧的识别方，否则是 [UnavailableRecognizer]。 */
    private fun pick(): VoiceRecognizer = when {
        engine.voiceAvailable -> EngineRecognizer(engine)
        else -> UnavailableRecognizer
    }

    /** 开始说话；[holding] 是按着空格说。返回开没开起来：不能用、要先申请权限都是 false。 */
    fun start(holding: Boolean): Boolean {
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
        val panel = panel?.takeIf { it.holding } ?: return
        panel.holding = false
        if (cancel) onCancel() else onFinish(act = false, hide = false)
    }

    override fun onUndo() { host.undoVoice() }

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
        generation += 1
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
        if (active) host.composeVoice(VoiceTextNormalizer.normalize(text))
    }

    override fun onFinal(text: String) {
        val then = after
        val requestGeneration = generation
        val completedPanel = panel
        recognizer = null
        after = After.NOTHING
        panel = completedPanel
        val original = VoiceTextNormalizer.normalize(text)
        if (original.isBlank()) {
            host.composeVoice("")
            return
        }

        // 先立即提交 ASR 原文，避免用户等待整段云端校正；校正随后尝试原位修订同一条撤销记录。
        // 若用户已移动光标、继续输入或切换输入框，宿主会因上下文不匹配而安全放弃修订。
        pendingCorrection = false
        host.commitVoice(original)
        if (then == After.ACTION) host.performVoiceAction()
        if (then == After.HIDE) host.hideKeyboard()

        host.correctVoiceText(original) { result ->
            if (generation != requestGeneration) return@correctVoiceText
            val corrected = result.getOrNull()
                ?.let(VoiceTextNormalizer::normalize)
                ?.takeIf { it.isNotBlank() && it.length <= maxOf(original.length * 2, original.length + 40) }
                ?: original
            if (result.isFailure) {
                android.util.Log.w("QingjianVoice", "Cloud voice correction unavailable; keeping immediate ASR text")
            } else if (corrected != original) {
                host.refineCommittedVoice(original, corrected)
            }
        }
        // 识别结束后保留面板，让用户能立即撤销/重做刚提交的整段语音。
        panel?.listening = false
        panel?.recognizing = false
    }

    override fun onError(message: String) {
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

    private fun end() {
        recognizer = null
        panel = null
        after = After.NOTHING
        host.closeVoicePanel()
    }
}
