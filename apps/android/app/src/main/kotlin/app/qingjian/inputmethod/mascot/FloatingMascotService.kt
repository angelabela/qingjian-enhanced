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
            stopSelf(startId); return START_NOT_STICKY
        }
        return START_STICKY
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
        private const val CHANNEL_ID = "qingjian_floating_mascot"
        private const val NOTIFICATION_ID = 7318
    }
}
