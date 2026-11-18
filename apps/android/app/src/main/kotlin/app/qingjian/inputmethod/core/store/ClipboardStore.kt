package app.qingjian.inputmethod.core.store

import android.content.Context
import app.qingjian.inputmethod.core.Native
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * 剪贴板历史与常用语的读写，都在 Rust 侧（`qingjian_learning` 的 `ClipboardHistory` / `PhraseBook`），这里只拼请求、解结果。
 * 不要引擎句柄，App 与输入法都能调；出错返回空的。
 */
object ClipboardStore {
    /**
     * 返回 Rust 与 Kotlin 共用的私有数据目录。不能忽略 mkdirs() 的失败：
     * 否则 JNI 会继续把一个不存在/不可写的目录交给 Rust，最后只显示笼统的“存储不可用”。
     */
    fun userDir(context: Context): String {
        val dir = File(context.filesDir, "user")
        if (!dir.exists() && !dir.mkdirs()) {
            throw java.io.IOException("无法创建应用数据目录：${dir.absolutePath}")
        }
        if (!dir.isDirectory) {
            throw java.io.IOException("应用数据路径不是目录：${dir.absolutePath}")
        }
        val probe = try {
            File.createTempFile(".qingjian-storage-", ".check", dir)
        } catch (error: Exception) {
            throw java.io.IOException("应用数据目录不可写：${dir.absolutePath}；${error.message}", error)
        }
        if (!probe.delete()) {
            probe.deleteOnExit()
            throw java.io.IOException("应用数据目录写入测试成功，但临时文件无法删除：${probe.absolutePath}")
        }
        return dir.absolutePath
    }

    fun list(context: Context, limit: Int) = clipboard(context, JSONObject().put("op", "list"), limit)

    fun add(context: Context, text: String, limit: Int) =
        clipboard(context, JSONObject().put("op", "add").put("text", text), limit)

    fun addImage(context: Context, bytes: ByteArray, extension: String, limit: Int): List<ClipItem> =
        parseClips(runCatching { Native.clipboardAddImage(userDir(context), bytes, extension, limit) }.getOrNull())

    fun remove(context: Context, index: Int, limit: Int) =
        clipboard(context, JSONObject().put("op", "remove").put("index", index), limit)

    /** 把第 [index] 条（原文 [from]，编辑期间被挤开了就按原文找）改成 [text]。 */
    fun edit(context: Context, index: Int, from: String, text: String, limit: Int) =
        clipboard(context, JSONObject().put("op", "edit").put("index", index).put("from", from).put("text", text), limit)

    fun clear(context: Context, limit: Int) = clipboard(context, JSONObject().put("op", "clear"), limit)

    private fun clipboard(context: Context, request: JSONObject, limit: Int): List<ClipItem> =
        parseClips(runCatching { Native.clipboard(userDir(context), request.toString(), limit) }.getOrNull())

    private fun parseClips(json: String?): List<ClipItem> {
        json ?: return emptyList()
        val array = JSONArray(json)
        return List(array.length()) { i ->
            val item = array.getJSONObject(i)
            ClipItem(item.optString("text"), item.optString("image").takeIf { !item.isNull("image") && it.isNotEmpty() })
        }
    }

    fun phrases(context: Context) = phrases(context, JSONObject().put("op", "list"))

    fun addPhrase(context: Context, text: String, group: String, code: String) =
        phrases(context, JSONObject().put("op", "add").put("text", text).put("group", group).put("code", code))

    fun updatePhrase(context: Context, index: Int, text: String, group: String, code: String) = phrases(
        context,
        JSONObject().put("op", "update").put("index", index).put("text", text).put("group", group).put("code", code),
    )

    fun removePhrase(context: Context, index: Int) = phrases(context, JSONObject().put("op", "remove").put("index", index))

    fun phraseToTop(context: Context, index: Int) = phrases(context, JSONObject().put("op", "top").put("index", index))

    private fun phrases(context: Context, request: JSONObject): PhrasePage {
        val json = try {
            val dir = userDir(context)
            Native.phrases(dir, request.toString()).also {
                if (it == null) android.util.Log.e("qingjian-phrases", "JNI 常用语接口返回 null；目录=$dir")
            }
        } catch (error: Exception) {
            android.util.Log.e("qingjian-phrases", "常用语存储目录检查失败", error)
            null
        } ?: return PhrasePage(listOf(PhraseRules.defaultGroup), emptyList(), operationSucceeded = false)
        val page = JSONObject(json)
        val groups = page.getJSONArray("groups")
        val phrases = page.getJSONArray("phrases")
        return PhrasePage(
            List(groups.length(), groups::getString),
            List(phrases.length()) { i ->
                val phrase = phrases.getJSONObject(i)
                PhraseItem(i, phrase.getString("text"), phrase.getString("group"), phrase.optString("code"))
            },
            page.optBoolean("operation_succeeded", true),
        )
    }
}
