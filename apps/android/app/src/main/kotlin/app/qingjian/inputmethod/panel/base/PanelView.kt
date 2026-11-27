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
import android.view.accessibility.AccessibilityManager
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
    private var hoveredAccessibilityId = INVALID_ACCESSIBILITY_ID
    private var accessibilityRightLabel: String? = null
    private val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager

    /** Canvas 自绘面板的可操作区域；子类按当前布局提供，TalkBack 点击仍走原有 onTap。 */
    protected data class AccessibilityElement(
        val label: String,
        val bounds: RectF,
        val selected: Boolean = false,
        /** 非空表示真实开关状态；让 TalkBack 获得可切换控件语义，而非仅朗读按钮文字。 */
        val checked: Boolean? = null,
        val enabled: Boolean = true,
        val rangeMin: Float? = null,
        val rangeMax: Float? = null,
        val rangeCurrent: Float? = null,
        val onSetProgress: ((Float) -> Unit)? = null,
        val virtualId: Int? = null,
        val onScroll: ((Int) -> Unit)? = null,
        /** Optional non-touch alternatives for drag-only operations, exposed as named TalkBack actions. */
        val onMoveUp: (() -> Unit)? = null,
        val onMoveDown: (() -> Unit)? = null,
        /** Override the default widget role for checkable Canvas controls such as toggle/radio buttons. */
        val role: String? = null,
    )

    protected open fun accessibilityElements(): List<AccessibilityElement> = emptyList()

    private fun accessibilityElementId(index: Int, element: AccessibilityElement) = element.virtualId ?: index

    private fun accessibilityElementForId(id: Int): AccessibilityElement? =
        accessibilityElements().withIndex().firstOrNull { accessibilityElementId(it.index, it.value) == id }?.value

    /** Expose a Canvas scroll viewport as a TalkBack scroll target without changing touch gestures. */
    protected fun accessibilityScrollElement(
        label: String,
        bounds: RectF,
        state: ScrollState,
        horizontal: Boolean = false,
        virtualId: Int = 1000000,
    ): AccessibilityElement? {
        if (state.max <= 0f || bounds.isEmpty) return null
        val page = (if (horizontal) bounds.width() else bounds.height()).coerceAtLeast(ink.dp(48f)) * 0.8f
        return AccessibilityElement(
            label = label,
            bounds = RectF(bounds),
            virtualId = virtualId,
            onScroll = { direction ->
                state.stop()
                state.drag(page * direction.coerceIn(-1, 1))
            },
        )
    }

    private fun hasAccessibilityNode(id: Int): Boolean {
        val node = panelAccessibilityProvider.createAccessibilityNodeInfo(id) ?: return false
        node.recycle()
        return true
    }

    /**
     * Clear focused/hovered Canvas content nodes before replacing a dynamic list whose rows
     * reuse position-based virtual IDs. This prevents TalkBack focus from silently moving
     * to a different item after deletion or reordering; header and tab focus remain intact.
     */
    protected fun clearAccessibilityElementFocusForContentReplacement() {
        val focused = accessibilityFocusedId
        if (focused >= ELEMENT_ACCESSIBILITY_BASE) {
            accessibilityFocusedId = INVALID_ACCESSIBILITY_ID
            sendPanelAccessibilityEvent(focused, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
        }
        val hovered = hoveredAccessibilityId
        if (hovered >= ELEMENT_ACCESSIBILITY_BASE) {
            hoveredAccessibilityId = INVALID_ACCESSIBILITY_ID
            sendPanelAccessibilityEvent(hovered, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
        }
    }

    protected fun notifyAccessibilityContentChanged() {
        // A panel may replace its dynamic rows while TalkBack is focused on one of them.
        // Clear stale virtual focus/hover immediately so findFocus() never points at a dead node.
        val focused = accessibilityFocusedId
        if (focused != INVALID_ACCESSIBILITY_ID && !hasAccessibilityNode(focused)) {
            accessibilityFocusedId = INVALID_ACCESSIBILITY_ID
            sendPanelAccessibilityEvent(focused, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
        }
        val hovered = hoveredAccessibilityId
        if (hovered != INVALID_ACCESSIBILITY_ID && !hasAccessibilityNode(hovered)) {
            hoveredAccessibilityId = INVALID_ACCESSIBILITY_ID
            sendPanelAccessibilityEvent(hovered, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
        }
        if (isAttachedToWindow && accessibilityManager.isEnabled) {
            sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        }
    }

    private fun accessibilityNodeIdAt(x: Float, y: Float): Int {
        val elements = accessibilityElements()
        // 滚动视口常覆盖整块内容区，先命中具体控件，避免容器遮住子节点。
        for (pass in 0..1) {
            for (index in elements.indices.reversed()) {
                val element = elements[index]
                if ((element.onScroll != null) != (pass == 1)) continue
                if (element.bounds.contains(x, y)) {
                    return ELEMENT_ACCESSIBILITY_BASE + accessibilityElementId(index, element)
                }
            }
        }
        if (hasHeader && y < headerHeight && x < ink.dp(BACK_WIDTH_DP)) return BACK_ACCESSIBILITY_ID
        if (accessibilityRightLabel != null && y < headerHeight && x > width - ink.dp(BACK_WIDTH_DP)) return RIGHT_ACCESSIBILITY_ID
        val tab = accessibilityTabs
        if (tab != null) {
            for (index in tab.labels.indices) {
                if (tab.accessibilityBounds(index)?.contains(x, y) == true) return TAB_ACCESSIBILITY_BASE + index
            }
            if (tab.accessibilityScrollable && tab.bounds.contains(x, y)) return TAB_SCROLL_ACCESSIBILITY_ID
        }
        return INVALID_ACCESSIBILITY_ID
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        if (!accessibilityManager.isTouchExplorationEnabled) return super.dispatchHoverEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                val next = accessibilityNodeIdAt(event.x, event.y)
                if (next != hoveredAccessibilityId) {
                    if (hoveredAccessibilityId != INVALID_ACCESSIBILITY_ID) {
                        sendPanelAccessibilityEvent(hoveredAccessibilityId, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
                    }
                    hoveredAccessibilityId = next
                    if (next != INVALID_ACCESSIBILITY_ID) {
                        sendPanelAccessibilityEvent(next, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)
                    }
                }
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                if (hoveredAccessibilityId != INVALID_ACCESSIBILITY_ID) {
                    sendPanelAccessibilityEvent(hoveredAccessibilityId, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
                    hoveredAccessibilityId = INVALID_ACCESSIBILITY_ID
                }
            }
        }
        return true
    }

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
                // Keep the virtual focus order aligned with the visible header:
                // back, tabs, optional tab-scroll action, then the trailing action.
                if (hasHeader) root.addChild(this@PanelView, BACK_ACCESSIBILITY_ID)
                accessibilityTabs?.labels?.indices?.forEach { index ->
                    if (accessibilityTabs?.accessibilityBounds(index) != null) {
                        root.addChild(this@PanelView, TAB_ACCESSIBILITY_BASE + index)
                    }
                }
                if (accessibilityTabs?.accessibilityScrollable == true) {
                    root.addChild(this@PanelView, TAB_SCROLL_ACCESSIBILITY_ID)
                }
                if (accessibilityRightLabel != null) root.addChild(this@PanelView, RIGHT_ACCESSIBILITY_ID)
                accessibilityElements().forEachIndexed { index, element ->
                    if (RectF.intersects(element.bounds, RectF(0f, 0f, width.toFloat(), height.toFloat()))) {
                        root.addChild(this@PanelView, ELEMENT_ACCESSIBILITY_BASE + accessibilityElementId(index, element))
                    }
                }
                return root
            }

            val isElement = virtualViewId >= ELEMENT_ACCESSIBILITY_BASE
            val isTabScroll = virtualViewId == TAB_SCROLL_ACCESSIBILITY_ID
            val elementIndex = virtualViewId - ELEMENT_ACCESSIBILITY_BASE
            val element = if (isElement) accessibilityElementForId(elementIndex) else null
            if (isElement && element == null) return null
            val tabIndex = virtualViewId - TAB_ACCESSIBILITY_BASE
            val isBack = virtualViewId == BACK_ACCESSIBILITY_ID
            val isRight = virtualViewId == RIGHT_ACCESSIBILITY_ID
            if (isBack && !hasHeader) return null
            if (isRight && accessibilityRightLabel == null) return null
            val tab = accessibilityTabs
            if (isTabScroll && (tab == null || !tab.accessibilityScrollable)) return null
            val label: String
            val bounds = if (isElement) {
                label = element!!.label
                Rect(element.bounds.left.toInt(), element.bounds.top.toInt(), element.bounds.right.toInt(), element.bounds.bottom.toInt())
            } else if (isBack) {
                label = "返回"
                Rect(0, 0, ink.dp(BACK_WIDTH_DP).toInt(), headerHeight.toInt())
            } else if (isRight) {
                label = accessibilityRightLabel ?: return null
                Rect((width - ink.dp(BACK_WIDTH_DP)).toInt(), 0, width, headerHeight.toInt())
            } else if (isTabScroll) {
                label = tab!!.accessibilityScrollLabel
                Rect(tab.bounds.left.toInt(), tab.bounds.top.toInt(), tab.bounds.right.toInt(), tab.bounds.bottom.toInt())
            } else {
                if (tab == null || tabIndex !in tab.labels.indices) return null
                label = tab.labels[tabIndex]
                val rect = tab.accessibilityBounds(tabIndex) ?: return null
                Rect(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
            }
            val visibleBounds = Rect(bounds)
            if (isElement && !visibleBounds.intersect(0, 0, width, height)) return null
            val node = AccessibilityNodeInfo.obtain()
            node.setSource(this@PanelView, virtualViewId)
            node.setParent(this@PanelView)
            node.packageName = context.packageName
            node.className = when {
                isTabScroll && tab?.accessibilityScrollLabel?.contains("横向") == true -> "android.widget.HorizontalScrollView"
                isTabScroll -> "android.widget.ScrollView"
                isElement && element!!.role != null -> element!!.role!!
                isElement && element!!.onScroll != null -> "android.widget.ScrollView"
                isElement && element!!.rangeMin != null -> "android.widget.SeekBar"
                isElement && element!!.checked != null -> "android.widget.Switch"
                isBack || isRight || isElement -> "android.widget.Button"
                else -> "android.widget.TabWidget"
            }
            node.text = label
            node.contentDescription = if (isBack) "返回上一级面板" else label
            node.isEnabled = isEnabled && (!isElement || element!!.enabled)
            node.isVisibleToUser = isShown
            node.isFocusable = true
            node.isClickable = isEnabled && !isTabScroll && (!isElement || (element!!.enabled && element.rangeMin == null && element.onScroll == null))
            node.isScrollable = node.isEnabled && (isTabScroll || (isElement && element!!.onScroll != null))
            if (node.isEnabled && (isTabScroll || (isElement && element!!.onScroll != null))) {
                node.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                node.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            }
            if (isElement && element!!.rangeMin != null) {
                node.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(
                    AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,
                    element.rangeMin!!,
                    element.rangeMax ?: element.rangeMin!!,
                    element.rangeCurrent ?: element.rangeMin!!,
                )
                if (node.isEnabled) {
                    node.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
                    node.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    node.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                }
            }
            node.isSelected = if (isElement) element!!.selected else !isBack && !isRight && tab?.selected == tabIndex
            if (isElement && element!!.checked != null) {
                node.isCheckable = true
                node.isChecked = element!!.checked == true
            }
            node.isAccessibilityFocused = accessibilityFocusedId == virtualViewId
            node.setBoundsInParent(visibleBounds)
            val screen = Rect(visibleBounds)
            val location = IntArray(2)
            getLocationOnScreen(location)
            screen.offset(location[0], location[1])
            node.setBoundsInScreen(screen)
            if (node.isClickable) node.addAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (isElement && element!!.enabled) {
                element!!.onMoveUp?.let {
                    node.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_MOVE_UP, "上移一位"))
                }
                element!!.onMoveDown?.let {
                    node.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_MOVE_DOWN, "下移一位"))
                }
            }
            node.addAction(if (accessibilityFocusedId == virtualViewId)
                AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS
            else AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            return node
        }

        override fun findFocus(focus: Int): AccessibilityNodeInfo? {
            if (focus != AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) return null
            val focusedId = accessibilityFocusedId
            if (focusedId == INVALID_ACCESSIBILITY_ID) return null
            return createAccessibilityNodeInfo(focusedId)
        }

        override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            if (action == ACTION_MOVE_UP || action == ACTION_MOVE_DOWN) {
                if (!isEnabled || virtualViewId < ELEMENT_ACCESSIBILITY_BASE) return false
                val item = accessibilityElementForId(virtualViewId - ELEMENT_ACCESSIBILITY_BASE) ?: return false
                if (!item.enabled) return false
                val operation = if (action == ACTION_MOVE_UP) item.onMoveUp else item.onMoveDown
                operation ?: return false
                operation.invoke()
                sendPanelAccessibilityEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_SELECTED)
                notifyAccessibilityContentChanged()
                invalidate()
                return true
            }
            when (action) {
                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                    if (!hasAccessibilityNode(virtualViewId)) return false
                    if (accessibilityFocusedId == virtualViewId) return true
                    val previous = accessibilityFocusedId
                    accessibilityFocusedId = virtualViewId
                    if (previous != INVALID_ACCESSIBILITY_ID) {
                        sendPanelAccessibilityEvent(previous, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
                    }
                    sendPanelAccessibilityEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
                    invalidate()
                    return true
                }
                AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                    if (accessibilityFocusedId != virtualViewId) return false
                    accessibilityFocusedId = INVALID_ACCESSIBILITY_ID
                    sendPanelAccessibilityEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
                    invalidate()
                    return true
                }
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> {
                    if (virtualViewId == TAB_SCROLL_ACCESSIBILITY_ID) {
                        if (!isEnabled) return false
                        val tabs = accessibilityTabs ?: return false
                        if (!tabs.accessibilityScrollable) return false
                        tabs.accessibilityScroll(if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) 1 else -1)
                        sendPanelAccessibilityEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_SCROLLED)
                        notifyAccessibilityContentChanged()
                        invalidate()
                        return true
                    }
                    if (virtualViewId < ELEMENT_ACCESSIBILITY_BASE) return false
                    val item = accessibilityElementForId(virtualViewId - ELEMENT_ACCESSIBILITY_BASE) ?: return false
                    if (!isEnabled || !item.enabled) return false
                    if (item.onScroll != null) {
                        item.onScroll.invoke(if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) 1 else -1)
                        sendPanelAccessibilityEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_SCROLLED)
                        notifyAccessibilityContentChanged()
                        invalidate()
                        return true
                    }
                    val setter = item.onSetProgress ?: return false
                    val min = item.rangeMin ?: return false
                    val max = item.rangeMax ?: return false
                    val current = item.rangeCurrent ?: min
                    val next = when (action) {
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id ->
                            arguments?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, current) ?: current
                        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> current + (max - min) / 20f
                        else -> current - (max - min) / 20f
                    }.coerceIn(min, max)
                    setter(next)
                    sendPanelAccessibilityEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_SELECTED)
                    notifyAccessibilityContentChanged()
                    invalidate()
                    return true
                }
                AccessibilityNodeInfo.ACTION_CLICK -> {
                    if (!isEnabled) return false
                    when {
                        virtualViewId == BACK_ACCESSIBILITY_ID && hasHeader -> onBack()
                        virtualViewId == RIGHT_ACCESSIBILITY_ID && accessibilityRightLabel != null ->
                            onTap(width - ink.dp(BACK_WIDTH_DP) / 2f, headerHeight / 2f)
                        virtualViewId >= ELEMENT_ACCESSIBILITY_BASE -> {
                            val item = accessibilityElementForId(virtualViewId - ELEMENT_ACCESSIBILITY_BASE) ?: return false
                            if (!item.enabled || item.rangeMin != null || item.onScroll != null) return false
                            val visible = Rect(item.bounds.left.toInt(), item.bounds.top.toInt(), item.bounds.right.toInt(), item.bounds.bottom.toInt())
                            if (!visible.intersect(0, 0, width, height)) return false
                            onTap(visible.exactCenterX(), visible.exactCenterY())
                        }
                        else -> {
                            val index = virtualViewId - TAB_ACCESSIBILITY_BASE
                            val tab = accessibilityTabs ?: return false
                            val rect = tab.accessibilityBounds(index) ?: return false
                            onTap(rect.centerX(), rect.centerY())
                        }
                    }
                    sendPanelAccessibilityEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED)
                    notifyAccessibilityContentChanged()
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
        // Mirror the provider root order so framework and virtual-tree queries agree.
        if (hasHeader) info.addChild(this, BACK_ACCESSIBILITY_ID)
        accessibilityTabs?.labels?.indices?.forEach { index ->
            if (accessibilityTabs?.accessibilityBounds(index) != null) {
                info.addChild(this, TAB_ACCESSIBILITY_BASE + index)
            }
        }
        if (accessibilityTabs?.accessibilityScrollable == true) info.addChild(this, TAB_SCROLL_ACCESSIBILITY_ID)
        if (accessibilityRightLabel != null) info.addChild(this, RIGHT_ACCESSIBILITY_ID)
        accessibilityElements().forEachIndexed { index, element ->
            if (RectF.intersects(element.bounds, RectF(0f, 0f, width.toFloat(), height.toFloat()))) {
                info.addChild(this, ELEMENT_ACCESSIBILITY_BASE + accessibilityElementId(index, element))
            }
        }
    }

    private fun sendPanelAccessibilityEvent(id: Int, type: Int) {
        // Focus-cleared/hover-exit can arrive after a dynamic item has been removed.
        // Still dispatch the lifecycle event, and recycle it if the parent declines it.
        val node = panelAccessibilityProvider.createAccessibilityNodeInfo(id)
        val event = AccessibilityEvent.obtain(type)
        event.packageName = context.packageName
        event.className = node?.className ?: this@PanelView.javaClass.name
        event.setSource(this, id)
        node?.text?.let(event.text::add)
        val accepted = parent?.requestSendAccessibilityEvent(this, event) == true
        if (!accepted) event.recycle()
        node?.recycle()
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

    /**
     * A panel can be replaced while TalkBack is focused on one of its virtual controls.
     * Stop inertial scrolling and close virtual focus/hover before the view leaves its
     * accessibility tree, so a detached panel cannot retain stale interaction state.
     */
    override fun onDetachedFromWindow() {
        scrollables.forEach { it.state.stop() }
        dragging = null

        val focused = accessibilityFocusedId
        accessibilityFocusedId = INVALID_ACCESSIBILITY_ID
        if (focused != INVALID_ACCESSIBILITY_ID) {
            sendPanelAccessibilityEvent(focused, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
        }

        val hovered = hoveredAccessibilityId
        hoveredAccessibilityId = INVALID_ACCESSIBILITY_ID
        if (hovered != INVALID_ACCESSIBILITY_ID) {
            sendPanelAccessibilityEvent(hovered, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
        }

        super.onDetachedFromWindow()
    }

    override fun computeScroll() {
        var moving = false
        for (scrollable in scrollables) moving = scrollable.state.compute() || moving
        if (moving) postInvalidateOnAnimation()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            notifyAccessibilityContentChanged()
            dragging = null
        }
        return handled
    }

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
        accessibilityRightLabel = rightIcon?.let { icon ->
            when (icon) {
                PanelIcons.Icon.PLUS -> "添加或新建"
                PanelIcons.Icon.TRASH -> "清空或删除"
                PanelIcons.Icon.SETTINGS -> "设置"
                PanelIcons.Icon.COPY -> "复制"
                PanelIcons.Icon.CLEAR -> "清空"
                PanelIcons.Icon.CHECK -> "完成"
                PanelIcons.Icon.PEN -> "编辑"
                PanelIcons.Icon.CLOSE -> "关闭"
                PanelIcons.Icon.SEARCH -> "搜索"
                else -> "更多操作"
            }
        }
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
        private const val RIGHT_ACCESSIBILITY_ID = 2
        private const val TAB_ACCESSIBILITY_BASE = 1000
        private const val TAB_SCROLL_ACCESSIBILITY_ID = 3
        private const val ELEMENT_ACCESSIBILITY_BASE = 20000
        private const val ACTION_MOVE_UP = 0x010F0001
        private const val ACTION_MOVE_DOWN = 0x010F0002
        private const val INVALID_ACCESSIBILITY_ID = -1
    }
}
