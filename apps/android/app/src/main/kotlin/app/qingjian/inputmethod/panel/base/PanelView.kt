package app.qingjian.inputmethod.panel.base

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import app.qingjian.inputmethod.keyboard.Haptics
import app.qingjian.inputmethod.panel.PanelActions
import app.qingjian.inputmethod.theme.Palette

/**
 * 面板的底子：盖住候选条与键盘那一整块。顶上一条与工具栏同高的表头（左边返回、中间页签、右边可选一个图标），
 * 下面由子类画。拖动交给手指落下处的那块 [ScrollState]（横或竖），没拖动就算点。
 */
@SuppressLint("ViewConstructor")
abstract class PanelView(
    context: Context,
    palette: Palette,
    protected val actions: PanelActions,
    /** 表头高度，与候选条一样高，盖上去时返回键正好在 logo 的位置。 */
    protected val headerHeight: Float,
) : View(context) {
    protected val ink = Ink(palette, resources.displayMetrics.density, resources.displayMetrics.scaledDensity)
    protected val palette get() = ink.palette

    /** 一块可滚动区域：落在 [contains] 里的拖动归它。 */
    protected class Scrollable(val state: ScrollState, val horizontal: Boolean, val contains: (Float, Float) -> Boolean)

    private val scrollables = mutableListOf<Scrollable>()
    private val headerCard = RectF()
    private var accessibilityTabs: Tabs? = null
    private var accessibilityFocusedId = INVALID_ACCESSIBILITY_ID

    /** Canvas 表头没有真实子 View，故用虚拟节点暴露返回键和每个页签。 */
    private val panelAccessibilityProvider = object : AccessibilityNodeProvider() {
        override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
            if (virtualViewId == View.NO_ID) {
                val root = AccessibilityNodeInfo.obtain()
                root.setSource(this@PanelView)
                root.packageName = context.packageName
                root.className = this@PanelView.javaClass.name
                root.isVisibleToUser = isShown
                root.isEnabled = isEnabled
                root.isFocusable = false
                root.isClickable = false
                if (hasHeader) root.addChild(this@PanelView, BACK_ACCESSIBILITY_ID)
                accessibilityTabs?.labels?.indices?.forEach { index ->
                    if (accessibilityTabs?.accessibilityBounds(index) != null) {
                        root.addChild(this@PanelView, TAB_ACCESSIBILITY_BASE + index)
                    }
                }
                return root
            }

            val tabIndex = virtualViewId - TAB_ACCESSIBILITY_BASE
            val isBack = virtualViewId == BACK_ACCESSIBILITY_ID
            if (isBack && !hasHeader) return null
            val tab = accessibilityTabs
            val label: String
            val bounds = if (isBack) {
                label = "返回"
                Rect(0, 0, ink.dp(BACK_WIDTH_DP).toInt(), headerHeight.toInt())
            } else {
                if (tab == null || tabIndex !in tab.labels.indices) return null
                label = tab.labels[tabIndex]
                val rect = tab.accessibilityBounds(tabIndex) ?: return null
                Rect(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
            }
            val node = AccessibilityNodeInfo.obtain()
            node.setSource(this@PanelView, virtualViewId)
            node.setParent(this@PanelView)
            node.packageName = context.packageName
            node.className = if (isBack) "android.widget.Button" else "android.widget.TabWidget"
            node.text = label
            node.contentDescription = if (isBack) "返回上一级面板" else label
            node.isEnabled = isEnabled
            node.isVisibleToUser = isShown
            node.isFocusable = true
            node.isClickable = true
            node.isSelected = !isBack && tab?.selected == tabIndex
            node.isAccessibilityFocused = accessibilityFocusedId == virtualViewId
            node.setBoundsInParent(bounds)
            val screen = Rect(bounds)
            val location = IntArray(2)
            getLocationOnScreen(location)
            screen.offset(location[0], location[1])
            node.setBoundsInScreen(screen)
            node.addAction(AccessibilityNodeInfo.ACTION_CLICK)
            node.addAction(if (accessibilityFocusedId == virtualViewId)
                AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS
            else AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            return node
        }

        override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            when (action) {
                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                    accessibilityFocusedId = virtualViewId
                    sendPanelAccessibilityEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
                    return true
                }
                AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                    if (accessibilityFocusedId != virtualViewId) return false
                    accessibilityFocusedId = INVALID_ACCESSIBILITY_ID
                    sendPanelAccessibilityEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
                    return true
                }
                AccessibilityNodeInfo.ACTION_CLICK -> {
                    if (!isEnabled) return false
                    if (virtualViewId == BACK_ACCESSIBILITY_ID && hasHeader) onBack()
                    else {
                        val index = virtualViewId - TAB_ACCESSIBILITY_BASE
                        val tab = accessibilityTabs ?: return false
                        val rect = tab.accessibilityBounds(index) ?: return false
                        onTap(rect.centerX(), rect.centerY())
                    }
                    sendPanelAccessibilityEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED)
                    invalidate()
                    return true
                }
            }
            return false
        }
    }

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = panelAccessibilityProvider

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (hasHeader) info.addChild(this, BACK_ACCESSIBILITY_ID)
        accessibilityTabs?.labels?.indices?.forEach { index ->
            if (accessibilityTabs?.accessibilityBounds(index) != null) {
                info.addChild(this, TAB_ACCESSIBILITY_BASE + index)
            }
        }
    }

    private fun sendPanelAccessibilityEvent(id: Int, type: Int) {
        val node = createAccessibilityNodeInfo(id) ?: return
        val event = AccessibilityEvent.obtain(type)
        event.packageName = context.packageName
        event.className = node.className
        event.setSource(this, id)
        node.text?.let(event.text::add)
        parent?.requestSendAccessibilityEvent(this, event) ?: event.recycle()
        node.recycle()
    }

    protected var animatedBackgroundEnabled: Boolean = false
        private set
    private var dragging: Scrollable? = null

    protected fun scrollable(
        horizontal: Boolean,
        friction: Float = 0.015f,
        contains: (Float, Float) -> Boolean,
    ): ScrollState {
        val state = ScrollState(context, friction)
        scrollables += Scrollable(state, horizontal, contains)
        return state
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scrollables.forEach { it.state.stop() }
            dragging = scrollables.firstOrNull { it.contains(e.x, e.y) }
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            val target = dragging ?: return false
            target.state.drag(if (target.horizontal) dx else dy)
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val target = dragging ?: return false
            target.state.fling(if (target.horizontal) vx else vy)
            postInvalidateOnAnimation()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            dragging = null
            onHold(e.x, e.y, e.rawX)
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            Haptics.tap(context)
            if (hasHeader && e.y < headerHeight && e.x < ink.dp(BACK_WIDTH_DP)) onBack() else onTap(e.x, e.y)
            invalidate()
            return true
        }
    })

    /** 有没有表头；没有的（符号面板）左上角不当返回键。 */
    protected open val hasHeader = true

    /** 长按（没拖动）；之后这根手指的移动与抬起由子类在 [onTouchEvent] 里接着收。 */
    protected open fun onHold(x: Float, y: Float, rawX: Float) = Unit

    /** 点了表头的返回：缺省回键盘，从别的面板进来的可以回那个面板。 */
    protected open fun onBack() = actions.closePanel()

    /** 点在返回键以外的地方。 */
    protected abstract fun onTap(x: Float, y: Float)

    /** 动态键盘背景开启时，面板底层必须透出根视图的动态背景。 */
    fun useAnimatedBackground(enabled: Boolean) {
        animatedBackgroundEnabled = enabled
        setBackgroundColor(if (enabled) Color.TRANSPARENT else palette.board)
        invalidate()
    }

    init {
        setBackgroundColor(palette.board)
    }

    override fun computeScroll() {
        var moving = false
        for (scrollable in scrollables) moving = scrollable.state.compute() || moving
        if (moving) postInvalidateOnAnimation()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event)

    /**
     * 表头：与工具栏同样浮起的圆角卡片，和下面的内容分出层次；左边返回（编辑类面板换成品牌色的 [leftIcon]，如完成的对勾），[rightIcon] 画在右边一格
     * （点它由子类在 [onTap] 里认 [isRightIcon]）。
     */
    protected fun drawHeader(
        canvas: Canvas,
        tabs: Tabs?,
        rightIcon: PanelIcons.Icon? = null,
        leftIcon: PanelIcons.Icon = PanelIcons.Icon.BACK,
    ) {
        val inset = ink.dp(6f)
        headerCard.set(inset, inset, width - inset, headerHeight - ink.dp(4f))
        headerCard.offset(0f, ink.density)
        if (!animatedBackgroundEnabled) {
            ink.round(canvas, headerCard, 14f, palette.keyShadow)
            headerCard.offset(0f, -ink.density)
            ink.round(canvas, headerCard, 14f, palette.surface)
        }
        val cy = headerCard.centerY()
        ink.icon(canvas, leftIcon, ink.dp(BACK_WIDTH_DP) / 2, cy, 24f, if (leftIcon == PanelIcons.Icon.BACK) palette.text else palette.brand)
        tabs?.let {
            it.setBounds(ink.dp(BACK_WIDTH_DP), headerCard.top, width - ink.dp(BACK_WIDTH_DP), headerCard.bottom)
            accessibilityTabs = it
            it.draw(canvas)
        } ?: run { accessibilityTabs = null }
        rightIcon?.let { ink.icon(canvas, it, width - ink.dp(BACK_WIDTH_DP) / 2, cy, 24f, palette.gloss) }
    }

    protected fun isRightIcon(x: Float, y: Float) = y < headerHeight && x > width - ink.dp(BACK_WIDTH_DP)

    protected companion object {
        const val BACK_WIDTH_DP = 56f
        private const val BACK_ACCESSIBILITY_ID = 1
        private const val TAB_ACCESSIBILITY_BASE = 1000
        private const val INVALID_ACCESSIBILITY_ID = -1
    }
}
