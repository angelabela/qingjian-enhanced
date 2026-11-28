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
import android.net.Uri
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
    private var collapsedWidthPx = 0
    private var collapsedHeightPx = 0
    private var expandedWidthPx = 0
    private var expandedHeightPx = 0
    private var dragMovePosted = false
    private val dragMoveDispatch = Runnable {
        dragMovePosted = false
        sendMascotDragBroadcast(SpriteTranslateService.ACTION_MASCOT_DRAG_MOVE)
    }
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
        } catch (error: RuntimeException) {
            android.util.Log.e("QingjianMascot", "Unable to enter foreground; preserving enabled preference", error)
            stopSelf(); return
        }
        windowManager = getSystemService(WindowManager::class.java)
        val density = resources.displayMetrics.density
        collapsedWidthPx = (68f * density).toInt()
        collapsedHeightPx = (76f * density).toInt()
        expandedWidthPx = (250f * density).toInt()
        expandedHeightPx = (120f * density).toInt()
        windowParams = WindowManager.LayoutParams(
            collapsedWidthPx, collapsedHeightPx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, (resources.displayMetrics.widthPixels - collapsedWidthPx - 18f * density).toInt()).coerceIn(0, (resources.displayMetrics.widthPixels - collapsedWidthPx).coerceAtLeast(0))
            y = prefs.getInt(KEY_Y, (resources.displayMetrics.heightPixels * 0.34f).toInt()).coerceIn(0, (resources.displayMetrics.heightPixels - collapsedHeightPx).coerceAtLeast(0))
        }
        mascotView = MascotView(this).also { view ->
            view.onDialogueVisibilityChanged = { expanded -> resizeMascotWindow(expanded) }
            view.onModeChanged = { mode -> getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_MODE, mode).apply() }
            view.setOnTouchListener(createTouchListener(view))
            try { windowManager.addView(view, windowParams) }
            catch (error: RuntimeException) {
                android.util.Log.e("QingjianMascot", "Unable to attach mascot overlay; preserving enabled preference", error)
                stopSelf(); return
            }
        }
        val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) }
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
            ACTION_SHOW_MESSAGE -> mascotView?.setMessage(intent.getStringExtra(EXTRA_MESSAGE).orEmpty(), animation = intent.getStringExtra(EXTRA_ANIMATION).orEmpty())
            ACTION_SHOW_PHONE -> mascotView?.setPhonePrompt(intent.getStringExtra(EXTRA_PHONE_NUMBER).orEmpty())
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
        pet.invalidate()
    }

    private fun hideTips() {
        val pet = mascotView ?: return
        pet.clearTips()
        pet.invalidate()
    }

    /** 气泡显示时扩大窗口；气泡消失后缩回宠物本体，释放透明区域的触摸命中范围。 */
    private fun resizeMascotWindow(expanded: Boolean) {
        val view = mascotView ?: return
        if (!view.isAttachedToWindow) return
        val screenWidth = resources.displayMetrics.widthPixels
        val wasExpanded = windowParams.width != collapsedWidthPx
        if (expanded) {
            val anchorX = if (wasExpanded && view.bubbleOnLeft) windowParams.x + windowParams.width - collapsedWidthPx else windowParams.x
            val targetWidth = view.dialogueWindowWidthPx
            val targetHeight = view.dialogueWindowHeightPx
            val requiredExtra = (targetWidth - collapsedWidthPx).coerceAtLeast(0)
            val leftSpace = anchorX
            val rightSpace = screenWidth - (anchorX + collapsedWidthPx)
            val putLeft = rightSpace < requiredExtra && leftSpace >= requiredExtra
            view.setBubbleOnLeft(putLeft)
            windowParams.x = if (putLeft) anchorX - requiredExtra else anchorX
            windowParams.width = targetWidth
            windowParams.height = targetHeight
        } else {
            if (wasExpanded && view.bubbleOnLeft) windowParams.x += windowParams.width - collapsedWidthPx
            view.setBubbleOnLeft(false)
            windowParams.width = collapsedWidthPx
            windowParams.height = collapsedHeightPx
        }
        windowParams.x = windowParams.x.coerceIn(0, (screenWidth - windowParams.width).coerceAtLeast(0))
        windowParams.y = windowParams.y.coerceIn(0, (resources.displayMetrics.heightPixels - windowParams.height).coerceAtLeast(0))
        runCatching { windowManager.updateViewLayout(view, windowParams) }
            .onFailure { android.util.Log.w("QingjianMascot", "Unable to resize mascot overlay", it) }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_X, windowParams.x).putInt(KEY_Y, windowParams.y).apply()
    }

    /** 把同一只悬浮小精灵的拖动轨迹交给翻译无障碍服务；不再创建第二只可拖动精灵。 */
    private fun dispatchMascotTranslationDrag(action: String) {
        val pet = mascotView ?: return
        if (action == SpriteTranslateService.ACTION_MASCOT_DRAG_MOVE) {
            // 同一帧内的多个触摸 MOVE 合并，只广播最新坐标，避免消息队列堆积造成准星拖尾。
            if (!dragMovePosted) {
                dragMovePosted = true
                pet.postOnAnimation(dragMoveDispatch)
            }
            return
        }
        if (action == SpriteTranslateService.ACTION_MASCOT_DRAG_STOP) {
            pet.removeCallbacks(dragMoveDispatch)
            dragMovePosted = false
            // STOP 广播本身携带最新坐标，复制命中点直接使用松手位置。
        }
        sendMascotDragBroadcast(action)
    }

    private fun sendMascotDragBroadcast(action: String) {
        val pet = mascotView ?: return
        val x = windowParams.x + pet.mascotOffsetPx
        val y = windowParams.y
        sendBroadcast(Intent(action).setPackage(packageName)
            .putExtra(SpriteTranslateService.EXTRA_MASCOT_X, x)
            .putExtra(SpriteTranslateService.EXTRA_MASCOT_Y, y)
            .putExtra(SpriteTranslateService.EXTRA_MASCOT_WIDTH, collapsedWidthPx)
            .putExtra(SpriteTranslateService.EXTRA_MASCOT_HEIGHT, collapsedHeightPx)
            .putExtra(SpriteTranslateService.EXTRA_MASCOT_MODE, pet.mode))
    }

    private fun createTouchListener(view: View) = View.OnTouchListener { _, event ->
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                view.setTag(floatArrayOf(event.rawX, event.rawY, windowParams.x.toFloat(), windowParams.y.toFloat(), 0f, 0f)); true
            }
            MotionEvent.ACTION_MOVE -> {
                val state = view.getTag() as? FloatArray ?: return@OnTouchListener true
                val dx = event.rawX - state[0]; val dy = event.rawY - state[1]
                if (abs(dx) > slop || abs(dy) > slop) state[4] = 1f
                if (state[4] > 0f) {
                    windowParams.x = (state[2] + dx).toInt().coerceIn(0, (resources.displayMetrics.widthPixels - view.width).coerceAtLeast(0))
                    windowParams.y = (state[3] + dy).toInt().coerceIn(0, (resources.displayMetrics.heightPixels - view.height).coerceAtLeast(0))
                    runCatching { windowManager.updateViewLayout(view, windowParams) }
                    if (SpriteTranslateService.isEnabled(this)) {
                        if (state[5] == 0f) {
                            dispatchMascotTranslationDrag(SpriteTranslateService.ACTION_MASCOT_DRAG_START)
                            state[5] = 1f
                        }
                        dispatchMascotTranslationDrag(SpriteTranslateService.ACTION_MASCOT_DRAG_MOVE)
                    } else if (state[5] == 0f) {
                        state[5] = 2f
                        (view as? MascotView)?.setMessage("请先授权小精灵无障碍服务，再使用翻译和复制")
                    }
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                val state = view.getTag() as? FloatArray
                if (state != null && state[5] == 1f) dispatchMascotTranslationDrag(SpriteTranslateService.ACTION_MASCOT_DRAG_STOP)
                if (state != null && state[4] == 0f) {
                    val pet = view as MascotView
                    if (!pet.selectTipAt(event.x, event.y) && !pet.onPhoneBubbleTapped(event.x, event.y)) pet.onPetTapped()
                }
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_X, windowParams.x).putInt(KEY_Y, windowParams.y).apply()
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                val state = view.getTag() as? FloatArray
                if (state != null && state[5] == 1f) dispatchMascotTranslationDrag(SpriteTranslateService.ACTION_MASCOT_DRAG_STOP)
                true
            }
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
        android.util.Log.w("QingjianMascot", "Floating mascot service destroyed; enabled=" + getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ENABLED, false))
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
        private val dialogueText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = 0xFF51445C.toInt() }
        private val dialogueBody = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF5FFFFFF.toInt() }
        private val dialogueBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = density; color = 0x55A58BB8 }
        private val dialogueAccent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x267F6BE0 }
        private var tipItems: List<Pair<String, Int>> = emptyList()
        private var messageUntil = 0L
        private var messageText = "你好呀！"
        private var animationType = "idle"
        private var animationStartedAt = 0L
        private var pendingPhoneNumber: String? = null
        private var dialogueExpanded = false
        private var expiryRunnable: Runnable? = null
        var onDialogueVisibilityChanged: ((Boolean) -> Unit)? = null
        var onModeChanged: ((String) -> Unit)? = null
        var mode: String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, MODE_TRANSLATE) ?: MODE_TRANSLATE
            private set
        private var bubbleWidthDp = 112f
        private var bubbleHeightDp = 32f
        private var dialogueLineCount = 1
        var bubbleOnLeft: Boolean = false
            private set
        val dialogueWindowWidthPx: Int get() = ((bubbleWidthDp + 84f) * density).toInt()
        val dialogueWindowHeightPx: Int get() = (maxOf(76f, bubbleHeightDp + 16f) * density).toInt()
        private val bubbleLeftDp: Float get() = if (bubbleOnLeft) 8f else 68f
        private val mascotLeftDp: Float get() = if (bubbleOnLeft) bubbleWidthDp + 16f else 0f
        val mascotOffsetPx: Int get() = (mascotLeftDp * density).toInt()

        fun setBubbleOnLeft(left: Boolean) {
            if (bubbleOnLeft == left) return
            bubbleOnLeft = left
            invalidate()
        }

        private fun updateDialogueMetrics() {
            // TextPaint 的测量单位是 px；气泡布局单位是 dp。先统一换算，避免高密度屏幕把气泡撑大。
            val maxTextWidthPx = (if (tipItems.isNotEmpty()) 160f else 172f) * density
            val values = if (tipItems.isNotEmpty()) tipItems.map { it.first } else listOf(messageText)
            val lines = values.flatMap { value ->
                var rest = value.trim()
                val result = ArrayList<String>()
                while (rest.isNotEmpty()) {
                    val count = dialogueText.breakText(rest, true, maxTextWidthPx, null).coerceAtLeast(1)
                    result += rest.substring(0, count)
                    rest = rest.substring(count)
                }
                result
            }
            dialogueLineCount = lines.size.coerceAtLeast(1)
            val widestDp = (lines.maxOfOrNull { dialogueText.measureText(it) } ?: 80f) / density
            bubbleWidthDp = (widestDp + 24f).coerceIn(68f, 196f)
            bubbleHeightDp = (dialogueLineCount * 13f + 12f).coerceIn(28f, 148f)
        }

        private fun setDialogueExpanded(expanded: Boolean) {
            if (dialogueExpanded == expanded) {
                // 内容变化时即使气泡仍处于展开状态，也要重新测量并更新窗口尺寸。
                if (expanded) onDialogueVisibilityChanged?.invoke(true)
                return
            }
            dialogueExpanded = expanded
            onDialogueVisibilityChanged?.invoke(expanded)
        }

        private fun scheduleExpiry(duration: Long) {
            expiryRunnable?.let(::removeCallbacks)
            val expectedUntil = messageUntil
            expiryRunnable = Runnable {
                if (messageUntil == expectedUntil && SystemClock.uptimeMillis() >= expectedUntil) {
                    tipItems = emptyList()
                    pendingPhoneNumber = null
                    messageText = ""
                    setDialogueExpanded(false)
                    invalidate()
                }
            }.also { postDelayed(it, duration) }
        }

        fun setTips(items: List<Pair<String, Int>>, surface: Int, text: Int, cloud: Int, accent: Int, border: Int) {
            tipItems = items.take(3)
            pendingPhoneNumber = null
            dialogueText.textSize = 9.5f
            dialogueText.color = text
            dialogueBody.color = Color.argb(242, Color.red(surface), Color.green(surface), Color.blue(surface))
            dialogueBorder.color = border
            dialogueAccent.color = Color.argb(45, Color.red(accent), Color.green(accent), Color.blue(accent))
            messageText = ""
            updateDialogueMetrics()
            // 云联想提示保持显示，直到被用户选中、主动隐藏或被下一条提示替换。
            // 避免用户还没来得及阅读，提示框就自动消失。
            messageUntil = Long.MAX_VALUE
            expiryRunnable?.let(::removeCallbacks)
            expiryRunnable = null
            setDialogueExpanded(true)
            invalidate()
        }

        fun clearTips() {
            if (tipItems.isEmpty()) return
            tipItems = emptyList()
            messageUntil = 0L
            messageText = ""
            expiryRunnable?.let(::removeCallbacks)
            expiryRunnable = null
            setDialogueExpanded(false)
            invalidate()
        }

        fun selectTipAt(x: Float, y: Float): Boolean {
            if (tipItems.isEmpty()) return false
            val px = x / density
            val py = y / density
            if (px !in bubbleLeftDp..(bubbleLeftDp + bubbleWidthDp) || py !in 8f..(8f + bubbleHeightDp)) return false
            var rowTop = 8f
            val item = tipItems.firstOrNull { candidate ->
                var rest = candidate.first
                var firstLine = true
                var lineCount = 0
                while (rest.isNotEmpty()) {
                    val count = dialogueText.breakText(
                        rest, true, (bubbleWidthDp - if (firstLine) 24f else 12f) * density, null,
                    ).coerceAtLeast(1)
                    rest = rest.substring(count)
                    firstLine = false
                    lineCount++
                }
                val rowBottom = rowTop + lineCount.coerceAtLeast(1) * 13f
                val hit = py in rowTop..rowBottom
                rowTop = rowBottom
                hit
            } ?: return false
            appContext.sendBroadcast(Intent(ACTION_TIP_SELECTED).setPackage(appContext.packageName).putExtra(EXTRA_TIP_INDEX, item.second))
            appContext.startService(Intent(appContext, FloatingMascotService::class.java).setAction(ACTION_HIDE_TIPS))
            return true
        }

        private fun drawDialogueBubble(canvas: Canvas, now: Long) {
            if (tipItems.isEmpty() && now >= messageUntil) return
            canvas.save()
            canvas.scale(density, density)
            // 统一使用小精灵最初的圆角提示语气泡，不绘制箭头或第二个浮窗。
            val rect = RectF(bubbleLeftDp, 8f, bubbleLeftDp + bubbleWidthDp, 8f + bubbleHeightDp)
            canvas.drawRoundRect(rect, 12f, 12f, dialogueBody)
            canvas.drawRoundRect(rect, 12f, 12f, dialogueBorder)
            if (tipItems.isNotEmpty()) {
                var baseline = 8f + (bubbleHeightDp - dialogueLineCount * 13f) / 2f - dialogueText.ascent()
                tipItems.take(3).forEach { item ->
                    var rest = item.first
                    var firstLine = true
                    while (rest.isNotEmpty() && baseline < 8f + bubbleHeightDp - 6f) {
                        val count = dialogueText.breakText(rest, true, (bubbleWidthDp - if (firstLine) 24f else 12f) * density, null).coerceAtLeast(1)
                        val visible = rest.substring(0, count)
                        if (firstLine) canvas.drawCircle(bubbleLeftDp + 6f, baseline - 4f, 1.8f, dialogueAccent)
                        canvas.drawText(visible, bubbleLeftDp + if (firstLine) 10f else 6f, baseline, dialogueText)
                        rest = rest.substring(count)
                        firstLine = false
                        baseline += 13f
                    }
                }
            } else {
                var rest = messageText.ifBlank { "我在这里陪你呀！" }
                var baseline = 8f + (bubbleHeightDp - dialogueLineCount * 13f) / 2f - dialogueText.ascent()
                var lines = 0
                while (rest.isNotEmpty() && lines < 9) {
                    val count = dialogueText.breakText(rest, true, (bubbleWidthDp - 12f) * density, null).coerceAtLeast(1)
                    val visible = rest.substring(0, count)
                    canvas.drawText(visible, bubbleLeftDp + 6f, baseline, dialogueText)
                    rest = rest.substring(count)
                    baseline += 13f
                    lines++
                }
                if (rest.isNotEmpty()) canvas.drawText("…", bubbleLeftDp + bubbleWidthDp - 12f, 8f + bubbleHeightDp - 8f, dialogueText)
            }
            canvas.restore()
        }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var mood = 0
        private val face = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD66B.toInt() }
        private val eye = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF473A50.toInt() }
        private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val cheek = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF8C9D.toInt() }
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB97842.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.6f * density }
        private val ear = Path()
        fun onPetTapped() {
            mode = if (mode == MODE_TRANSLATE) MODE_COPY else MODE_TRANSLATE
            onModeChanged?.invoke(mode)
            mood = if (mode == MODE_TRANSLATE) 2 else 1
            setMessage(if (mode == MODE_TRANSLATE) "翻译模式已开启\n拖动我，让准星指向文字" else "复制模式已开启\n拖动我，松开即可复制准星文字", 3_200L, if (mode == MODE_TRANSLATE) "cheer" else "wave")
            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        }
        fun setMessage(value: String, duration: Long = 12_000L, animation: String = "idle") {
            tipItems = emptyList()
            pendingPhoneNumber = null
            dialogueText.textSize = 11f
            dialogueText.color = 0xFF51445C.toInt()
            dialogueBody.color = 0xF5FFFFFF.toInt()
            dialogueBorder.color = 0x55A58BB8
            dialogueAccent.color = 0x267F6BE0
            messageText = value.trim().take(160).ifBlank { "我在这里陪你呀！" }
            updateDialogueMetrics()
            messageUntil = SystemClock.uptimeMillis() + duration
            animationType = animation
            animationStartedAt = SystemClock.uptimeMillis()
            setDialogueExpanded(true)
            scheduleExpiry(duration)
            invalidate()
        }
        fun setPhonePrompt(number: String) {
            if (number.isBlank()) return
            setMessage("发现电话号码：$number  点击提示框打开拨号器", 15_000L)
            pendingPhoneNumber = number
        }
        fun onPhoneBubbleTapped(x: Float, y: Float): Boolean {
            val number = pendingPhoneNumber ?: return false
            val px = x / density
            val py = y / density
            if (px !in bubbleLeftDp..(bubbleLeftDp + bubbleWidthDp) || py !in 8f..(8f + bubbleHeightDp)) return false
            pendingPhoneNumber = null
            runCatching {
                appContext.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { android.util.Log.w("QingjianMascot", "Unable to open phone dialer", it) }
            return true
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val now = SystemClock.uptimeMillis()
            canvas.save()
            canvas.scale(density, density)
            val elapsed = (now - animationStartedAt).coerceAtLeast(0L).toFloat()
            val active = elapsed < 1_900f
            val bounce = if (active && animationType == "happy") -abs(sin(elapsed / 105f)).toFloat() * 7f else 0f
            val nod = if (active && animationType == "thanks") abs(sin(elapsed / 125f)).toFloat() * 2.8f else 0f
            // 顶部预留 8dp，再做上浮动画，避免耳朵在负向弹跳时被窗口裁掉。
            canvas.translate(mascotLeftDp, 8f + sin(now / 560.0).toFloat() * 1.5f + bounce + nod)
            canvas.translate(32f, 38f)
            canvas.scale(0.88f, 0.88f)
            canvas.translate(-32f, -38f)
            val breathe = sin(now / 760.0).toFloat()
            val bob = sin(now / 510.0).toFloat() * 1.7f + breathe * 0.7f
            val squash = if (active) sin(elapsed / 95f).toFloat() * 0.025f else sin(now / 1700.0).toFloat() * 0.012f
            canvas.translate(0f, bob)
            canvas.translate(32f, 38f)
            canvas.scale(1f + squash, 1f - squash)
            canvas.translate(-32f, -38f)
            paint.color = 0x55202A45
            canvas.drawOval(RectF(14f, 60f, 50f, 67f), paint)
            // Small rounded body and feet sit behind the head, giving the pet a full silhouette.
            canvas.drawOval(RectF(20f, 46f, 44f, 66f), face)
            canvas.drawOval(RectF(20f, 46f, 44f, 66f), outline)
            canvas.drawOval(RectF(19f, 60f, 28f, 67f), face)
            canvas.drawOval(RectF(36f, 60f, 45f, 67f), face)
            val earWiggle = sin(now / 930.0).toFloat() * 2.2f
            ear.reset(); ear.moveTo(12f,18f); ear.lineTo(14f,2f); ear.lineTo(26f,13f); ear.close()
            canvas.save(); canvas.rotate(earWiggle, 18f, 15f)
            canvas.drawPath(ear, face); canvas.drawPath(ear, outline); canvas.restore()
            ear.reset(); ear.moveTo(38f,13f); ear.lineTo(51f,2f); ear.lineTo(52f,20f); ear.close()
            canvas.save(); canvas.rotate(-earWiggle * 0.7f, 46f, 15f)
            canvas.drawPath(ear, face); canvas.drawPath(ear, outline); canvas.restore()
            canvas.drawCircle(32f,34f,25f,face); canvas.drawCircle(32f,34f,25f,outline)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.2f
            paint.color = if (mode == MODE_TRANSLATE) 0xFF39C9B4.toInt() else 0xFFFFA34F.toInt()
            canvas.drawCircle(32f, 34f, 27.8f + sin(now / 430.0).toFloat() * 0.6f, paint)
            paint.style = Paint.Style.FILL
            if (active) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3.1f
                paint.strokeCap = Paint.Cap.ROUND
                paint.color = 0xFFFFA66E.toInt()
                when (animationType) {
                    "wave" -> {
                        val swing = sin(elapsed / 85f).toFloat() * 5f
                        canvas.drawLine(49f, 40f, 58f, 25f + swing, paint)
                        canvas.drawCircle(58f, 24f + swing, 3f, cheek)
                    }
                    "cheer" -> {
                        canvas.drawLine(15f, 41f, 6f, 24f, paint)
                        canvas.drawLine(49f, 41f, 58f, 24f, paint)
                        canvas.drawCircle(6f, 23f, 3f, cheek)
                        canvas.drawCircle(58f, 23f, 3f, cheek)
                    }
                    "thanks" -> {
                        canvas.drawLine(15f, 41f, 24f, 47f, paint)
                        canvas.drawLine(49f, 41f, 40f, 47f, paint)
                    }
                    "comfort" -> {
                        canvas.drawLine(14f, 41f, 25f, 46f, paint)
                        canvas.drawLine(50f, 41f, 39f, 46f, paint)
                        paint.style = Paint.Style.FILL
                        paint.color = 0xFFFF80B5.toInt()
                        canvas.drawCircle(57f, 14f, 3f, paint)
                    }
                }
                paint.style = Paint.Style.FILL
            }
            val blinkPhase = now % 4300L
            val blinking = blinkPhase < 150L || blinkPhase in 2470L..2590L
            val gaze = sin(now / 1250.0).toFloat() * 0.9f
            if (blinking) {
                paint.color = 0xFF473A50.toInt(); paint.strokeWidth = 2.5f; paint.strokeCap = Paint.Cap.ROUND
                canvas.drawLine(19f,29f,26f,29f,paint); canvas.drawLine(38f,29f,45f,29f,paint)
            } else {
                canvas.drawCircle(22.5f,29f,4.1f,white); canvas.drawCircle(41.5f,29f,4.1f,white)
                canvas.drawCircle(22.5f + gaze,29.4f,2.7f,eye); canvas.drawCircle(41.5f + gaze,29.4f,2.7f,eye)
                canvas.drawCircle(23.4f + gaze,28.2f,1f,white); canvas.drawCircle(42.4f + gaze,28.2f,1f,white)
            }
            canvas.drawOval(RectF(12f,37f,22f,42f),cheek); canvas.drawOval(RectF(42f,37f,52f,42f),cheek)
            paint.color = 0xFF8A4A54.toInt(); paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.9f; paint.strokeCap = Paint.Cap.ROUND
            val mouth = Path().apply {
                moveTo(27f,39f)
                when (mood) {
                    1 -> quadTo(32f,47f,37f,39f)
                    2 -> quadTo(32f,45f,37f,39f)
                    else -> quadTo(32f,43f,37f,39f)
                }
            }
            canvas.drawPath(mouth, paint)
            if (active && (animationType == "cheer" || animationType == "happy")) {
                paint.style = Paint.Style.FILL
                paint.color = 0xFFFF7C9B.toInt()
                canvas.drawOval(RectF(29f,42f,35f,47f), paint)
            }
            paint.style = Paint.Style.FILL
            paint.color = when (mood) { 1 -> 0xFFFF6FAE.toInt(); 2 -> 0xFF8B73FF.toInt(); else -> Color.WHITE }
            canvas.drawCircle(49f,12f,5f + sin(now / 260.0).toFloat() * 1.1f,paint)
            paint.color = when (mood) { 1 -> 0xFFFF6FAE.toInt(); 2 -> 0xFF8B73FF.toInt(); 3 -> 0xFF55C8A8.toInt(); else -> 0xFFFF80B5.toInt() }
            canvas.drawCircle(8f,45f,2f,paint)
            canvas.restore()
            drawDialogueBubble(canvas, now)
            if (visibility == View.VISIBLE && isAttachedToWindow) postInvalidateDelayed(32L)
        }
    }
    companion object {
        const val PREFS = "floating_mascot"
        const val KEY_ENABLED = "enabled"
        const val KEY_MODE = "interaction_mode"
        const val MODE_TRANSLATE = "translate"
        const val MODE_COPY = "copy"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
        const val ACTION_STOP = "app.qingjian.inputmethod.action.STOP_FLOATING_MASCOT"
        const val ACTION_SHOW_TIPS = "app.qingjian.inputmethod.action.SHOW_MASCOT_TIPS"
        const val ACTION_HIDE_TIPS = "app.qingjian.inputmethod.action.HIDE_MASCOT_TIPS"
        const val ACTION_SHOW_MESSAGE = "app.qingjian.inputmethod.action.SHOW_MASCOT_MESSAGE"
        const val ACTION_SHOW_PHONE = "app.qingjian.inputmethod.action.SHOW_MASCOT_PHONE"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_ANIMATION = "animation"
        const val EXTRA_PHONE_NUMBER = "phone_number"
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
