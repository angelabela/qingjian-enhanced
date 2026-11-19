package app.qingjian.inputmethod.core

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import app.qingjian.inputmethod.cloud.CloudConfig
import app.qingjian.inputmethod.core.frame.Frame
import app.qingjian.inputmethod.core.strip.StripImage
import app.qingjian.inputmethod.voice.VoiceConfig
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.json.JSONObject

/**
 * Rust Engine 的句柄。解随包数据和装配都慢（首次要解几十 MB），放后台线程；
 * 没装好之前 [ready] 为 false，壳把按键原样交给应用。
 */
class Engine(context: Context, private val onReady: () -> Unit) {
    private val app = context.applicationContext
    private var handle = 0L
    private var closed = false
    private val density = context.resources.displayMetrics.density

    /** 交给 Rust 的云端配置的摘要；0 是没接（新装配的引擎也是没接）。 */
    private var cloudSignature = 0

    /** 交给 Rust 的补充译词配置的摘要；0 是关着（新装配的引擎也是关着）。 */
    private var glossSignature = 0

    /** 交给 Rust 的语音识别配置的摘要；0 是没配（新装配的引擎也是）。 */
    private var voiceSignature = 0

    /** 第几次装配；卸下或重装时加一，晚到的旧装配结果直接销毁。 */
    private var generation = 0

    /** 候选条的像素中转与位图，只增不减地复用：每次敲键都画一帧，不能每次新开几 MB。 */
    private var stripBuffer: ByteBuffer? = null
    private var stripBitmap: Bitmap? = null

    /** 候选条的主题与字号档；引擎（重新）装好时照这份交过去。 */
    private var appearance: Triple<String, Float, Int>? = null

    val ready: Boolean get() = handle != 0L

    init {
        live += this
        load()
    }

    private fun load() {
        val main = Handler(Looper.getMainLooper())
        val loading = ++generation
        thread(name = "qingjian-engine-load") {
            val dataDir = BundledData.ensure(app)
            val created = Native.create(dataDir.path, userDir(app).path)
            main.post {
                if (closed || loading != generation) {
                    Native.destroy(created)
                } else {
                    handle = created
                    glossSignature = 0
                    voiceSignature = 0
                    cloudSignature = 0
                    cloudConnected = false
                    predicting = false
                    appearance?.let { (theme, scale, weight) -> if (ready) Native.setAppearance(handle, theme, scale, weight) }
                    if (ready) onReady()
                }
            }
        }
    }

    /** 落盘并销毁 Rust 侧，装配到一半的作废；之后 [load] 重来。 */
    private fun unload() {
        generation++
        if (ready) Native.destroy(handle)
        handle = 0
    }

    fun push(codePoint: Int): Frame = Frame.parse(Native.push(handle, codePoint))

    fun backspace(): Frame = Frame.parse(Native.backspace(handle))

    /** 滑完一笔：解出的字母进组句，同一个个点出来。 */
    fun swipe(input: String): Frame = Frame.parse(Native.swipe(handle, input))

    fun decodeSwipe(input: String): String = if (ready) Native.decodeSwipe(handle, input).orEmpty() else ""

    fun select(index: Int): Frame = Frame.parse(Native.select(handle, index))

    fun toggleSingleChars(): Frame = Frame.parse(Native.toggleSingleChars(handle))

    fun moveCaret(index: Int): Frame = Frame.parse(Native.moveCaret(handle, index))

    fun selectTranslation(index: Int, sense: Int): Frame =
        Frame.parse(Native.selectTranslation(handle, index, sense))

    fun forget(index: Int): Frame = Frame.parse(Native.forget(handle, index))

    fun commitRaw(): Frame = Frame.parse(Native.commitRaw(handle))

    fun clear(): Frame = Frame.parse(Native.clear(handle))

    fun cleared(text: String): Frame = Frame.parse(Native.cleared(handle, text))

    fun hasOffer(): Boolean = ready && Native.hasOffer(handle)

    fun setNineKey(on: Boolean): Frame = Frame.parse(Native.setNineKey(handle, on))

    fun pin(alternative: String): Frame = Frame.parse(Native.pin(handle, alternative))

    fun setEnglishMode(on: Boolean): Frame = Frame.parse(Native.setEnglishMode(handle, on))

    fun setZhuyin(on: Boolean): Frame = Frame.parse(Native.setZhuyin(handle, on))

    fun setShuangpin(scheme: String, rawPreedit: Boolean): Frame = Frame.parse(Native.setShuangpin(handle, scheme, rawPreedit))

    fun setWubi(on: Boolean, mixed: Boolean): Frame = Frame.parse(Native.setWubi(handle, on, mixed))

    fun setOptions(options: InputOptions): Frame = Frame.parse(Native.setOptions(handle, options.toJson()))

    /** 中文标点键：Core 按设置转全角或原样；引擎没装好原样。 */
    fun punctuate(c: Char): String = (if (ready) Native.punctuate(handle, c) else null) ?: c.toString()

    /** 成对符号（#928）：见 [Native.pairPunctuation]；引擎没装好照常上屏。 */
    fun pairPunctuation(text: String, english: Boolean): String? =
        if (ready) Native.pairPunctuation(handle, text, english) else null

    /** 没在组句时的退格：刚补完一对时回这一对，见 [Native.pairBackspace]。 */
    fun pairBackspace(): String? = if (ready) Native.pairBackspace(handle) else null

    /**
     * 剪贴板智能拆分：普通中文交 Core 语言模型；网址、邮箱、电话号码按结构切分，
     * 分隔符仍作为独立块保留，所有块按原顺序拼回去与原文完全一致。
     */
    fun textPieces(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val result = mutableListOf<String>()
        var cursor = 0
        for (match in STRUCTURED_TEXT.findAll(text)) {
            if (match.range.first > cursor) result += nativeTextPieces(text.substring(cursor, match.range.first))
            result += structuredPieces(match.value)
            cursor = match.range.last + 1
        }
        if (cursor < text.length) result += nativeTextPieces(text.substring(cursor))
        return result
    }

    private fun nativeTextPieces(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val json = if (ready) Native.textPieces(handle, text) else null
        json ?: return text.map(Char::toString)
        val array = org.json.JSONArray(json)
        return List(array.length(), array::getString)
    }

    private fun structuredPieces(raw: String): List<String> {
        val core = raw.trimEnd { it in "，。！？；：、,.!?;:)" }
        if (core.length < raw.length) return structuredPieces(core) + nativeTextPieces(raw.substring(core.length))

        // 邮箱：用户名、@、域名分开，方便单独选择或复制局部。
        if ('@' in raw && !raw.startsWith("http", ignoreCase = true) && !raw.startsWith("www.", ignoreCase = true)) {
            val at = raw.indexOf('@')
            return listOf(raw.substring(0, at), "@", raw.substring(at + 1)).filter(String::isNotEmpty)
        }

        // 网址：协议、主机名作为完整语义块；路径与查询参数按分隔符切，不把域名拆成碎字符。
        if (raw.startsWith("http://", true) || raw.startsWith("https://", true) || raw.startsWith("www.", true)) {
            val schemeLength = when {
                raw.startsWith("https://", true) -> 8
                raw.startsWith("http://", true) -> 7
                else -> 0
            }
            val result = mutableListOf<String>()
            if (schemeLength > 0) result += raw.substring(0, schemeLength)
            val rest = raw.substring(schemeLength)
            val hostEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
            if (hostEnd > 0) result += rest.substring(0, hostEnd)
            if (hostEnd < rest.length) {
                val tail = rest.substring(hostEnd)
                val delimiter = Regex("([/?&#=])")
                var start = 0
                for (part in delimiter.findAll(tail)) {
                    if (part.range.first > start) result += tail.substring(start, part.range.first)
                    result += part.value
                    start = part.range.last + 1
                }
                if (start < tail.length) result += tail.substring(start)
            }
            return result.filter(String::isNotEmpty)
        }

        // 11 位手机号无分隔符时按 3-4-4 分组；已有空格/连字符则保留分隔符。
        val digits = raw.filter(Char::isDigit)
        val nationalDigits = if (digits.length == 13 && digits.startsWith("86")) digits.drop(2) else digits
        if (nationalDigits.length == 11 && nationalDigits.firstOrNull() == '1') {
            if (raw.all(Char::isDigit)) {
                val prefix = if (digits.length == 13) listOf(digits.take(2)) else emptyList()
                return prefix + listOf(nationalDigits.substring(0, 3), nationalDigits.substring(3, 7), nationalDigits.substring(7))
            }
            val result = mutableListOf<String>()
            val chunks = Regex("(\\d+|\\D+)")
            for (part in chunks.findAll(raw)) {
                val value = part.value
                if (value.all(Char::isDigit) && value.length > 4) {
                    var index = 0
                    while (index < value.length) {
                        val size = when {
                            index == 0 -> 3
                            value.length - index > 4 -> 4
                            else -> value.length - index
                        }
                        result += value.substring(index, index + size)
                        index += size
                    }
                } else result += value
            }
            return result
        }
        if (digits.startsWith("0") && digits.length in 10..12) {
            val areaLength = if (digits.getOrNull(1)?.let { it in '3'..'9' } == true) 4 else 3
            if (raw.all(Char::isDigit)) {
                val area = digits.take(areaLength)
                val subscriber = digits.drop(areaLength)
                return listOf(area) + subscriber.chunked(4)
            }
            val result = mutableListOf<String>()
            var areaRemaining = areaLength
            for (part in Regex("(\\d+|\\D+)").findAll(raw)) {
                val value = part.value
                if (!value.all(Char::isDigit)) {
                    result += value
                    continue
                }
                var digitsPart = value
                if (areaRemaining > 0) {
                    val take = minOf(areaRemaining, digitsPart.length)
                    if (take > 0) result += digitsPart.take(take)
                    digitsPart = digitsPart.drop(take)
                    areaRemaining -= take
                }
                if (digitsPart.isNotEmpty()) result += digitsPart.chunked(4)
            }
            return result
        }
        return nativeTextPieces(raw)
    }

    fun reloadPhrases(userDir: String) {
        if (ready) Native.reloadPhrases(handle, userDir)
    }

    /**
     * 按配置接上云端（[predict] 时云联想与整句补全一起开），null 或没配好就断开；返回能不能翻译。
     * 同一份配置不重交（Rust 侧每接一次都另起后台线程），只记摘要，不留密钥明文。
     */
    fun setCloud(config: CloudConfig?, predict: Boolean): Boolean {
        if (!ready) return false
        val json = config?.takeIf { it.ready }?.toJson(predict)
        val signature = json?.hashCode() ?: 0
        if (signature != cloudSignature) {
            cloudSignature = signature
            cloudConnected = Native.setCloud(handle, json)
            predicting = cloudConnected && predict
        }
        return cloudConnected
    }

    /** 云端接着（能翻译）。 */
    var cloudConnected = false
        private set

    /** 云联想开着：组句时壳要轮询结果（[pollPrediction]）。 */
    var predicting = false
        private set

    /** 这段组句的光标前后文，云联想用。 */
    fun setSurrounding(before: String, after: String) {
        if (predicting) Native.setSurrounding(handle, before, after)
    }

    /** 云联想的结果插进候选后的一帧；没有新结果为 null。 */
    fun pollPrediction(): Frame? =
        if (predicting) Native.pollPrediction(handle)?.let(Frame::parse) else null

    /**
     * 释义兜底开或关（[config] 为 null 是关）。同一份配置不重交：每交一次 Rust 侧都另起一条后台线程。
     * 记的是配置的摘要，不留密钥明文。
     */
    fun setGlossFill(config: CloudConfig?) {
        val json = config?.takeIf { it.ready }?.toJson()
        val signature = json?.hashCode() ?: 0
        if (!ready || signature == glossSignature) return
        glossSignature = signature
        Native.setGlossFill(handle, json)
    }

    fun translate(text: String): Boolean = ready && Native.translate(handle, text)

    fun pollTranslation(): String? = if (ready) Native.pollTranslation(handle) else null

    fun setMetered(metered: Boolean) {
        if (ready) Native.setMetered(handle, metered)
    }

    fun setPrivate(private: Boolean) {
        if (ready) Native.setPrivate(handle, private)
    }

    /** 交通讯录（见 [Native.setContacts]）；引擎还没装好返回 -1。 */
    fun setContacts(contacts: String, details: Boolean): Int =
        if (ready) Native.setContacts(handle, contacts, details) else -1

    fun cancelTranslation() {
        if (ready) Native.cancelTranslation(handle)
    }

    fun setJapanese(on: Boolean): Frame = Frame.parse(Native.setJapanese(handle, on))

    fun setTraditional(on: Boolean): Frame = Frame.parse(Native.setTraditional(handle, on))

    fun space(): Frame = Frame.parse(Native.space(handle))

    /** 光标前是「用户名@」时，帧的候选是常见邮箱域名，选中上屏剩下的那截；别的情况是空帧。 */
    fun complete(beforeCursor: String): Frame = Frame.parse(Native.complete(handle, beforeCursor))

    /** 算式里按了 [c]：要交给 [push] 的字符（× ÷ 换成 Core 认的 * /）；不是算式字符为 null。按 Core 的规则，不要句柄。 */
    fun expressionChar(c: Char): Char? = Native.expressionChar(c).takeIf { it != '\u0000' }

    /** 光标前最后一个字是 [c] 时可能是一道算式的结尾（数字、右括号、等号），才值得 [complete]。 */
    fun mayEndCalculation(c: Char): Boolean = Native.mayEndCalculation(c)

    /**
     * 画最新一帧的候选条。返回的位图下次调用时会被改写（复用），只在这一帧里用；没装好或画不出来为 null。
     */
    fun renderStrip(highlighted: Int, dark: Boolean, visibleWidth: Int): StripImage? {
        if (!ready) return null
        val meta = Native.renderStrip(handle, highlighted, density, dark, visibleWidth)?.let(::JSONObject) ?: return null
        val width = meta.getInt("width")
        val height = meta.getInt("height")
        if (width <= 0 || height <= 0) return null
        // 先用 Long 计算缓冲区大小，防止乘法溢出后分配错误尺寸。
        val byteCount = width.toLong() * height.toLong() * 4L
        if (byteCount <= 0L || byteCount > Int.MAX_VALUE) return null
        val bytes = byteCount.toInt()
        val buffer = stripBuffer?.takeIf { it.capacity() >= bytes }
            ?: ByteBuffer.allocateDirect(bytes).also { stripBuffer = it }
        if (!Native.copyStrip(handle, buffer)) return null
        buffer.position(0).limit(bytes)
        val bitmap = stripBitmap?.takeIf { it.allocationByteCount >= bytes }
            ?.apply { if (this.width != width || this.height != height) reconfigure(width, height, Bitmap.Config.ARGB_8888) }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { stripBitmap = it }
        bitmap.copyPixelsFromBuffer(buffer)
        buffer.clear()
        return StripImage.parse(meta, bitmap)
    }

    /** 候选条换主题与字号档（[textScale] 是候选字与译文一起的倍数）；没装好时记着，装好再交。 */
    fun setAppearance(theme: String, textScale: Float, candidateWeight: Int = 400) {
        appearance = Triple(theme, textScale, candidateWeight.coerceIn(100, 1000))
        if (ready) Native.setAppearance(handle, theme, textScale, candidateWeight.coerceIn(100, 1000))
    }

    /** 识别出的一段文字上屏：Core 转繁体、记输入历史与统计。 */
    fun commitVoice(text: String): Frame = Frame.parse(Native.commitVoice(handle, text))

    /**
     * 按「语音识别」设置换识别方，null 或没配好是不用。同一份配置不重交，只记摘要，不留密钥明文。
     */
    fun setVoice(config: VoiceConfig?) {
        val json = config?.takeIf { it.ready }?.toJson()
        val signature = json?.hashCode() ?: 0
        if (!ready || signature == voiceSignature) return
        voiceSignature = signature
        Native.setVoice(handle, json)
    }

    /** Rust 侧有能用的识别方（云端、离线模型）：「语音识别」里配好了。 */
    val voiceAvailable: Boolean get() = ready && Native.voiceAvailable(handle)

    fun voiceStart(sampleRate: Int) {
        if (ready) Native.voiceStart(handle, sampleRate)
    }

    fun voiceFeed(samples: ShortArray) {
        if (ready) Native.voiceFeed(handle, samples)
    }

    fun voiceFinish() {
        if (ready) Native.voiceFinish(handle)
    }

    fun voiceCancel() {
        if (ready) Native.voiceCancel(handle)
    }

    /** 识别的一件事：（partial / final / error，文字）；没有为 null。 */
    fun pollVoice(): Pair<String, String>? {
        val json = (if (ready) Native.pollVoice(handle) else null)?.let(::JSONObject) ?: return null
        return json.getString("kind") to json.getString("text")
    }

    fun reset() {
        if (ready) Native.reset(handle)
    }

    fun flushLearning() {
        if (ready) Native.flushLearning(handle)
    }

    fun close() {
        closed = true
        live -= this
        if (ready) Native.destroy(handle)
        handle = 0
    }

    companion object {
        private val STRUCTURED_TEXT = Regex(
            """(?i)(?:https?://[^\s<>()]+|www\.[^\s<>()]+|[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}|(?<![A-Z0-9])(?:\+?86[- ]?)?1[3-9]\d{2}[- ]?\d{4}[- ]?\d{4}(?!\d)|(?<!\d)0\d{2,3}[- ]?\d{7,8}(?!\d))"""
        )
        /** 进程里开着的引擎（输入法服务那一个；App 与输入法同进程）。只在主线程用。 */
        private val live = mutableSetOf<Engine>()

        fun userDir(context: Context) = File(context.filesDir, "user").apply { mkdirs() }

        /** 导出前把开着的引擎里还没落盘的学习数据写下（组句一并丢掉，导出时用户在 App 里）。主线程调。 */
        fun flushAll() = live.forEach(Engine::reset)

        /**
         * 清空学习数据，返回删掉的文件数（-1 是出错）。引擎内存里的学习数据下次落盘会把删掉的写回去，
         * 所以先落盘卸下开着的引擎、再删、再重新装配（装好之前按键原样上屏）。主线程调。
         */
        fun clearLearning(context: Context, habits: Boolean, vocabulary: Boolean): Int {
            live.forEach(Engine::unload)
            val removed = runCatching { Native.clearLearning(userDir(context).path, habits, vocabulary) }.getOrDefault(-1)
            live.forEach(Engine::load)
            return removed
        }

        /** 导出数据要打包的文件：(压缩包里的名字, 文件)。 */
        fun learningFiles(context: Context): List<Pair<String, File>> {
            val json = runCatching { Native.learningFiles(userDir(context).path) }.getOrNull() ?: return emptyList()
            val array = org.json.JSONArray(json)
            return List(array.length()) { i ->
                array.getJSONObject(i).let { it.getString("name") to File(it.getString("path")) }
            }
        }

        /**
         * 恢复完整用户数据目录。先卸下所有正在使用的引擎，再原子替换 user 目录，
         * 最后重新装配，避免内存中的旧学习数据覆盖刚恢复的文件。
         */
        fun replaceUserData(context: Context, staged: File): Boolean {
            // Engine 句柄只允许主线程操作；ZIP 解压可在后台完成，目录切换在主线程短暂执行。
            if (Looper.myLooper() != Looper.getMainLooper()) {
                val latch = CountDownLatch(1)
                var restored = false
                Handler(Looper.getMainLooper()).post {
                    restored = replaceUserData(context, staged)
                    latch.countDown()
                }
                return runCatching { latch.await(30, TimeUnit.SECONDS) && restored }.getOrDefault(false)
            }
            val engines = live.toList()
            engines.forEach(Engine::unload)
            val target = File(context.filesDir, "user")
            val backup = File(context.filesDir, "user-before-restore-${System.currentTimeMillis()}")
            var oldMoved = false
            return try {
                if (!staged.isDirectory || staged.listFiles() == null) error("恢复暂存目录无效")
                if (target.exists()) {
                    if (!target.renameTo(backup)) error("无法备份当前用户数据")
                    oldMoved = true
                }
                if (!staged.renameTo(target)) error("无法写入恢复数据")
                if (!target.isDirectory) error("恢复后的用户数据目录校验失败")
                if (oldMoved) backup.deleteRecursively()
                true
            } catch (_: Exception) {
                if (target.exists() && oldMoved) target.deleteRecursively()
                if (oldMoved && backup.exists()) backup.renameTo(target)
                false
            } finally {
                engines.forEach(Engine::load)
            }
        }
    }
}
