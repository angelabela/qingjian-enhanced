package app.qingjian.inputmethod.settings.page

import android.app.AlertDialog
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import app.qingjian.inputmethod.R
import app.qingjian.inputmethod.core.AppLog
import app.qingjian.inputmethod.core.Engine
import app.qingjian.inputmethod.cloud.SecretStore
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
    private var restoredSecretsUnavailable = false

    override fun content(): List<View> = listOf(
        // 原有的独立数据/设置备份恢复入口继续保留。
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
            ui.action(R.string.settings_backup_title, null) {
                if (!exporting && !restoring) pickTarget(BACKUP_SETTINGS)
            },
            ui.action(R.string.settings_restore_title, null) {
                if (!exporting && !restoring) pickSource(RESTORE_SETTINGS)
            },
            footer = R.string.settings_backup_note,
        ),
        // 新增的一键功能独立成组，不替代上面的旧功能。
        ui.group(
            R.string.backup_all_title,
            ui.action(
                R.string.backup_all_title,
                getString(if (exporting) R.string.backup_all_running else R.string.backup_all_value),
            ) {
                if (!exporting && !restoring) backupAll()
            },
            ui.action(
                R.string.restore_all_title,
                getString(if (restoring) R.string.restore_all_running else R.string.restore_all_value),
            ) {
                if (!exporting && !restoring) restoreAll()
            },
            footer = R.string.backup_all_note,
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

    /** 一键同时备份个人数据和应用设置；两份 ZIP 使用完全相同的时间戳。 */
    private fun backupAll() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, R.string.backup_requires_android_10, Toast.LENGTH_LONG).show()
            return
        }
        Engine.flushAll()
        runBackground("qingjian-backup-all", {
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val data = writeBackupToDownloads(settings = false, stamp = stamp)
            try {
                val settings = writeBackupToDownloads(settings = true, stamp = stamp)
                data.second to settings.second
            } catch (failure: Throwable) {
                contentResolver.delete(data.first, null, null)
                throw failure
            }
        }) { counts ->
            getString(R.string.backup_all_done, counts.first, counts.second)
        }
    }

    /** 分别选择用户数据与设置各自最新的 ZIP；两份备份时间戳可以不同。 */
    private fun restoreAll() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, R.string.restore_requires_android_10, Toast.LENGTH_LONG).show()
            return
        }
        val data = findLatestDownloadBackup("qingjian-data")
        val settings = findLatestDownloadBackup("qingjian-settings")
        if (data == null || settings == null) {
            // 重装后 MediaStore 对旧安装创建文件的可见性可能变化；不能只按本次安装的媒体索引判定备份不存在。
            // 改用系统文件选择器，让用户从 Download 或其他目录同时选中旧的数据 ZIP 与设置 ZIP。
            pickBothBackups()
            return
        }
        restoreAllFromPair(data.first, settings.first, "${data.second} + ${settings.second}")
    }

    private fun pickTarget(request: Int) {
        // 保留原有的独立备份：Android 10+ 自动写入 Download；旧系统使用系统文件选择器。
        if (request == PACK_LOGS || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            openCreateDocument(request)
            return
        }
        if (request == CREATE_ZIP) Engine.flushAll()
        val settings = request == BACKUP_SETTINGS
        runBackground(
            if (settings) "qingjian-settings-backup" else "qingjian-data-backup",
            {
                val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                    .format(java.util.Date())
                writeBackupToDownloads(settings, stamp).second
            },
        ) { count ->
            if (settings) getString(R.string.settings_backup_done, count)
            else getString(R.string.export_done, count)
        }
    }

    private fun openCreateDocument(request: Int) {
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val settings = request == RESTORE_SETTINGS
            val prefix = if (settings) "qingjian-settings" else "qingjian-data"
            val newest = findLatestDownloadBackup(prefix)
            if (newest != null) {
                confirmRestore(newest.first, settings, newest.second)
                return
            }
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/zip")
        startActivityForResult(intent, request)
    }

    /** 在 Download 中自动找本应用最近生成的对应备份，不要求用户手动浏览目录。 */
    private fun findLatestDownloadBackup(prefix: String): Pair<Uri, String>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        require(prefix == "qingjian-data" || prefix == "qingjian-settings") {
            "只允许查找青简数据或设置备份"
        }
        // 只接受 Download 根目录中以指定前缀开头、且扩展名为 ZIP 的文件。
        // DATE_MODIFIED 优先，确保同类备份有多个时选取最近修改的一份。
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.RELATIVE_PATH,
            MediaStore.Downloads.IS_PENDING,
        )
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} LIKE ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ? AND ${MediaStore.Downloads.IS_PENDING} = 0"
        val args = arrayOf("$prefix%", Environment.DIRECTORY_DOWNLOADS + "/")
        val order = "${MediaStore.Downloads.DATE_MODIFIED} DESC, ${MediaStore.Downloads.DATE_ADDED} DESC"

        fun findIn(collection: Uri): Pair<Uri, String>? {
            contentResolver.query(collection, projection, selection, args, order)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME))
                    val relativePath = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Downloads.RELATIVE_PATH))
                    if (
                        relativePath == Environment.DIRECTORY_DOWNLOADS + "/" &&
                        name.startsWith(prefix, ignoreCase = false) &&
                        name.endsWith(".zip", ignoreCase = true)
                    ) {
                        return collection.buildUpon().appendPath(id.toString()).build() to name
                    }
                }
            }
            return null
        }

        return findIn(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL))
            ?: findIn(MediaStore.Downloads.EXTERNAL_CONTENT_URI)
    }

    /** Android 10+ 使用 MediaStore 的 Download 集合与 pending 写入，避免申请宽泛存储权限。 */
    private fun writeBackupToDownloads(settings: Boolean, stamp: String): Pair<Uri, Int> {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val prefix = if (settings) "qingjian-settings" else "qingjian-data"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "$prefix-$stamp.zip")
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("无法在 Download 创建备份文件")
        try {
            val count = if (settings) writeSettingsZip(uri) else writeUserDataZip(uri)
            val ready = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            if (contentResolver.update(uri, ready, null, null) != 1) error("备份已写入，但无法完成文件发布")
            return uri to count
        } catch (failure: Throwable) {
            contentResolver.delete(uri, null, null)
            throw failure
        }
    }

    @Deprecated("Activity 的老接口，这页没有别的结果要收")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        if (requestCode == RESTORE_ALL_PICK) {
            val uris = buildList {
                data?.clipData?.let { clip ->
                    for (index in 0 until clip.itemCount) add(clip.getItemAt(index).uri)
                } ?: data?.data?.let(::add)
            }.distinct()
            restoreAllFromUris(uris)
            return
        }
        val target = data?.data ?: return
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

    /** 重装后旧安装创建的 Download 文件可能不在当前应用的 MediaStore 查询结果里，允许手动一次选中两份旧 ZIP。 */
    private fun pickBothBackups() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        startActivityForResult(intent, RESTORE_ALL_PICK)
    }

    /** 依据压缩包清单识别类型，不依赖备份文件名或当前安装版本号。 */
    private fun backupKind(uri: Uri): String? {
        ZipInputStream(BufferedInputStream(contentResolver.openInputStream(uri) ?: return null)).use { zip ->
            var entry = zip.nextEntry
            var count = 0
            while (entry != null && count++ < 1000) {
                if (!entry.isDirectory && entry.name == "manifest.json") {
                    val manifest = JSONObject(String(zip.readBytes(), Charsets.UTF_8))
                    return manifest.optString("format").takeIf {
                        it == "qingjian-user-data" || it == "qingjian-settings"
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val name = uri.lastPathSegment.orEmpty()
        return when {
            name.contains("qingjian-data", ignoreCase = true) -> "qingjian-user-data"
            name.contains("qingjian-settings", ignoreCase = true) -> "qingjian-settings"
            else -> null
        }
    }

    private fun restoreAllFromUris(uris: List<Uri>) {
        if (uris.size != 2) {
            Toast.makeText(this, "请同时选择一份 qingjian-data 数据备份和一份 qingjian-settings 设置备份", Toast.LENGTH_LONG).show()
            return
        }
        val classified = uris.map { it to backupKind(it) }
        val data = classified.firstOrNull { it.second == "qingjian-user-data" }?.first
        val settings = classified.firstOrNull { it.second == "qingjian-settings" }?.first
        if (data == null || settings == null) {
            Toast.makeText(this, "无法识别这两份备份，请确认分别选择青简数据 ZIP 和设置 ZIP", Toast.LENGTH_LONG).show()
            return
        }
        restoreAllFromPair(data, settings, "手动选择的旧版本备份")
    }

    private fun restoreAllFromPair(data: Uri, settings: Uri, label: String) {
        Engine.flushAll()
        AlertDialog.Builder(this)
            .setTitle(R.string.restore_all_title)
            .setMessage(getString(R.string.restore_all_confirm, "数据备份", "设置备份") + "\\n\\n" + label)
            .setPositiveButton(R.string.restore_confirm) { _, _ ->
                runBackground("qingjian-restore-all", {
                    restoredSecretsUnavailable = false
                    val liveUser = Engine.userDir(this)
                    val snapshot = File(filesDir, "user-before-restore-${System.currentTimeMillis()}")
                    snapshot.deleteRecursively()
                    if (liveUser.exists()) liveUser.copyRecursively(snapshot, overwrite = true)
                    try {
                        val dataCount = restoreUserData(data)
                        val settingsCount = restoreSettings(settings)
                        snapshot.deleteRecursively()
                        dataCount to settingsCount
                    } catch (failure: Throwable) {
                        if (snapshot.exists() && !Engine.replaceUserData(this, snapshot)) {
                            error("设置恢复失败，且原用户数据回滚失败；恢复前快照仍保留。原因：${failure.message}")
                        }
                        throw failure
                    }
                }) { counts ->
                    getString(R.string.restore_all_done, counts.first, counts.second) +
                        if (restoredSecretsUnavailable) "\\n云端大模型和语音识别 API Key 因 Android 安全密钥随卸载删除，需要重新填写。" else ""
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmRestore(source: Uri, settings: Boolean, backupName: String? = null) {
        AlertDialog.Builder(this)
            .setTitle(if (settings) R.string.settings_restore_title else R.string.restore_data_title)
            .setMessage(buildString {
                append(getString(if (settings) R.string.settings_restore_confirm else R.string.restore_data_confirm))
                backupName?.let { append("\n\n").append(it) }
            })
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
            val notice = result.fold(message) { failure ->
                AppLog.error("$name failed: ${android.util.Log.getStackTraceString(failure)}")
                failure.message?.takeIf { it.isNotBlank() } ?: getString(R.string.export_failed)
            }
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
        val staging = File(filesDir, "user-restore-staging-${System.currentTimeMillis()}").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
        val root = Engine.userDir(this)
        val legacy = Engine.learningFiles(this).mapNotNull { (name, file) ->
            runCatching { name to file.relativeTo(root).invariantSeparatorsPath }.getOrNull()
        }.toMap()
        var written = 0
        var totalBytes = 0L
        var manifestSeen = false
        var modernEntriesSeen = false
        val restoredPaths = mutableSetOf<String>()
        val input = contentResolver.openInputStream(source) ?: error("无法读取备份文件")
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            var entry = zip.nextEntry
            var entries = 0
            while (entry != null) {
                entries++
                if (entries > 10000) error("备份文件项目过多")
                if (!entry.isDirectory && entry.name == "manifest.json") {
                    val manifest = JSONObject(String(zip.readBytes(), Charsets.UTF_8))
                    if (manifest.optString("format") != "qingjian-user-data" || manifest.optInt("version") != 1) {
                        error("数据备份格式不兼容")
                    }
                    manifestSeen = true
                } else if (!entry.isDirectory) {
                    val relative = when {
                        entry.name.startsWith("user/") -> {
                            modernEntriesSeen = true
                            entry.name.removePrefix("user/")
                        }
                        else -> legacy[entry.name]
                    }
                    if (!relative.isNullOrBlank()) {
                        if (!restoredPaths.add(relative)) error("备份中存在重复文件：$relative")
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
        // 新格式必须同时具有清单和 user/ 前缀；旧版 ZIP 只允许通过 Engine.learningFiles 白名单映射。
        if (written == 0) error("备份里没有可恢复的青简数据")
        if (modernEntriesSeen && !manifestSeen) error("数据备份缺少格式清单，已拒绝恢复")
        if (manifestSeen && !modernEntriesSeen) error("数据备份结构不完整，缺少 user/ 数据目录")
        if (!Engine.replaceUserData(this, staging)) error("恢复失败，原有数据已保留")
        return written
        } finally {
            staging.deleteRecursively()
        }
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
        }
        return written
    }

    /** 恢复设置前先完整解析备份；只接受 prefs/ 下的 JSON，不执行压缩包里的路径。 */
    private fun restoreSettings(source: Uri): Int {
        val backups = linkedMapOf<String, JSONObject>()
        var manifestSeen = false
        val input = contentResolver.openInputStream(source) ?: error("无法读取设置备份")
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            var entry = zip.nextEntry
            var total = 0
            while (entry != null) {
                if (++total > 1000) error("设置备份项目过多")
                if (!entry.isDirectory && entry.name == "manifest.json") {
                    val manifest = JSONObject(String(zip.readBytes(), Charsets.UTF_8))
                    if (manifest.optString("format") != "qingjian-settings" || manifest.optInt("version") != 1) {
                        error("设置备份格式不兼容")
                    }
                    manifestSeen = true
                } else if (!entry.isDirectory && entry.name.startsWith("prefs/") && entry.name.endsWith(".json")) {
                    val name = entry.name.removePrefix("prefs/").removeSuffix(".json")
                    if (!name.matches(Regex("[A-Za-z0-9_.-]+"))) error("设置备份名称无效")
                    val bytes = zip.readBytes()
                    if (bytes.size > 2 * 1024 * 1024) error("设置备份文件过大")
                    backups[name] = JSONObject(String(bytes, Charsets.UTF_8))
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        if (!manifestSeen || backups.isEmpty()) error("这不是有效的青简设置备份")
        val current = File(applicationInfo.dataDir, "shared_prefs").listFiles()
            ?.filter { it.isFile && it.name.endsWith(".xml") }
            ?.map { it.name.removeSuffix(".xml") }.orEmpty()
        val names = (current + backups.keys).distinct()
        val snapshots = names.associateWith { getSharedPreferences(it, MODE_PRIVATE).all.toMap() }

        fun writePreferences(name: String, values: Map<String, *>?) {
            val editor = getSharedPreferences(name, MODE_PRIVATE).edit().clear()
            values.orEmpty().forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            if (!editor.commit()) error("写入设置失败：$name")
        }

        fun decode(json: JSONObject): Map<String, Any> {
            val values = linkedMapOf<String, Any>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val encoded = json.getJSONObject(key)
                when (encoded.getString("type")) {
                    "string" -> values[key] = encoded.getString("value")
                    "boolean" -> values[key] = encoded.getBoolean("value")
                    "int" -> values[key] = encoded.getInt("value")
                    "long" -> values[key] = encoded.getLong("value")
                    "float" -> values[key] = encoded.getDouble("value").toFloat()
                    "set" -> {
                        val array = encoded.getJSONArray("value")
                        values[key] = List(array.length()) { array.getString(it) }.toSet()
                    }
                    else -> error("设置备份包含未知数据类型")
                }
            }
            return values
        }

        val decoded = backups.mapValues { (name, json) ->
            decode(json).toMutableMap().apply {
                // Android Keystore 的 AES 密钥会随卸载销毁；旧安装导出的密文在重装后不可解。
                // 当前安装能解开的密钥照常恢复，解不开的密文必须清掉，避免界面误以为配置仍有效。
                if (name == "cloud" || name == "voice") {
                    val cipher = this["api_key_cipher"] as? String
                    if (cipher != null && SecretStore.open(cipher) == null) {
                        remove("api_key_cipher")
                        restoredSecretsUnavailable = true
                    }
                }
            }
        }
        try {
            names.forEach { name -> writePreferences(name, decoded[name]) }
        } catch (failure: Throwable) {
            snapshots.forEach { (name, values) -> runCatching { writePreferences(name, values) } }
            throw failure
        }
        Settings.preferences(this).edit().putLong(Settings.KEY_DICTIONARIES_STAMP, System.currentTimeMillis()).apply()
        return backups.size
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
