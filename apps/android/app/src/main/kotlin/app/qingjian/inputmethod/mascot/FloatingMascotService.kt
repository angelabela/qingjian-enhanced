package app.qingjian.inputmethod.mascot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.text.TextPaint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import app.qingjian.inputmethod.R
import app.qingjian.inputmethod.settings.home.MainActivity
import kotlin.math.abs
import kotlin.math.sin

/** 独立于输入法窗口的全局悬浮宠物；窗口不获取焦点，不拦截其他应用的输入。 */
class FloatingMascotService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var windowParams: WindowManager.LayoutParams
    private var mascotView: MascotView? = null
    private var receiverRegistered = false
    private val dragHandler = Handler(Looper.getMainLooper())
    private var pendingCopy: Runnable? = null
    private var lastDragX = 0
    private var lastDragY = 0
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == MascotTextAccessibilityService.ACTION_APP_CONTEXT) {
                mascotView?.setContextLabel(intent.getStringExtra(MascotTextAccessibilityService.EXTRA_APP_HINT).orEmpty().ifBlank { intent.getStringExtra(MascotTextAccessibilityService.EXTRA_APP_LABEL).orEmpty() })
            } else mascotView?.visibility = if (intent.action == Intent.ACTION_SCREEN_OFF) View.GONE else View.VISIBLE
        }
    }

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ENABLED, false) || !android.provider.Settings.canDrawOverlays(this)) {
            stopSelf(); return
        }
        createNotificationChannel()
        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION_ID, notification)
        } catch (_: RuntimeException) {
            prefs.edit().putBoolean(KEY_ENABLED, false).apply()
            stopSelf(); return
        }
        windowManager = getSystemService(WindowManager::class.java)
        val density = resources.displayMetrics.density
        val size = (64f * density).toInt()
        windowParams = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, (resources.displayMetrics.widthPixels - size - 18f * density).toInt()).coerceAtLeast(0)
            y = prefs.getInt(KEY_Y, (resources.displayMetrics.heightPixels * 0.34f).toInt()).coerceAtLeast(0)
        }
        mascotView = MascotView(this).also { view ->
            view.setOnTouchListener(createTouchListener(view))
            try { windowManager.addView(view, windowParams) }
            catch (_: RuntimeException) {
                prefs.edit().putBoolean(KEY_ENABLED, false).apply()
                stopSelf(); return
            }
        }
        val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT); addAction(MascotTextAccessibilityService.ACTION_APP_CONTEXT) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenReceiver, filter)
        receiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ENABLED, false)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_SHOW_TIPS -> {
                pendingTipColors = intent
                val labels = intent.getStringArrayListExtra(EXTRA_TIP_LABELS).orEmpty()
                val indexes = intent.getIntegerArrayListExtra(EXTRA_TIP_INDEXES).orEmpty()
                showTips(labels, indexes)
                pendingTipColors = null
            }
            ACTION_HIDE_TIPS -> hideTips()
        }
        return START_STICKY
    }

    /** 云联想直接绘制在小精灵自己的悬浮窗口中，不再创建第二个悬浮窗。 */
    private var pendingTipColors: Intent? = null
    private fun intentColor(key: String, fallback: Int): Int = pendingTipColors?.getIntExtra(key, fallback) ?: fallback

    private fun showTips(labels: List<String>, indexes: List<Int>) {
        val items = labels.zip(indexes).filter { it.first.isNotBlank() }.take(3)
        val pet = mascotView ?: return
        if (items.isEmpty()) { hideTips(); return }
        pet.setTips(items,
            intentColor(ACTION_COLOR_SURFACE, 0xFFF2F0F8.toInt()),
            intentColor(ACTION_COLOR_TEXT, 0xFF302B3B.toInt()),
            intentColor(ACTION_COLOR_CLOUD, 0xFF9278D8.toInt()),
            intentColor(ACTION_COLOR_ACCENT, 0x267F6BE0),
            intentColor(ACTION_COLOR_BORDER, 0x337A70A0))
        windowParams.width = pet.overlayWidth
        windowParams.height = pet.overlayHeight
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        windowParams.x = windowParams.x.coerceIn(0, (screenW - windowParams.width).coerceAtLeast(0))
        windowParams.y = windowParams.y.coerceIn(0, (screenH - windowParams.height).coerceAtLeast(0))
        runCatching { windowManager.updateViewLayout(pet, windowParams); pet.invalidate() }
            .onFailure { android.util.Log.w("QingjianMascot", "Unable to expand mascot cloud bubble", it) }
    }

    private fun hideTips() {
        val pet = mascotView ?: return
        pet.clearTips()
        windowParams.width = (64f * resources.displayMetrics.density).toInt()
        windowParams.height = windowParams.width
        windowParams.x = windowParams.x.coerceIn(0, (resources.displayMetrics.widthPixels - windowParams.width).coerceAtLeast(0))
        windowParams.y = windowParams.y.coerceIn(0, (resources.displayMetrics.heightPixels - windowParams.height).coerceAtLeast(0))
        runCatching { windowManager.updateViewLayout(pet, windowParams); pet.invalidate() }
    }

    private fun createTouchListener(view: View) = View.OnTouchListener { _, event ->
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                view.setTag(floatArrayOf(event.rawX, event.rawY, windowParams.x.toFloat(), windowParams.y.toFloat(), 0f)); true
            }
            MotionEvent.ACTION_MOVE -> {
                val state = view.getTag() as? FloatArray ?: return@OnTouchListener true
                val dx = event.rawX - state[0]; val dy = event.rawY - state[1]
                if (abs(dx) > slop || abs(dy) > slop) state[4] = 1f
                if (state[4] > 0f) {
                    lastDragX = event.rawX.toInt(); lastDragY = event.rawY.toInt()
                    windowParams.x = (state[2] + dx).toInt().coerceIn(0, (resources.displayMetrics.widthPixels - view.width).coerceAtLeast(0))
                    windowParams.y = (state[3] + dy).toInt().coerceIn(0, (resources.displayMetrics.heightPixels - view.height).coerceAtLeast(0))
                    runCatching { windowManager.updateViewLayout(view, windowParams) }
                    pendingCopy?.let(dragHandler::removeCallbacks)
                    pendingCopy = Runnable {
                        if (getSharedPreferences(MascotAssistActivity.PREFS, MODE_PRIVATE).getBoolean(MascotAssistActivity.KEY_ENABLED, false)) MascotTextAccessibilityService.requestCopyAt(lastDragX, lastDragY)
                    }.also { dragHandler.postDelayed(it, 1_000L) }
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                pendingCopy?.let(dragHandler::removeCallbacks); pendingCopy = null
                val state = view.getTag() as? FloatArray
                if (state != null && state[4] == 0f) {
                    val pet = view as MascotView
                    if (!pet.selectTipAt(event.x, event.y)) pet.onPetTapped()
                }
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_X, windowParams.x).putInt(KEY_Y, windowParams.y).apply()
                true
            }
            MotionEvent.ACTION_CANCEL -> { pendingCopy?.let(dragHandler::removeCallbacks); pendingCopy = null; true }
            else -> true
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "悬浮小精灵", NotificationManager.IMPORTANCE_LOW).apply {
                description = "小精灵开启时保持跨应用悬浮显示"
            },
        )
    }
    private fun buildNotification(): Notification {
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID).setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("青简小精灵正在运行")
            .setContentText("点击常用功能中的「悬浮小精灵」即可关闭")
            .setContentIntent(pending).setOngoing(true).build()
    }
    override fun onDestroy() {
        hideTips()
        mascotView?.let { view -> runCatching { if (view.isAttachedToWindow) windowManager.removeView(view) } }
        mascotView = null
        if (receiverRegistered) runCatching { unregisterReceiver(screenReceiver) }
        receiverRegistered = false
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    /** Canvas 卡通宠物：漂浮、眨眼、轻触切换表情，不加载大图。 */
    private class MascotView(context: Context) : View(context) {
        private val appContext = context
        private val density = resources.displayMetrics.density
        private val tipText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13f * density; color = Color.DKGRAY }
        private val tipTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f * density; typeface = android.graphics.Typeface.DEFAULT_BOLD }
        private val tipBody = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tipBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = density }
        private val tipAccent = Paint(Paint.ANTI_ALIAS_FLAG)
        private var tipItems: List<Pair<String, Int>> = emptyList()
        private var tipRows: List<List<String>> = emptyList()
        private var tipRowHeights: List<Float> = emptyList()
        private var tipWidthDp = 0f
        private var tipHeightDp = 0f
        private var tipCloudColor = 0xFF9278D8.toInt()
        val overlayWidth: Int get() = if (tipItems.isEmpty()) (64f * density).toInt() else ((72f + tipWidthDp) * density).toInt()
        val overlayHeight: Int get() = if (tipItems.isEmpty()) (64f * density).toInt() else (maxOf(64f, tipHeightDp) * density).toInt()

        fun setTips(items: List<Pair<String, Int>>, surface: Int, text: Int, cloud: Int, accent: Int, border: Int) {
            tipItems = items.take(3)
            tipText.color = text
            tipCloudColor = cloud
            tipTitle.color = cloud
            tipBody.color = Color.argb(224, Color.red(surface), Color.green(surface), Color.blue(surface))
            tipBorder.color = border
            tipAccent.color = Color.argb(58, Color.red(accent), Color.green(accent), Color.blue(accent))
            tipWidthDp = ((tipItems.maxOfOrNull { tipText.measureText(it.first) / density } ?: 90f) + 48f).coerceIn(148f, 232f)
            val maxLinePx = (tipWidthDp - 48f) * density
            tipRows = tipItems.map { wrapTipText(it.first, maxLinePx) }
            tipRowHeights = tipRows.map { maxOf(31f, it.size * 17f + 8f) }
            tipHeightDp = 30f + tipRowHeights.sum() + 8f
            invalidate()
        }

        fun clearTips() {
            tipItems = emptyList(); tipRows = emptyList(); tipRowHeights = emptyList()
            tipWidthDp = 0f; tipHeightDp = 0f
            invalidate()
        }

        private fun wrapTipText(value: String, maxWidth: Float): List<String> {
            val result = mutableListOf<String>()
            var remaining = value.trim()
            while (remaining.isNotEmpty() && result.size < 4) {
                val count = tipText.breakText(remaining, true, maxWidth, null).coerceAtLeast(1)
                result += remaining.substring(0, count)
                remaining = remaining.substring(count)
            }
            if (remaining.isNotEmpty() && result.isNotEmpty()) result[result.lastIndex] = result.last() + "…"
            return result.ifEmpty { listOf(value) }
        }

        fun selectTipAt(x: Float, y: Float): Boolean {
            if (tipItems.isEmpty()) return false
            val localX = x / density
            val localY = y / density
            if (localX < 72f || localX > 72f + tipWidthDp) return false
            val top = (height / density - tipHeightDp) / 2f
            var cursor = top + 30f
            tipRowHeights.forEachIndexed { index, rowHeight ->
                if (localY in cursor..(cursor + rowHeight)) {
                    appContext.sendBroadcast(Intent(ACTION_TIP_SELECTED).setPackage(appContext.packageName).putExtra(EXTRA_TIP_INDEX, tipItems[index].second))
                    appContext.startService(Intent(appContext, FloatingMascotService::class.java).setAction(ACTION_HIDE_TIPS))
                    return true
                }
                cursor += rowHeight
            }
            return false
        }

        private fun drawTipBubble(canvas: Canvas) {
            canvas.save()
            canvas.scale(density, density)
            val left = 72f
            val top = (height / density - tipHeightDp) / 2f
            val right = left + tipWidthDp
            val bottom = top + tipHeightDp
            val arrow = Path().apply { moveTo(65f, top + 25f); lineTo(left + 1f, top + 18f); lineTo(left + 1f, top + 34f); close() }
            canvas.drawPath(arrow, tipBody)
            canvas.drawRoundRect(RectF(left, top, right, bottom), 15f, 15f, tipBody)
            canvas.drawPath(arrow, tipBorder)
            canvas.drawRoundRect(RectF(left, top, right, bottom), 15f, 15f, tipBorder)
            tipTitle.color = tipCloudColor
            canvas.drawText("✦  云联想", left + 12f, top + 20f, tipTitle)
            var rowTop = top + 30f
            tipRows.forEachIndexed { index, lines ->
                val rowHeight = tipRowHeights[index]
                val rowRect = RectF(left + 6f, rowTop, right - 6f, rowTop + rowHeight)
                canvas.drawRoundRect(rowRect, 8f, 8f, tipAccent)
                canvas.drawText("›", left + 11f, rowTop + 19f, tipTitle)
                lines.forEachIndexed { lineIndex, line -> canvas.drawText(line, left + 28f, rowTop + 17f + lineIndex * 17f, tipText) }
                rowTop += rowHeight
            }
            canvas.restore()
        }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var mood = 0
        private var messageUntil = 0L
        private var messageText = "你好！"
        private val face = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD66B.toInt() }
        private val eye = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF473A50.toInt() }
        private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val cheek = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF8C9D.toInt() }
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB97842.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.6f * density }
        private val ear = Path()
        fun onPetTapped() {
            mood = (mood + 1) % 4
            messageText = when (mood) { 1 -> "开心！"; 2 -> "加油！"; 3 -> "收到！"; else -> "你好！" }
            messageUntil = SystemClock.uptimeMillis() + 1500L
            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            invalidate()
        }
        fun setContextLabel(label: String) {
            messageText = label.take(6).ifBlank { "你好！" }
            messageUntil = SystemClock.uptimeMillis() + 2600L
            invalidate()
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val now = SystemClock.uptimeMillis()
            canvas.save()
            canvas.scale(density, density)
            canvas.translate(0f, sin(now / 560.0).toFloat() * 2.2f)
            paint.color = 0x55202A45; canvas.drawCircle(32f, 34f, 29f, paint)
            ear.reset(); ear.moveTo(12f,18f); ear.lineTo(14f,2f); ear.lineTo(26f,13f); ear.close()
            canvas.drawPath(ear, face); canvas.drawPath(ear, outline)
            ear.reset(); ear.moveTo(38f,13f); ear.lineTo(51f,2f); ear.lineTo(52f,20f); ear.close()
            canvas.drawPath(ear, face); canvas.drawPath(ear, outline)
            canvas.drawCircle(32f,34f,25f,face); canvas.drawCircle(32f,34f,25f,outline)
            if ((now % 3400L) < 150L) {
                paint.color = 0xFF473A50.toInt(); paint.strokeWidth = 2.4f; paint.strokeCap = Paint.Cap.ROUND
                canvas.drawLine(20f,29f,25f,29f,paint); canvas.drawLine(39f,29f,44f,29f,paint)
            } else {
                canvas.drawCircle(22.5f,29f,3.1f,eye); canvas.drawCircle(41.5f,29f,3.1f,eye)
                canvas.drawCircle(23.5f,27.8f,1f,white); canvas.drawCircle(42.5f,27.8f,1f,white)
            }
            canvas.drawOval(RectF(12f,37f,22f,42f),cheek); canvas.drawOval(RectF(42f,37f,52f,42f),cheek)
            paint.color = 0xFF8A4A54.toInt(); paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.8f; paint.strokeCap = Paint.Cap.ROUND
            canvas.drawPath(Path().apply { moveTo(28f,39f); quadTo(32f,44f,36f,39f) },paint)
            paint.style = Paint.Style.FILL
            paint.color = when (mood) { 1 -> 0xFFFF6FAE.toInt(); 2 -> 0xFF8B73FF.toInt(); else -> Color.WHITE }
            canvas.drawCircle(49f,12f,5f + sin(now / 260.0).toFloat() * 1.1f,paint)
            paint.color = when (mood) { 1 -> 0xFFFF6FAE.toInt(); 2 -> 0xFF8B73FF.toInt(); 3 -> 0xFF55C8A8.toInt(); else -> 0xFFFF80B5.toInt() }
            canvas.drawCircle(8f,45f,2f,paint)
            if (now < messageUntil) {
                val bubble = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xEEFFFFFF.toInt() }
                val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF55445F.toInt(); textSize = 8.5f; textAlign = Paint.Align.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD }
                val text = messageText
                canvas.drawRoundRect(RectF(3f, 48f, 61f, 63f), 7f, 7f, bubble)
                canvas.drawText(text, 32f, 58f, label)
            }
            canvas.restore()
            if (tipItems.isNotEmpty()) drawTipBubble(canvas)
            if (visibility == View.VISIBLE && isAttachedToWindow) postInvalidateDelayed(50L)
        }
    }
    companion object {
        const val PREFS = "floating_mascot"
        const val KEY_ENABLED = "enabled"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
        const val ACTION_STOP = "app.qingjian.inputmethod.action.STOP_FLOATING_MASCOT"
        const val ACTION_SHOW_TIPS = "app.qingjian.inputmethod.action.SHOW_MASCOT_TIPS"
        const val ACTION_HIDE_TIPS = "app.qingjian.inputmethod.action.HIDE_MASCOT_TIPS"
        const val ACTION_TIP_SELECTED = "app.qingjian.inputmethod.action.MASCOT_TIP_SELECTED"
        const val EXTRA_TIP_LABELS = "tip_labels"
        const val EXTRA_TIP_INDEXES = "tip_indexes"
        const val EXTRA_TIP_INDEX = "tip_index"
        const val ACTION_COLOR_SURFACE = "tip_surface"
        const val ACTION_COLOR_TEXT = "tip_text"
        const val ACTION_COLOR_CLOUD = "tip_cloud"
        const val ACTION_COLOR_ACCENT = "tip_accent"
        const val ACTION_COLOR_BORDER = "tip_border"
        private const val CHANNEL_ID = "qingjian_floating_mascot"
        private const val NOTIFICATION_ID = 7318
    }
}
