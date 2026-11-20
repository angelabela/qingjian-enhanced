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

/** 仅在用户开启验证码监测后处理短信；只在本机提取验证码，不上传短信正文。 */
class SmsCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ENABLED, false)) return

        val messages = runCatching {
            Telephony.Sms.Intents.getMessagesFromIntent(intent).toList()
        }.getOrDefault(emptyList())
        val body = messages.joinToString("") { it.messageBody.orEmpty() }
        val code = extractCode(body) ?: return

        // 自动复制，随后通知当前输入法候选栏提供一键上屏入口。
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("短信验证码", code))
        prefs.edit().putString(KEY_LATEST, code).putLong(KEY_LATEST_AT, System.currentTimeMillis()).apply()
        context.sendBroadcast(
            Intent(ACTION_CODE_READY).setPackage(context.packageName).putExtra(EXTRA_CODE, code),
        )
        val mascotPrefs = context.getSharedPreferences(FloatingMascotService.PREFS, Context.MODE_PRIVATE)
        if (mascotPrefs.getBoolean(FloatingMascotService.KEY_ENABLED, false) &&
            android.provider.Settings.canDrawOverlays(context)
        ) {
            val mascotIntent = Intent(context, FloatingMascotService::class.java)
                .setAction(FloatingMascotService.ACTION_SHOW_MESSAGE)
                .putExtra(FloatingMascotService.EXTRA_MESSAGE, "收到短信验证码：$code，已经帮你复制好啦！")
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(mascotIntent)
                else context.startService(mascotIntent)
            }.onFailure { android.util.Log.w("QingjianMascot", "Unable to show SMS code in mascot", it) }
        }
        Toast.makeText(context, "验证码 $code 已复制", Toast.LENGTH_SHORT).show()
    }

    private fun extractCode(text: String): String? {
        if (text.isBlank()) return null
        val context = Regex(
            "(验证码|校验码|动态码|短信码|verification\\s*code|one[- ]time\\s+password|\\bOTP\\b)",
            RegexOption.IGNORE_CASE,
        )
        val match = context.find(text) ?: return null
        val tail = text.substring(match.range.last + 1).take(36)
        val digits = Regex("(?<!\\d)(\\d{4,6})(?!\\d)")
        return digits.find(tail)?.groupValues?.getOrNull(1)
    }

    companion object {
        const val KEY_ENABLED = "sms_code_enabled"
        const val KEY_LATEST = "sms_code_latest"
        const val KEY_LATEST_AT = "sms_code_latest_at"
        const val ACTION_CODE_READY = "app.qingjian.inputmethod.SMS_CODE_READY"
        const val EXTRA_CODE = "code"
    }
}
