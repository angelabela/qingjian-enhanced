package app.qingjian.inputmethod.settings.page

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.Toast
import app.qingjian.inputmethod.panel.base.PanelIcons.Icon

/** 汇总显示青简声明的运行时与特殊权限；点击未授权项可直接发起授权。 */
class PermissionSettingsActivity : PageActivity() {
    override val title: Int = app.qingjian.inputmethod.R.string.page_permissions

    override fun content(): List<View> {
        val runtime = mutableListOf<View>(
            permissionRow("相机权限（扫码）", "扫码功能需要使用相机", Manifest.permission.CAMERA),
            permissionRow("短信权限（验证码）", "读取系统短信广播中的验证码", Manifest.permission.RECEIVE_SMS),
            permissionRow("麦克风权限（语音输入）", "使用语音识别时需要", Manifest.permission.RECORD_AUDIO),
            permissionRow("定位权限（实时地点）", "显示当前位置与地点信息", Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
            permissionRow("通讯录权限（联系人联想）", "开启通讯录词库时需要", Manifest.permission.READ_CONTACTS),
        )
        if (Build.VERSION.SDK_INT >= 33) {
            runtime += permissionRow("通知权限（扫码服务）", "显示持续扫码时的前台服务通知", Manifest.permission.POST_NOTIFICATIONS)
        }
        val special = listOf(
            ui.entry(Icon.LOCK, "悬浮窗权限", overlayStatus()) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            },
            ui.entry(Icon.LOCK, "应用权限详情", "打开系统应用信息页") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            },
        )
        return listOf(
            ui.group("需要手动授权", runtime, "点击未开启的项目申请权限；部分权限也可在系统设置中随时关闭。"),
            ui.group("特殊权限", special, "悬浮扫码需要允许青简显示在其他应用上层。"),
            ui.group(
                "系统自动授予的权限",
                listOf(
                    ui.row("网络访问", "联网功能使用；无需单独弹出授权框"),
                    ui.row("网络状态", "检测 Wi-Fi 与网络连接状态"),
                    ui.row("按键震动", "键盘触感反馈使用"),
                    ui.row("前台服务", "保持悬浮扫码运行；由 Android 系统管理"),
                ),
            ),
        )
    }

    private fun permissionRow(title: String, note: String, vararg permissions: String): View {
        val granted = permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        return ui.entry(
            Icon.LOCK,
            title,
            if (granted) "已开启" else "未开启",
        ) {
            if (granted) {
                Toast.makeText(this, "该权限已经开启", Toast.LENGTH_SHORT).show()
            } else {
                requestPermissions(permissions, REQUEST_PERMISSION)
            }
        }
    }

    private fun overlayStatus(): String =
        if (Settings.canDrawOverlays(this)) "已开启" else "未开启"

    override fun onResume() {
        super.onResume()
        rebuild()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSION) rebuild()
    }

    private companion object {
        const val REQUEST_PERMISSION = 2940
    }
}
