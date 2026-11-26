package app.qingjian.inputmethod.keyboard.layout

import android.graphics.RectF

/** 键盘的尺寸与排版：行高跟着用户设的高度百分比走，其余按屏幕密度定死。 */
class KeyboardMetrics(
    val density: Float,
    heightPercent: Int,
    private val fontScale: Float = 1f,
    private val keyTextScale: Float = 1f,
) {
    private val layoutScale = fontScale.coerceAtLeast(1f) * keyTextScale.coerceAtLeast(1f)
    private var requestedRowHeight = rowHeightFor(heightPercent)
    private var availableHeight: Float? = null
    private var visibleRowCount = ROWS
    val rowHeight: Float
        get() {
            val available = availableHeight ?: return requestedRowHeight
            val maxRowHeight = (available - topPadding - gapY).coerceAtLeast(1f) / ROWS
            return minOf(requestedRowHeight, maxRowHeight)
        }

    /** Actual drawable row height after landscape/split-screen constraints and multi-row layouts. */
    val effectiveRowHeight: Float get() = rowHeight * ROWS / maxOf(ROWS, visibleRowCount)

    val gapX = 2.5f * density
    val gapY = 4f * density
    val topPadding = 4f * density
    val radius = 7f * density
    val shadowOffset = 1f * density
    private val edge = 2f * density

    /** 100% 时键盘区的高度，拖动调高度时的位移按它换算成百分比。 */
    val baseHeight: Float get() = ROW_HEIGHT_DP * density * ROWS * layoutScale

    val height: Int get() = (topPadding + rowHeight * ROWS + gapY).toInt()

    fun setHeightPercent(percent: Int) {
        requestedRowHeight = rowHeightFor(percent)
    }

    /** Respect the actual IME window height in landscape and split-screen without changing the saved height preference. */
    fun setAvailableHeight(height: Int?) {
        availableHeight = height?.takeIf { it > 0 }?.toFloat()
    }

    /**
     * 每行的键按 weight 分行宽；不满一行的居中，九宫格的前几行从拼音列右边排起。
     * 行多的（注音大千五行）不加高键盘，几行平分同样的高度。
     */
    fun layout(rows: List<List<Key>>, side: Boolean, width: Int): List<KeySlot> {
        if (width == 0) return emptyList()
        val unit = unit(width)
        visibleRowCount = maxOf(ROWS, rows.size)
        val rowHeight = rowHeight * ROWS / visibleRowCount
        return rows.flatMapIndexed { row, keys ->
            val total = keys.sumOf { it.weight.toDouble() }.toFloat()
            var x = edge + unit * if (side && row < KeyboardLayout.SIDE_ROWS) {
                KeyboardLayout.SIDE_WIDTH
            } else {
                (KeyboardLayout.UNITS_PER_ROW - total) / 2
            }
            val top = topPadding + row * rowHeight + gapY / 2
            keys.map { key ->
                val keyWidth = key.weight * unit
                KeySlot(key, RectF(x + gapX, top, x + keyWidth - gapX, top + rowHeight - gapY))
                    .also { x += keyWidth }
            }
        }
    }

    fun sideBounds(width: Int) = RectF(
        edge + gapX, topPadding + gapY / 2,
        edge + KeyboardLayout.SIDE_WIDTH * unit(width) - gapX,
        topPadding + KeyboardLayout.SIDE_ROWS * rowHeight - gapY / 2,
    )

    private fun unit(width: Int) = (width - edge * 2) / KeyboardLayout.UNITS_PER_ROW

    private fun rowHeightFor(percent: Int) = ROW_HEIGHT_DP * density * layoutScale * percent / 100f

    private companion object {
        const val ROWS = 4
        const val ROW_HEIGHT_DP = 54f
    }
}
