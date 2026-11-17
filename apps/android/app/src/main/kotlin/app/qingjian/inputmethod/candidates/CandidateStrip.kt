package app.qingjian.inputmethod.candidates

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.TextPaint
import app.qingjian.inputmethod.settings.Settings
import app.qingjian.inputmethod.core.frame.Candidate
import app.qingjian.inputmethod.core.strip.StripImage
import app.qingjian.inputmethod.panel.base.PanelIcons

/**
 * 候选条的内容：Rust 渲染器按主题画好的位图（拼音行 + 横排候选 + 译文），这里只贴图。
 * 拼音行那一截钉在视口上，候选那一截随横向滚动；emoji 候选渲染器画不出，在词的位置上用 Canvas 补；
 * 通讯录里的名字在词的右上角补一个人头（各主题都有，不用每个主题自己画）。
 */
class CandidateStrip(private val density: Float, private val barHeight: Float, textScale: Float, markColor: Int, fontWeight: Settings.FontWeight = Settings.FontWeight.NORMAL) {
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = markColor }
    private val emojiPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).also {
        it.textSize = 19f * density * textScale
        it.textAlign = Paint.Align.CENTER
        it.typeface = fontWeight.typeface
    }
    private val fill = Paint()
    private val extraText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = markColor
        textSize = 20f * density * textScale
        textAlign = Paint.Align.CENTER
        typeface = fontWeight.typeface
    }
    private val extraFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = markColor and 0x18FFFFFF }
    private val fallbackText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = markColor; textSize = 19f * density * textScale; textAlign = Paint.Align.CENTER; typeface = fontWeight.typeface }
    private val fallbackRects = ArrayList<RectF>()
    private val src = Rect()
    private val dst = Rect()

    private var preedit = ""
    private var image: StripImage? = null

    /** 当前一帧的候选，长按气泡要它们的译文。 */
    var candidates: List<Candidate> = emptyList()
        private set

    val isEmpty: Boolean get() = preedit.isEmpty() && candidates.isEmpty()

    /** 主题底色；还没画过为透明。 */
    val background: Int get() = image?.background ?: 0

    /** 拼音行与候选行的分界（位图像素）。 */
    val candidatesTop: Int get() = image?.candidatesTop ?: 0

    /** 位图宽（像素），比视口宽时可以横向滚动。 */
    val contentWidth: Float
        get() {
            val frame = image ?: return fallbackWidth()
            val extra = extraCandidates()
            if (extra.isEmpty()) return frame.bitmap.width.toFloat()
            return frame.bitmap.width + 12f * density + extra.size * 38f * density
        }

    fun show(preedit: String, candidates: List<Candidate>) {
        this.preedit = preedit
        this.candidates = candidates
    }

    /** 换上新画的一帧；画不出来（null）时只铺底色。 */
    fun paint(image: StripImage?) {
        this.image = image
    }

    fun indexAt(contentX: Float): Int {
        val frame = image ?: return fallbackRects.indexOfFirst { contentX >= it.left && contentX <= it.right }
        frame.candidateAt(contentX).takeIf { it >= 0 }?.let { return it }
        val extras = extraCandidates()
        val start = frame.bitmap.width + 12f * density
        if (contentX < start) return -1
        return extras.getOrNull(((contentX - start) / (38f * density)).toInt()) ?: -1
    }

    private fun extraCandidates(): List<Int> {
        val frame = image ?: return emptyList()
        return candidates.indices.filter { frame.wordRect(it) == null }
    }

    /** 候选文字格的实际绘制区域（已考虑无拼音行时的垂直居中偏移）。 */
    fun wordRect(index: Int): RectF? {
        val frame = image ?: return fallbackRects.getOrNull(index)?.let { RectF(it) }
        val rect = frame.wordRect(index) ?: return null
        if (frame.candidatesTop == 0) {
            rect.offset(0f, ((barHeight - frame.bitmap.height) / 2).coerceAtLeast(0f))
        }
        return rect
    }

    /** 点在拼音行上（视图坐标，拼音行不随候选滚动）：光标该落在第几个字之后；不在拼音行上为 -1。 */
    fun caretAt(x: Float, y: Float): Int = image?.caretAt(x, y) ?: -1

    /** 拖动中：只按横坐标找最近的缝，手指上下偏出拼音行也照样跟。 */
    fun caretNear(x: Float): Int = image?.caretNear(x) ?: -1

    /** [scrollX] 是视图已经滚过去的距离，画布已经按它平移过。 */
    fun draw(canvas: Canvas, scrollX: Int, viewWidth: Int) {
        val image = image
        if (image == null) { drawFallback(canvas, scrollX, viewWidth); return }
        val bitmap = image.bitmap
        val top = image.candidatesTop.coerceIn(0, bitmap.height)
        fill.color = image.background
        canvas.drawRect(scrollX.toFloat(), 0f, (scrollX + viewWidth).toFloat(), barHeight, fill)
        // 没有拼音行（补全）时候选行在条里上下居中，不贴着顶
        val shift = if (top == 0) ((barHeight - bitmap.height) / 2).toInt().coerceAtLeast(0) else 0
        val right = minOf(scrollX + viewWidth, bitmap.width)
        if (right > scrollX && top < bitmap.height) {
            src.set(scrollX, top, right, bitmap.height)
            dst.set(src)
            dst.offset(0, shift)
            canvas.drawBitmap(bitmap, src, dst, null)
        }
        if (top > 0) {
            src.set(0, 0, minOf(viewWidth, bitmap.width), top)
            dst.set(scrollX, 0, scrollX + src.width(), top)
            canvas.drawBitmap(bitmap, src, dst, null)
        }
        // Kotlin 侧补充的语义标点没有 Rust 位图格子，直接追加绘制在主候选栏。
        val extras = extraCandidates()
        val extraStart = bitmap.width + 12f * density
        val extraCell = 38f * density
        extras.forEachIndexed { slot, index ->
            val left = extraStart + slot * extraCell
            if (left + extraCell < scrollX || left > scrollX + viewWidth) return@forEachIndexed
            val rect = RectF(left + 2f * density, 8f * density, left + extraCell - 2f * density, barHeight - 8f * density)
            canvas.drawRoundRect(rect, 9f * density, 9f * density, extraFill)
            val value = candidates.getOrNull(index)?.text ?: return@forEachIndexed
            val baseline = rect.centerY() - (extraText.ascent() + extraText.descent()) / 2f
            canvas.drawText(value, rect.centerX(), baseline, extraText)
        }
        for (index in image.emoji) {
            val word = image.wordRect(index) ?: continue
            if (word.right < scrollX || word.left > scrollX + viewWidth) continue
            val text = candidates.getOrNull(index)?.text ?: continue
            val baseline = word.centerY() + shift - (emojiPaint.ascent() + emojiPaint.descent()) / 2
            canvas.drawText(text, word.centerX(), baseline, emojiPaint)
        }
        val size = CONTACT_MARK_DP * density
        candidates.forEachIndexed { index, candidate ->
            if (!candidate.contact) return@forEachIndexed
            val word = image.wordRect(index) ?: return@forEachIndexed
            if (word.right < scrollX || word.left > scrollX + viewWidth) return@forEachIndexed
            // 格子左右各有一截内边距，人头放在右边那截里、贴着上沿
            val cx = word.right - size * 0.75f
            val cy = word.top + shift + size * 0.85f
            PanelIcons.draw(canvas, PanelIcons.Icon.USER, cx, cy, size, markPaint)
        }
    }

    /** 原生位图暂不可用时，仍按候选数据绘制可见、可点击的候选。 */
    private fun fallbackWidth(): Float = (if (preedit.isNotEmpty()) 52f * density else 8f * density) + candidates.size * 56f * density + 8f * density

    private fun drawFallback(canvas: Canvas, scrollX: Int, viewWidth: Int) {
        fill.color = 0xFFF2F3F5.toInt()
        canvas.drawRect(scrollX.toFloat(), 0f, (scrollX + viewWidth).toFloat(), barHeight, fill)
        fallbackRects.clear()
        val start = if (preedit.isNotEmpty()) 52f * density else 8f * density
        if (preedit.isNotEmpty()) {
            fallbackText.textAlign = Paint.Align.LEFT
            fallbackText.textSize = 13f * density
            canvas.drawText(preedit, 8f * density - scrollX, barHeight * 0.28f, fallbackText)
        }
        fallbackText.textAlign = Paint.Align.CENTER
        fallbackText.textSize = 19f * density
        val cell = 56f * density
        candidates.forEachIndexed { index, candidate ->
            val left = start + index * cell
            val rect = RectF(left + density, 4f * density, left + cell - density, barHeight - 4f * density)
            fallbackRects += rect
            if (rect.right < scrollX || rect.left > scrollX + viewWidth) return@forEachIndexed
            val baseline = rect.centerY() - (fallbackText.ascent() + fallbackText.descent()) / 2f
            canvas.drawText(candidate.text, rect.centerX() - scrollX, baseline, fallbackText)
        }
        canvas.drawRect(scrollX.toFloat(), barHeight - density, (scrollX + viewWidth).toFloat(), barHeight, fill)
    }
    private companion object {
        /** 人头标记多大（dp）：比译文字号小一点，不抢候选字。 */
        const val CONTACT_MARK_DP = 11f
    }
}
