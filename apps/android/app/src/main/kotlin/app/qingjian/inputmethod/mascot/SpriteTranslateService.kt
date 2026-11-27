package app.qingjian.inputmethod.mascot

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.util.DisplayMetrics
import android.util.LruCache
import android.util.TypedValue
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import app.qingjian.inputmethod.R
import app.qingjian.inputmethod.cloud.CloudConfig
import app.qingjian.inputmethod.core.TranslationEngine
import kotlin.math.*

/**
 * 浮动小精灵 + 十字准星：拖着小精灵划过屏幕，准星指到哪段文字，就调用青简当前学习语言的翻译目标显示在气泡里。
 *
 * - 小精灵不动：准星动画收起；开始拖动：准星动画展开并跟随。
 * - 准星靠近屏幕边缘时，自动换到小精灵的另一侧，不会跑出屏幕。
 * - 取字用无障碍接口（读屏幕上控件的文字），翻译复用青简现有 Rust 翻译核心与云端配置。
 */
class SpriteTranslateService : AccessibilityService() {

    // ───────── 尺寸（dp）与参数 ─────────
    private companion object {
        const val SPRITE = 56f        // 小精灵
        const val CROSS = 88f         // 准星本身
        const val DIST = 84f          // 准星中心到小精灵中心的距离
        const val BOX = 2 * (DIST + CROSS / 2)   // 准星窗口：能装下任何方向的准星
        const val IDLE_MS = 350L      // 手指停住多久后收起准星
        const val QUERY_GAP_MS = 60L  // 取字节流
        const val DEBOUNCE_MS = 120L  // 指到新文字后等多久再翻译
        const val BUBBLE_HOLD_MS = 3000L
        const val MAX_CHARS = 400
        const val CORE_POLL_MS = 100L
        const val CORE_GIVE_UP_MS = 15_000L
        // 准星相对小精灵的优先方位（度，0=右，-90=上），依次尝试
        val ANGLES = doubleArrayOf(-90.0, -45.0, -135.0, 0.0, 180.0, 45.0, 135.0, 90.0)
    }

    private lateinit var wm: WindowManager
    private lateinit var spriteView: ImageView
    private lateinit var crossView: CrosshairView
    private lateinit var bubble: TextView
    private lateinit var spriteLp: WindowManager.LayoutParams
    private lateinit var crossLp: WindowManager.LayoutParams
    private lateinit var bubbleLp: WindowManager.LayoutParams

    private val ui = Handler(Looper.getMainLooper())
    private val worker = HandlerThread("sprite-hit").apply { start() }
    private val bg by lazy { Handler(worker.looper) }
    private var translator: TranslationEngine? = null
    private var translatorReady = false
    private var translatorLoadFailed = false
    private var translationStartedAt = 0L
    private val cache = LruCache<String, String>(300)

    private var density = 1f
    private var screenW = 0
    private var screenH = 0
    private var crossShown = false
    private var lastQueryAt = 0L
    private var lastText: String? = null
    private var translateGen = 0
    private var targetOff = PointF(0f, 0f)
    private var revealAnim: ValueAnimator? = null
    private var offsetAnim: ValueAnimator? = null

    private val idleRun = Runnable { hideCross() }
    private val hideBubbleRun = Runnable { bubble.animate().alpha(0f).setDuration(150).start(); lastText = null }

    // ───────── 生命周期 ─────────

    override fun onServiceConnected() {
        density = resources.displayMetrics.density
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        readScreen()
        translator = TranslationEngine(this) { loaded ->
            translatorReady = loaded
            translatorLoadFailed = !loaded
        }
        crossView = CrosshairView(this, dp(CROSS))
        crossLp = lp(dp(BOX), dp(BOX), touchable = false)

        bubble = TextView(this).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f)); maxWidth = dp(280f)
            background = GradientDrawable().apply { cornerRadius = dp(12f).toFloat(); setColor(0xE6202124.toInt()) }
            alpha = 0f
        }
        bubbleLp = lp(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, touchable = false)

        spriteView = ImageView(this).apply {
            setImageResource(R.drawable.ic_sprite)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        spriteLp = lp(dp(SPRITE), dp(SPRITE), touchable = true).apply {
            x = screenW - dp(SPRITE) - dp(12f); y = screenH / 3
        }

        // 先加准星和气泡，小精灵最后加，保证在最上面接收触摸
        wm.addView(crossView, crossLp)
        wm.addView(bubble, bubbleLp)
        wm.addView(spriteView, spriteLp)

        targetOff = PointF(0f, -dp(DIST).toFloat())
        crossView.offX = targetOff.x; crossView.offY = targetOff.y
        placeAll()
        attachTouch()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        readScreen()
        spriteLp.x = spriteLp.x.coerceIn(0, max(0, screenW - spriteLp.width))
        spriteLp.y = spriteLp.y.coerceIn(0, max(0, screenH - spriteLp.height))
        placeAll(); chooseOffset()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        runCatching { wm.removeView(spriteView); wm.removeView(crossView); wm.removeView(bubble) }
        worker.quitSafely(); translator?.close(); translator = null
        super.onDestroy()
    }

    // ───────── 拖动 ─────────

    private fun attachTouch() {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false
        spriteView.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = spriteLp.x; startY = spriteLp.y; dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) dragging = true
                    if (dragging) {
                        spriteLp.x = (startX + dx).toInt().coerceIn(0, max(0, screenW - spriteLp.width))
                        spriteLp.y = (startY + dy).toInt().coerceIn(0, max(0, screenH - spriteLp.height))
                        wm.updateViewLayout(spriteView, spriteLp)
                        onSpriteMoved()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) onSpriteStopped()
                }
            }
            true
        }
    }

    private fun onSpriteMoved() {
        placeAll()
        chooseOffset()
        if (!crossShown) showCross()
        ui.removeCallbacks(hideBubbleRun)
        ui.removeCallbacks(idleRun); ui.postDelayed(idleRun, IDLE_MS)   // 手指停住就收起
        requestHit()
    }

    private fun onSpriteStopped() {
        ui.removeCallbacks(idleRun)
        hideCross()
        ui.removeCallbacks(hideBubbleRun); ui.postDelayed(hideBubbleRun, BUBBLE_HOLD_MS)
    }

    // ───────── 准星：位置、展开/收起 ─────────

    /** 准星窗口以小精灵中心为中心，跟着小精灵走。 */
    private fun placeAll() {
        val cx = spriteLp.x + spriteLp.width / 2
        val cy = spriteLp.y + spriteLp.height / 2
        crossLp.x = cx - crossLp.width / 2
        crossLp.y = cy - crossLp.height / 2
        wm.updateViewLayout(crossView, crossLp)
    }

    /** 当前方位还在屏幕内就不动（避免来回跳）；否则按优先级换到另一侧，并带动画过渡。 */
    private fun chooseOffset() {
        val cx = spriteLp.x + spriteLp.width / 2f
        val cy = spriteLp.y + spriteLp.height / 2f
        val m = dp(CROSS / 2 + 6f).toFloat()
        val top = m + dp(24f)
        fun valid(o: PointF) = (cx + o.x) in m..(screenW - m) && (cy + o.y) in top..(screenH - m)
        if (valid(targetOff)) return
        val dist = dp(DIST).toFloat()
        val pick = ANGLES.map { PointF((dist * cos(Math.toRadians(it))).toFloat(), (dist * sin(Math.toRadians(it))).toFloat()) }
            .firstOrNull { valid(it) } ?: return
        setOffset(pick)
    }

    private fun setOffset(to: PointF) {
        val from = PointF(crossView.offX, crossView.offY)
        targetOff = to
        offsetAnim?.cancel()
        offsetAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 160
            addUpdateListener {
                val f = it.animatedFraction
                crossView.offX = from.x + (to.x - from.x) * f
                crossView.offY = from.y + (to.y - from.y) * f
                crossView.invalidate()
            }
            start()
        }
    }

    private fun showCross() { crossShown = true; animateReveal(1f) }
    private fun hideCross() { crossShown = false; animateReveal(0f) }

    private fun animateReveal(to: Float) {
        revealAnim?.cancel()
        revealAnim = ValueAnimator.ofFloat(crossView.reveal, to).apply {
            duration = if (to > 0f) 220 else 180
            interpolator = if (to > 0f) OvershootInterpolator(1.6f) else DecelerateInterpolator()
            addUpdateListener { crossView.reveal = it.animatedValue as Float }
            start()
        }
    }

    // ───────── 取字：准星指到的文字 ─────────

    private data class Hit(val text: String)

    /** 准星中心的屏幕坐标（用控件实际位置，免得算状态栏偏移）。 */
    private fun targetPoint(): Point {
        val loc = IntArray(2)
        spriteView.getLocationOnScreen(loc)
        val cx = loc[0] + spriteView.width / 2
        val cy = loc[1] + spriteView.height / 2
        return Point(cx + crossView.offX.roundToInt(), cy + crossView.offY.roundToInt())
    }

    private fun requestHit() {
        val now = SystemClock.uptimeMillis()
        if (now - lastQueryAt < QUERY_GAP_MS) return
        lastQueryAt = now
        val p = targetPoint()
        bg.post {                                   // 遍历控件树有跨进程调用，放后台线程
            val hit = findTextAt(p.x, p.y)
            ui.post { onHit(hit) }
        }
    }

    private fun findTextAt(x: Int, y: Int): Hit? {
        val r = Rect()
        val ws = windows.filter {
            it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD
        }
            .sortedByDescending { it.layer }
        for (w in ws) {
            w.getBoundsInScreen(r)
            if (!r.contains(x, y)) continue
            val root = w.root ?: continue
            return deepest(root, x, y, 0)          // 最上层、包含该点的窗口说了算
        }
        return null
    }

    private fun deepest(n: AccessibilityNodeInfo, x: Int, y: Int, depth: Int): Hit? {
        if (depth > 40 || !n.isVisibleToUser || n.isPassword || n.packageName?.toString() == packageName) return null   // 不读密码框
        val b = Rect(); n.getBoundsInScreen(b)
        if (!b.contains(x, y)) return null
        for (i in n.childCount - 1 downTo 0) {      // 后面的子控件画在上面，倒着找
            val c = n.getChild(i) ?: continue
            deepest(c, x, y, depth + 1)?.let { return it }
        }
        val t = (n.text ?: n.contentDescription)?.toString()?.trim()
        return if (t.isNullOrEmpty()) null else Hit(t.take(MAX_CHARS))
    }

    // ───────── 翻译与气泡 ─────────

    private fun onHit(hit: Hit?) {
        if (hit == null) { lastText = null; ++translateGen; fadeBubble(); return }
        if (hit.text == lastText) { positionBubble(); return }   // 还在同一段文字上，只跟随位置
        lastText = hit.text
        val gen = ++translateGen
        ui.removeCallbacks(debounceRun)
        cache.get(hit.text)?.let { showBubble(it); return }
        showBubble("…")
        pendingText = hit.text; pendingGen = gen
        ui.postDelayed(debounceRun, DEBOUNCE_MS)
    }

    private var pendingText = ""
    private var pendingGen = 0
    private val debounceRun = Runnable {
        val text = pendingText; val gen = pendingGen
        translateWithCore(text, gen)
    }

    private fun translateWithCore(text: String, gen: Int) {
        if (gen != translateGen) return
        if (!translatorReady) {
            if (translatorLoadFailed) {
                showBubble("青简翻译引擎初始化失败，请重新开启小精灵翻译")
            } else {
                showBubble("青简翻译引擎正在准备…")
                ui.postDelayed({ if (gen == translateGen) translateWithCore(text, gen) }, 250L)
            }
            return
        }
        val config = CloudConfig.load(this)
        if (!config.ready) {
            showBubble("请先在青简「云端大模型」中配置翻译服务")
            return
        }
        if (translator?.translate(config, text) != true) {
            showBubble("翻译暂不可用，请检查云端配置")
            return
        }
        translationStartedAt = SystemClock.uptimeMillis()
        ui.postDelayed({ pollCoreTranslation(text, gen) }, CORE_POLL_MS)
    }

    private fun pollCoreTranslation(text: String, gen: Int) {
        if (gen != translateGen) return
        when (val result = translator?.poll()) {
            null -> if (SystemClock.uptimeMillis() - translationStartedAt > CORE_GIVE_UP_MS) {
                showBubble("翻译服务没有回应")
            } else ui.postDelayed({ pollCoreTranslation(text, gen) }, CORE_POLL_MS)
            "" -> showBubble("没有取得译文，请稍后重试")
            else -> { cache.put(text, result); showBubble(result) }
        }
    }
    private fun showBubble(text: String) {
        bubble.text = text
        positionBubble()
        bubble.animate().alpha(1f).setDuration(120).start()
    }

    private fun fadeBubble() { bubble.animate().alpha(0f).setDuration(120).start() }

    /** 气泡放在准星上方，上方放不下就放下方；水平方向夹在屏幕内。 */
    private fun positionBubble() {
        val p = targetPoint()
        bubble.measure(
            View.MeasureSpec.makeMeasureSpec(dp(280f) + dp(24f), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val w = bubble.measuredWidth; val h = bubble.measuredHeight
        val gap = dp(CROSS / 2 + 8f)
        var y = p.y - gap - h
        if (y < dp(40f)) y = p.y + gap
        bubbleLp.x = (p.x - w / 2).coerceIn(dp(8f), max(dp(8f), screenW - w - dp(8f)))
        bubbleLp.y = y
        wm.updateViewLayout(bubble, bubbleLp)
    }

    // ───────── 杂项 ─────────

    private fun dp(v: Float) = (v * density).roundToInt()

    private fun lp(w: Int, h: Int, touchable: Boolean) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun readScreen() {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            screenW = b.width(); screenH = b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            screenW = dm.widthPixels; screenH = dm.heightPixels
        }
    }
}

/** 十字准星：reveal 0→1 从小精灵中心"长出来"，1→0 缩回小精灵中心。 */
private class CrosshairView(ctx: Context, private val sizePx: Int) : View(ctx) {
    var offX = 0f      // 准星中心相对小精灵中心的偏移（像素）
    var offY = 0f
    var reveal = 0f
        set(v) { field = v; invalidate() }

    private val d = ctx.resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * d; strokeCap = Paint.Cap.ROUND
        color = 0xFF2EE6A8.toInt()
    }
    private val edge = Paint(line).apply { strokeWidth = 4.5f * d; color = 0x99000000.toInt() }
    private val link = Paint(line).apply { strokeWidth = 1.5f * d; pathEffect = DashPathEffect(floatArrayOf(6f * d, 5f * d), 0f) }

    override fun onDraw(c: Canvas) {
        val r = reveal
        if (r <= 0.01f) return
        val a = (255 * r.coerceIn(0f, 1f)).toInt()
        val cx = width / 2f; val cy = height / 2f
        val px = cx + offX * r; val py = cy + offY * r        // 收起时回到小精灵中心
        val arm = sizePx / 2f * r
        val ring = 9f * d * r
        edge.alpha = a * 0x99 / 255; line.alpha = a; link.alpha = a / 2

        c.drawLine(cx, cy, px, py, link)                      // 小精灵到准星的虚线
        for (p in arrayOf(edge, line)) {                      // 先描黑边再画彩线，亮暗背景都看得清
            c.drawCircle(px, py, ring, p)
            c.drawLine(px - arm, py, px - ring, py, p)
            c.drawLine(px + ring, py, px + arm, py, p)
            c.drawLine(px, py - arm, px, py - ring, p)
            c.drawLine(px, py + ring, px, py + arm, p)
        }
    }
}

