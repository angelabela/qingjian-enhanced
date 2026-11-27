package app.qingjian.inputmethod.keyboard

import android.graphics.RectF
import android.view.MotionEvent
import kotlin.math.abs

/**
 * 九宫格左边那一列：组句中是同码的拼音，平时是标点。项目多了可以上下拖，没拖就算点。
 * 只认自己那根手指，别的手指照常按键。
 */
class SideColumn(private val density: Float, private val onItem: (String) -> Unit) {
    var enabled = false
    var bounds = RectF()

    var items: List<String> = emptyList()
        set(value) {
            if (value == field) return
            field = value
            scroll = 0f
        }

    private var scroll = 0f
    private var pointer = -1
    private var lastY = 0f
    private var dragged = false

    private val itemHeight get() = bounds.height() / VISIBLE_ITEMS
    private val maxScroll get() = (items.size * itemHeight - bounds.height()).coerceAtLeast(0f)

    /** TalkBack 可通过滚动动作浏览超出可视区域的拼音/标点项目。 */
    val accessibilityScrollable: Boolean
        get() = enabled && maxScroll > 0f

    fun accessibilityScroll(direction: Int): Boolean {
        if (!accessibilityScrollable) return false
        val previous = scroll
        val page = (bounds.height() * 0.8f).coerceAtLeast(itemHeight)
        scroll = (scroll + page * direction.coerceIn(-1, 1)).coerceIn(0f, maxScroll)
        return scroll != previous
    }

    /** TalkBack 虚拟节点使用的可见项目区域。 */
    fun accessibilityBounds(index: Int): RectF? {
        if (!enabled || index !in items.indices || itemHeight <= 0f) return null
        val top = bounds.top + itemHeight * index - scroll
        val rect = RectF(bounds.left, top, bounds.right, top + itemHeight)
        if (!RectF.intersects(rect, bounds)) return null
        return RectF(rect.left.coerceAtLeast(bounds.left), rect.top.coerceAtLeast(bounds.top),
            rect.right.coerceAtMost(bounds.right), rect.bottom.coerceAtMost(bounds.bottom))
    }

    fun clickAccessibilityItem(index: Int): Boolean {
        val item = items.getOrNull(index) ?: return false
        if (!enabled) return false
        onItem(item)
        return true
    }

    /** 逐个给出看得见的项目和它的中线 y。 */
    fun visibleItems(draw: (String, Float) -> Unit) {
        items.forEachIndexed { index, item ->
            val cy = bounds.top + itemHeight * (index + 0.5f) - scroll
            if (cy > bounds.top - itemHeight && cy < bounds.bottom + itemHeight) draw(item, cy)
        }
    }

    /** 返回 true 表示这个事件归拼音列，键盘不用再管；内容变了（滚动）由 [invalidate] 通知。 */
    fun onTouch(event: MotionEvent, invalidate: () -> Unit): Boolean {
        val index = event.actionIndex
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (!enabled || !bounds.contains(event.getX(index), event.getY(index))) return false
                pointer = event.getPointerId(index)
                lastY = event.getY(index)
                dragged = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val at = event.findPointerIndex(pointer)
                if (at < 0) return false
                val dy = lastY - event.getY(at)
                if (dragged || abs(dy) > DRAG_SLOP_DP * density) {
                    dragged = true
                    scroll = (scroll + dy).coerceIn(0f, maxScroll)
                    lastY = event.getY(at)
                    invalidate()
                }
                // 别的手指可能正按着键，移动事件接着往下传
                return false
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(index) != pointer) return false
                pointer = -1
                if (!dragged) {
                    val item = ((event.getY(index) - bounds.top + scroll) / itemHeight).toInt()
                    items.getOrNull(item)?.let(onItem)
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> pointer = -1
        }
        return false
    }

    private companion object {
        const val VISIBLE_ITEMS = 4.5f
        const val DRAG_SLOP_DP = 8
    }
}
