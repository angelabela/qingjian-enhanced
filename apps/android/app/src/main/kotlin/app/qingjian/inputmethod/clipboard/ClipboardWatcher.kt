package app.qingjian.inputmethod.clipboard

import app.qingjian.inputmethod.clipboard.sync.ClipboardSync
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import app.qingjian.inputmethod.core.AppLog
import app.qingjian.inputmethod.core.store.ClipboardStore
import app.qingjian.inputmethod.settings.Settings
import kotlin.concurrent.thread

/**
 * 盯着系统剪贴板，复制了文字或图片就记进剪贴板历史。默认输入法读剪贴板不要权限（安卓 10 起只有它和前台应用能读）。
 * 标了敏感的（密码管理器复制的密码，安卓 13 的 `EXTRA_IS_SENSITIVE`）不记；设置里关了「记录剪贴板」也不记。
 * 图片读字节放后台线程，太大的（超过 [MAX_IMAGE_BYTES]）不记。
 * 历史记录按时间戳去重；临时预览独立于历史记录开关，键盘弹出时也提示系统当前内容。
 */
class ClipboardWatcher(private val context: Context) {
    private val manager = context.getSystemService(ClipboardManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val listener = ClipboardManager.OnPrimaryClipChangedListener { capture() }

    fun start() {
        val available = manager != null
        manager?.addPrimaryClipChangedListener(listener)
        AppLog.info("[CLIPBOARD_DIAG] watcher started managerAvailable=$available")
    }

    fun stop() {
        manager?.removePrimaryClipChangedListener(listener)
        AppLog.info("[CLIPBOARD_DIAG] watcher stopped")
    }

    /** 读取系统当前剪贴板：预览独立于历史记录开关，键盘弹出时也能显示最新文字。 */
    fun capture() {
        val settings = Settings.load(context)
        if (!settings.recordClipboard) {
            AppLog.debug("[CLIPBOARD_DIAG] capture skipped: history recording disabled")
            return
        }
        val clip = runCatching { manager?.primaryClip }
            .onFailure { AppLog.warn("[CLIPBOARD_DIAG] primaryClip read failed: ${it.javaClass.simpleName}") }
            .getOrNull() ?: run {
                AppLog.debug("[CLIPBOARD_DIAG] capture skipped: primaryClip unavailable")
                return
            }
        if (clip.itemCount == 0) {
            AppLog.debug("[CLIPBOARD_DIAG] capture skipped: clip has no items")
            return
        }
        if (sensitive(clip.description)) {
            AppLog.info("[CLIPBOARD_DIAG] capture skipped: system marked clip sensitive")
            return
        }
        val item = clip.getItemAt(0)
        val uri = item.uri
        val mime = uri?.let { runCatching { context.contentResolver.getType(it) }.getOrNull() }
        if (uri != null && mime?.startsWith("image/") == true) {
            val identity = "image:${mime}:${uri}:${clip.description.timestamp}"
            if (!firstSeen(identity)) return
            thread(name = "qingjian-clipboard-image") {
                val bytes = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val buffer = input.readNBytesCompat(MAX_IMAGE_BYTES + 1)
                        buffer.takeIf { it.size <= MAX_IMAGE_BYTES }
                    }
                }.getOrNull() ?: return@thread
                ClipboardStore.addImage(context, bytes, ClipboardImageProvider.extensionOf(mime), settings.clipboardLimit)
                main.post { ClipboardSync.changed() }
            }
            return
        }
        // 某些应用复制的是 URI/Intent：text 为空但系统仍可转换成可粘贴文本。
        val text = runCatching { item.coerceToText(context)?.toString() }
            .onFailure { AppLog.warn("[CLIPBOARD_DIAG] clip text conversion failed: ${it.javaClass.simpleName}") }
            .getOrNull() ?: run {
                AppLog.debug("[CLIPBOARD_DIAG] capture skipped: item has no convertible text")
                return
            }
        if (!firstSeen("text:$text")) {
            AppLog.debug("[CLIPBOARD_DIAG] duplicate text clip ignored length=${text.length}")
            return
        }
        if (text == ClipboardSync.lastReceived) {
            AppLog.debug("[CLIPBOARD_DIAG] clip ignored: identical to last synchronized item length=${text.length}")
            return
        }
        ClipboardStore.add(context, text, settings.clipboardLimit)
        AppLog.info("[CLIPBOARD_DIAG] text clip stored length=${text.length} timestamp=${clip.description.timestamp}")
        ClipboardSync.changed()
    }

    /**
     * 用内容指纹去重，而不是依赖部分国产 ROM 经常为 0 或不变化的 ClipDescription.timestamp。
     * 指纹持久化，输入法进程重启后仍能识别同一条剪贴板内容。
     */
    private fun firstSeen(identity: String): Boolean {
        val fingerprint = java.security.MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val prefs = context.getSharedPreferences("clipboard", Context.MODE_PRIVATE)
        if (prefs.getString(LAST_SEEN_FINGERPRINT, null) == fingerprint) return false
        prefs.edit().putString(LAST_SEEN_FINGERPRINT, fingerprint).apply()
        return true
    }

    private fun sensitive(description: ClipDescription): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (out.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 10 * 1024 * 1024
        const val LAST_SEEN_FINGERPRINT = "last_seen_fingerprint"
    }
}
