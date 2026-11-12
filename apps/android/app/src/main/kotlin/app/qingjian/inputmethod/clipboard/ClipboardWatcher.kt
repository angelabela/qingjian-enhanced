package app.qingjian.inputmethod.clipboard

import app.qingjian.inputmethod.clipboard.sync.ClipboardSync
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import app.qingjian.inputmethod.core.store.ClipboardStore
import app.qingjian.inputmethod.core.store.ClipItem
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
        manager?.addPrimaryClipChangedListener(listener)
    }

    fun stop() {
        manager?.removePrimaryClipChangedListener(listener)
    }

    /** 读取系统当前剪贴板：预览独立于历史记录开关，键盘弹出时也能显示最新文字。 */
    fun capture() {
        val settings = Settings.load(context)
        val clip = runCatching { manager?.primaryClip }.getOrNull() ?: return
        if (clip.itemCount == 0 || sensitive(clip.description)) return
        val item = clip.getItemAt(0)
        val uri = item.uri
        val mime = uri?.let { runCatching { context.contentResolver.getType(it) }.getOrNull() }
        val firstRecord = settings.recordClipboard && firstSeen(clip.description.timestamp)
        if (uri != null && mime?.startsWith("image/") == true) {
            if (!firstRecord) return
            thread(name = "qingjian-clipboard-image") {
                val bytes = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val buffer = input.readNBytesCompat(MAX_IMAGE_BYTES + 1)
                        buffer.takeIf { it.size <= MAX_IMAGE_BYTES }
                    }
                }.getOrNull() ?: return@thread
                val saved = ClipboardStore.addImage(context, bytes, ClipboardImageProvider.extensionOf(mime), settings.clipboardLimit)
                ClipboardSync.changed()
                main.post { saved.firstOrNull()?.let { onChanged?.invoke(it) } }
            }
            return
        }
        val text = item.text?.toString() ?: return
        // 历史记录按设置保存；临时预览不依赖历史记录，也不依赖是否刚刚记录过。
        if (firstRecord && text != ClipboardSync.lastReceived) {
            ClipboardStore.add(context, text, settings.clipboardLimit)
            ClipboardSync.changed()
        }
        onChanged?.invoke(ClipItem(text, null))
    }

    /** 最新剪贴板内容变化时回调；文字预览不依赖历史记录开关。 */
    var onChanged: ((ClipItem) -> Unit)? = null

    /** 时间戳记在磁盘上：输入法进程会被杀，重起后系统剪贴板里还是那一份。 */
    private fun firstSeen(timestamp: Long): Boolean {
        val prefs = context.getSharedPreferences("clipboard", Context.MODE_PRIVATE)
        if (prefs.getLong(LAST_SEEN, 0L) == timestamp) return false
        prefs.edit().putLong(LAST_SEEN, timestamp).apply()
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
        const val LAST_SEEN = "last_seen_timestamp"
    }
}
