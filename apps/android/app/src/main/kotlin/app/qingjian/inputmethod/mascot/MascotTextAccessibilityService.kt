package app.qingjian.inputmethod.mascot

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Runs only after the user explicitly enables this service in Android Accessibility settings. */
class MascotTextAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() { super.onServiceConnected(); active = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!featureEnabled() || event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg.substringAfterLast('.'))
        sendBroadcast(android.content.Intent(ACTION_APP_CONTEXT).setPackage(packageName).putExtra(EXTRA_APP_LABEL, label))
    }
    override fun onInterrupt() = Unit
    private fun featureEnabled() = getSharedPreferences("mascot_assist", MODE_PRIVATE).getBoolean("cross_app_enabled", false)
    private fun copyAt(x: Int, y: Int): Boolean {
        if (!featureEnabled()) return false
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() == packageName) return false
        val node = findTextNode(root, x, y) ?: return false
        val value = node.text?.toString()?.trim().orEmpty()
        if (value.isBlank()) { node.recycle(); return false }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("青简复制", value))
        node.recycle()
        return true
    }
    private fun findTextNode(node: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.contains(x, y) || node.isPassword) return null
        var result: AccessibilityNodeInfo? = if (node.isVisibleToUser && !node.text.isNullOrBlank()) AccessibilityNodeInfo.obtain(node) else null
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findTextNode(child, x, y)
            child.recycle()
            if (found != null) {
                if (result == null) result = found else {
                    val a = Rect().also { result!!.getBoundsInScreen(it) }
                    val b = Rect().also { found.getBoundsInScreen(it) }
                    if (b.width().toLong() * b.height() < a.width().toLong() * a.height()) { result!!.recycle(); result = found } else found.recycle()
                }
            }
        }
        return result
    }
    companion object {
        const val ACTION_APP_CONTEXT = "app.qingjian.inputmethod.action.MASCOT_APP_CONTEXT"
        const val EXTRA_APP_LABEL = "app_label"
        @Volatile private var active: MascotTextAccessibilityService? = null
        fun isEnabled(context: Context): Boolean {
            val expected = context.packageName + "/" + MascotTextAccessibilityService::class.java.name
            val value = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return value.split(':').any { it.equals(expected, true) }
        }
        fun requestCopyAt(x: Int, y: Int): Boolean = active?.copyAt(x, y) ?: false
    }
    override fun onDestroy() { if (active === this) active = null; super.onDestroy() }
}
