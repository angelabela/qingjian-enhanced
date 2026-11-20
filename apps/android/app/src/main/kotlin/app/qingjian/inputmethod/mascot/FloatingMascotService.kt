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
import android.text.TextUtils
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.IBinder
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
    private var tipsView: TipBubbleView? = null
    private var tipsParams: WindowManager.LayoutParams? = null
    private var receiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            mascotView?.visibility = if (intent.action == Intent.ACTION_SCREEN_OFF) View.GONE else View.VISIBLE
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
        val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT) }
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

    /** 云联想数据直接进入小精灵气泡；气泡独立成窗，避免扩大宠物触摸热区。 */
    private var pendingTipColors: Intent? = null

    private fun intentColor(key: String, fallback: Int): Int =
        pendingTipColors?.getIntExtra(key, fallback) ?: fallback

    private fun showTips(labels: List<String>, indexes: List<Int>) {
        val items = labels.zip(indexes).filter { it.first.isNotBlank() }.take(3)
        if (items.isEmpty() || mascotView == null) {
            hideTips()
            return
        }
        val existing = tipsView
        if (existing != null && existing.isAttachedToWindow) {
            existing.updateItems(items)
            positionTipsBubble()
            return
        }
        val density = resources.displayMetrics.density
        val view = TipBubbleView(
            this,
            items,
            intentColor(ACTION_COLOR_SURFACE, 0xFFF2F0F8.toInt()),
            intentColor(ACTION_COLOR_TEXT, 0xFF302B3B.toInt()),
            intentColor(ACTION_COLOR_CLOUD, 0xFF9278D8.toInt()),
            intentColor(ACTION_COLOR_ACCENT, 0x267F6BE0),
            intentColor(ACTION_COLOR_BORDER, 0x337A70A0),
        ) { candidateIndex ->
            sendBroadcast(Intent(ACTION_TIP_SELECTED).setPackage(packageName).putExtra(EXTRA_TIP_INDEX, candidateIndex))
            hideTips()
        }
        val width = minOf((232f * density).toInt(), (resources.displayMetrics.widthPixels - 16f * density).toInt().coerceAtLeast(1))
        val height = view.bubbleHeight
        val params = WindowManager.LayoutParams(
            width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        tipsView = view
        tipsParams = params
        runCatching {
            windowManager.addView(view, params)
            view.alpha = 0f
            view.scaleX = 0.96f
            view.scaleY = 0.96f
            view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180L).start()
            positionTipsBubble()
        }.onFailure {
            tipsView = null
            tipsParams = null
            android.util.Log.w("QingjianMascot", "Unable to show cloud suggestion bubble", it)
        }
    }

    private fun positionTipsBubble() {
        val view = tipsView ?: return
        val params = tipsParams ?: return
        val pet = mascotView ?: return
        if (!view.isAttachedToWindow) return
        val density = resources.displayMetrics.density
        params.height = view.bubbleHeight
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val gap = (8f * density).toInt()
        val rightSide = windowParams.x < screenW / 2
        val proposedX = if (rightSide) windowParams.x + pet.width + gap else windowParams.x - params.width - gap
        val proposedY = windowParams.y + (pet.height - params.height) / 2
        params.x = proposedX.coerceIn(0, (screenW - params.width).coerceAtLeast(0))
        params.y = proposedY.coerceIn((12f * density).toInt(), (screenH - params.height - 48f * density).toInt().coerceAtLeast(0))
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun hideTips() {
        tipsView?.let { view -> runCatching { if (view.isAttachedToWindow) windowManager.removeView(view) } }
        tipsView = null
        tipsParams = null
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
                    windowParams.x = (state[2] + dx).toInt().coerceIn(0, (resources.displayMetrics.widthPixels - view.width).coerceAtLeast(0))
                    windowParams.y = (state[3] + dy).toInt().coerceIn(0, (resources.displayMetrics.heightPixels - view.height).coerceAtLeast(0))
                    runCatching { windowManager.updateViewLayout(view, windowParams) }
                    positionTipsBubble()
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                val state = view.getTag() as? FloatArray
                if (state != null && state[4] == 0f) (view as MascotView).onPetTapped()
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_X, windowParams.x).putInt(KEY_Y, windowParams.y).apply()
                true
            }
            MotionEvent.ACTION_CANCEL -> true
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

    /** 云联想气泡：最多三条，内容可点击上屏；与宠物本体分窗，空白区域不会挡住底层应用。 */
    private class TipBubbleView(
        context: Context,
        items: List<Pair<String, Int>>,
        surfaceColor: Int,
        textColor: Int,
        cloudColor: Int,
        accentColor: Int,
        borderColor: Int,
        private val onSelect: (Int) -> Unit,
    ) : View(context) {
        private val density = resources.displayMetrics.density
        private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(244, Color.red(surfaceColor), Color.green(surfaceColor), Color.blue(surfaceColor))
        }
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = borderColor; style = Paint.Style.STROKE; strokeWidth = density }
        private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cloudColor; textSize = 11f * density; typeface = android.graphics.Typeface.DEFAULT_BOLD }
        private val text = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = textColor; textSize = 13f * density; typeface = android.graphics.Typeface.DEFAULT }
        private val star = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cloudColor; textSize = 14f * density; typeface = android.graphics.Typeface.DEFAULT_BOLD }
        private val active = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(0x32, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
        }
        private var entries: List<Pair<String, Int>> = items.take(3)
        private var pressed = -1
        val bubbleHeight: Int get() = ((38f + entries.size * 31f) * density).toInt()

        fun updateItems(items: List<Pair<String, Int>>) {
            entries = items.take(3)
            requestLayout()
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), bubbleHeight)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawRoundRect(RectF(0.5f * density, 0.5f * density, w - 0.5f * density, h - 0.5f * density), 17f * density, 17f * density, body)
            canvas.drawRoundRect(RectF(0.5f * density, 0.5f * density, w - 0.5f * density, h - 0.5f * density), 17f * density, 17f * density, border)
            canvas.drawText("✦  云联想", 14f * density, 22f * density, title)
            entries.forEachIndexed { index, entry ->
                val top = (29f + index * 31f) * density
                val row = RectF(8f * density, top, w - 8f * density, top + 27f * density)
                if (pressed == index) canvas.drawRoundRect(row, 9f * density, 9f * density, active)
                canvas.drawText("›", 15f * density, top + 18f * density, star)
                val available = w - 48f * density
                val label = TextUtils.ellipsize(entry.first, text, available, TextUtils.TruncateAt.END).toString()
                canvas.drawText(label, 32f * density, top + 18f * density, text)
            }
        }

        private fun rowAt(y: Float): Int {
            val row = ((y / density - 29f) / 31f).toInt()
            if (y < 29f * density) return -1
            return row.takeIf { it in entries.indices && y <= (29f + it * 31f + 27f) * density } ?: -1
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressed = rowAt(event.y)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val next = rowAt(event.y)
                    if (next != pressed) { pressed = next; invalidate() }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val selected = rowAt(event.y)
                    if (selected >= 0 && selected == pressed) onSelect(entries[selected].second)
                    pressed = -1
                    invalidate()
                    performClick()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    pressed = -1
                    invalidate()
                    return true
                }
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    /** Canvas 卡通宠物：漂浮、眨眼、轻触切换表情，不加载大图。 */
    private class MascotView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var mood = 0
        private var messageUntil = 0L
        private val face = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD66B.toInt() }
        private val eye = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF473A50.toInt() }
        private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val cheek = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF8C9D.toInt() }
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB97842.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.6f * density }
        private val ear = Path()
        fun onPetTapped() {
            mood = (mood + 1) % 4
            messageUntil = SystemClock.uptimeMillis() + 1500L
            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            invalidate()
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val now = SystemClock.uptimeMillis()
            canvas.save()
            canvas.scale(minOf(width.toFloat(), height.toFloat()) / 64f, minOf(width.toFloat(), height.toFloat()) / 64f)
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
                val text = when (mood) { 1 -> "开心！"; 2 -> "加油！"; 3 -> "收到！"; else -> "你好！" }
                canvas.drawRoundRect(RectF(3f, 48f, 61f, 63f), 7f, 7f, bubble)
                canvas.drawText(text, 32f, 58f, label)
            }
            canvas.restore()
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
