package app.qingjian.inputmethod.panel.clipboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.ClipData
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.PopupWindow
import android.icu.text.Transliterator
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.text.TextWatcher
import android.text.Editable
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import app.qingjian.inputmethod.core.store.ClipItem
import app.qingjian.inputmethod.clipboard.ClipboardImageProvider
import app.qingjian.inputmethod.core.store.ClipboardStore
import app.qingjian.inputmethod.core.store.CodeView
import app.qingjian.inputmethod.core.store.PhraseItem
import app.qingjian.inputmethod.core.store.PhrasePage
import app.qingjian.inputmethod.core.store.PhraseRules
import app.qingjian.inputmethod.scan.ScanContent
import app.qingjian.inputmethod.scan.WifiJoinActivity
import app.qingjian.inputmethod.keyboard.Haptics
import app.qingjian.inputmethod.keyboard.layout.Key
import java.lang.ref.WeakReference
import app.qingjian.inputmethod.panel.PanelActions
import app.qingjian.inputmethod.panel.base.PanelIcons.Icon
import app.qingjian.inputmethod.panel.base.PanelView
import app.qingjian.inputmethod.panel.base.Tabs
import app.qingjian.inputmethod.settings.Settings
import app.qingjian.inputmethod.theme.Palette
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * 剪贴板与常用语：表头两个页签，下面一张张圆角卡片，点卡片上屏，点右边 ⌄ 展开。
 * - 剪贴板：展开后 + 常用语 / 拆词 / 删除；右上角清空（点两次）。文字原样显示，像代码的用等宽字体、不重排；图片显示缩略图，长按看大图。
 * - 常用语：底下一排分组，展开后 编辑 / 移至首位 / 删除；右上角 + 新加一条。
 * 数据在 Rust 侧（[ClipboardStore]），这里每次改完拿回整份列表重画。
 */
@SuppressLint("ViewConstructor")
class ClipboardPanel(
    context: Context,
    palette: Palette,
    actions: PanelActions,
    headerHeight: Float,
    phrasesFirst: Boolean,
) : PanelView(context, palette, actions, headerHeight) {
    private val tabs = Tabs(ink, scrollable(true) { _, _ -> false }, Tabs.Style.UNDERLINE).apply {
        labels = listOf("剪贴板", "常用语")
        selected = if (phrasesFirst) PHRASES else CLIPS
    }
    private val showingPhrases get() = tabs.selected == PHRASES

    private var clips: List<ClipItem> = ClipboardStore.list(context, actions.clipboardLimit)
    private var searchQuery = ""
    private val pinyinInitialsCache = HashMap<String, String>()
    private val visibleClipIndices: List<Int> get() = clips.indices.filter { index ->
        searchQuery.isBlank() ||
            clips[index].text.contains(searchQuery, ignoreCase = true) ||
            pinyinInitials(clips[index].text).contains(searchQuery, ignoreCase = true)
    }
    private fun pinyinInitials(text: String): String = pinyinInitialsCache.getOrPut(text) {
        HAN_TO_LATIN.transliterate(text).lowercase()
            .split(Regex("[^a-z0-9]+")).mapNotNull { it.firstOrNull() }.joinToString("")
    }
    private fun clipSourceIndex(index: Int): Int = visibleClipIndices.getOrNull(index) ?: -1
    private fun clipAt(index: Int): ClipItem? = clips.getOrNull(clipSourceIndex(index))
    private var searchEditing = false

    init { activeInstance = WeakReference(this) }
    private val searchRect = RectF()
    private val searchCursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.brand
        strokeWidth = ink.dp(1.3f)
    }
    private var searchCursorVisible = true
    private val searchCursorBlink = object : Runnable {
        override fun run() {
            if (!searchEditing || !isAttachedToWindow || showingPhrases) {
                searchCursorVisible = false
                return
            }
            searchCursorVisible = !searchCursorVisible
            invalidate()
            postDelayed(this, 520L)
        }
    }
    private var page: PhrasePage = ClipboardStore.phrases(context)

    // 自动记忆短语只供输入引擎召回，不作为用户管理的常用语展示。
    private val visiblePhraseGroups get() = page.groups.filterNot { it == AUTO_MEMORY_GROUP }

    private val groups = Tabs(ink, scrollable(true) { x, y -> showingPhrases && y > groupTop() }, Tabs.Style.PILL).apply {
        labels = visiblePhraseGroups
        selected = visiblePhraseGroups.indexOf(lastGroup).coerceAtLeast(0)
    }

    private val list = RectF()
    private val listScroll = scrollable(false, friction = 0.009f) { x, y -> list.contains(x, y) }
    private var restoreClipScrollPending = !phrasesFirst && tabs.selected == CLIPS

    /** 展开着的那一张（列表里的下标），-1 是都收着。 */
    private var expanded = -1

    /** 剪贴板「清空」点了第一下，等第二下确认。 */
    private var clearArmed = false

    /** 设置里的「代码高亮」；面板每次打开读一次。 */
    private val highlight = Settings.load(context).codeHighlight

    /** 数字的蓝：调色板里没有，按深浅色各取一个。 */
    private val numberColor = if (palette.dark) {
        0xFF7FA8F0.toInt()
    } else {
        0xFF2F6FDB.toInt()
    }

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val card = RectF()
    private val layouts = HashMap<String, StaticLayout>()
    private val thumbnails = HashMap<String, Bitmap?>()
    private val pendingThumbnails = HashSet<String>()
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val scrollGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ink.dp(1.4f)
    }

    /** 长按打开的大图（图片完整路径），null 是没开。 */
    private var preview: String? = null
    private var previewDecoded: Pair<String, Bitmap?>? = null

    /** 当前展示二维码的剪贴板文字；再次点击面板即可关闭。 */
    private var qrPreview: String? = null
    private var qrBitmapCache: Pair<String, Bitmap?>? = null

    /** 剪贴板粘贴后回车换行：全局开关，不按记录分别保存。 */
    private var pasteEnter = Settings.preferences(context).getBoolean(Settings.KEY_CLIPBOARD_PASTE_ENTER, false)
    private var pasteNextCell = Settings.preferences(context).getBoolean(Settings.KEY_CLIPBOARD_PASTE_NEXT_CELL, false)
    private val deleteBackwardRect = RectF()
    private val pasteToggleRect = RectF()
    private val pasteNextCellRect = RectF()
    private var deleteBackwardTracking = false
    private var pasteToggleTracking = false
    private var pasteNextCellTracking = false
    private var pasteSummaryTracking = false

    /** 剪贴板条目长按后左右拖动删除；拖动偏移直接作用于卡片绘制。 */
    private var swipeTracking = false
    private var swipeLongPressed = false
    private var swipeDownX = 0f
    private var swipeDownY = 0f
    private var swipeOffsetX = 0f
    private var swipeClipIndex = -1
    private var deleteRepeatStarted = false

    // 剪贴板与常用语列表共用轻微错层回弹动效。
    private var lastDrawnScrollOffset = 0f
    private var scrollMotion = 0f

    private val activateSwipeRunnable = Runnable {
        if (swipeTracking && swipeClipIndex >= 0 && !showingPhrases) {
            swipeLongPressed = true
            parent?.requestDisallowInterceptTouchEvent(true)
            Haptics.tap(context)
            invalidate()
        }
    }

    /** 退格按钮按住后先删一次，再按固定间隔连续删除；抬起立即停止。 */
    private val deleteRepeatRunnable = object : Runnable {
        override fun run() {
            if (!pasteSummaryTracking || !deleteBackwardTracking) return
            deleteRepeatStarted = true
            actions.deleteBackward()
            postDelayed(this, DELETE_REPEAT_INTERVAL_MS)
        }
    }

    /** 每张卡片在列表内容里的上下边（不含滚动），画与点共用。 */
    private var tops = FloatArray(0)
    private var bottoms = FloatArray(0)

    private fun shownPhrases(): List<PhraseItem> {
        val group = visiblePhraseGroups.getOrNull(groups.selected) ?: PhraseRules.defaultGroup
        return page.phrases.filter { it.group == group && it.group != AUTO_MEMORY_GROUP }
    }

    private val count get() = if (showingPhrases) shownPhrases().size else visibleClipIndices.size

    private fun groupTop() = height - ink.dp(GROUP_BAR_DP)

    /** 点击搜索框后弹出独立的 26 键 QWERTY 小键盘，不依赖系统输入法或输入连接。 */
    private var searchPopup: PopupWindow? = null

    private fun openSearchEditor() {
        searchPopup?.dismiss()
        searchEditing = true
        removeCallbacks(searchCursorBlink)
        searchCursorVisible = true
        postDelayed(searchCursorBlink, 520L)
        context.getSharedPreferences(SEARCH_STATE_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(SEARCH_EDITOR_ACTIVE, true).apply()
        val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        // 顶排 QWERTY 对应 1–0：轻点输入字母，长按输入数字。
        val topRowDigits = "1234567890"
        val screenWidth = resources.displayMetrics.widthPixels
        // 浮窗保留约 14% 的横向余量，允许从屏幕左侧完整拖到右侧。
        val popupWidth = minOf(
            (screenWidth - ink.dp(24f)).coerceAtLeast(ink.dp(240f)),
            maxOf(ink.dp(280f), screenWidth * 0.86f),
        ).toInt()
        val headerHeight = ink.dp(42f)
        val keyHeight = ink.dp(42f)
        val rowGap = ink.dp(6f)
        val popupHeight = (headerHeight + keyHeight * 3 + rowGap * 2 + ink.dp(16f)).toInt()
        var popupWindow: PopupWindow? = null
        var dragging = false
        var longPressTriggered = false
        var downRawX = 0f
        var downRawY = 0f
        var downKeyForLongPress: Char? = null
        var longPressDigitValue: Char? = null
        var popupStartX = 0
        var popupStartY = 0
        var popupCurrentX = 0
        var popupCurrentY = 0
        var clearPressedKey: (() -> Unit)? = null
        val beginDrag = Runnable {
            if (isAttachedToWindow) {
                val topIndex = downKeyForLongPress?.let(rows.first()::indexOf) ?: -1
                val digit = topRowDigits.getOrNull(topIndex)
                if (digit != null) {
                    longPressTriggered = true
                    longPressDigitValue = digit
                    clearPressedKey?.invoke()
                } else {
                    dragging = true
                    longPressTriggered = true
                    clearPressedKey?.invoke()
                }
                Haptics.tap(context)
            }
        }
        val keyboard = object : View(context) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            private val keys = ArrayList<Pair<Char, RectF>>()
            private val gap = ink.dp(4f)
            private val deleteRect = RectF()
            private var pressedKey: Char? = null
            private var downKey: Char? = null
            private var downDelete = false
            private var headerPressed = false
            init { clearPressedKey = { pressedKey = null; headerPressed = false; invalidate() } }
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                keys.clear()
                deleteRect.set((w - ink.dp(78f)).toFloat(), ink.dp(5f).toFloat(), (w - ink.dp(7f)).toFloat(), (headerHeight - ink.dp(5f)).toFloat())
                rows.forEachIndexed { row, letters ->
                    val usable = w - ink.dp(20f)
                    val kw = (usable - gap * (letters.length - 1)) / letters.length
                    val rw = kw * letters.length + gap * (letters.length - 1)
                    val left = (w - rw) / 2f
                    val top = headerHeight + ink.dp(8f) + row * (keyHeight + rowGap)
                    letters.forEachIndexed { col, ch ->
                        val x = left + col * (kw + gap)
                        keys += ch to RectF(x, top.toFloat(), x + kw, (top + keyHeight).toFloat())
                    }
                }
            }
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                paint.style = Paint.Style.FILL
                paint.color = if (headerPressed) palette.brand else palette.surface
                canvas.drawRoundRect(RectF(ink.dp(7f).toFloat(), ink.dp(5f).toFloat(), (width - ink.dp(7f)).toFloat(), (headerHeight - ink.dp(5f)).toFloat()), ink.dp(9f).toFloat(), ink.dp(9f).toFloat(), paint)
                paint.color = if (headerPressed) palette.onBrand else palette.gloss
                paint.textSize = ink.dp(12f).toFloat()
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("长按顶部区域拖动键盘", (width - ink.dp(82f)) / 2f, headerHeight / 2f - (paint.ascent() + paint.descent()) / 2f, paint)
                paint.color = palette.brand
                canvas.drawRoundRect(deleteRect, ink.dp(8f), ink.dp(8f), paint)
                paint.color = palette.onBrand
                paint.textSize = ink.dp(21f).toFloat()
                canvas.drawText("⌫", deleteRect.centerX(), deleteRect.centerY() - (paint.ascent() + paint.descent()) / 2f, paint)
                keys.forEach { (ch, r) ->
                    val pressed = ch == pressedKey
                    val keyBounds = RectF(r).apply { if (pressed) offset(0f, ink.dp(1.2f).toFloat()) }
                    paint.style = Paint.Style.FILL
                    paint.color = if (pressed) palette.brand else palette.surface
                    canvas.drawRoundRect(keyBounds, ink.dp(8f), ink.dp(8f), paint)
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = if (pressed) ink.dp(1.5f) else ink.dp(1f)
                    paint.color = if (pressed) palette.onBrand else palette.brand
                    canvas.drawRoundRect(keyBounds, ink.dp(8f), ink.dp(8f), paint)
                    paint.style = Paint.Style.FILL
                    val digit = if (r.top < headerHeight + ink.dp(8f) + keyHeight) {
                        topRowDigits.getOrNull(rows.first().indexOf(ch))
                    } else null
                    if (digit != null) {
                        paint.color = if (pressed) palette.onBrand else palette.gloss
                        paint.textAlign = Paint.Align.CENTER
                        paint.textSize = ink.dp(9f).toFloat()
                        canvas.drawText(digit.toString(), r.centerX(),
                            r.top + ink.dp(11f) - (paint.ascent() + paint.descent()) / 2f, paint)
                    }
                    paint.color = if (pressed) palette.onBrand else palette.text
                    paint.textAlign = Paint.Align.CENTER
                    paint.textSize = ink.dp(18f).toFloat()
                    val letterCenterY = r.centerY() + if (digit != null) ink.dp(4f) else 0f
                    canvas.drawText(ch.toString(), r.centerX(), letterCenterY - (paint.ascent() + paint.descent()) / 2f, paint)
                }
            }
            override fun onTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        dragging = false
                        longPressTriggered = false
                        downRawX = event.rawX
                        downRawY = event.rawY
                        downKeyForLongPress = null
                        longPressDigitValue = null
                        parent?.requestDisallowInterceptTouchEvent(true)
                        downDelete = deleteRect.contains(event.x, event.y)
                        headerPressed = event.y < headerHeight && !downDelete
                        downKey = if (downDelete || headerPressed) null else keys.firstOrNull { it.second.contains(event.x, event.y) }?.first
                        downKeyForLongPress = downKey
                        pressedKey = downKey
                        invalidate()
                        popupStartX = popupCurrentX
                        popupStartY = popupCurrentY
                        postDelayed(beginDrag, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downRawX
                        val dy = event.rawY - downRawY
                        val moved = kotlin.math.hypot(dx.toDouble(), dy.toDouble())
                        // 从键盘任意位置起步都能拖动；长按顶排按键则保留为数字输入。
                        if (!dragging && !longPressTriggered && moved > ink.dp(7f)) {
                            removeCallbacks(beginDrag)
                            dragging = true
                            longPressTriggered = true
                            longPressDigitValue = null
                            clearPressedKey?.invoke()
                        }
                        if (dragging) {
                            val screen = resources.displayMetrics
                            val maxX = (screen.widthPixels - popupWidth).coerceAtLeast(0)
                            val maxY = (screen.heightPixels - popupHeight).coerceAtLeast(0)
                            val nextX = (popupStartX + dx).toInt().coerceIn(0, maxX)
                            val nextY = (popupStartY + dy).toInt().coerceIn(0, maxY)
                            popupCurrentX = nextX
                            popupCurrentY = nextY
                            popupWindow?.update(nextX, nextY, -1, -1)
                        } else if (moved > ink.dp(8f)) {
                            pressedKey = null
                            invalidate()
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        removeCallbacks(beginDrag)
                        if (!dragging) {
                            when {
                                longPressDigitValue != null -> {
                                    updateSearchQuery(searchQuery + longPressDigitValue)
                                    Haptics.tap(context)
                                }
                                !longPressTriggered && downDelete && deleteRect.contains(event.x, event.y) -> {
                                    if (searchQuery.isNotEmpty()) updateSearchQuery(searchQuery.dropLast(1))
                                    Haptics.tap(context)
                                }
                                !longPressTriggered -> {
                                    val releasedKey = keys.firstOrNull { it.second.contains(event.x, event.y) }?.first
                                    if (downKey != null && releasedKey == downKey) {
                                        updateSearchQuery(searchQuery + downKey)
                                        Haptics.tap(context)
                                    }
                                }
                            }
                        }
                        pressedKey = null
                        downKey = null
                        downDelete = false
                        headerPressed = false
                        downKeyForLongPress = null
                        longPressDigitValue = null
                        parent?.requestDisallowInterceptTouchEvent(false)
                        invalidate()
                        dragging = false
                        longPressTriggered = false
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        removeCallbacks(beginDrag)
                        pressedKey = null
                        downKey = null
                        downDelete = false
                        headerPressed = false
                        downKeyForLongPress = null
                        longPressDigitValue = null
                        parent?.requestDisallowInterceptTouchEvent(false)
                        invalidate()
                        dragging = false
                        longPressTriggered = false
                        return true
                    }
                }
                return true
            }
        }
        val popup = PopupWindow(keyboard, popupWidth, popupHeight, false).apply {
            isFocusable = false
            isTouchable = true
            isOutsideTouchable = false
            if (android.os.Build.VERSION.SDK_INT >= 29) setTouchModal(false)
            setBackgroundDrawable(ColorDrawable(palette.board))
            elevation = ink.dp(12f)
            setClippingEnabled(true)
            setOnDismissListener {
                if (searchPopup === this) searchPopup = null
                searchEditing = false
                removeCallbacks(searchCursorBlink)
                searchCursorVisible = false
                context.getSharedPreferences(SEARCH_STATE_PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(SEARCH_EDITOR_ACTIVE, false).apply()
            }
        }
        val location = IntArray(2)
        getLocationOnScreen(location)
        val screen = resources.displayMetrics
        val initialX = ((screen.widthPixels - popupWidth) / 2).coerceIn(0, (screen.widthPixels - popupWidth).coerceAtLeast(0))
        val initialY = (location[1] + searchRect.bottom + ink.dp(6f)).toInt()
            .coerceIn(0, (screen.heightPixels - popupHeight).coerceAtLeast(0))
        popupCurrentX = initialX
        popupCurrentY = initialY
        popup.showAtLocation(this, Gravity.TOP or Gravity.START, initialX, initialY)
        popupWindow = popup
        searchPopup = popup
        Haptics.tap(context)
    }

    private fun updateSearchQuery(value: String) {
        searchQuery = value
        expanded = -1
        layouts.clear()
        listScroll.reset()
        searchCursorVisible = true
        removeCallbacks(searchCursorBlink)
        if (searchEditing) postDelayed(searchCursorBlink, 520L)
        invalidate()
    }

    private fun applySearchKey(key: Key): Boolean {
        if (!searchEditing) return false
        val next = when (key) {
            Key.Backspace -> {
                if (searchQuery.isEmpty()) {
                    searchEditing = false
                    removeCallbacks(searchCursorBlink)
                    searchPopup?.dismiss()
                    searchPopup = null
                    context.getSharedPreferences(SEARCH_STATE_PREFS, Context.MODE_PRIVATE)
                        .edit().putBoolean(SEARCH_EDITOR_ACTIVE, false).apply()
                    invalidate()
                    return true
                }
                searchQuery.dropLast(1)
            }
            is Key.Letter -> searchQuery + key.char
            is Key.Digit -> searchQuery + key.digit
            is Key.Text -> searchQuery + key.text
            is Key.Space -> searchQuery + " "
            is Key.Enter -> {
                searchEditing = false
                removeCallbacks(searchCursorBlink)
                searchPopup?.dismiss()
                searchPopup = null
                context.getSharedPreferences(SEARCH_STATE_PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(SEARCH_EDITOR_ACTIVE, false).apply()
                invalidate()
                return true
            }
            else -> return false
        }
        updateSearchQuery(next)
        return true
    }

    override fun onDetachedFromWindow() {
        searchPopup?.dismiss()
        searchPopup = null
        searchEditing = false
        removeCallbacks(searchCursorBlink)
        searchCursorVisible = false
        context.getSharedPreferences(SEARCH_STATE_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(SEARCH_EDITOR_ACTIVE, false).apply()
        if (activeInstance?.get() === this) activeInstance = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        drawHeader(canvas, tabs, if (showingPhrases) Icon.PLUS else Icon.TRASH)
        val searchTop = headerHeight + ink.dp(SUMMARY_DP)
        if (!showingPhrases) {
            // 搜索框固定占面板宽度 30%，三个功能按钮均分剩余空间。
            searchRect.set(ink.dp(8f), searchTop + ink.dp(2f),
                ink.dp(8f) + width * 0.27f, searchTop + ink.dp(38f))
            deleteBackwardRect.set(deleteBackwardBounds())
            pasteToggleRect.set(pasteToggleBounds())
            pasteNextCellRect.set(pasteNextCellBounds())
            ink.round(canvas, searchRect, 10f, palette.surface)
            ink.icon(canvas, Icon.SEARCH, searchRect.left + ink.dp(14f), searchRect.centerY(), 15f, palette.gloss)
            val queryLabel = searchQuery.ifEmpty { if (searchEditing) "" else if (clearArmed) "确认清空" else "搜索" }
            ink.textStart(canvas, queryLabel, 11.5f,
                if (searchQuery.isEmpty()) palette.gloss else palette.text,
                searchRect.left + ink.dp(26f), searchRect.centerY() + ink.dp(1f),
                searchRect.right - ink.dp(30f))
            if (searchEditing && searchCursorVisible) {
                val cursorX = (searchRect.left + ink.dp(26f) + ink.measure(searchQuery, 11.5f))
                    .coerceAtMost(searchRect.right - ink.dp(29f))
                canvas.drawLine(cursorX, searchRect.centerY() - ink.dp(8f),
                    cursorX, searchRect.centerY() + ink.dp(8f), searchCursorPaint)
            }
            if (searchQuery.isNotEmpty()) ink.icon(canvas, Icon.CLOSE, searchRect.right - ink.dp(11f), searchRect.centerY(), 13f, palette.gloss)
            val resultCount = visibleClipIndices.size.coerceIn(0, clips.size)
            val resultLabel = "$resultCount/${clips.size}"
            val counterWidth = ink.measure(resultLabel, 10.5f) + ink.dp(10f)
            val counterRect = RectF(
                searchRect.right + ink.dp(6f), searchRect.top,
                searchRect.right + ink.dp(6f) + counterWidth, searchRect.bottom,
            )
            // 计数只绘制文字，不铺底色；宽度随“命中数/总数”文本自动变化。
            if (counterRect.right <= deleteBackwardBounds().left - ink.dp(4f)) {
                ink.text(canvas, resultLabel, 10.5f, palette.text,
                    counterRect.centerX(), counterRect.centerY() + ink.dp(1f))
            }
            ink.round(canvas, deleteBackwardRect, 10f, palette.surface)
            ink.icon(canvas, Icon.BACKSPACE, deleteBackwardRect.centerX(), deleteBackwardRect.centerY(), 18f, palette.text)
            ink.round(canvas, pasteToggleRect, 10f, if (pasteEnter) palette.brand else palette.surface)
            ink.icon(canvas, Icon.ENTER, pasteToggleRect.centerX(), pasteToggleRect.centerY(), 18f, if (pasteEnter) palette.onBrand else palette.text)
            ink.round(canvas, pasteNextCellRect, 10f, if (pasteNextCell) palette.brand else palette.surface)
            ink.icon(canvas, Icon.NEXT_CELL, pasteNextCellRect.centerX(), pasteNextCellRect.centerY(), 18f, if (pasteNextCell) palette.onBrand else palette.text)
        } else {
            searchRect.setEmpty()
            deleteBackwardRect.setEmpty()
            pasteToggleRect.setEmpty()
            pasteNextCellRect.setEmpty()
        }
        val listTop = if (showingPhrases) headerHeight + ink.dp(6f) else searchTop + ink.dp(SEARCH_BAR_DP)
        val listBottom = if (showingPhrases) groupTop() else height - ink.dp(6f)
        list.set(0f, listTop, width.toFloat(), listBottom)
        arrange()
        if (!showingPhrases) {
            if (restoreClipScrollPending) {
                listScroll.drag(lastClipScrollOffset)
                restoreClipScrollPending = false
            }
            lastClipScrollOffset = listScroll.offset
        } else {
            restoreClipScrollPending = false
        }
        val scrollDelta = listScroll.offset - lastDrawnScrollOffset
        if (kotlin.math.abs(scrollDelta) > 0.25f) {
            scrollMotion = (scrollMotion + scrollDelta * 0.62f).coerceIn(-ink.dp(60f), ink.dp(60f))
        }
        lastDrawnScrollOffset = listScroll.offset
        if (kotlin.math.abs(scrollMotion) > 0.18f) {
            // 以更长的缓出尾迹收束快速滑动，让减速过程清楚可见。
            scrollMotion *= 0.92f
            postInvalidateOnAnimation()
        } else scrollMotion = 0f
        canvas.save()
        canvas.clipRect(list)
        if (count == 0) {
            ink.text(
                canvas, if (showingPhrases) "这组还没有常用语，点右上角 + 加一条" else "复制的文字和图片会出现在这里",
                14f, palette.gloss, width / 2f, list.top + ink.dp(60f),
            )
        }
        for (i in 0 until count) {
            val top = list.top + tops[i] - listScroll.offset
            val bottom = list.top + bottoms[i] - listScroll.offset
            if (bottom < list.top || top > list.bottom) continue
            val phase = ((i % 4) + 1) / 4f
            val strength = (kotlin.math.abs(scrollMotion) / ink.dp(60f).coerceAtLeast(1f)).coerceIn(0f, 1f)
            val direction = if (scrollMotion < 0f) -1f else 1f
            // 新的「纵向弹性波」：卡片错层上下浮动、轻微缩放，并带品牌色边缘扫光。
            val driftY = direction * strength * ink.dp(18f) * phase
            val driftX = direction * strength * ink.dp(5f) * phase
            val scale = 1f - strength * 0.035f * phase
            val motionAlpha = (255 - strength * 88f).toInt().coerceIn(150, 255)
            val cardLayer = canvas.saveLayerAlpha(0f, top - ink.dp(22f), width.toFloat(), bottom + ink.dp(22f), motionAlpha)
            canvas.save()
            canvas.translate(driftX, driftY)
            canvas.scale(scale, scale, width / 2f, (top + bottom) / 2f)
            if (!showingPhrases && swipeLongPressed && i == swipeClipIndex && swipeOffsetX != 0f) {
                canvas.translate(swipeOffsetX, 0f)
            }
            drawCard(canvas, i, top, bottom)
            if (strength > 0.08f) {
                scrollGlowPaint.color = (palette.brand and 0x00FFFFFF) or (((0xA0 * strength).toInt().coerceIn(0, 0xA0)) shl 24)
                val glowBounds = RectF(ink.dp(2f), top + ink.dp(2f), width - ink.dp(2f), bottom - ink.dp(2f))
                canvas.drawRoundRect(glowBounds, ink.dp(13f), ink.dp(13f), scrollGlowPaint)
            }
            canvas.restore()
            canvas.restoreToCount(cardLayer)
        }
        canvas.restore()
        if (showingPhrases) {
            groups.setBounds(ink.dp(6f), groupTop(), width - ink.dp(6f), height.toFloat() - ink.dp(4f))
            groups.draw(canvas)
        }
        preview?.let { drawPreview(canvas, it) }
        qrPreview?.let { drawQrPreview(canvas, it) }
    }

    /** 长按图片的大图：盖住整个面板，图按比例放到最大，底下 关闭 / 发送。 */
    private fun drawPreview(canvas: Canvas, path: String) {
        canvas.drawColor(palette.board)
        val barTop = height - ink.dp(PREVIEW_BAR_DP)
        val bitmap = previewBitmap(path)
        if (bitmap == null) {
            ink.text(canvas, "图片读不出来了", 15f, palette.gloss, width / 2f, barTop / 2)
        } else {
            val boxW = width - ink.dp(20f)
            val boxH = barTop - ink.dp(20f)
            val scale = minOf(boxW / bitmap.width, boxH / bitmap.height)
            val w = bitmap.width * scale
            val h = bitmap.height * scale
            card.set((width - w) / 2, (barTop - h) / 2, (width + w) / 2, (barTop + h) / 2)
            canvas.drawBitmap(bitmap, null, card, imagePaint)
        }
        val each = (width - ink.dp(20f)) / PREVIEW_BUTTONS.size
        PREVIEW_BUTTONS.forEachIndexed { i, label ->
            card.set(ink.dp(10f) + each * i + ink.dp(4f), barTop + ink.dp(8f), ink.dp(10f) + each * (i + 1) - ink.dp(4f), height - ink.dp(8f))
            val primary = i == PREVIEW_BUTTONS.lastIndex
            ink.round(canvas, card, 12f, if (primary) palette.brand else palette.surface)
            ink.text(canvas, label, 15f, if (primary) palette.onBrand else palette.text, card.centerX(), card.centerY(), bold = primary)
        }
    }

    /** 在面板内显示当前剪贴板文字的二维码，不离开输入法。 */
    private fun qrBounds(): RectF {
        val side = minOf(width - ink.dp(48f), height - ink.dp(112f)).coerceAtLeast(ink.dp(120f))
        val left = (width - side) / 2f
        val top = (height - side - ink.dp(28f)) / 2f
        return RectF(left, top, left + side, top + side)
    }

    /** 二维码只生成一次；绘制与分享共用同一张位图，避免每帧重复编码和分配。 */
    private fun qrBitmap(value: String): Bitmap? {
        qrBitmapCache?.takeIf { it.first == value }?.let { return it.second }
        qrBitmapCache?.second?.takeIf { !it.isRecycled }?.recycle()
        val bitmap = runCatching {
            val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 512, 512,
                mapOf(EncodeHintType.MARGIN to 1))
            Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).also { result ->
                for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
                    result.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
        }.getOrNull()
        qrBitmapCache = value to bitmap
        return bitmap
    }

    private fun drawQrPreview(canvas: Canvas, value: String) {
        canvas.drawColor(palette.board)
        val bitmap = qrBitmap(value)
        if (bitmap == null) {
            ink.text(canvas, "内容过长，无法生成二维码", 14f, palette.text, width / 2f, height / 2f)
        } else {
            val bounds = qrBounds()
            card.set(bounds)
            ink.round(canvas, card, 8f, Color.WHITE)
            canvas.drawBitmap(bitmap, null, card, imagePaint)
            ink.text(canvas, "长按二维码分享 · 点击关闭", 13f, palette.text, width / 2f, bounds.bottom + ink.dp(24f))
        }
    }

    private fun shareQrPreview(value: String) {
        val bitmap = qrBitmap(value) ?: return
        runCatching {
            val dir = ClipboardImageProvider.imageDir(context).apply { mkdirs() }
            val expiry = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
            dir.listFiles()?.filter { it.name.startsWith("qingjian-qr-") && it.lastModified() < expiry }?.forEach { it.delete() }
            val file = File.createTempFile("qingjian-qr-", ".png", dir)
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "二维码保存失败" }
            }
            val uri = ClipboardImageProvider.uriOf(file.absolutePath)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(context.contentResolver, "青简二维码", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "分享二维码").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            android.widget.Toast.makeText(context, "二维码分享失败", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun closeQrPreview() {
        qrPreview = null
        qrBitmapCache?.second?.takeIf { !it.isRecycled }?.recycle()
        qrBitmapCache = null
        invalidate()
    }

    /** 大图按面板大小降采样解码，只留当前这一张。 */
    private fun previewBitmap(path: String): Bitmap? {
        previewDecoded?.let { (decodedPath, bitmap) -> if (decodedPath == path) return bitmap }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) sample *= 2
        val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        previewDecoded = path to bitmap
        return bitmap
    }

    override fun onHold(x: Float, y: Float, rawX: Float) {
        // 二维码长按分享 PNG 图片；普通点击仍由 onTap 关闭预览。
        qrPreview?.let { value ->
            if (qrBounds().contains(x, y)) {
                Haptics.tap(context)
                shareQrPreview(value)
            }
            return
        }
        // 剪贴板卡片的长按由本面板负责：文字条目进入拖动删除，图片静止长按仍可预览。
        if (swipeLongPressed || showingPhrases || preview != null || !list.contains(x, y)) return
        val contentY = y - list.top + listScroll.offset
        val i = (0 until count).firstOrNull { contentY >= tops[it] && contentY < bottoms[it] } ?: return
        preview = clipAt(i)?.image ?: return
        Haptics.tap(context)
        invalidate()
    }

    override fun onBack() {
        when {
            qrPreview != null -> closeQrPreview()
            preview != null -> closePreview()
            else -> super.onBack()
        }
    }

    private fun closePreview() {
        preview = null
        previewDecoded = null
        invalidate()
    }

    /** 量出每张卡片的高；展开的那张多一行操作。 */
    private fun arrange() {
        tops = FloatArray(count)
        bottoms = FloatArray(count)
        var y = ink.dp(2f)
        for (i in 0 until count) {
            tops[i] = y
            y += contentHeight(i) + ink.dp(CARD_PADDING_DP) * 2 + (if (i == expanded && actionLabels().isNotEmpty()) ink.dp(ACTIONS_DP) else 0f)
            bottoms[i] = y
            y += ink.dp(CARD_GAP_DP)
        }
        listScroll.max = y - list.height()
    }

    private fun textWidth() = (width - ink.dp(CARD_MARGIN_DP) * 2 - ink.dp(CARD_PADDING_DP) * 2 - ink.dp(CHEVRON_DP)).toInt()

    private fun contentHeight(i: Int): Float {
        if (!showingPhrases) clipAt(i)?.image?.let { return ink.dp(IMAGE_DP) }
        return layout(i).height + (if (codeOf(i) != null) ink.dp(CODE_LINE_DP) else 0f)
    }

    /** 常用语的输入码（有才画一行）。 */
    private fun codeOf(i: Int): String? = if (showingPhrases) shownPhrases()[i].code.ifEmpty { null } else null

    private fun textOf(i: Int) = if (showingPhrases) shownPhrases()[i].text else clipAt(i)?.text.orEmpty()

    /**
     * 卡片里的字：收着最多 3 行、展开最多 15 行；像代码的（Core 认，见 [CodeView]）等宽、不重排，开着「代码高亮」再按片段上色。
     * 只排前一段，长文不整篇排版。
     */
    private fun layout(i: Int): StaticLayout {
        val text = textOf(i)
        val open = i == expanded
        val key = "$open|${textWidth()}|$text"
        return layouts.getOrPut(key) {
            val shown = text.take(if (open) 3000 else 600)
            val view = CodeView.of(shown)
            textPaint.textSize = ink.dp(if (view.code) 13f else 15.5f)
            textPaint.typeface = if (view.code) Typeface.MONOSPACE else Typeface.DEFAULT
            textPaint.color = palette.text
            val styled: CharSequence = if (view.code && highlight) colored(shown, view) else shown
            StaticLayout.Builder.obtain(styled, 0, styled.length, TextPaint(textPaint), textWidth().coerceAtLeast(1))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setMaxLines(if (open) 15 else 3)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setLineSpacing(0f, 1.15f)
                .build()
        }
    }

    /** 按 Core 切的片段上色：关键字品牌色、字符串橙、注释灰（斜体）、数字蓝。 */
    private fun colored(text: String, view: CodeView): CharSequence {
        val styled = SpannableString(text)
        for (span in view.spans) {
            if (span.start < 0 || span.end > text.length || span.start >= span.end) continue
            val color = when (span.kind) {
                "keyword" -> palette.brand
                "string" -> palette.fresh
                "comment" -> palette.gloss
                else -> numberColor
            }
            styled.setSpan(ForegroundColorSpan(color), span.start, span.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (span.kind == "comment") styled.setSpan(StyleSpan(Typeface.ITALIC), span.start, span.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return styled
    }

    private fun drawCard(canvas: Canvas, i: Int, top: Float, bottom: Float) {
        val margin = ink.dp(CARD_MARGIN_DP)
        val padding = ink.dp(CARD_PADDING_DP)
        card.set(margin, top, width - margin, bottom)
        ink.round(canvas, card, 12f, palette.surface)
        val image = if (showingPhrases) null else clipAt(i)?.image
        if (image != null) {
            thumbnail(image)?.let { bitmap ->
                val h = ink.dp(IMAGE_DP)
                val w = (bitmap.width * h / bitmap.height).coerceAtMost(textWidth().toFloat())
                card.set(margin + padding, top + padding, margin + padding + w, top + padding + h)
                canvas.drawBitmap(bitmap, null, card, null)
            } ?: ink.textStart(canvas, "[图片]", 15f, palette.gloss, margin + padding, top + padding + ink.dp(IMAGE_DP) / 2, width - margin)
        } else {
            canvas.save()
            canvas.translate(margin + padding, top + padding)
            layout(i).draw(canvas)
            canvas.restore()
            codeOf(i)?.let { code ->
                val y = top + padding + layout(i).height + ink.dp(CODE_LINE_DP) / 2
                ink.textStart(canvas, "输入码  $code", 12.5f, palette.gloss, margin + padding, y, width - margin)
            }
        }
        val open = i == expanded
        ink.icon(canvas, if (open) Icon.COLLAPSE else Icon.EXPAND, width - margin - ink.dp(CHEVRON_DP) / 2, top + padding + ink.dp(11f), 20f, palette.gloss)
        if (open && actionLabels().isNotEmpty()) drawActions(canvas, bottom - ink.dp(ACTIONS_DP), bottom)
    }

    private fun actionLabels(): List<Pair<Icon, String>> {
        if (showingPhrases) return listOf(Icon.PEN to "编辑", Icon.TOP to "移至首位", Icon.TRASH to "删除")
        val clip = clipAt(expanded) ?: return emptyList()
        val contextual = when {
            ScanContent.parseWifi(clip.text) != null -> listOf(Icon.LOCK to "加入 WiFi")
            ScanContent.webUrl(clip.text) != null -> listOf(Icon.GLOBE to "访问网址")
            else -> emptyList()
        }
        if (clip.image != null) return contextual
        return contextual + listOf(Icon.PEN to "编辑", Icon.PLUS to "常用语", Icon.SPLIT to "拆词", Icon.CAMERA to "二维码")
    }

    /** 每个操作的宽度：96dp，剪贴板文字的四个在窄屏上放不下时均分卡片宽度。 */
    private fun actionWidth() =
        if (actionLabels().isEmpty()) 0f else minOf(ink.dp(96f), (width - ink.dp(2 * (CARD_MARGIN_DP + CARD_PADDING_DP))) / actionLabels().size)

    private fun drawActions(canvas: Canvas, top: Float, bottom: Float) {
        val cy = (top + bottom) / 2 - ink.dp(4f)
        var x = ink.dp(CARD_MARGIN_DP + CARD_PADDING_DP)
        for ((icon, label) in actionLabels()) {
            ink.icon(canvas, icon, x + ink.dp(9f), cy, 17f, palette.gloss)
            ink.textStart(canvas, label, 13.5f, palette.text, x + ink.dp(22f), cy, x + actionWidth())
            x += actionWidth()
        }
    }

    /** 图片解码放到单线程后台执行，避免剪贴板面板绘制期间阻塞输入法主线程。 */
    private fun thumbnail(path: String): Bitmap? {
        if (thumbnails.containsKey(path)) return thumbnails[path]
        synchronized(pendingThumbnails) {
            if (!pendingThumbnails.add(path)) return null
        }
        IMAGE_DECODER.execute {
            val bitmap = decodeThumbnail(path)
            synchronized(pendingThumbnails) { pendingThumbnails.remove(path) }
            post {
                if (thumbnails.size >= 32 && !thumbnails.containsKey(path)) {
                    thumbnails.keys.firstOrNull()?.let { thumbnails.remove(it) }
                }
                thumbnails[path] = bitmap
                if (isAttachedToWindow) invalidate()
            }
        }
        return null
    }

    private fun decodeThumbnail(path: String): Bitmap? {
        return try {
            if (!File(path).isFile) {
                null
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    null
                } else {
                    val target = ink.dp(IMAGE_DP).toInt().coerceAtLeast(1)
                    var sample = 1
                    while (maxOf(bounds.outWidth / (sample * 2), bounds.outHeight / (sample * 2)) >= target) {
                        sample *= 2
                    }
                    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inDither = true
                    })
                }
            }
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun actionRowTop() = headerHeight + ink.dp(SUMMARY_DP)
    private fun actionButtonBounds(index: Int): RectF {
        // 按图标的实际绘制宽度与固定留白定宽，三个按钮靠右排列。
        val buttonWidth = ink.dp(18f + 14f)
        val gap = ink.dp(6f)
        val right = width - ink.dp(8f)
        val buttonLeft = right - (3 - index) * (buttonWidth + gap) + gap
        return RectF(buttonLeft, actionRowTop() + ink.dp(2f), buttonLeft + buttonWidth, actionRowTop() + ink.dp(38f))
    }
    private fun deleteBackwardBounds(): RectF = actionButtonBounds(0)
    private fun pasteToggleBounds(): RectF = actionButtonBounds(1)
    private fun pasteNextCellBounds(): RectF = actionButtonBounds(2)

    private fun isPasteSummaryTouch(x: Float, y: Float): Boolean =
        !showingPhrases && y >= actionRowTop() && y < actionRowTop() + ink.dp(SEARCH_BAR_DP) &&
            (deleteBackwardBounds().contains(x, y) || pasteToggleBounds().contains(x, y) || pasteNextCellBounds().contains(x, y))

    @SuppressLint("ClickableViewAccessibility")
    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        // 摘要栏热区由本面板独占处理，避免父容器抢走按钮触摸序列。
        if (pasteSummaryTracking || (event.actionMasked == android.view.MotionEvent.ACTION_DOWN &&
                isPasteSummaryTouch(event.x, event.y))) {
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    pasteSummaryTracking = true
                    deleteBackwardTracking = deleteBackwardBounds().contains(event.x, event.y)
                    pasteToggleTracking = pasteToggleBounds().contains(event.x, event.y)
                    pasteNextCellTracking = pasteNextCellBounds().contains(event.x, event.y)
                    deleteRepeatStarted = false
                    if (deleteBackwardTracking) {
                        postDelayed(deleteRepeatRunnable, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                    }
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (deleteBackwardTracking && !deleteBackwardBounds().contains(event.x, event.y)) {
                        removeCallbacks(deleteRepeatRunnable)
                        deleteBackwardTracking = false
                    }
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val activateDelete = deleteBackwardTracking && deleteBackwardBounds().contains(event.x, event.y)
                    val activateEnter = pasteToggleTracking && pasteToggleBounds().contains(event.x, event.y)
                    val activateNext = pasteNextCellTracking && pasteNextCellBounds().contains(event.x, event.y)
                    removeCallbacks(deleteRepeatRunnable)
                    val repeated = deleteRepeatStarted
                    pasteSummaryTracking = false
                    deleteBackwardTracking = false
                    pasteToggleTracking = false
                    pasteNextCellTracking = false
                    deleteRepeatStarted = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    if (activateDelete && !repeated) {
                        actions.deleteBackward()
                        Haptics.tap(context)
                    }
                    if (activateEnter || activateNext) {
                        val prefs = Settings.preferences(context)
                        if (activateEnter) {
                            pasteEnter = !pasteEnter
                            if (pasteEnter) pasteNextCell = false
                        } else {
                            pasteNextCell = !pasteNextCell
                            if (pasteNextCell) pasteEnter = false
                        }
                        prefs.edit()
                            .putBoolean(Settings.KEY_CLIPBOARD_PASTE_ENTER, pasteEnter)
                            .putBoolean(Settings.KEY_CLIPBOARD_PASTE_NEXT_CELL, pasteNextCell)
                            .apply()
                        clearArmed = false
                        Haptics.tap(context)
                        invalidate()
                    }
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(deleteRepeatRunnable)
                    pasteSummaryTracking = false
                    deleteBackwardTracking = false
                    pasteToggleTracking = false
                    pasteNextCellTracking = false
                    deleteRepeatStarted = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            return true
        }

        if (swipeTracking) {
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - swipeDownX
                    val dy = event.y - swipeDownY
                    if (swipeLongPressed) {
                        if (kotlin.math.abs(dx) > kotlin.math.abs(dy) * SWIPE_DIRECTION_RATIO) {
                            swipeOffsetX = dx.coerceIn(-width * 0.9f, width * 0.9f)
                            invalidate()
                        }
                        return true
                    }
                    // 长按前若手指已明显移动，取消删除候选，让原有纵向列表滚动/点击识别接管。
                    val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
                    if (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop) {
                        removeCallbacks(activateSwipeRunnable)
                        resetSwipeTracking()
                    }
                }
                android.view.MotionEvent.ACTION_UP -> {
                    removeCallbacks(activateSwipeRunnable)
                    if (swipeLongPressed) {
                        val index = swipeClipIndex
                        val shouldDelete = kotlin.math.abs(swipeOffsetX) >= ink.dp(SWIPE_DELETE_DP)
                        val image = clipAt(index)?.image
                        resetSwipeTracking()
                        parent?.requestDisallowInterceptTouchEvent(false)
                        if (shouldDelete) {
                            deleteClipAt(index)
                            Haptics.tap(context)
                        } else if (image != null) {
                            preview = image
                            invalidate()
                        }
                        invalidate()
                        return true
                    }
                    resetSwipeTracking()
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(activateSwipeRunnable)
                    val consumed = swipeLongPressed
                    resetSwipeTracking()
                    parent?.requestDisallowInterceptTouchEvent(false)
                    if (consumed) return true
                }
            }
        } else if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN &&
            !showingPhrases && preview == null && qrPreview == null && list.contains(event.x, event.y)) {
            val contentY = event.y - list.top + listScroll.offset
            val index = (0 until count).firstOrNull {
                contentY >= tops.getOrElse(it) { Float.MAX_VALUE } &&
                    contentY < bottoms.getOrElse(it) { Float.MIN_VALUE }
            } ?: -1
            if (index >= 0) {
                swipeTracking = true
                swipeLongPressed = false
                swipeOffsetX = 0f
                swipeDownX = event.x
                swipeDownY = event.y
                swipeClipIndex = index
                postDelayed(activateSwipeRunnable, android.view.ViewConfiguration.getLongPressTimeout().toLong())
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun resetSwipeTracking() {
        removeCallbacks(activateSwipeRunnable)
        swipeTracking = false
        swipeLongPressed = false
        swipeOffsetX = 0f
        swipeClipIndex = -1
    }

    private fun deleteClipAt(index: Int) {
        val sourceIndex = clipSourceIndex(index)
        if (sourceIndex !in clips.indices) return
        ClipboardStore.remove(context, sourceIndex, actions.clipboardLimit)
        clips = ClipboardStore.list(context, actions.clipboardLimit)
        app.qingjian.inputmethod.clipboard.sync.ClipboardSync.changed()
        // 删除当前卡片后保留列表滚动量，下一条内容自然补位，不跳回第一条。
        collapse(resetScroll = false)
    }

    override fun onTap(x: Float, y: Float) {
        if (searchEditing && !searchRect.contains(x, y)) {
            removeCallbacks(searchCursorBlink)
            searchCursorVisible = false
            searchPopup?.dismiss()
            searchPopup = null
            searchEditing = false
            context.getSharedPreferences(SEARCH_STATE_PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(SEARCH_EDITOR_ACTIVE, false).apply()
        }
        if (qrPreview != null) {
            closeQrPreview()
            return
        }
        preview?.let { path ->
            val send = y > height - ink.dp(PREVIEW_BAR_DP) && x > width / 2f
            closePreview()
            if (send) actions.commitImage(path)
            return
        }
        when {
            y < headerHeight && isRightIcon(x, y) -> onRightIcon()
            y < headerHeight -> tabs.indexAt(x)?.let(::switchTab)
            showingPhrases && y > groupTop() -> groups.indexAt(x)?.let { index ->
                groups.selected = index
                lastGroup = visiblePhraseGroups[index]
                collapse()
            }
            !showingPhrases && searchRect.contains(x, y) -> {
                if (x > searchRect.right - ink.dp(26f) && searchQuery.isNotEmpty()) updateSearchQuery("")
                else openSearchEditor()
            }
            list.contains(x, y) -> tapList(x, y - list.top + listScroll.offset)
        }
        if (!(y < headerHeight && isRightIcon(x, y))) clearArmed = false
    }

    private fun switchTab(index: Int) {
        if (index == tabs.selected) return
        tabs.selected = index
        collapse()
    }

    private fun collapse(resetScroll: Boolean = true) {
        expanded = -1
        if (resetScroll) {
            listScroll.reset()
            lastClipScrollOffset = 0f
            lastDrawnScrollOffset = 0f
            scrollMotion = 0f
            restoreClipScrollPending = false
        } else if (!showingPhrases) {
            lastClipScrollOffset = listScroll.offset
        }
        layouts.clear()
    }

    private fun onRightIcon() {
        if (showingPhrases) {
            actions.editPhrase(null, visiblePhraseGroups.getOrNull(groups.selected) ?: PhraseRules.defaultGroup)
        } else if (clips.isNotEmpty()) {
            if (clearArmed) {
                clips = ClipboardStore.clear(context, actions.clipboardLimit)
                app.qingjian.inputmethod.clipboard.sync.ClipboardSync.changed()
                clearArmed = false
                collapse()
            } else {
                clearArmed = true
            }
        }
    }

    /** [y] 是列表内容里的位置（已加滚动）。 */
    private fun tapList(x: Float, y: Float) {
        val i = (0 until count).firstOrNull { y >= tops[it] && y < bottoms[it] } ?: return
        val chevron = x > width - ink.dp(CARD_MARGIN_DP + CHEVRON_DP + 8f) && y < tops[i] + ink.dp(CARD_PADDING_DP + 30f)
        val inActions = i == expanded && actionLabels().isNotEmpty() && y > bottoms[i] - ink.dp(ACTIONS_DP)
        when {
            chevron -> {
                expanded = if (expanded == i) -1 else i
                layouts.clear()
            }
            inActions -> onAction(i, ((x - ink.dp(CARD_MARGIN_DP + CARD_PADDING_DP)) / actionWidth()).toInt())
            showingPhrases -> actions.commitText(shownPhrases()[i].text)
            else -> clipAt(i)?.let { clip -> clip.image?.let(actions::commitImage) ?: actions.commitClipboardText(clip.text) }
        }
    }

    private fun onAction(i: Int, action: Int) {
        val labels = actionLabels()
        val label = labels.getOrNull(action)?.second ?: return
        if (showingPhrases) {
            val phrase = shownPhrases()[i]
            when (label) {
                "编辑" -> actions.editPhrase(phrase, phrase.group)
                "移至首位" -> reloadPhrases(ClipboardStore.phraseToTop(context, phrase.index))
                "删除" -> reloadPhrases(ClipboardStore.removePhrase(context, phrase.index))
            }
            return
        }
        when (label) {
            "加入 WiFi" -> {
                val wifi = ScanContent.parseWifi(clipAt(i)?.text.orEmpty()) ?: return
                context.startActivity(Intent(context, WifiJoinActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(WifiJoinActivity.EXTRA_SSID, wifi.ssid)
                    putExtra(WifiJoinActivity.EXTRA_PASSWORD, wifi.password)
                    putExtra(WifiJoinActivity.EXTRA_SECURITY, wifi.security)
                    putExtra(WifiJoinActivity.EXTRA_HIDDEN, wifi.hidden)
                })
            }
            "访问网址" -> {
                val url = ScanContent.webUrl(clipAt(i)?.text.orEmpty()) ?: return
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            "编辑" -> clipAt(i)?.let { actions.editClip(clipSourceIndex(i), it.text) }
            "常用语" -> {
                val clipText = clipAt(i)?.text.orEmpty()
                if (clipText.isBlank()) {
                    actions.toast("这条剪贴板记录没有可添加的文字")
                    return
                }
                val group = visiblePhraseGroups.getOrNull(groups.selected) ?: PhraseRules.defaultGroup
                val updated = ClipboardStore.addPhrase(context, clipText, group, "")
                if (updated.operationSucceeded) {
                    lastGroup = group
                    reloadPhrases(updated)
                    actions.toast("已加入常用语「$group」")
                } else {
                    // 不把未持久化的内存结果显示成已保存的常用语。
                    actions.toast("常用语保存失败：${updated.operationError ?: "底层存储拒绝写入"}")
                    invalidate()
                }
            }
            "拆词" -> actions.openPieces(clipAt(i)?.text.orEmpty())
            "二维码" -> {
                qrPreview = clipAt(i)?.text.orEmpty()
                invalidate()
            }
            "删除" -> deleteClipAt(i)
        }
    }

    private fun reloadPhrases(page: PhrasePage) {
        this.page = page
        groups.labels = visiblePhraseGroups
        groups.selected = visiblePhraseGroups.indexOf(lastGroup).coerceAtLeast(0)
        actions.phrasesChanged()
        // 从剪贴板条目添加常用语时仍留在剪贴板页，不能把当前浏览位置重置到顶部。
        collapse(resetScroll = showingPhrases)
    }

    companion object {
        private val HAN_TO_LATIN: Transliterator = Transliterator.getInstance("Han-Latin; Latin-ASCII")
        private val IMAGE_DECODER = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "qingjian-clipboard-image").apply { isDaemon = true }
        }
        private const val CLIPS = 0
        private const val PHRASES = 1
        private const val AUTO_MEMORY_GROUP = "自动记忆"
        private const val SUMMARY_DP = 4f
        private const val SEARCH_STATE_PREFS = "clipboard_search_state"
        private const val SEARCH_EDITOR_ACTIVE = "search_editor_active"
        private const val SEARCH_BAR_DP = 42f
        private const val GROUP_BAR_DP = 48f
        private const val CARD_MARGIN_DP = 10f
        private const val CARD_PADDING_DP = 14f
        private const val CARD_GAP_DP = 8f
        private const val CHEVRON_DP = 32f
        private const val ACTIONS_DP = 40f
        private const val IMAGE_DP = 96f
        private const val CODE_LINE_DP = 24f
        private const val PREVIEW_BAR_DP = 60f
        private const val SWIPE_DELETE_DP = 64f
        private const val SWIPE_DIRECTION_RATIO = 1.25f
        private const val DELETE_REPEAT_INTERVAL_MS = 55L
        private val PREVIEW_BUTTONS = listOf("关闭", "发送")

        private var activeInstance: WeakReference<ClipboardPanel>? = null

        fun isSearchEditorActive(context: Context): Boolean =
            activeInstance?.get()?.searchEditing == true ||
                context.getSharedPreferences(SEARCH_STATE_PREFS, Context.MODE_PRIVATE)
                    .getBoolean(SEARCH_EDITOR_ACTIVE, false)

        fun handleSearchKey(key: Key): Boolean = activeInstance?.get()?.applySearchKey(key) == true

        fun clearSearchQuery(): Boolean {
            val panel = activeInstance?.get() ?: return false
            if (!panel.searchEditing) return false
            panel.updateSearchQuery("")
            return true
        }

        /** 常用语停在哪一组：关了面板再开还在这组；加完常用语跳到它所在的组。 */
        var lastGroup = PhraseRules.defaultGroup

        /** 剪贴板在同一编辑场景重建面板时恢复滚动位置。 */
        private var lastClipScrollOffset = 0f
    }
}
