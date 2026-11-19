package app.qingjian.inputmethod.candidates

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.roundToInt
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
class CandidateStrip(private val density: Float, private val barHeight: Float, textScale: Float, markColor: Int, fontWeight: Settings.FontWeight = Settings.FontWeight.NORMAL, private val transparentBackground: Boolean = false, private val glassTint: Int = markColor, candidateTextColor: Int = markColor, styleConfig: CandidateTextStyleEngine.Config = CandidateTextStyleEngine.Config.defaults(candidateTextColor, fontWeight)) {
    private val textStyles = CandidateTextStyleEngine(density, 20f * density * textScale, candidateTextColor, fontWeight, styleConfig)
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = markColor }
    private val glossColor = markColor
    private val emojiPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).also {
        it.textSize = 19f * density * textScale
        it.textAlign = Paint.Align.CENTER
        it.typeface = fontWeight.typeface
        it.isFakeBoldText = fontWeight.weight >= 600
    }
    private val fill = Paint()
    private val glassFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glassEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glassHighlight = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val leftFadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
    }
    /** 正常候选文字由 Android 原生字体重新绘制；字号、字重与键盘文字设置保持一致。 */
    private val candidateText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = candidateTextColor
        textSize = 20f * density * textScale
        textAlign = Paint.Align.CENTER
        typeface = fontWeight.typeface
        isFakeBoldText = fontWeight.weight >= 600
        isSubpixelText = true
    }
    /** 用稍粗的 CLEAR 字形擦掉 Rust 位图中的旧字形，不动候选格底色、分隔线和译文。 */
    private val eraseCandidateText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.TRANSPARENT
        textSize = 20f * density * textScale
        textAlign = Paint.Align.CENTER
        typeface = fontWeight.typeface
        isFakeBoldText = fontWeight.weight >= 600
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
    }
    private val eraseCandidateFill = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.TRANSPARENT
        textSize = 20f * density * textScale
        textAlign = Paint.Align.CENTER
        typeface = fontWeight.typeface
        isFakeBoldText = fontWeight.weight >= 600
        style = Paint.Style.FILL
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
    }
    private val extraText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = markColor
        textSize = 20f * density * textScale
        textAlign = Paint.Align.CENTER
        typeface = fontWeight.typeface
        isFakeBoldText = fontWeight.weight >= 600
    }
    private val extraFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = markColor and 0x18FFFFFF }
    private val fallbackText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = markColor; textSize = 19f * density * textScale; textAlign = Paint.Align.CENTER; typeface = fontWeight.typeface; isFakeBoldText = fontWeight.weight >= 600 }
    private val fallbackRects = ArrayList<RectF>()
    private val src = Rect()
    private val dst = Rect()

    private var preedit = ""
    private var image: StripImage? = null
    /** 背景特效模式下使用独立的透明位图，避免修改 Rust 渲染器持有的原始帧。 */
    private var renderedBitmap: Bitmap? = null

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
        renderedBitmap = if (transparentBackground && image != null) {
            // 不假设 Rust 返回的 Bitmap 可变；复制后再清理底色，避免污染渲染器缓存。
            val source = image.bitmap
            val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val bg = image.background
            val br = (bg shr 16) and 0xFF
            val bgc = (bg shr 8) and 0xFF
            val bb = bg and 0xFF
            for (index in pixels.indices) {
                val pixel = pixels[index]
                val alpha = pixel ushr 24
                if (alpha == 0) continue
                val dr = kotlin.math.abs(((pixel shr 16) and 0xFF) - br)
                val dg = kotlin.math.abs(((pixel shr 8) and 0xFF) - bgc)
                val db = kotlin.math.abs((pixel and 0xFF) - bb)
                // 去除纯底色，同时对抗锯齿边缘做反底色（un-matte）还原。
                // 旧算法只降低 alpha 却保留混入底色的 RGB，叠到动态背景后会出现
                // 灰白描边/重影；这里先估算覆盖率，再反算前景色。
                val distance = maxOf(dr, dg, db)
                if (distance <= 5) {
                    pixels[index] = 0
                } else if (distance <= 58) {
                    val bgMax = maxOf(br, bgc, bb)
                    val bgMin = minOf(br, bgc, bb)
                    val coverage = if (bgMax >= 128) {
                        maxOf(
                            (br - ((pixel shr 16) and 0xFF)).coerceAtLeast(0) * 255 / br.coerceAtLeast(1),
                            (bgc - ((pixel shr 8) and 0xFF)).coerceAtLeast(0) * 255 / bgc.coerceAtLeast(1),
                            (bb - (pixel and 0xFF)).coerceAtLeast(0) * 255 / bb.coerceAtLeast(1),
                        )
                    } else {
                        maxOf(
                            (((pixel shr 16) and 0xFF) - br).coerceAtLeast(0) * 255 / (255 - br).coerceAtLeast(1),
                            (((pixel shr 8) and 0xFF) - bgc).coerceAtLeast(0) * 255 / (255 - bgc).coerceAtLeast(1),
                            ((pixel and 0xFF) - bb).coerceAtLeast(0) * 255 / (255 - bb).coerceAtLeast(1),
                        )
                    }.coerceIn(1, 255)
                    val inverse = 255 - coverage
                    val r = ((((pixel shr 16) and 0xFF) * 255 - br * inverse) / coverage).coerceIn(0, 255)
                    val g = ((((pixel shr 8) and 0xFF) * 255 - bgc * inverse) / coverage).coerceIn(0, 255)
                    val b = (((pixel and 0xFF) * 255 - bb * inverse) / coverage).coerceIn(0, 255)
                    pixels[index] = ((alpha * coverage / 255) shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            // 保留 Rust 主题渲染器输出的释义颜色；动态背景只处理背景透明像素，
            // 不再强制改成白色，避免候选文字脱离当前 App 主题。
            bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            bitmap
        } else null
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
    fun draw(canvas: Canvas, scrollX: Int, viewWidth: Int, selectedIndex: Int = -1) {
        val layer = canvas.saveLayer(scrollX.toFloat(), 0f, (scrollX + viewWidth).toFloat(), barHeight, null)
        val leftInset = (viewWidth * 0.05f).roundToInt().toFloat()
        canvas.translate(leftInset, 0f)
        val contentViewWidth = (viewWidth - leftInset).toInt().coerceAtLeast(1)
        val image = image
        if (image == null) {
            drawFallback(canvas, scrollX, contentViewWidth)
            applyLeftFade(canvas, scrollX, contentViewWidth, if (preedit.isNotEmpty()) barHeight * 0.42f else 0f)
            canvas.restoreToCount(layer)
            return
        }
        val bitmap = renderedBitmap ?: image.bitmap
        val top = image.candidatesTop.coerceIn(0, bitmap.height)
        if (!transparentBackground) {
            fill.color = image.background
            canvas.drawRect(scrollX.toFloat(), 0f, (scrollX + viewWidth).toFloat(), barHeight, fill)
        }
        // 没有拼音行（补全）时候选行在条里上下居中，不贴着顶
        val shift = if (top == 0) ((barHeight - bitmap.height) / 2).toInt().coerceAtLeast(0) else 0
        val right = minOf(scrollX + contentViewWidth, bitmap.width)
        if (right > scrollX && top < bitmap.height) {
            src.set(scrollX, top, right, bitmap.height)
            dst.set(src)
            dst.offset(0, shift)
            canvas.drawBitmap(bitmap, src, dst, null)
        }
        // 没有正在编辑的拼音时，不绘制 Rust 位图上一次留下的字母行。
        if (top > 0 && preedit.isNotEmpty()) {
            src.set(0, 0, minOf(contentViewWidth, bitmap.width), top)
            dst.set(scrollX, 0, scrollX + src.width(), top)
            canvas.drawBitmap(bitmap, src, dst, null)
        }
        // 候选文字、拼音、释义全部由独立样式引擎覆盖绘制；Rust 只提供几何与命中区域。
        candidates.forEachIndexed { index, candidate ->
            if (image.emoji.contains(index)) return@forEachIndexed
            val word = image.wordRect(index) ?: return@forEachIndexed
            if (word.right < scrollX || word.left > scrollX + contentViewWidth) return@forEachIndexed
            val role = when {
                index == selectedIndex -> CandidateTextStyleEngine.Role.SELECTED
                index == 0 -> CandidateTextStyleEngine.Role.FIRST_CANDIDATE
                else -> CandidateTextStyleEngine.Role.CANDIDATE
            }
            textStyles.drawReplacing(canvas, candidate.text, RectF(word.left, word.top + shift, word.right, word.bottom + shift), role)
        }
        // 拼音行按 Rust 提供的字符边界定位，逐字原生重绘，不改变光标命中区域。
        val codePoints = preedit.codePoints().toArray()
        val edges = image.preeditEdges
        if (codePoints.isNotEmpty() && edges.size >= codePoints.size + 1) {
            val topY = image.preeditTop
            val bottomY = image.preeditBottom.coerceAtLeast(topY + 1f)
            for (i in codePoints.indices) {
                val left = edges[i]
                val rightEdge = edges[i + 1]
                if (rightEdge < scrollX || left > scrollX + contentViewWidth) continue
                val glyph = String(Character.toChars(codePoints[i]))
                textStyles.drawReplacing(canvas, glyph, RectF(left, topY, rightEdge, bottomY), CandidateTextStyleEngine.Role.PREEDIT)
            }
        }
        // 释义由 Candidate.senses 提供文本，Rust hit rect 提供每一条释义的位置。
        image.hits.filter { it.sense != null }.forEach { hit ->
            if (hit.rect.right < scrollX || hit.rect.left > scrollX + contentViewWidth) return@forEach
            val gloss = candidates.getOrNull(hit.candidate)?.senses?.getOrNull(hit.sense!!)?.text ?: return@forEach
            textStyles.drawReplacing(canvas, gloss, RectF(hit.rect), CandidateTextStyleEngine.Role.GLOSS)
        }
        // Kotlin 侧补充的语义标点没有 Rust 位图格子，直接追加绘制在主候选栏。
        val extras = extraCandidates()
        val extraStart = bitmap.width + 12f * density
        val extraCell = 38f * density
        extras.forEachIndexed { slot, index ->
            val left = extraStart + slot * extraCell
            if (left + extraCell < scrollX || left > scrollX + contentViewWidth) return@forEachIndexed
            val rect = RectF(left + 2f * density, 8f * density, left + extraCell - 2f * density, barHeight - 8f * density)
            // 补充候选不再铺独立色块，保持与主候选一致的透明文字层。
            val value = candidates.getOrNull(index)?.text ?: return@forEachIndexed
            val baseline = rect.centerY() - (extraText.ascent() + extraText.descent()) / 2f
            canvas.drawText(value, rect.centerX(), baseline, extraText)
        }
        for (index in image.emoji) {
            val word = image.wordRect(index) ?: continue
            if (word.right < scrollX || word.left > scrollX + contentViewWidth) continue
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
        applyLeftFade(canvas, scrollX, contentViewWidth, (top + shift).toFloat())
        applyRightFade(canvas, scrollX, contentViewWidth, (top + shift).toFloat())
        canvas.restoreToCount(layer)
    }

    /** 滚动内容在视口左边缘渐隐，不允许候选文字直接越过屏幕边界。 */
    private fun applyLeftFade(canvas: Canvas, scrollX: Int, viewWidth: Int, fadeTop: Float) {
        // 左边缘先留出安全距离，再在其内侧完成渐隐，避免文字贴边才突然消失。
        val inset = minOf(8f * density, viewWidth * 0.12f)
        val fadeWidth = minOf(34f * density, (viewWidth - inset).coerceAtLeast(1f))
        leftFadePaint.shader = android.graphics.LinearGradient(
            scrollX.toFloat(), 0f, scrollX + inset + fadeWidth, 0f,
            intArrayOf(0x00000000, 0x00000000, 0xFF000000.toInt(), 0xFF000000.toInt()),
            floatArrayOf(0f, 0.18f, 0.82f, 1f), android.graphics.Shader.TileMode.CLAMP,
        )
        canvas.drawRect(scrollX.toFloat(), fadeTop.coerceIn(0f, barHeight), (scrollX + inset + fadeWidth).coerceAtMost(scrollX + viewWidth), barHeight, leftFadePaint)
        leftFadePaint.shader = null
    }



    /** 右侧紧贴候选栏竖向分隔线渐隐，候选从分隔线方向进入时由透明逐渐显现。 */
    private fun applyRightFade(canvas: Canvas, scrollX: Int, viewWidth: Int, fadeTop: Float) {
        val fadeWidth = minOf(28f * density, viewWidth.toFloat()).coerceAtLeast(1f)
        val right = (scrollX + viewWidth).toFloat()
        val left = right - fadeWidth
        leftFadePaint.shader = android.graphics.LinearGradient(
            left, 0f, right, 0f,
            intArrayOf(0xFF000000.toInt(), 0xFF000000.toInt(), 0x00000000),
            floatArrayOf(0f, 0.55f, 1f), android.graphics.Shader.TileMode.CLAMP,
        )
        canvas.drawRect(left, fadeTop.coerceIn(0f, barHeight), right, barHeight, leftFadePaint)
        leftFadePaint.shader = null
    }

    /** 原生位图暂不可用时，仍按候选数据绘制可见、可点击的候选。 */
    private fun fallbackWidth(): Float = (if (preedit.isNotEmpty()) 52f * density else 8f * density) + candidates.size * 56f * density + 8f * density

    private fun drawFallback(canvas: Canvas, scrollX: Int, viewWidth: Int) {
        if (!transparentBackground) {
            fill.color = 0xFFF2F3F5.toInt()
            canvas.drawRect(scrollX.toFloat(), 0f, (scrollX + viewWidth).toFloat(), barHeight, fill)
        }
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
