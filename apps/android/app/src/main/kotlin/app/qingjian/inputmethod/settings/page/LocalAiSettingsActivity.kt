package app.qingjian.inputmethod.settings.page

import android.app.AlertDialog
import android.view.View
import android.widget.Toast
import app.qingjian.inputmethod.R
import app.qingjian.inputmethod.localai.LocalAiModelManager

/** 本地 AI 模型管理：手动下载、校验并执行一次离线推理测试。 */
class LocalAiSettingsActivity : PageActivity() {
    override val title = R.string.page_local_ai

    private var busy = false
    private var progress = 0
    private var statusMessage = ""

    override fun content(): List<View> {
        val installed = LocalAiModelManager.isInstalled(this)
        val downloadValue = when {
            busy && statusMessage.startsWith("download:") ->
                getString(R.string.local_ai_downloading, progress)
            installed -> getString(R.string.local_ai_installed)
            else -> getString(R.string.local_ai_model_details)
        }
        val testValue = if (busy && statusMessage.startsWith("test:")) {
            getString(R.string.local_ai_loading)
        } else if (installed) {
            "使用本机模型，不联网推理"
        } else {
            "请先下载模型"
        }
        val rows = listOf(
            ui.action(R.string.local_ai_download, downloadValue) {
                if (!busy) startDownload()
            },
            ui.action(R.string.local_ai_test, testValue) {
                if (!busy) runTest()
            },
            ui.action(R.string.local_ai_delete, if (installed) "删除约 328 MiB 文件" else "模型未安装", ui.colors.danger) {
                if (!busy && installed) confirmDelete()
            },
            ui.text(
                statusMessage.takeUnless { it.startsWith("download:") || it.startsWith("test:") }
                    ?: getString(R.string.local_ai_status_not_downloaded),
                14f,
                ui.colors.secondary,
            ).apply {
                setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
            },
        )
        return listOf(
            ui.group(
                getString(R.string.local_ai_model_section),
                rows,
                getString(R.string.local_ai_settings_summary),
            ),
        )
    }

    private fun startDownload() {
        busy = true
        progress = 0
        statusMessage = "download:0"
        rebuild()
        LocalAiModelManager.download(
            this,
            onProgress = { value ->
                if (!isFinishing && !isDestroyed && busy) {
                    progress = value
                    statusMessage = "download:" + value
                    rebuild()
                }
            },
            onComplete = { result ->
                if (isFinishing || isDestroyed) return@download
                busy = false
                statusMessage = result.fold(
                    onSuccess = { getString(R.string.local_ai_download_done) },
                    onFailure = { getString(R.string.local_ai_download_failed, it.message ?: "未知错误") },
                )
                rebuild()
            },
        )
    }

    private fun runTest() {
        if (!LocalAiModelManager.isInstalled(this)) {
            Toast.makeText(this, R.string.local_ai_status_not_downloaded, Toast.LENGTH_LONG).show()
            return
        }
        busy = true
        statusMessage = "test:running"
        rebuild()
        LocalAiModelManager.runOfflineSmokeTest(this) { result ->
            if (isFinishing || isDestroyed) return@runOfflineSmokeTest
            busy = false
            statusMessage = result.fold(
                onSuccess = { getString(R.string.local_ai_test_done, it) },
                onFailure = { getString(R.string.local_ai_test_failed, it.message ?: "未知错误") },
            )
            rebuild()
        }
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle(R.string.local_ai_delete)
            .setMessage(R.string.local_ai_delete_confirm)
            .setNegativeButton(R.string.close, null)
            .setPositiveButton(R.string.local_ai_delete) { _, _ ->
                val deleted = LocalAiModelManager.deleteModel(this)
                statusMessage = if (deleted) getString(R.string.local_ai_deleted) else "删除失败，请稍后重试"
                rebuild()
            }
            .show()
    }
}
