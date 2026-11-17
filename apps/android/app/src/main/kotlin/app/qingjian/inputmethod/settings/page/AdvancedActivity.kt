package app.qingjian.inputmethod.settings.page

import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.Toast
import app.qingjian.inputmethod.R
import app.qingjian.inputmethod.core.AppLog
import app.qingjian.inputmethod.core.Engine
import app.qingjian.inputmethod.settings.Settings
import java.io.BufferedInputStream
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * 高级页（PC 设置程序的「高级」改成手机的样子）：不露文件路径，有「导出数据」「清空学习数据」与日志（打包、详细日志）。
 * 导出与打包日志都用系统的「存到哪里」（SAF `ACTION_CREATE_DOCUMENT`）存一个 zip，数据打包哪些文件由 Rust 侧定，
 * 日志见 [LogPack]；清空问两次。
 * PC 的配置文件编辑与输入日志开关不做。
 */
class AdvancedActivity : PageActivity() {
    override val title = R.string.page_advanced

    /** 正在写压缩包（后台线程），行上显示「导出中…」。 */
    private var exporting = false
    private var restoring = false

    override fun content(): List<View> = listOf(
        ui.group(
            R.string.data_backup_section,
            ui.action(R.string.export_title, getString(if (exporting) R.string.export_running else R.string.export_value)) {
                if (!exporting && !restoring) pickTarget(CREATE_ZIP)
            },
            ui.action(R.string.restore_data_title, getString(if (restoring) R.string.restore_running else R.string.restore_data_value)) {
                if (!exporting && !restoring) pickSource(RESTORE_ZIP)
            },
            footer = R.string.data_backup_note,
        ),
        ui.group(
            R.string.settings_backup_section,
            ui.action(R.string.settings_backup_title, null) { if (!exporting && !restoring) pickTarget(BACKUP_SETTINGS) },
            ui.action(R.string.settings_restore_title, null) { if (!exporting && !restoring) pickSource(RESTORE_SETTINGS) },
            footer = R.string.settings_backup_note,
        ),
        ui.group(
            null,
            ui.action(R.string.clear_learning_title, null, ui.colors.danger) { chooseParts() },
            footer = R.string.clear_learning_note,
        ),
        ui.group(
            R.string.logs_section,
            ui.action(R.string.logs_export_title, null) { chooseLogTarget() },
            ui.toggle(R.string.logs_verbose_title, R.string.logs_verbose_note, AppLog.verbose(this)) {
                AppLog.setVerbose(this, it)
            },
            footer = R.string.logs_export_note,
        ),
    )

    private fun pickTarget(request: Int) {
        val name = when (request) {
            PACK_LOGS -> "qingjian-logs"
            BACKUP_SETTINGS -> "qingjian-settings"
            else -> "qingjian-data"
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_TITLE, "$name-${LocalDate.now()}.zip")
        startActivityForResult(intent, request)
    }

    private fun pickSource(request: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/zip")
        startActivityForResult(intent, request)
    }

    @Deprecated("Activity 的老接口，这页没有别的结果要收")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val target = data?.data
        if (resultCode != RESULT_OK || target == null) return
        when (requestCode) {
            PACK_LOGS -> { packLogs(target); return }
            CREATE_ZIP -> {
                Engine.flushAll()
                runBackground("qingjian-export", { writeUserDataZip(target) }) { count ->
                    getString(R.string.export_done, count)
                }
            }
            BACKUP_SETTINGS -> runBackground("qingjian-settings-backup", { writeSettingsZip(target) }) { count ->
                getString(R.string.settings_backup_done, count)
            }
            RESTORE_ZIP -> confirmRestore(target, settings = false)
            RESTORE_SETTINGS -> confirmRestore(target, settings = true)
        }
    }

    private fun confirmRestore(source: Uri, settings: Boolean) {
        AlertDialog.Builder(this)
            .setTitle(if (settings) R.string.settings_restore_title else R.string.restore_data_title)
            .setMessage(if (settings) R.string.settings_restore_confirm else R.string.restore_data_confirm)
            .setPositiveButton(R.string.restore_confirm) { _, _ ->
                if (settings) {
                    runBackground("qingjian-settings-restore", { restoreSettings(source) }) { count ->
                        getString(R.string.settings_restore_done, count)
                    }
                } else {
                    runBackground("qingjian-data-restore", { restoreUserData(source) }) { count ->
                        getString(R.string.restore_data_done, count)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 发出去排第一：手机上存下来的文件多数人找不到，反馈又多在 QQ 群里。 */
    private fun chooseLogTarget() {
        AlertDialog.Builder(this)
            .setTitle(R.string.logs_export_title)
            .setItems(arrayOf(getString(R.string.logs_send), getString(R.string.logs_save))) { _, which ->
                if (which == 0) shareLogs() else pickTarget(PACK_LOGS)
            }
            .show()
    }

    private fun shareLogs() {
        thread(name = "qingjian-logs") {
            val file = runCatching { LogPack.forSharing(this, "qingjian-logs-${LocalDate.now()}.zip") }
                .onFailure { AppLog.error("log export failed: ${android.util.Log.getStackTraceString(it)}") }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val zip = file.getOrNull()
                when {
                    file.isFailure -> Toast.makeText(this, R.string.export_failed, Toast.LENGTH_SHORT).show()
                    zip == null -> Toast.makeText(this, R.string.logs_export_empty, Toast.LENGTH_SHORT).show()
                    else -> {
                        val uri = LogShareProvider.uriOf(zip)
                        val send = Intent(Intent.ACTION_SEND)
                            .setType(LogShareProvider.MIME)
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        send.clipData = ClipData.newRawUri(zip.name, uri)
                        startActivity(Intent.createChooser(send, getString(R.string.logs_send)))
                    }
                }
            }
        }
    }

    private fun packLogs(target: Uri) {
        thread(name = "qingjian-logs") {
            val message = runCatching { LogPack.save(this, target) }.fold(
                { if (it == 0) getString(R.string.logs_export_empty) else getString(R.string.logs_export_done, it) },
                {
                    AppLog.error("log export failed: ${android.util.Log.getStackTraceString(it)}")
                    getString(R.string.export_failed)
                },
            )
            runOnUiThread {
                if (!isDestroyed) Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }
    }


    private fun <T> runBackground(name: String, work: () -> T, message: (T) -> String) {
        exporting = true
        restoring = name.contains("restore")
        rebuild()
        thread(name = name) {
            val result = runCatching(work)
            val notice = result.fold(message) { getString(R.string.export_failed) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                exporting = false
                restoring = false
                rebuild()
                Toast.makeText(this, notice, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 导出整个 user 目录：学习习惯、生词本、统计、剪贴板、常用语、导入词库与自定义数据。 */
    private fun writeUserDataZip(target: Uri): Int {
        val root = Engine.userDir(this)
        val files = root.walkTopDown().filter { it.isFile }.toList()
        val output = contentResolver.openOutputStream(target, "wt") ?: error("打不开导出位置")
        var written = 0
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(JSONObject().put("format", "qingjian-user-data").put("version", 1).toString().toByteArray())
            zip.closeEntry()
            for (file in files) {
                val relative = file.relativeTo(root).invariantSeparatorsPath
                zip.putNextEntry(ZipEntry("user/$relative").apply { time = file.lastModified() })
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                written++
            }
        }
        return written
    }

    /** 恢复整个用户数据目录；兼容旧版根目录平铺的学习数据 ZIP。 */
    private fun restoreUserData(source: Uri): Int {
        val staging = File(cacheDir, "qingjian-restore-${System.currentTimeMillis()}").apply {
            deleteRecursively()
            mkdirs()
        }
        val root = Engine.userDir(this)
        val legacy = Engine.learningFiles(this).mapNotNull { (name, file) ->
            runCatching { name to file.relativeTo(root).invariantSeparatorsPath }.getOrNull()
        }.toMap()
        var written = 0
        var totalBytes = 0L
        val input = contentResolver.openInputStream(source) ?: error("无法读取备份文件")
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            var entry = zip.nextEntry
            var entries = 0
            while (entry != null) {
                entries++
                if (entries > 10000) error("备份文件项目过多")
                if (!entry.isDirectory) {
                    val relative = when {
                        entry.name.startsWith("user/") -> entry.name.removePrefix("user/")
                        else -> legacy[entry.name]
                    }
                    if (!relative.isNullOrBlank()) {
                        if (relative.startsWith("/") || relative.split('/').any { it == ".." || it.isBlank() }) {
                            error("备份文件路径无效")
                        }
                        val destination = File(staging, relative).canonicalFile
                        if (!destination.path.startsWith(staging.canonicalPath + File.separator)) error("备份文件路径无效")
                        destination.parentFile?.mkdirs()
                        destination.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                totalBytes += count
                                if (totalBytes > 512L * 1024 * 1024) error("备份文件超过 512 MB")
                                output.write(buffer, 0, count)
                            }
                        }
                        written++
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        if (written == 0) error("备份里没有可恢复的青简数据")
        if (!Engine.replaceUserData(this, staging)) error("恢复失败，原有数据已保留")
        return written
    }

    /** 备份应用内全部 SharedPreferences；每个值带类型，避免恢复时把整数变成长整数。 */
    private fun writeSettingsZip(target: Uri): Int {
        val directory = File(applicationInfo.dataDir, "shared_prefs")
        val files = directory.listFiles()?.filter { it.isFile && it.name.endsWith(".xml") }.orEmpty()
        val output = contentResolver.openOutputStream(target, "wt") ?: error("打不开导出位置")
        var written = 0
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(JSONObject().put("format", "qingjian-settings").put("version", 1).toString().toByteArray())
            zip.closeEntry()
            for (file in files) {
                val name = file.name.removeSuffix(".xml")
                val preferences = getSharedPreferences(name, MODE_PRIVATE)
                val json = JSONObject()
                preferences.all.forEach { (key, value) ->
                    val encoded = JSONObject()
                    when (value) {
                        is String -> encoded.put("type", "string").put("value", value)
                        is Boolean -> encoded.put("type", "boolean").put("value", value)
                        is Int -> encoded.put("type", "int").put("value", value)
                        is Long -> encoded.put("type", "long").put("value", value)
                        is Float -> encoded.put("type", "float").put("value", value.toDouble())
                        is Set<*> -> encoded.put("type", "set").put("value", JSONArray(value.filterIsInstance<String>()))
                        else -> return@forEach
                    }
                    json.put(key, encoded)
                }
                zip.putNextEntry(ZipEntry("prefs/$name.json"))
                zip.write(json.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                written++
            }
            // 常用短语独立保存在 user/phrases.json，不属于 SharedPreferences。
            val phrases = File(Engine.userDir(this), "phrases.json")
            zip.putNextEntry(ZipEntry("user/phrases.json"))
            if (phrases.isFile) phrases.inputStream().use { it.copyTo(zip) } else zip.write("[]".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            written++
        }
        return written
    }

    /** 恢复设置前先完整解析备份；常用短语先校验，再写入用户数据目录。 */
    private fun restoreSettings(source: Uri): Int {
        val backups = linkedMapOf<String, JSONObject>()
        var phraseData: ByteArray? = null
        val input = contentResolver.openInputStream(source) ?: error("无法读取设置备份")
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            var entry = zip.nextEntry
            var total = 0
            while (entry != null) {
                if (++total > 1000) error("设置备份项目过多")
                if (!entry.isDirectory && entry.name.startsWith("prefs/") && entry.name.endsWith(".json")) {
                    val name = entry.name.removePrefix("prefs/").removeSuffix(".json")
                    if (!name.matches(Regex("[A-Za-z0-9_.-]+"))) error("设置备份名称无效")
                    val bytes = zip.readBytes()
                    if (bytes.size > 2 * 1024 * 1024) error("设置备份文件过大")
                    backups[name] = JSONObject(String(bytes, Charsets.UTF_8))
                } else if (!entry.isDirectory && entry.name == "user/phrases.json") {
                    val bytes = zip.readBytes()
                    if (bytes.size > 2 * 1024 * 1024) error("常用短语备份过大")
                    JSONArray(String(bytes, Charsets.UTF_8)) // 校验完整 JSON 数组，避免损坏数据覆盖现有短语
                    phraseData = bytes
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        if (backups.isEmpty() && phraseData == null) error("备份里没有设置或常用短语")
        val current = File(applicationInfo.dataDir, "shared_prefs").listFiles()
            ?.filter { it.isFile && it.name.endsWith(".xml") }
            ?.map { it.name.removeSuffix(".xml") }.orEmpty()
        (current + backups.keys).distinct().forEach { name ->
            val editor = getSharedPreferences(name, MODE_PRIVATE).edit().clear()
            backups[name]?.let { json ->
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val encoded = json.getJSONObject(key)
                    when (encoded.getString("type")) {
                        "string" -> editor.putString(key, encoded.getString("value"))
                        "boolean" -> editor.putBoolean(key, encoded.getBoolean("value"))
                        "int" -> editor.putInt(key, encoded.getInt("value"))
                        "long" -> editor.putLong(key, encoded.getLong("value"))
                        "float" -> editor.putFloat(key, encoded.getDouble("value").toFloat())
                        "set" -> {
                            val array = encoded.getJSONArray("value")
                            editor.putStringSet(key, List(array.length()) { array.getString(it) }.toSet())
                        }
                    }
                }
            }
            if (!editor.commit()) error("写入设置失败")
        }
        phraseData?.let { bytes ->
            val phraseFile = File(Engine.userDir(this), "phrases.json")
            phraseFile.parentFile?.mkdirs()
            phraseFile.writeBytes(bytes)
            Engine.reloadPhrasesAll(this)
        }
        Settings.preferences(this).edit().putLong(Settings.KEY_DICTIONARIES_STAMP, System.currentTimeMillis()).apply()
        return backups.size + if (phraseData != null) 1 else 0
    }

    /** 第一次：勾清哪几样（缺省全勾）；第二次：确认不能恢复。 */
    private fun chooseParts() {
        val checked = booleanArrayOf(true, true)
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_learning_title)
            .setMultiChoiceItems(
                arrayOf(getString(R.string.clear_habits), getString(R.string.clear_vocabulary)), checked,
            ) { _, which, on -> checked[which] = on }
            .setPositiveButton(R.string.clear_next) { _, _ ->
                if (checked.any { it }) confirm(checked[0], checked[1])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirm(habits: Boolean, vocabulary: Boolean) {
        val parts = listOfNotNull(
            getString(R.string.clear_habits_short).takeIf { habits },
            getString(R.string.clear_vocabulary_short).takeIf { vocabulary },
        ).joinToString("和")
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.clear_confirm, parts))
            .setPositiveButton(R.string.clear_do) { _, _ ->
                val removed = Engine.clearLearning(this, habits, vocabulary)
                val message = if (removed < 0) R.string.clear_failed else R.string.clear_done
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .getButton(AlertDialog.BUTTON_POSITIVE)
            .setTextColor(ui.colors.danger)
    }

    private companion object {
        const val CREATE_ZIP = 1
        const val PACK_LOGS = 2
        const val RESTORE_ZIP = 3
        const val BACKUP_SETTINGS = 4
        const val RESTORE_SETTINGS = 5
    }
}
