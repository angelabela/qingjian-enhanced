package app.qingjian.inputmethod.candidates

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.RectF
import android.text.TextUtils
import android.util.Log
import kotlin.math.roundToInt
import android.text.TextPaint
import app.qingjian.inputmethod.settings.Settings
import app.qingjian.inputmethod.core.frame.Candidate
import app.qingjian.inputmethod.core.strip.StripImage
import app.qingjian.inputmethod.panel.base.PanelIcons

/**
 * 候选条的原生绘制层：Rust 只提供布局快照与命中矩形，文字、拼音、释义和标记由 Android Canvas 绘制。
 * 拼音行钉在视口上，候选行随横向滚动；Emoji、云候选补充项和通讯录标记沿用统一样式与命中体系。
 */
class CandidateStrip(
    context: Context,
    animationCallback: Drawable.Callback,
    private val density: Float, private val barHeight: Float, textScale: Float, markColor: Int, fontWeight: Settings.FontWeight = Settings.FontWeight.NORMAL, private var transparentBackground: Boolean = false, private val glassTint: Int = markColor, candidateTextColor: Int = markColor, styleConfig: CandidateTextStyleEngine.Config = CandidateTextStyleEngine.Config.defaults(candidateTextColor, fontWeight),
    private val fallbackBackgroundColor: Int = markColor,
    private val scaledDensity: Float = density,
    /** 候选文字入场效果：0 淡入滑入、1 上浮、2 缩放、3 左滑、4 轻柔缩放。 */
    private val entryEffect: Int = 0) {
    private val emojiAnimations = NotoEmojiAnimations(context, animationCallback)
    private val customTextColor = styleConfig.firstCandidate.color.takeIf { it != candidateTextColor }
    val textStyles = CandidateTextStyleEngine(
        density, 20f * scaledDensity * textScale, candidateTextColor, fontWeight, styleConfig,
        preeditBaseTextSize = 18f * scaledDensity,
    )
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = markColor }
    private val glossColor = markColor
    private val fill = Paint()
    private val leftFadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
    }
    /** 正常候选文字由 Android 原生字体重新绘制；字号、字重与键盘文字设置保持一致。 */
    private val candidateText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = candidateTextColor
        textSize = 20f * scaledDensity * textScale
        textAlign = Paint.Align.CENTER
        typeface = fontWeight.typeface
        isFakeBoldText = fontWeight.weight >= 600
        isSubpixelText = true
    }
    private val extraFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = markColor and 0x18FFFFFF }
    private val fallbackRects = ArrayList<RectF>()
    /** 回退布局矩形对应的原始候选下标；过滤后不能再把矩形序号当作候选序号。 */
    private val fallbackCandidateIndices = ArrayList<Int>()
    private val caretPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = markColor
        strokeWidth = 1.6f * density
        strokeCap = Paint.Cap.ROUND
    }

    private var preedit = ""
    private var image: StripImage? = null
    private var lastAcceptedFrameId = -1L
    private var awaitingCandidateFrame = false

    /** 当前一帧的候选，长按气泡要它们的译文。 */
    var candidates: List<Candidate> = emptyList()
        private set

    val isEmpty: Boolean get() = preedit.isEmpty() && candidates.isEmpty()

    /** 主题底色；还没画过为透明。 */
    val background: Int get() = image?.background ?: 0

    /** 拼音行与候选行的分界（布局快照像素）。 */
    val candidatesTop: Int get() = image?.candidatesTop ?: 0

    /** 布局内容宽（像素），比视口宽时可以横向滚动。 */
    val contentWidth: Float
        get() {
            val frame = image ?: return fallbackWidth()
            if (extraCandidates().isEmpty()) return frame.width.toFloat()
            return frame.width + EXTRA_START_GAP_DP * density + extraTotalWidth()
        }

    fun setAdaptiveTextColor(color: Int?) {
        // 用户手动指定的文字颜色优先于视频/光效背景的自动黑白对比色。
        val effectiveColor = customTextColor ?: color
        textStyles.setAdaptiveTextColor(effectiveColor)
    }

    private var textFadeStartedAt = 0L
    private var textFadeActive = false

    fun onCandidatesChanged() = emojiAnimations.onCandidatesChanged()
    fun isEmojiCandidate(index: Int): Boolean = image?.emoji?.contains(index) == true
    fun resumeEmojiForLongPress(index: Int) {
        val text = candidates.getOrNull(index)?.text ?: return
        emojiAnimations.resumeForLongPress(text)
    }
    fun pauseEmojiAfterLongPress() = emojiAnimations.pauseAll()
    fun pauseAll() = emojiAnimations.pauseAll()
    fun resumeAll() = emojiAnimations.resumeAll()

    fun show(preedit: String, candidates: List<Candidate>, animateReveal: Boolean = true) {
        val changed = this.preedit != preedit || this.candidates != candidates
        if (changed) {
            // 新候选不能复用上一帧的矩形：先清空快照，等待与当前候选列表匹配的新 frame。
            image = null
            awaitingCandidateFrame = true
            cachedExtraCandidates = null
            cachedExtraCandidatePositions = null
            cachedExtraWidths = null
            cachedExtraOffsets = null
        }
        this.preedit = preedit
        this.candidates = candidates
        if (changed) {
            // 只记录形似输入残片的候选（字母混数字/分隔符），用于定位脏候选来源；
            // 不记录普通中文句子，避免日志收集大量用户输入内容。
            candidates.forEachIndexed { index, candidate ->
                val value = candidate.text
                val suspiciousToken = value.length in 2..40 &&
                    value.any { it.isLetter() } &&
                    value.any { it.isDigit() || it in ":._-" } &&
                    value.all { it.isLetterOrDigit() || it in ":._-" }
                if (suspiciousToken) {
                    val cps = value.codePoints().toArray().joinToString("-") { it.toString(16) }
                    Log.w("QJ_CANDIDATE_DIAG", "preedit=$preedit index=$index text=$value codepoints=$cps cloud=${candidate.cloud}")
                }
            }
        }
        cachedExtraCandidates = null
        cachedExtraCandidatePositions = null
        cachedExtraWidths = null
        cachedExtraOffsets = null
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
    private fun candidateRevealOffset(index: Int): Float {
        val distance = (1f - candidateRevealProgress(index)) * CANDIDATE_REVEAL_OFFSET_DP * density
        return when (entryEffect.coerceIn(0, 4)) {
            0 -> distance
            3 -> -distance
            else -> 0f
        }
    }

    /** 将候选字、释义和补充候选统一应用同一入场变换，避免动画只作用于文字的一部分。 */
    private fun applyCandidateReveal(canvas: Canvas, index: Int, rect: RectF) {
        val progress = candidateRevealProgress(index)
        val remaining = 1f - progress
        val dx = when (entryEffect.coerceIn(0, 4)) {
            0 -> CANDIDATE_REVEAL_OFFSET_DP * density * remaining
            3 -> -CANDIDATE_REVEAL_OFFSET_DP * density * remaining
            else -> 0f
        }
        val dy = when (entryEffect.coerceIn(0, 4)) {
            1 -> -10f * density * remaining
            4 -> -4f * density * remaining
            else -> 0f
        }
        val scale = when (entryEffect.coerceIn(0, 4)) {
            2 -> 0.78f + 0.22f * progress
            4 -> 0.88f + 0.12f * progress
            else -> 1f
        }
        canvas.translate(dx, dy)
        if (scale != 1f) canvas.scale(scale, scale, rect.centerX(), rect.centerY())
    }

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

    /** Replace the current layout snapshot and reject stale asynchronous frames. */
    /** 与 Rust strip::displayable_candidate 保持完全一致，所有布局路径共用此准入规则。 */
    private fun isDisplayableCandidate(candidate: Candidate): Boolean {
        val value = candidate.text.trim()
        if (value.isEmpty() || value.any { Character.isISOControl(it) }) return false
        if (value.startsWith(':')) return false
        var digits = 0
        while (digits < value.length && value[digits] in '0'..'9') digits++
        return !(digits > 0 && digits < value.length && value[digits] == ':' &&
            value.getOrNull(digits + 1)?.let { it in 'A'..'Z' || it in 'a'..'z' } == true)
    }

    fun paint(image: StripImage?) {
        if (image != null && image.frameId < lastAcceptedFrameId) return
        if (image == null || image.width <= 0 || image.height <= 0) {
            this.image = null
            return
        }
        // frame_id 只表示 Rust 内部先后；候选帧与布局异步抵达时，还必须确认内容本身相同。
        if (image.preedit != preedit) return
        if (image.candidateTexts != candidates.map { it.text }) return
        val expectedLocalCandidates = candidates.indices.filter {
            !candidates[it].cloud && isDisplayableCandidate(candidates[it])
        }
        if (image.candidateIndices != expectedLocalCandidates) return
        if (awaitingCandidateFrame && image.frameId <= lastAcceptedFrameId) return
        // 本地候选缺少主矩形属于布局协议错误，不能降级伪装成云候选补充项。
        if (expectedLocalCandidates.any { image.wordRect(it) == null }) return
        this.image = image
        lastAcceptedFrameId = image.frameId
        awaitingCandidateFrame = false
        cachedExtraCandidates = null
        cachedExtraCandidatePositions = null
        cachedExtraWidths = null
        cachedExtraOffsets = null
    }

    /** Transparent backgrounds are handled by the parent surface. */
    fun setTransparentBackground(enabled: Boolean) {
        transparentBackground = enabled
    }

    fun indexAt(contentX: Float): Int {
        val frame = image ?: run {
            val rectIndex = fallbackRects.indexOfFirst { contentX >= it.left && contentX <= it.right }
            return fallbackCandidateIndices.getOrNull(rectIndex) ?: -1
        }
        frame.candidateAt(contentX).takeIf { it >= 0 }?.let { return it }
        val extras = extraCandidates()
        val relativeX = contentX - frame.width - EXTRA_START_GAP_DP * density
        if (relativeX < 0f) return -1
        val widths = extraWidths()
        val offsets = extraOffsets()
        val slot = extras.indices.firstOrNull { relativeX >= offsets[it] && relativeX < offsets[it] + widths[it] }
            ?: return -1
        return extras[slot]
    }

    private var cachedExtraCandidates: List<Int>? = null
    private var cachedExtraCandidatePositions: Map<Int, Int>? = null
    private var cachedExtraWidths: List<Float>? = null
    private var cachedExtraOffsets: List<Float>? = null

    private fun extraCandidateWidth(index: Int): Float {
        val candidate = candidates.getOrNull(index) ?: return EXTRA_MIN_WIDTH_DP * density
        val role = if (index == 0) CandidateTextStyleEngine.Role.FIRST_CANDIDATE
            else CandidateTextStyleEngine.Role.CANDIDATE
        val wordPaint = textStyles.paint(role)
        val maxTextWidth = (EXTRA_MAX_WIDTH_DP - EXTRA_HORIZONTAL_PADDING_DP) * density
        val word = TextUtils.ellipsize(candidate.text, wordPaint, maxTextWidth, TextUtils.TruncateAt.END).toString()
        var measured = wordPaint.measureText(word)
        candidate.senses.firstOrNull()?.let { sense ->
            val gloss = buildString {
                sense.pos?.takeIf { it.isNotBlank() }?.let { append(it).append(' ') }
                append(sense.text)
            }
            val glossPaint = textStyles.paint(CandidateTextStyleEngine.Role.GLOSS)
            val shownGloss = TextUtils.ellipsize(gloss, glossPaint, maxTextWidth, TextUtils.TruncateAt.END).toString()
            measured = maxOf(measured, glossPaint.measureText(shownGloss))
        }
        return (measured + EXTRA_HORIZONTAL_PADDING_DP * density)
            .coerceIn(EXTRA_MIN_WIDTH_DP * density, EXTRA_MAX_WIDTH_DP * density)
    }

    private fun extraWidths(): List<Float> = cachedExtraWidths ?: extraCandidates()
        .map(::extraCandidateWidth)
        .also { cachedExtraWidths = it }

    /** 每个补充候选的累计起点；绘制、命中和滚动宽度共用同一份布局。 */
    private fun extraOffsets(): List<Float> = cachedExtraOffsets ?: run {
        var offset = 0f
        extraWidths().map { width ->
            val start = offset
            offset += width + EXTRA_GAP_DP * density
            start
        }.also { cachedExtraOffsets = it }
    }

    private fun extraTotalWidth(): Float {
        val widths = extraWidths()
        return widths.sum() + (widths.size - 1).coerceAtLeast(0) * EXTRA_GAP_DP * density
    }

    private fun extraCandidates(): List<Int> {
        val frame = image ?: return emptyList()
        return cachedExtraCandidates ?: candidates.indices
            .filter { index ->
                if (frame.wordRect(index) != null) return@filter false
                val candidate = candidates[index]
                // 没有 Rust 主布局格的补充项只允许云候选或纯标点/符号。
                // 本地字词若缺少有效矩形属于异常帧，不能悄悄追加成一个可点击的隐形占位。
                candidate.cloud || isPunctuationOrSymbolOnly(candidate.text)
            }
            .also { extra ->
                cachedExtraCandidates = extra
                cachedExtraCandidatePositions = extra.withIndex().associate { it.value to it.index }
            }
    }

    private fun isPunctuationOrSymbolOnly(text: String): Boolean {
        val codePoints = text.codePoints().toArray()
        if (codePoints.isEmpty()) return false
        return codePoints.all { codePoint ->
            when (Character.getType(codePoint)) {
                Character.CONNECTOR_PUNCTUATION.toInt(),
                Character.DASH_PUNCTUATION.toInt(),
                Character.START_PUNCTUATION.toInt(),
                Character.END_PUNCTUATION.toInt(),
                Character.OTHER_PUNCTUATION.toInt(),
                Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
                Character.FINAL_QUOTE_PUNCTUATION.toInt(),
                Character.MATH_SYMBOL.toInt(),
                Character.CURRENCY_SYMBOL.toInt(),
                Character.MODIFIER_SYMBOL.toInt(),
                Character.OTHER_SYMBOL.toInt() -> true
                else -> false
            }
        }
    }

    /** 候选文字格的实际绘制区域（已考虑无拼音行时的垂直居中偏移）。 */
    fun wordRect(index: Int): RectF? {
        val frame = image ?: run {
            val rectIndex = fallbackCandidateIndices.indexOf(index)
            return fallbackRects.getOrNull(rectIndex)?.let { RectF(it) }
        }
        val rect = frame.wordRect(index)
        if (rect != null) {
            if (frame.candidatesTop == 0) {
                rect.offset(0f, ((barHeight - frame.height) / 2).coerceAtLeast(0f))
            }
            return rect
        }
        // 超出 Rust 布局宽度的补充候选由 Android 绘制；同样提供稳定的命中/无障碍区域。
        val extras = extraCandidates()
        val extraIndex = cachedExtraCandidatePositions?.get(index) ?: extras.indexOf(index)
        if (extraIndex < 0) return null
        val width = extraWidths().getOrNull(extraIndex) ?: return null
        val left = frame.width + EXTRA_START_GAP_DP * density + extraOffsets()[extraIndex]
        return RectF(
            left + EXTRA_SIDE_PADDING_DP * density,
            8f * density,
            left + width - EXTRA_SIDE_PADDING_DP * density,
            barHeight - 8f * density,
        )
    }

    /** 首选词的整体视觉区域：同时包含词条和其下方的词性/英文释义 hit 区。 */
    fun candidateEffectRect(index: Int, maxTextWidth: Float? = null): RectF? {
        val frame = image ?: return wordRect(index)
        val result = wordRect(index) ?: return null
        val wordBounds = RectF(result)
        val shift = if (frame.candidatesTop == 0) {
            ((barHeight - frame.height) / 2).coerceAtLeast(0f)
        } else 0f
        val candidate = candidates.getOrNull(index)
        // 常用语文字超过候选区 1/3 时，按实际显示（省略后）的宽度计算中文部分。
        if (candidate != null && maxTextWidth != null && maxTextWidth > 0f) {
            val role = if (index == 0) CandidateTextStyleEngine.Role.FIRST_CANDIDATE else CandidateTextStyleEngine.Role.CANDIDATE
            val paint = textStyles.paint(role)
            val display = TextUtils.ellipsize(candidate.text, paint, maxTextWidth, TextUtils.TruncateAt.END).toString()
            val displayWidth = (paint.measureText(display) + 12f * density)
                .coerceAtMost(maxTextWidth + 12f * density)
                .coerceAtMost(wordBounds.width())
            result.set(wordBounds.centerX() - displayWidth / 2f, wordBounds.top,
                wordBounds.centerX() + displayWidth / 2f, wordBounds.bottom)
        }
        // Rust 的释义 hit 框通常覆盖整格；用实际英文/词性宽度收紧后再并入高亮框。
        val glossPaint = textStyles.paint(CandidateTextStyleEngine.Role.GLOSS)
        frame.hits.filter { it.candidate == index && it.sense != null }
            .groupBy { it.sense!! }
            .forEach senseLoop@{ (senseIndex, hits) ->
                val sense = candidate?.senses?.getOrNull(senseIndex) ?: return@senseLoop
                val pos = sense.pos?.trim().orEmpty()
                val gloss = sense.text.trim()
                val segments = if (hits.size == 1) {
                    listOf(listOf(pos, gloss).filter { it.isNotEmpty() }.joinToString("  "))
                } else {
                    listOfNotNull(pos.takeIf { it.isNotEmpty() }?.plus(" "), gloss.takeIf { it.isNotEmpty() })
                }
                hits.forEachIndexed { segmentIndex, hit ->
                    val text = segments.getOrNull(segmentIndex) ?: return@forEachIndexed
                    val measured = glossPaint.measureText(text).coerceAtMost(hit.rect.width())
                    if (measured <= 0f) return@forEachIndexed
                    result.union(RectF(hit.rect.centerX() - measured / 2f, hit.rect.top + shift,
                        hit.rect.centerX() + measured / 2f, hit.rect.bottom + shift))
                }
            }
        // Kotlin 追加候选（常见为「？。！」）没有 Rust 的文字格，wordRect 会覆盖整条候选栏。
        // 选中框必须按真正绘制后的字形尺寸收紧，否则会变成整栏高的长框，并在底边形成黑线。
        if (frame.wordRect(index) == null && candidate != null) {
            val role = if (index == 0) CandidateTextStyleEngine.Role.FIRST_CANDIDATE
                else CandidateTextStyleEngine.Role.CANDIDATE
            val paint = textStyles.paint(role)
            val originalSize = paint.textSize
            val availableWidth = minOf(
                (wordBounds.width() - 2f * density).coerceAtLeast(1f),
                maxTextWidth?.takeIf { it > 0f } ?: Float.MAX_VALUE,
            )
            val display = TextUtils.ellipsize(candidate.text, paint, availableWidth, TextUtils.TruncateAt.END).toString()
            val measuredWidth = paint.measureText(display).coerceAtLeast(1f)
            val measuredHeight = (paint.descent() - paint.ascent()).coerceAtLeast(1f)
            val availableHeight = (wordBounds.height() - 2f * density).coerceAtLeast(1f)
            val fitScale = minOf(1f, availableWidth / measuredWidth, availableHeight / measuredHeight)
            paint.textSize = originalSize * fitScale
            val glyphWidth = paint.measureText(display)
            val metrics = paint.fontMetrics
            val glyphHeight = metrics.descent - metrics.ascent
            val padding = 3f * density
            result.set(
                wordBounds.centerX() - glyphWidth / 2f - padding,
                wordBounds.centerY() - glyphHeight / 2f - padding,
                wordBounds.centerX() + glyphWidth / 2f + padding,
                wordBounds.centerY() + glyphHeight / 2f + padding,
            )
            paint.textSize = originalSize
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
        val top = image.candidatesTop.coerceIn(0, image.height)
        if (!transparentBackground) {
            fill.color = image.background
            canvas.drawRect(scrollX.toFloat(), 0f, (scrollX + viewWidth).toFloat(), barHeight, fill)
        }
        val shift = if (top == 0) ((barHeight - image.height) / 2).coerceAtLeast(0f) else 0f
        // 选中/首选高亮统一由 CandidateBarView 绘制，确保同一时刻只有一个高亮层。
        // 候选文字、拼音、释义全部由独立样式引擎覆盖绘制；Rust 只提供几何与命中区域。
        var animatedEmojiCount = 0
        candidates.forEachIndexed { index, candidate ->
            val word = image.wordRect(index) ?: return@forEachIndexed
            if (word.right < scrollX || word.left > scrollX + contentViewWidth) return@forEachIndexed
            val role = when {
                image.emoji.contains(index) -> CandidateTextStyleEngine.Role.EMOJI
                index == 0 -> CandidateTextStyleEngine.Role.FIRST_CANDIDATE
                else -> CandidateTextStyleEngine.Role.CANDIDATE
            }
            val revealSave = canvas.save()
            val candidateRect = RectF(word.left, word.top + shift, word.right, word.bottom + shift)
            applyCandidateReveal(canvas, index, candidateRect)
            val animated = image.emoji.contains(index) && animatedEmojiCount < 2 &&
                emojiAnimations.draw(canvas, candidate.text, candidateRect)
            // 只统计真正成功绘制的动画；未收录的 Emoji 静态回退，不占用另外两个动画名额。
            if (animated) animatedEmojiCount++
            if (!animated) {
                textStyles.drawReplacing(
                    canvas, candidate.text, candidateRect, role,
                    eraseUnderlay = false,
                    alphaMultiplier = candidateRevealAlpha(index),
                    maxTextWidth = minOf(contentViewWidth / 3f, 180f * density),
                )
            }
            canvas.restoreToCount(revealSave)
        }
        // 拼音数据可能比上一帧短或字符宽度发生变化。先清理整条旧拼音绘制带，
        // 再按当前 preeditEdges 重绘，避免旧字母/拼音残影留在新字符之间。
        val codePoints = preedit.codePoints().toArray()
        val edges = image.preeditEdges
        // 拼音作为一整行按自然字距绘制，避免逐字符塞进过窄的字格导致缩小、挤叠和笔画发虚。
        // Rust 提供的 preeditEdges 仍保留给光标命中与拖动，不改变任何触摸区域。
        if (codePoints.isNotEmpty() && edges.size >= codePoints.size + 1) {
            val topY = image.preeditTop
            val bottomY = image.preeditBottom.coerceAtLeast(topY + 1f)
            val left = edges.first()
            val right = edges[codePoints.size]
            // Rust 边界仍用于光标命中；绘制框按整串拼音的真实字宽扩展，
            // 防止首尾字母被过窄的预编辑命中区裁切。
            val preeditPaint = textStyles.paint(CandidateTextStyleEngine.Role.PREEDIT)
            val requiredWidth = preeditPaint.measureText(preedit) + 8f * density
            val displayWidth = maxOf(right - left, requiredWidth)
            val displayLeft = (left + right - displayWidth) / 2f
            val displayRight = displayLeft + displayWidth
            if (displayRight >= scrollX && displayLeft <= scrollX + contentViewWidth) {
                textStyles.drawReplacing(
                    canvas, preedit, RectF(displayLeft, topY, displayRight, bottomY),
                    CandidateTextStyleEngine.Role.PREEDIT,
                    eraseUnderlay = false,
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
                    applyCandidateReveal(canvas, candidateIndex, RectF(hit.rect))
                    textStyles.drawReplacing(
                        canvas, segment, RectF(hit.rect), CandidateTextStyleEngine.Role.GLOSS,
                        eraseUnderlay = false, alphaMultiplier = candidateRevealAlpha(candidateIndex),
                    )
                    canvas.restoreToCount(revealSave)
                }
            }
        // Kotlin 侧补充的语义标点没有 Rust 主布局格子，直接追加绘制在主候选栏。
        val extras = extraCandidates()
        val extraStart = image.width + EXTRA_START_GAP_DP * density
        val cellWidths = extraWidths()
        val cellOffsets = extraOffsets()
        extras.forEachIndexed { slot, index ->
            val cellWidth = cellWidths[slot]
            val left = extraStart + cellOffsets[slot]
            if (left + cellWidth < scrollX || left > scrollX + contentViewWidth) return@forEachIndexed
            val rect = RectF(
                left + EXTRA_SIDE_PADDING_DP * density,
                8f * density,
                left + cellWidth - EXTRA_SIDE_PADDING_DP * density,
                barHeight - 8f * density,
            )
            val candidate = candidates.getOrNull(index) ?: return@forEachIndexed
            // 没有 Rust hit 矩形的云端/补充候选也必须走同一套 Android 样式引擎，
            // 不能再用 extraText 的 markColor + 粗体直接 drawText，否则会露出黑色粗体且丢失释义。
            val revealSave = canvas.save()
            applyCandidateReveal(canvas, index, rect)
            val alpha = candidateRevealAlpha(index)
            val role = when {
                image.emoji.contains(index) -> CandidateTextStyleEngine.Role.EMOJI
                index == 0 -> CandidateTextStyleEngine.Role.FIRST_CANDIDATE
                else -> CandidateTextStyleEngine.Role.CANDIDATE
            }
            val senses = candidate.senses.firstOrNull()
            val wordBottom = if (senses == null) rect.bottom else rect.top + rect.height() * 0.62f
            val wordRect = RectF(rect.left, rect.top, rect.right, wordBottom)
            textStyles.drawReplacing(
                canvas, candidate.text, wordRect, role,
                eraseUnderlay = false,
                alphaMultiplier = alpha,
                maxTextWidth = minOf(contentViewWidth / 3f, 180f * density),
            )
            if (senses != null) {
                val gloss = buildString {
                    senses.pos?.takeIf { it.isNotBlank() }?.let { append(it).append(' ') }
                    append(senses.text)
                }
                val glossRect = RectF(rect.left, wordBottom, rect.right, rect.bottom)
                textStyles.drawReplacing(
                    canvas, gloss, glossRect, CandidateTextStyleEngine.Role.GLOSS,
                    eraseUnderlay = false,
                    alphaMultiplier = alpha,
                    maxTextWidth = minOf(contentViewWidth / 3f, 180f * density),
                )
            }
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

    /** 布局快照暂不可用时，仍按候选数据绘制可见、可点击的候选。 */
    private fun fallbackWidth(): Float = (if (preedit.isNotEmpty()) 52f * density else 8f * density) +
        candidates.count(::isDisplayableCandidate) * 56f * density + 8f * density

    private fun drawFallback(canvas: Canvas, scrollX: Int, viewWidth: Int) {
        if (!transparentBackground) {
            fill.color = fallbackBackgroundColor
            canvas.drawRect(scrollX.toFloat(), 0f, (scrollX + viewWidth).toFloat(), barHeight, fill)
        }
        fallbackRects.clear()
        fallbackCandidateIndices.clear()
        val start = if (preedit.isNotEmpty()) 52f * density else 8f * density
        if (preedit.isNotEmpty()) {
            val preeditRect = RectF(8f * density, 4f * density, start - 4f * density, barHeight * 0.52f)
            if (preeditRect.right >= scrollX && preeditRect.left <= scrollX + viewWidth) {
                textStyles.drawReplacing(
                    canvas, preedit, preeditRect,
                    CandidateTextStyleEngine.Role.PREEDIT,
                    eraseUnderlay = false,
                )
            }
        }
        val cell = 56f * density
        var visibleSlot = 0
        candidates.forEachIndexed { index, candidate ->
            if (!isDisplayableCandidate(candidate)) return@forEachIndexed
            val left = start + visibleSlot++ * cell
            val rect = RectF(left + density, 4f * density, left + cell - density, barHeight - 4f * density)
            fallbackRects += rect
            fallbackCandidateIndices += index
            if (rect.right < scrollX || rect.left > scrollX + viewWidth) return@forEachIndexed
            val revealSave = canvas.save()
            applyCandidateReveal(canvas, index, rect)
            val alpha = candidateRevealAlpha(index)
            val role = when {
                candidate.text.codePoints().anyMatch { Character.getType(it) == Character.OTHER_SYMBOL.toInt() } ->
                    CandidateTextStyleEngine.Role.EMOJI
                index == 0 -> CandidateTextStyleEngine.Role.FIRST_CANDIDATE
                else -> CandidateTextStyleEngine.Role.CANDIDATE
            }
            val senses = candidate.senses.firstOrNull()
            val wordBottom = if (senses == null) rect.bottom else rect.top + rect.height() * 0.62f
            textStyles.drawReplacing(
                canvas, candidate.text, RectF(rect.left, rect.top, rect.right, wordBottom),
                role, eraseUnderlay = false, alphaMultiplier = alpha,
                maxTextWidth = minOf(viewWidth / 3f, 180f * density),
            )
            if (senses != null) {
                val gloss = buildString {
                    senses.pos?.takeIf { it.isNotBlank() }?.let { append(it).append(' ') }
                    append(senses.text)
                }
                textStyles.drawReplacing(
                    canvas, gloss, RectF(rect.left, wordBottom, rect.right, rect.bottom),
                    CandidateTextStyleEngine.Role.GLOSS,
                    eraseUnderlay = false, alphaMultiplier = alpha,
                )
            }
            canvas.restoreToCount(revealSave)
        }
        // 布局暂缺时也保留通讯录标记；使用与触摸命中相同的回退候选矩形。
        val markerSize = CONTACT_MARK_DP * density
        candidates.forEachIndexed { index, candidate ->
            if (!candidate.contact) return@forEachIndexed
            val rectIndex = fallbackCandidateIndices.indexOf(index)
            val word = fallbackRects.getOrNull(rectIndex) ?: return@forEachIndexed
            if (word.right < scrollX || word.left > scrollX + viewWidth) return@forEachIndexed
            val cx = word.right - markerSize * 0.75f
            val cy = word.top + markerSize * 0.85f
            PanelIcons.draw(canvas, PanelIcons.Icon.USER, cx - scrollX, cy, markerSize, markPaint)
        }
        canvas.drawRect(scrollX.toFloat(), barHeight - density, (scrollX + viewWidth).toFloat(), barHeight, fill)
    }
    private companion object {
        const val EXTRA_START_GAP_DP = 12f
        const val EXTRA_GAP_DP = 4f
        const val EXTRA_SIDE_PADDING_DP = 4f
        const val EXTRA_HORIZONTAL_PADDING_DP = EXTRA_SIDE_PADDING_DP * 2f
        const val EXTRA_MIN_WIDTH_DP = 42f
        const val EXTRA_MAX_WIDTH_DP = 120f
        const val TEXT_FADE_DURATION_MS = 220L
        const val CANDIDATE_REVEAL_STAGGER_MS = 58L
        const val CANDIDATE_REVEAL_OFFSET_DP = 9f
        /** 人头标记多大（dp）：比译文字号小一点，不抢候选字。 */
        const val CONTACT_MARK_DP = 11f
    }
}
