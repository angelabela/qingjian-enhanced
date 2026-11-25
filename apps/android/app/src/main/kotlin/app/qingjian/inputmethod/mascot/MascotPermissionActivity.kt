package app.qingjian.inputmethod.mascot
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast

/** 首次开启时解释并申请悬浮窗权限；授权返回后自动启用。 */
class MascotPermissionActivity : Activity() {
    private var waitingForSettings = false
    private var handled = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Settings.canDrawOverlays(this)) { enableMascot(); return }
        AlertDialog.Builder(this)
            .setTitle("开启悬浮小精灵")
            .setMessage("小精灵需要「显示在其他应用上层」权限，才能在微信、浏览器等其他应用界面持续显示。此权限只用于显示你主动开启的小精灵。")
            .setNegativeButton("暂不开启") { _, _ -> finish() }
            .setPositiveButton("去授权") { _, _ ->
                waitingForSettings = true
                runCatching {
                    startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), REQUEST_OVERLAY)
                }.onFailure {
                    Toast.makeText(this, "无法打开悬浮窗授权页面", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            .setOnCancelListener { finish() }
            .show()
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OVERLAY) finishPermissionFlow()
    }
    override fun onResume() {
        super.onResume()
        if (waitingForSettings) finishPermissionFlow()
    }
    private fun finishPermissionFlow() {
        if (handled) return
        handled = true
        if (Settings.canDrawOverlays(this)) enableMascot()
        else {
            Toast.makeText(this, "未获得悬浮窗权限，小精灵未开启", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
    private fun enableMascot() {
        getSharedPreferences(FloatingMascotService.PREFS, MODE_PRIVATE).edit()
            .putBoolean(FloatingMascotService.KEY_ENABLED, true).apply()
        runCatching { startForegroundService(Intent(this, FloatingMascotService::class.java)) }
            .onFailure {
                getSharedPreferences(FloatingMascotService.PREFS, MODE_PRIVATE).edit()
                    .putBoolean(FloatingMascotService.KEY_ENABLED, false).apply()
                Toast.makeText(this, "小精灵启动失败，请稍后重试", Toast.LENGTH_SHORT).show()
            }
        finish()
    }
    companion object { private const val REQUEST_OVERLAY = 7401 }
}
