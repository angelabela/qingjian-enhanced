package app.qingjian.inputmethod.settings.page

import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.Toast
import app.qingjian.inputmethod.R
import app.qingjian.inputmethod.core.Engine
import java.io.File
import java.io.RandomAccessFile
import kotlin.concurrent.thread

/** 可选知意三元语言模型：用户导入本地 .ngram，不随 APK 打包。 */
class TrigramModelActivity : PageActivity() {
    override val title: Int = R.string.page_dictionaries

    private val modelFile get() = File(Engine.userDir(this), "models/zhiyi-ngram/zh.ngram")
    private val enabledFile get() = File(modelFile.parentFile, ".enabled")
    private var busy = false

    override fun content(): List<View> {
        val exists = modelFile.isFile && modelFile.length() > 0
        val enabled = exists && enabledFile.isFile
        return listOf(
            spaced(ui.group(
                "知意三元语言模型",
                ui.row("模型文件", if (exists) "已导入 · ${modelFile.length() / (1024 * 1024)} MB" else "尚未导入"),
                ui.row("运行状态", if (enabled) "已启用（重新装配引擎后生效）" else "未启用"),
                ui.row("模型位置", "应用私有目录 / models/zhiyi-ngram/zh.ngram"),
                footer = "模型由用户自行选择导入，不会打包进 APK。启用后用于整句词图与候选上下文重排；模型缺失、格式错误或加载失败时自动回退原有二元模型。",
            ), 12),
            spaced(ui.group(
                "模型管理",
                ui.link(if (busy) "正在导入…" else if (exists) "重新导入模型文件" else "选择 .ngram 模型文件", null) {
                    if (!busy) pickModel()
                },
                ui.toggle("启用三元模型", "关闭后仅使用原有二元语言模型", enabled, null) { on ->
                    if (!exists) {
                        Toast.makeText(this, "请先导入模型文件", Toast.LENGTH_SHORT).show()
                        rebuild()
                    } else {
                        if (on) enabledFile.writeText("enabled") else enabledFile.delete()
                        Engine.reloadAll()
                        rebuild()
                        Toast.makeText(this, if (on) "已启用，正在重新加载模型" else "已停用，正在回退二元模型", Toast.LENGTH_LONG).show()
                    }
                },
                ui.link("删除已导入模型", null) {
                    if (exists && !busy) {
                        modelFile.delete()
                        enabledFile.delete()
                        Engine.reloadAll()
                        rebuild()
                        Toast.makeText(this, "模型已删除，已回退二元模型", Toast.LENGTH_SHORT).show()
                    }
                },
            ), 12),
        )
    }

    private fun pickModel() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        startActivityForResult(intent, PICK_MODEL)
    }

    @Deprecated("Activity result compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_MODEL || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runImport(uri)
    }

    private fun runImport(uri: Uri) {
        busy = true
        rebuild()
        thread(name = "qingjian-trigram-import") {
            val result = runCatching {
                val dir = modelFile.parentFile!!.apply { mkdirs() }
                val staged = File(dir, "zh.ngram.importing")
                contentResolver.openInputStream(uri)?.use { input ->
                    staged.outputStream().use { output -> input.copyTo(output) }
                } ?: error("无法读取所选文件")
                try {
                    validateNgram(staged)
                    if (modelFile.exists() && !modelFile.delete()) error("无法替换旧模型")
                    if (!staged.renameTo(modelFile)) error("无法将模型写入应用私有目录")
                    enabledFile.writeText("enabled")
                    "模型文件校验通过，已导入并启用"
                } finally {
                    staged.delete()
                }
            }.getOrElse { "导入失败：${it.message ?: "文件无效"}" }
            runOnUiThread {
                busy = false
                if (!isDestroyed) {
                    if (result.startsWith("模型文件校验通过")) Engine.reloadAll()
                    rebuild()
                    Toast.makeText(this, result, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** 先做文件头、计数、各段边界及分段终点校验；Rust TrigramModel::open 再做运行时完整校验。 */
    private fun validateNgram(file: File) {
        require(file.length() in 24L..(512L * 1024L * 1024L)) { "文件大小不在有效范围" }
        RandomAccessFile(file, "r").use { raf ->
            val magic = ByteArray(8).also(raf::readFully)
            require(magic.contentEquals("ZYNGRAM1".toByteArray(Charsets.US_ASCII))) { "模型魔数不正确，并非知意 .ngram 文件" }
            val n1 = raf.readIntLE()
            val n2 = raf.readIntLE()
            val n3 = raf.readIntLE()
            val reserved = raf.readIntLE()
            require(n1 in 1..20_000_000 && n2 in 0..100_000_000 && n3 in 0..200_000_000) { "模型计数异常" }
            require(reserved == 0) { "模型文件头版本不受支持" }
            var cursor = 24L
            fun take(bytes: Long): Long {
                cursor = (cursor + 7L) and -8L
                val start = cursor
                cursor = Math.addExact(cursor, bytes)
                require(cursor <= raf.length()) { "模型数据不完整或已损坏" }
                return start
            }
            val wordOff = take((n1.toLong() + 1) * 4)
            raf.seek(wordOff + n1.toLong() * 4)
            val wordsLength = raf.readIntLE()
            require(wordsLength >= 0)
            val words = take(wordsLength.toLong())
            val p1 = take(n1.toLong() * 2)
            val b1 = take(n1.toLong() * 2)
            val biStart = take((n1.toLong() + 1) * 4)
            take(n2.toLong() * 4)
            take(n2.toLong() * 2)
            take(n2.toLong() * 2)
            val triStart = take((n2.toLong() + 1) * 4)
            take(n3.toLong() * 4)
            take(n3.toLong() * 2)
            raf.seek(biStart + n1.toLong() * 4)
            require(raf.readIntLE() == n2) { "二元分段索引不一致" }
            raf.seek(triStart + n2.toLong() * 4)
            require(raf.readIntLE() == n3) { "三元分段索引不一致" }
            raf.seek(wordOff)
            var previous = 0
            repeat(n1 + 1) {
                val offset = raf.readIntLE()
                require(offset >= previous && offset <= wordsLength) { "词表偏移越界" }
                previous = offset
            }
            require(previous == wordsLength) { "词表长度不一致" }
            require(p1 < raf.length() && b1 < raf.length() && words <= raf.length()) { "模型区段越界" }
        }
    }

    private fun RandomAccessFile.readIntLE(): Int {
        val b0 = readUnsignedByte()
        val b1 = readUnsignedByte()
        val b2 = readUnsignedByte()
        val b3 = readUnsignedByte()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private companion object {
        const val PICK_MODEL = 731
    }
}
