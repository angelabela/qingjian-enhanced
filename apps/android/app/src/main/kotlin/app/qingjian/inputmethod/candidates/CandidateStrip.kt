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
class CandidateStrip(private val density: Float, private val barHeight: Float, textScale: Float, markColor: Int, fontWeight: Settings.FontWeight = Settings.FontWeight.NORMAL, private var transparentBackground: Boolean = false, private val glassTint: Int = markColor, candidateTextColor: Int = markColor, styleConfig: CandidateTextStyleEngine.Config = CandidateTextStyleEngine.Config.defaults(candidateTextColor, fontWeight),
    private val fallbackBackgroundColor: Int = markColor) {
    private val textStyles = CandidateTextStyleEngine(density, 20f * density * textScale, candidateTextColor, fontWeight, styleConfig)
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = markColor }
    private val glossColor = markColor
    private val emojiPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).also {
        it.textSize = 25f * density * textScale
        it.textAlign = Paint.Align.CENTER
        it.typeface = fontWeight.typeface
        it.isFakeBoldText = fontWeight.weight >= 600
    }
    private val fill = Paint()
    private val leftFadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
    }
    /** 清除旧拼音整行字形；只清字形区域，保留透明背景/主题底色。 */
    private val clearPreeditPaint = Paint().apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
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
    private val caretPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = markColor
        strokeWidth = 1.6f * density
        strokeCap = Paint.Cap.ROUND
    }
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

    fun setAdaptiveTextColor(color: Int?) {
        textStyles.setAdaptiveTextColor(color)
        extraText.color = color ?: candidateText.color
        fallbackText.color = color ?: candidateText.color
    }

    private var textFadeStartedAt = 0L
    private var textFadeActive = false

    fun show(preedit: String, candidates: List<Candidate>, animateReveal: Boolean = true) {
        val changed = this.preedit != preedit || this.candidates.map { it.text } != candidates.map { it.text }
        this.preedit = preedit
        this.candidates = candidates
        if (animateReveal && changed && (preedit.isNotEmpty() || candidates.isNotEmpty())) {
            textFadeStartedAt = android.os.SystemClock.uptimeMillis()
            textFadeActive = true
        } else if (!animateReveal) {
            // AI 重排只调整既有候选顺序，不再重播第二轮逐字淡入，避免用户感觉候选刷新两遍。
            textFadeActive = false
            textStyles.setTransitionAlpha(255)
        }
    }

    /** 每个候选按自己的起始时间渐入；滚动时仍使用同一时间轴，避免动画跳帧。 */
    private fun candidateRevealProgress(index: Int): Float {
        if (!textFadeActive) return 1f
        val elapsed = android.os.SystemClock.uptimeMillis() - textFadeStartedAt -
            index.coerceAtLeast(0) * CANDIDATE_REVEAL_STAGGER_MS
        if (elapsed <= 0L) return 0f
        val fraction = (elapsed.toFloat() / TEXT_FADE_DURATION_MS).coerceIn(0f, 1f)
        // cubic ease-out：先轻快进入，末段柔和停稳，不产生弹跳。
        return 1f - (1f - fraction) * (1f - fraction) * (1f - fraction)
    }

    private fun candidateRevealAlpha(index: Int): Int =
        (candidateRevealProgress(index) * 255f).roundToInt().coerceIn(0, 255)

    /** 候选从右侧轻微位移并渐入，最终精确落回原布局位置。 */
    private fun candidateRevealOffset(index: Int): Float =
        (1f - candidateRevealProgress(index)) * CANDIDATE_REVEAL_OFFSET_DP * density

    /** 供外层 View 在最后一个候选完成显现前持续请求下一帧。 */
    fun advanceTextFade(): Boolean {
        if (!textFadeActive) return false
        val elapsed = android.os.SystemClock.uptimeMillis() - textFadeStartedAt
        val staggerEnd = (candidates.size.coerceAtLeast(1) - 1) * CANDIDATE_REVEAL_STAGGER_MS
        if (elapsed >= TEXT_FADE_DURATION_MS + staggerEnd) {
            textFadeActive = false
            textStyles.setTransitionAlpha(255)
            return false
        }
        // 只对候选项单独做动画，拼音行和释义不再跟着整行一起闪现。
        textStyles.setTransitionAlpha(255)
        return true
    }

    /** 换上新画的一帧；画不出来（null）时只铺底色。 */
    fun paint(image: StripImage?) {
        // 释放上一帧透明背景副本，避免候选频繁变化时 Bitmap 持续堆积。
        renderedBitmap?.let { if (!it.isRecycled) it.recycle() }
        renderedBitmap = null
        // Rust 在空内容或尺寸异常时可能返回 0×N 元数据；禁止把无效位图交给 Canvas。
        if (image == null || image.bitmap.isRecycled ||
            image.bitmap.width <= 0 || image.bitmap.height <= 0
        ) {
            this.image = null
            return
        }
        this.image = image
        renderedBitmap = if (transparentBackground) {
            // 不假设 Rust 返回的 Bitmap 可变；复制后再清理底色，避免污染渲染器缓存。
            val source = image.bitmap
            val bitmap = source.copy(Bitmap.Config.ARGB_8888, true) ?: run {
                this.image = null
                return
            }
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

    /** 背景模式切换时重新处理当前帧，避免旧的实底位图遮住视频或主题底色。 */
    fun setTransparentBackground(enabled: Boolean) {
        if (transparentBackground == enabled) return
        transparentBackground = enabled
        image?.let { paint(it) }
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

    /** 首选词的整体视觉区域：同时包含词条和其下方的词性/英文释义 hit 区。 */
    fun candidateEffectRect(index: Int): RectF? {
        val frame = image ?: return wordRect(index)
        val result = wordRect(index) ?: return null
        // wordRect() 已处理无拼音候选行的垂直居中；释义命中框也必须应用同一偏移。
        val shift = if (frame.candidatesTop == 0) {
            ((barHeight - frame.bitmap.height) / 2).coerceAtLeast(0f)
        } else 0f
        frame.hits.filter { it.candidate == index && it.sense != null }.forEach { hit ->
            result.union(RectF(hit.rect).apply { offset(0f, shift) })
        }
        return result
    }

    /** 候选文字显现动画当前的横向位移，供外层首选特效与文字保持同步。 */
    fun candidateRevealOffsetPx(index: Int): Float = candidateRevealOffset(index)

    /** 点在拼音行上（视图坐标，拼音行不随候选滚动）：光标该落在第几个字之后；不在拼音行上为 -1。 */
    fun caretAt(x: Float, y: Float): Int = image?.caretAt(x, y) ?: -1

    /** 拖动中：只按横坐标找最近的缝，手指上下偏出拼音行也照样跟。 */
    fun caretNear(x: Float): Int = image?.caretNear(x) ?: -1

    /** 在拼音行绘制明确的插入光标，让用户知道下一次输入/退格作用于哪个字母位置。 */
    fun drawPreeditCaret(canvas: Canvas, index: Int, leftInset: Float) {
        val frame = image ?: return
        val x = frame.preeditEdges.getOrNull(index) ?: return
        if (frame.preeditBottom <= frame.preeditTop) return
        val half = 1f * density
        val top = (frame.preeditTop - 2f * density).coerceAtLeast(0f)
        val bottom = (frame.preeditBottom + 2f * density).coerceAtMost(barHeight)
        caretPaint.alpha = 230
        canvas.drawRoundRect(
            RectF(leftInset + x - half, top, leftInset + x + half, bottom),
            half, half, caretPaint,
        )
    }

    /** 左侧内边距与触摸命中共用，避免视觉位置和点击位置产生偏差。 */
    // 左侧保留明显的安全留白，滚动候选在进入可视区时先渐显，避免贴边或露出半个字。
    fun leftInsetPx(viewWidth: Int): Float = maxOf(16f * density, viewWidth * 0.045f)

    /** [scrollX] 是视图已经滚过去的距离，画布已经按它平移过。 */
    fun draw(canvas: Canvas, scrollX: Int, viewWidth: Int, selectedIndex: Int = -1) {
        advanceTextFade()
        val layer = canvas.saveLayer(scrollX.toFloat(), 0f, (scrollX + viewWidth).toFloat(), barHeight, null)
        val leftInset = leftInsetPx(viewWidth)
        canvas.translate(leftInset, 0f)
        val contentViewWidth = (viewWidth - leftInset).toInt().coerceAtLeast(1)
        // 硬裁剪候选绘制视口，滑动和入场动画中的字形、释义、图标都不能越过左侧内边距。
        canvas.clipRect(scrollX.toFloat(), 0f, (scrollX + contentViewWidth).toFloat(), barHeight)
        val image = image
        if (image == null) {
            drawFallback(canvas, scrollX, contentViewWidth)
            applyLeftFade(canvas, scrollX, contentViewWidth)
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
        // 选中/首选高亮统一由 CandidateBarView 绘制，确保同一时刻只有一个高亮层。
        // 候选文字、拼音、释义全部由独立样式引擎覆盖绘制；Rust 只提供几何与命中区域。
        candidates.forEachIndexed { index, candidate ->
            if (image.emoji.contains(index)) return@forEachIndexed
            val word = image.wordRect(index) ?: return@forEachIndexed
            if (word.right < scrollX || word.left > scrollX + contentViewWidth) return@forEachIndexed
            val role = when {
                index == 0 -> CandidateTextStyleEngine.Role.FIRST_CANDIDATE
                else -> CandidateTextStyleEngine.Role.CANDIDATE
            }
            val revealSave = canvas.save()
            canvas.translate(candidateRevealOffset(index), 0f)
            textStyles.drawReplacing(
                canvas, candidate.text,
                RectF(word.left, word.top + shift, word.right, word.bottom + shift),
                role, eraseUnderlay = false, alphaMultiplier = candidateRevealAlpha(index),
            )
            canvas.restoreToCount(revealSave)
        }
        // 拼音数据可能比上一帧短或字符宽度发生变化。先清理整条旧拼音绘制带，
        // 再按当前 preeditEdges 重绘，避免旧字母/拼音残影留在新字符之间。
        val codePoints = preedit.codePoints().toArray()
        val edges = image.preeditEdges
        if (top > 0 && preedit.isNotEmpty()) {
            val clearTop = image.preeditTop.coerceIn(0f, top.toFloat())
            val clearBottom = image.preeditBottom.coerceIn(clearTop, top.toFloat())
            if (clearBottom > clearTop) {
                canvas.drawRect(scrollX.toFloat(), clearTop, (scrollX + contentViewWidth).toFloat(), clearBottom, clearPreeditPaint)
                if (!transparentBackground) {
                    fill.color = image.background
                    canvas.drawRect(scrollX.toFloat(), clearTop, (scrollX + contentViewWidth).toFloat(), clearBottom, fill)
                }
            }
        }
        // 拼音作为一整行按自然字距绘制，避免逐字符塞进过窄的字格导致缩小、挤叠和笔画发虚。
        // Rust 提供的 preeditEdges 仍保留给光标命中与拖动，不改变任何触摸区域。
        if (codePoints.isNotEmpty() && edges.size >= codePoints.size + 1) {
            val topY = image.preeditTop
            val bottomY = image.preeditBottom.coerceAtLeast(topY + 1f)
            val left = edges.first()
            val right = edges[codePoints.size]
            if (right >= scrollX && left <= scrollX + contentViewWidth) {
                textStyles.drawReplacing(
                    canvas, preedit, RectF(left, topY, right, bottomY),
                    CandidateTextStyleEngine.Role.PREEDIT,
                )
            }
        }
        // 释义由 Candidate.senses 提供文本，Rust hit rect 只提供布局位置。
        // 一个 sense 可能被布局成「词性+译文」两个 hit，也可能只有一个合并 hit；
        // 先按 sense 分组，再按实际 hit 数量决定合并或拆分，避免只有词性、英文释义丢失。
        image.hits.filter { it.sense != null }
            .groupBy { it.candidate to it.sense!! }
            .forEach { (key, hits) ->
                val candidateIndex = key.first
                val senseIndex = key.second
                val sense = candidates.getOrNull(candidateIndex)?.senses?.getOrNull(senseIndex) ?: return@forEach
                val pos = sense.pos?.trim().orEmpty()
                val gloss = sense.text.trim()
                if (pos.isEmpty() && gloss.isEmpty()) return@forEach
                val segments = if (hits.size == 1) {
                    listOf(listOf(pos, gloss).filter { it.isNotEmpty() }.joinToString("  "))
                } else {
                    listOfNotNull(pos.takeIf { it.isNotEmpty() }?.plus(" "), gloss.takeIf { it.isNotEmpty() })
                }
                hits.forEachIndexed { segmentIndex, hit ->
                    if (hit.rect.right < scrollX || hit.rect.left > scrollX + contentViewWidth) return@forEachIndexed
                    val segment = segments.getOrNull(segmentIndex) ?: return@forEachIndexed
                    val revealSave = canvas.save()
                    canvas.translate(candidateRevealOffset(candidateIndex), 0f)
                    textStyles.drawReplacing(
                        canvas, segment, RectF(hit.rect), CandidateTextStyleEngine.Role.GLOSS,
                        eraseUnderlay = false, alphaMultiplier = candidateRevealAlpha(candidateIndex),
                    )
                    canvas.restoreToCount(revealSave)
                }
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
            val originalAlpha = extraText.alpha
            val revealSave = canvas.save()
            canvas.translate(candidateRevealOffset(index), 0f)
            extraText.alpha = originalAlpha * candidateRevealAlpha(index) / 255
            canvas.drawText(value, rect.centerX(), baseline, extraText)
            extraText.alpha = originalAlpha
            canvas.restoreToCount(revealSave)
        }
        for (index in image.emoji) {
            val word = image.wordRect(index) ?: continue
            if (word.right < scrollX || word.left > scrollX + contentViewWidth) continue
            val text = candidates.getOrNull(index)?.text ?: continue
            val baseline = word.centerY() + shift - (emojiPaint.ascent() + emojiPaint.descent()) / 2
            val originalAlpha = emojiPaint.alpha
            val revealSave = canvas.save()
            canvas.translate(candidateRevealOffset(index), 0f)
            emojiPaint.alpha = originalAlpha * candidateRevealAlpha(index) / 255
            canvas.drawText(text, word.centerX(), baseline, emojiPaint)
            emojiPaint.alpha = originalAlpha
            canvas.restoreToCount(revealSave)
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
        applyLeftFade(canvas, scrollX, contentViewWidth)
        applyRightFade(canvas, scrollX, contentViewWidth, (top + shift).toFloat())
        canvas.restoreToCount(layer)
    }

    /** 左侧候选区保留固定内边距，并在内容进入视口时渐显；滚动后遮罩仍锚定在内容视口边缘。 */
    private fun applyLeftFade(canvas: Canvas, scrollX: Int, viewWidth: Int) {
        val fadeWidth = minOf(16f * density, viewWidth.toFloat()).coerceAtLeast(1f)
        // draw() 已将画布向右平移 leftInset；从 scrollX 开始，正好对应候选内容的可视左边缘。
        val left = scrollX.toFloat()
        val right = scrollX.toFloat() + viewWidth
        leftFadePaint.shader = android.graphics.LinearGradient(
            left, 0f, left + fadeWidth, 0f,
            intArrayOf(0x00000000, 0xFF000000.toInt()),
            floatArrayOf(0f, 1f), android.graphics.Shader.TileMode.CLAMP,
        )
        canvas.drawRect(left, 0f, right, barHeight, leftFadePaint)
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
            fill.color = fallbackBackgroundColor
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
            val originalAlpha = fallbackText.alpha
            val revealSave = canvas.save()
            canvas.translate(candidateRevealOffset(index), 0f)
            fallbackText.alpha = originalAlpha * candidateRevealAlpha(index) / 255
            canvas.drawText(candidate.text, rect.centerX() - scrollX, baseline, fallbackText)
            fallbackText.alpha = originalAlpha
            canvas.restoreToCount(revealSave)
        }
        canvas.drawRect(scrollX.toFloat(), barHeight - density, (scrollX + viewWidth).toFloat(), barHeight, fill)
    }
    private companion object {
        const val TEXT_FADE_DURATION_MS = 220L
        const val CANDIDATE_REVEAL_STAGGER_MS = 58L
        const val CANDIDATE_REVEAL_OFFSET_DP = 9f
        /** 人头标记多大（dp）：比译文字号小一点，不抢候选字。 */
        const val CONTACT_MARK_DP = 11f
    }
}
