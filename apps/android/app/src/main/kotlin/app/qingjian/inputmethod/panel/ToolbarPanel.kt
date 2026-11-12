package app.qingjian.inputmethod.panel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import app.qingjian.inputmethod.panel.base.PanelIcons.Icon
import app.qingjian.inputmethod.panel.base.PanelView
import app.qingjian.inputmethod.panel.base.Tabs
import app.qingjian.inputmethod.settings.Settings
import app.qingjian.inputmethod.settings.ToolbarTool
import app.qingjian.inputmethod.theme.Palette

/**
 * 定制工具栏（#777，功能面板「常用功能」里进），在键盘区原地编辑：上面一排是工具栏上的、按顺序，右上角「−」点了移除；
 * 下面是全部工具，放了的高亮，点没放的加到末尾、点放了的移除。左上角 ✓ 完成才写进设置并回到键盘，
 * 系统返回键放弃这次改动；右上角「恢复默认」先换成 [ToolbarTool.DEFAULT]，同样要点 ✓ 才算。
 */
@SuppressLint("ViewConstructor")
class ToolbarPanel(
    context: Context,
    palette: Palette,
    actions: PanelActions,
    headerHeight: Float,
    private val saved: List<ToolbarTool>,
) : PanelView(context, palette, actions, headerHeight) {
    private val tabs = Tabs(ink, scrollable(true) { _, _ -> false }, Tabs.Style.UNDERLINE)
        .apply { labels = listOf("定制工具栏") }

    private val card = RectF()
    private val scroll = scrollable(false) { x, y -> card.contains(x, y) }
    private val columns = 5

    /** 正在编辑的工具栏，点 ✓ 才存。 */
    private var placed = saved

    private var draggingTool: ToolbarTool? = null
    private var draggingIndex = -1
    private var draggingX = 0f
    private var draggingY = 0f

    private val sectionTitle get() = ink.dp(34f)
    private val cellHeight get() = ink.dp(68f)

    private fun rows(count: Int) = maxOf(1, (count + columns - 1) / columns)

    /** 下面「全部工具」那一节在内容里的起点（相对卡片顶，未滚动）。 */
    private val allTop get() = sectionTitle + rows(placed.size) * cellHeight

    private val contentHeight get() = allTop + sectionTitle + rows(ToolbarTool.entries.size) * cellHeight + ink.dp(8f)

    private fun cellWidth() = card.width() / columns

    private enum class Badge { REMOVE, ADD, PLACED }

    override fun onDraw(canvas: Canvas) {
        drawHeader(canvas, tabs, leftIcon = Icon.CHECK)
        ink.text(canvas, RESET, 14f, palette.brand, width - ink.dp(RESET_WIDTH_DP) / 2, headerHeight / 2)
        card.set(ink.dp(10f), headerHeight, width - ink.dp(10f), height - ink.dp(8f))
        ink.round(canvas, card, 16f, palette.surface)
        scroll.max = contentHeight - card.height()
        canvas.save()
        canvas.clipRect(card)
        val top = card.top - scroll.offset
        drawTitle(canvas, "工具栏（按这个顺序排）", top)
        if (placed.isEmpty()) {
            ink.text(canvas, "中间空着，从下面加", 13f, palette.gloss, card.centerX(), top + sectionTitle + cellHeight / 2)
        }
        placed.forEachIndexed { i, tool -> drawCell(canvas, tool, i, top + sectionTitle, Badge.REMOVE) }
        drawTitle(canvas, "全部工具（点一下加到末尾）", top + allTop)
        ToolbarTool.entries.forEachIndexed { i, tool ->
            drawCell(canvas, tool, i, top + allTop + sectionTitle, if (tool in placed) Badge.PLACED else Badge.ADD)
        }
        // 手指下方跟随一个浮起的图标，让拖动目标始终清晰可见。
        draggingTool?.let { tool ->
            val ghost = RectF(
                draggingX - ink.dp(25f), draggingY - ink.dp(25f),
                draggingX + ink.dp(25f), draggingY + ink.dp(25f),
            )
            ink.round(canvas, ghost, 13f, palette.brand)
            ink.icon(canvas, tool.icon, draggingX, draggingY, 27f, palette.onBrand)
        }
        canvas.restore()
    }

    private fun drawTitle(canvas: Canvas, title: String, top: Float) {
        ink.textStart(canvas, title, 12f, palette.gloss, card.left + ink.dp(16f), top + sectionTitle / 2 + ink.dp(4f), card.right - ink.dp(16f))
    }

    private fun drawCell(canvas: Canvas, tool: ToolbarTool, index: Int, top: Float, badge: Badge) {
        val cx = card.left + cellWidth() * (index % columns + 0.5f)
        val cy = top + cellHeight * (index / columns + 0.5f)
        val highlighted = badge == Badge.PLACED
        val lifted = draggingTool == tool && badge == Badge.REMOVE
        if (lifted) {
            val tile = RectF(cx - cellWidth() * 0.43f, cy - cellHeight * 0.43f, cx + cellWidth() * 0.43f, cy + cellHeight * 0.43f)
            ink.round(canvas, tile, 12f, palette.functionalKey)
            canvas.save()
            canvas.scale(1.10f, 1.10f, cx, cy)
        }
        ink.icon(canvas, tool.icon, cx, cy - ink.dp(10f), 26f, if (highlighted || lifted) palette.brand else palette.text)
        ink.text(canvas, tool.label, 12f, if (highlighted || lifted) palette.brand else palette.gloss, cx, cy + ink.dp(18f), maxWidth = cellWidth() - ink.dp(4f))
        if (lifted) canvas.restore()
        if (!lifted) {
            val bx = cx + ink.dp(17f)
            val by = cy - ink.dp(24f)
            ink.fill.color = if (highlighted) palette.brand else palette.functionalKey
            canvas.drawCircle(bx, by, ink.dp(7.5f), ink.fill)
            val mark = when (badge) {
                Badge.REMOVE -> Icon.MINUS
                Badge.ADD -> Icon.PLUS
                Badge.PLACED -> Icon.CHECK
            }
            ink.icon(canvas, mark, bx, by, 13f, if (highlighted) palette.onBrand else palette.text)
        }
    }

    /** 左上角是 ✓：存下并回到键盘。 */
    override fun onBack() {
        actions.closePanel()
        if (placed != saved) actions.setChoice(Settings.KEY_TOOLBAR, ToolbarTool.format(placed))
    }

    override fun onHold(x: Float, y: Float, rawX: Float) {
        if (!card.contains(x, y)) return
        val inContent = y - card.top + scroll.offset
        // 仅拖动上方已放置的项目；下方全部工具仍通过点按添加或移除。
        if (inContent < sectionTitle || inContent >= allTop || placed.isEmpty()) return
        val column = ((x - card.left) / cellWidth()).toInt().coerceIn(0, columns - 1)
        val row = ((inContent - sectionTitle) / cellHeight).toInt()
        val index = row * columns + column
        draggingTool = placed.getOrNull(index) ?: return
        draggingIndex = index
        draggingX = x
        draggingY = y
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = super.onTouchEvent(event)
        if (draggingTool != null) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    draggingX = event.x
                    draggingY = event.y
                    if (placed.isNotEmpty()) {
                        val inContent = (event.y - card.top + scroll.offset)
                            .coerceIn(sectionTitle, (allTop - 1f).coerceAtLeast(sectionTitle))
                        val column = ((event.x.coerceIn(card.left, card.right - 1f) - card.left) / cellWidth())
                            .toInt().coerceIn(0, columns - 1)
                        val row = ((inContent - sectionTitle) / cellHeight).toInt()
                        val target = (row * columns + column).coerceIn(0, placed.lastIndex)
                        val from = placed.indexOf(draggingTool)
                        if (from >= 0 && target != from) {
                            val reordered = placed.toMutableList()
                            val moving = reordered.removeAt(from)
                            reordered.add(target.coerceIn(0, reordered.size), moving)
                            placed = reordered
                            draggingIndex = target
                        }
                    }
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // 松手后保持当前顺序，点击表头完成键时写入设置。
                    draggingTool = null
                    draggingIndex = -1
                    invalidate()
                }
            }
            return true
        }
        return handled
    }

    override fun onTap(x: Float, y: Float) {
        if (y < headerHeight) {
            if (x > width - ink.dp(RESET_WIDTH_DP)) placed = ToolbarTool.DEFAULT
            return
        }
        if (!card.contains(x, y)) return
        val inContent = y - card.top + scroll.offset
        val column = ((x - card.left) / cellWidth()).toInt().coerceIn(0, columns - 1)
        if (inContent >= allTop + sectionTitle) {
            val row = ((inContent - allTop - sectionTitle) / cellHeight).toInt()
            val tool = ToolbarTool.entries.getOrNull(row * columns + column) ?: return
            placed = if (tool in placed) placed - tool else placed + tool
        } else if (inContent in sectionTitle..allTop) {
            val row = ((inContent - sectionTitle) / cellHeight).toInt()
            placed.getOrNull(row * columns + column)?.let { placed = placed - it }
        }
    }

    private companion object {
        const val RESET = "恢复默认"
        const val RESET_WIDTH_DP = 100f
    }
}
