package app.qingjian.inputmethod.panel

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import app.qingjian.inputmethod.panel.base.PanelIcons.Icon
import app.qingjian.inputmethod.panel.base.PanelView
import app.qingjian.inputmethod.panel.base.Tabs
import app.qingjian.inputmethod.settings.Settings
import app.qingjian.inputmethod.settings.ToolbarTool
import app.qingjian.inputmethod.theme.Palette
import kotlin.math.abs

/**
 * 定制工具栏：上面一排是工具栏上的项目，长按可排序；下面是全部工具，点按添加或移除。
 * 拖动时使用抬升、柔和光晕、轻微倾斜和目标槽位提示；松手后带弹性归位。
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
    private var placed = saved

    private var draggingTool: ToolbarTool? = null
    private var draggingIndex = -1
    private var dragTargetIndex = -1
    private var draggingX = 0f
    private var draggingY = 0f
    private var dragTilt = 0f
    private var liftProgress = 0f
    private var dragAnimator: ValueAnimator? = null

    private val dragFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dragStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ink.dp(1.5f)
    }

    private val sectionTitle get() = ink.dp(34f)
    private val cellHeight get() = ink.dp(68f)
    private fun rows(count: Int) = maxOf(1, (count + columns - 1) / columns)
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
        draggingTool?.let { drawFloatingTool(canvas, it) }
        canvas.restore()
    }

    private fun drawTitle(canvas: Canvas, title: String, top: Float) {
        ink.textStart(canvas, title, 12f, palette.gloss, card.left + ink.dp(16f), top + sectionTitle / 2 + ink.dp(4f), card.right - ink.dp(16f))
    }

    private fun cellCenter(index: Int, top: Float): Pair<Float, Float> {
        val cx = card.left + cellWidth() * (index % columns + 0.5f)
        val cy = top + cellHeight * (index / columns + 0.5f)
        return cx to cy
    }

    private fun drawCell(canvas: Canvas, tool: ToolbarTool, index: Int, top: Float, badge: Badge) {
        val (cx, cy) = cellCenter(index, top)
        val highlighted = badge == Badge.PLACED
        val isDragged = draggingTool == tool && badge == Badge.REMOVE
        val isTarget = badge == Badge.REMOVE && draggingTool != null && dragTargetIndex == index

        if (isDragged) {
            val placeholder = RectF(cx - cellWidth() * 0.40f, cy - cellHeight * 0.39f, cx + cellWidth() * 0.40f, cy + cellHeight * 0.39f)
            dragFill.color = withAlpha(palette.brand, 28)
            canvas.drawRoundRect(placeholder, ink.dp(13f), ink.dp(13f), dragFill)
            dragStroke.color = withAlpha(palette.brand, 150)
            dragStroke.pathEffect = android.graphics.DashPathEffect(floatArrayOf(ink.dp(4f), ink.dp(3f)), 0f)
            canvas.drawRoundRect(placeholder, ink.dp(13f), ink.dp(13f), dragStroke)
            dragStroke.pathEffect = null
            return
        }

        if (isTarget) {
            val target = RectF(cx - cellWidth() * 0.43f, cy - cellHeight * 0.43f, cx + cellWidth() * 0.43f, cy + cellHeight * 0.43f)
            dragFill.color = withAlpha(palette.brand, 36)
            canvas.drawRoundRect(target, ink.dp(13f), ink.dp(13f), dragFill)
            dragStroke.color = withAlpha(palette.brand, 190)
            canvas.drawRoundRect(target, ink.dp(13f), ink.dp(13f), dragStroke)
        }

        ink.icon(canvas, tool.icon, cx, cy - ink.dp(10f), 26f, if (highlighted) palette.brand else palette.text)
        ink.text(canvas, tool.label, 12f, if (highlighted) palette.brand else palette.gloss, cx, cy + ink.dp(18f), maxWidth = cellWidth() - ink.dp(4f))
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

    private fun drawFloatingTool(canvas: Canvas, tool: ToolbarTool) {
        val scale = 1f + 0.16f * liftProgress
        val size = ink.dp(56f) * scale
        val rect = RectF(draggingX - size / 2, draggingY - size / 2, draggingX + size / 2, draggingY + size / 2)
        val save = canvas.save()
        canvas.rotate(dragTilt * liftProgress, draggingX, draggingY)

        // 多层柔影比单一放大更有悬浮深度。
        for (layer in 4 downTo 1) {
            val shadow = RectF(rect).apply {
                offset(0f, ink.dp(layer * 1.6f))
                inset(-ink.dp(layer * 1.2f), -ink.dp(layer * 1.2f))
            }
            dragFill.color = withAlpha(Color.BLACK, (10 + layer * 5) * liftProgress.toInt().coerceAtLeast(1))
            canvas.drawRoundRect(shadow, ink.dp(17f), ink.dp(17f), dragFill)
        }

        val halo = RectF(rect).apply { inset(-ink.dp(5f + liftProgress * 3f), -ink.dp(5f + liftProgress * 3f)) }
        dragStroke.color = withAlpha(palette.brand, (75 + 90 * liftProgress).toInt())
        dragStroke.strokeWidth = ink.dp(1.2f + liftProgress)
        canvas.drawRoundRect(halo, ink.dp(20f), ink.dp(20f), dragStroke)

        dragFill.color = palette.brand
        canvas.drawRoundRect(rect, ink.dp(16f), ink.dp(16f), dragFill)
        ink.icon(canvas, tool.icon, draggingX, draggingY - ink.dp(3f), 29f, palette.onBrand)
        ink.text(canvas, tool.label, 10f, palette.onBrand, draggingX, draggingY + ink.dp(17f), maxWidth = size - ink.dp(8f))
        canvas.restoreToCount(save)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)

    private fun animateLift(from: Float, to: Float, duration: Long, overshoot: Boolean = false, end: (() -> Unit)? = null) {
        dragAnimator?.cancel()
        dragAnimator = ValueAnimator.ofFloat(from, to).apply {
            this.duration = duration
            interpolator = if (overshoot) OvershootInterpolator(1.15f) else DecelerateInterpolator()
            addUpdateListener {
                liftProgress = it.animatedValue as Float
                invalidate()
            }
            if (end != null) addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = end()
            })
            start()
        }
    }

    override fun onBack() {
        actions.closePanel()
        if (placed != saved) actions.setChoice(Settings.KEY_TOOLBAR, ToolbarTool.format(placed))
    }

    override fun onHold(x: Float, y: Float, rawX: Float) {
        if (!card.contains(x, y)) return
        val inContent = y - card.top + scroll.offset
        if (inContent < sectionTitle || inContent >= allTop || placed.isEmpty()) return
        val column = ((x - card.left) / cellWidth()).toInt().coerceIn(0, columns - 1)
        val row = ((inContent - sectionTitle) / cellHeight).toInt()
        val index = row * columns + column
        draggingTool = placed.getOrNull(index) ?: return
        draggingIndex = index
        dragTargetIndex = index
        draggingX = x
        draggingY = y
        dragTilt = 0f
        liftProgress = 0f
        animateLift(0f, 1f, 240, overshoot = true)
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = super.onTouchEvent(event)
        if (draggingTool != null) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - draggingX
                    draggingX = event.x
                    draggingY = event.y
                    dragTilt = (dx / ink.dp(2f)).coerceIn(-8f, 8f)
                    if (placed.isNotEmpty()) {
                        val inContent = (event.y - card.top + scroll.offset)
                            .coerceIn(sectionTitle, (allTop - 1f).coerceAtLeast(sectionTitle))
                        val column = ((event.x.coerceIn(card.left, card.right - 1f) - card.left) / cellWidth())
                            .toInt().coerceIn(0, columns - 1)
                        val row = ((inContent - sectionTitle) / cellHeight).toInt()
                        val target = (row * columns + column).coerceIn(0, placed.lastIndex)
                        val from = placed.indexOf(draggingTool)
                        dragTargetIndex = target
                        if (from >= 0 && target != from) {
                            val reordered = placed.toMutableList()
                            val moving = reordered.removeAt(from)
                            reordered.add(target.coerceIn(0, reordered.size), moving)
                            placed = reordered
                            draggingIndex = target
                        }
                    }
                    postInvalidateOnAnimation()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val tool = draggingTool
                    val target = placed.indexOf(tool)
                    if (target >= 0) {
                        val top = card.top - scroll.offset + sectionTitle
                        val (targetX, targetY) = cellCenter(target, top)
                        val startX = draggingX
                        val startY = draggingY
                        ValueAnimator.ofFloat(0f, 1f).apply {
                            duration = 190
                            interpolator = DecelerateInterpolator()
                            addUpdateListener {
                                val t = it.animatedValue as Float
                                draggingX = startX + (targetX - startX) * t
                                draggingY = startY + (targetY - startY) * t
                                dragTilt *= (1f - t)
                                invalidate()
                            }
                            addListener(object : android.animation.AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: android.animation.Animator) {
                                    draggingTool = null
                                    draggingIndex = -1
                                    dragTargetIndex = -1
                                    liftProgress = 0f
                                    invalidate()
                                }
                            })
                            start()
                        }
                        animateLift(liftProgress, 0f, 190)
                    } else {
                        draggingTool = null
                        draggingIndex = -1
                        dragTargetIndex = -1
                    }
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
