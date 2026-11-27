package app.qingjian.inputmethod.sms

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import android.widget.Toast
import android.os.Build
import app.qingjian.inputmethod.mascot.FloatingMascotService
import app.qingjian.inputmethod.core.AppLog
import app.qingjian.inputmethod.scan.ScanContent

/** 仅在用户开启验证码监测后处理短信；只在本机提取验证码，不上传短信正文。 */
class SmsCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        AppLog.info("[SMS_CODE_DIAG] SMS broadcast received")
        val codeMonitorEnabled = prefs.getBoolean(KEY_ENABLED, false)
        val mascotPreviewEnabled = prefs.getBoolean(KEY_MASCOT_PREVIEW, false)
        if (!codeMonitorEnabled && !mascotPreviewEnabled) {
            AppLog.debug("[SMS_CODE_DIAG] ignored: monitor and mascot preview disabled")
            return
        }

        val messages = runCatching {
            Telephony.Sms.Intents.getMessagesFromIntent(intent).toList()
        }.getOrDefault(emptyList())
        val body = messages.joinToString("") { it.messageBody.orEmpty() }
        val sender = messages.firstOrNull()?.originatingAddress.orEmpty()
        val detectedCode = ScanContent.extractSmsCode(body)
        val code = detectedCode.takeIf { codeMonitorEnabled }
        if (code == null && codeMonitorEnabled) {
            AppLog.info("[SMS_CODE_DIAG] no code extracted messageCount=${messages.size}")
        }
        if (code != null) {
            AppLog.info("[SMS_CODE_DIAG] code extracted length=${code.length} messageCount=${messages.size}")
            prefs.edit().putString(KEY_LATEST, code).putLong(KEY_LATEST_AT, System.currentTimeMillis()).apply()
            runCatching {
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("短信验证码", code))
            }.onSuccess {
                AppLog.info("[SMS_CODE_DIAG] clipboard updated codeLength=${code.length}")
            }.onFailure {
                AppLog.warn("[SMS_CODE_DIAG] clipboard update failed: ${it.javaClass.simpleName}")
            }
            context.sendBroadcast(
                Intent(ACTION_CODE_READY).setPackage(context.packageName).putExtra(EXTRA_CODE, code),
            )
            AppLog.info("[SMS_CODE_DIAG] code-ready broadcast sent")
        }
        val mascotPrefs = context.getSharedPreferences(FloatingMascotService.PREFS, Context.MODE_PRIVATE)
        if (mascotPrefs.getBoolean(FloatingMascotService.KEY_ENABLED, false) &&
            android.provider.Settings.canDrawOverlays(context) &&
            (code != null || mascotPreviewEnabled)
        ) {
            val excerpt = body.replace(Regex("\\s+"), " ").take(92)
            val preview = if (mascotPreviewEnabled) {
                "短信来自：${sender.ifBlank { "未知号码" }}\n${excerpt.ifBlank { "（短信内容为空）" }}" +
                    if (detectedCode != null) "\n验证码：$detectedCode" else ""
            } else "收到短信验证码：$code，已经帮你复制好啦！"
            val mascotIntent = Intent(context, FloatingMascotService::class.java)
                .setAction(FloatingMascotService.ACTION_SHOW_MESSAGE)
                .putExtra(FloatingMascotService.EXTRA_MESSAGE, preview)
                .putExtra(FloatingMascotService.EXTRA_ANIMATION, "wave")
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(mascotIntent)
                else context.startService(mascotIntent)
            }.onFailure { android.util.Log.w("QingjianMascot", "Unable to show SMS in mascot", it) }
        }
        if (code != null) Toast.makeText(context, "验证码 $code 已复制", Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val KEY_ENABLED = "sms_code_enabled"
        const val KEY_MASCOT_PREVIEW = "mascot_sms_preview"
        const val KEY_LATEST = "sms_code_latest"
        const val KEY_LATEST_AT = "sms_code_latest_at"
        const val ACTION_CODE_READY = "app.qingjian.inputmethod.SMS_CODE_READY"
        const val EXTRA_CODE = "code"
    }
}
