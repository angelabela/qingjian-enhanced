package app.qingjian.inputmethod.panel

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import app.qingjian.inputmethod.scan.BarcodeScannerActivity
import app.qingjian.inputmethod.sms.SmsCodeSettingsActivity
import app.qingjian.inputmethod.sms.SmsCodeReceiver
import android.graphics.Canvas
import android.graphics.RectF
import app.qingjian.inputmethod.panel.base.PanelIcons.Icon
import app.qingjian.inputmethod.panel.base.PanelView
import app.qingjian.inputmethod.panel.base.Tabs
import app.qingjian.inputmethod.settings.Settings
import app.qingjian.inputmethod.theme.Palette

/**
 * 点工具栏 logo 进的功能面板：「常用功能」是图标宫格，「键盘设置」是就地改的开关；
 * 右上角进完整设置页。整块圆角卡片垫在键盘底色上。
 */
@SuppressLint("ViewConstructor")
class FunctionPanel(
    context: Context,
    palette: Palette,
    actions: PanelActions,
    headerHeight: Float,
    private val settings: Settings,
) : PanelView(context, palette, actions, headerHeight) {
    /** 一格功能；[on] 是开关类功能开着（品牌色加对勾，同键盘选择）。 */
    private class Feature(
        val icon: Icon,
        val label: String,
        val on: Boolean = false,
        val run: PanelActions.() -> Unit,
    )

    private class Toggle(val key: String, val label: String, val note: String, val on: Boolean)

    private var voiceEffectPage = false
    private var backgroundEffectPage = false
    private var backgroundEffectEnabled = settings.keyboardBackgroundEffect
    private var selectedBackgroundStyle = if (settings.keyboardBackgroundEffect) settings.keyboardBackgroundStyle else -1
    private var selectedVoiceStyle = context.getSharedPreferences("voice", Context.MODE_PRIVATE).getInt(VoicePanel.PREF_STYLE, 0)

    private fun smsMonitoringEnabled(): Boolean =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getBoolean(SmsCodeReceiver.KEY_ENABLED, false)

    private val features: List<Feature>
        get() {
            val smsEnabled = smsMonitoringEnabled()
            return listOf(
                Feature(Icon.TRANSLATE, "文字工具") { openPanel(PanelKind.CONTEXT_TOOLS) },
                Feature(Icon.CAMERA, "扫码") {
                    context.startActivity(Intent(context, BarcodeScannerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
                Feature(if (smsEnabled) Icon.LOCK else Icon.UNLOCK, "验证码", on = smsEnabled) {
                    context.startActivity(Intent(context, SmsCodeSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
                Feature(if (backgroundEffectEnabled) Icon.WAVEFORM else Icon.PALETTE, "背景光效", on = backgroundEffectEnabled) {
                    selectedBackgroundStyle = if (backgroundEffectEnabled) settings.keyboardBackgroundStyle else -1
                    backgroundEffectPage = true
                    voiceEffectPage = false
                    invalidate()
                },
                Feature(Icon.WAVEFORM, "视频背景", on = context.getSharedPreferences("dynamic_background", Context.MODE_PRIVATE).getBoolean("video_enabled", false)) { openPanel(PanelKind.DYNAMIC_BACKGROUND) },
                Feature(Icon.KEYBOARD, "切换键盘") { openPanel(PanelKind.KEYBOARDS) },
                Feature(Icon.TRADITIONAL, "繁体", on = settings.traditional) {
                    setToggle(Settings.KEY_TRADITIONAL, !settings.traditional)
                },
                // 两套键位排法之间切，见 Settings.Arrangement
                Feature(Icon.LEFT_HAND, "左手布局", on = settings.arrangement == Settings.Arrangement.LEFT_THUMB) {
                    val next = if (settings.arrangement == Settings.Arrangement.LEFT_THUMB) Settings.Arrangement.STANDARD else Settings.Arrangement.LEFT_THUMB
                    setChoice(Settings.KEY_ARRANGEMENT, next.name)
                },
                Feature(Icon.RESIZE, "键盘高度") { startResize() },
                Feature(Icon.TUNE, "定制工具栏") { openPanel(PanelKind.TOOLBAR) },
                Feature(Icon.WAVEFORM, "语音动效") { voiceEffectPage = true; invalidate() },
                Feature(Icon.GLOBE, "切换输入法") { switchInputMethod() },
                Feature(Icon.SETTINGS, "设置") { openSettings() },
            ) + listOfNotNull(
                actions.gameKeyboard?.let { on -> Feature(Icon.GAMEPAD, "游戏键盘", on = on) { toggleGameKeyboard() } },
            )
        }

    fun refreshState() {
        backgroundEffectEnabled = Settings.preferences(context).getBoolean(Settings.KEY_BACKGROUND_EFFECT, settings.keyboardBackgroundEffect)
        if (!backgroundEffectEnabled) selectedBackgroundStyle = -1
        invalidate()
    }

    private val toggles
        get() = listOf(
            Toggle(Settings.KEY_HAPTIC, "按键震动", "", settings.haptic),
            Toggle(Settings.KEY_POPUP, "按键气泡", "按下字母时在上面放大显示", settings.keyPopup),
            Toggle(Settings.KEY_FULLSCREEN, "全面屏适配", "手势条那一截不留白，两个角各放一个键", settings.fullscreenAdapt),
            Toggle(Settings.KEY_KEEP_CHINESE, "换语言时保留中文", "开着时总在中文和选的语言之间切", settings.keepChinese),
        )

    private val tabs = Tabs(ink, scrollable(true) { x, y -> y < headerHeight && x > ink.dp(BACK_WIDTH_DP) }, Tabs.Style.UNDERLINE)
        .apply {
            labels = listOf("常用功能", "键盘设置")
            selected = lastTab
        }

    private val card = RectF()
    private val knob = RectF()
    // 两个动效选择页共用竖向惯性滚动，内容始终裁切在面板卡片内部。
    private val effectScroll = scrollable(false) { x, y ->
        (voiceEffectPage || backgroundEffectPage) &&
            x >= card.left && x <= card.right &&
            y >= card.top + ink.dp(38f) && y <= card.bottom - ink.dp(6f)
    }

    private val columns = 5

    override fun onDraw(canvas: Canvas) {
        drawHeader(canvas, if (voiceEffectPage || backgroundEffectPage) null else tabs, Icon.SETTINGS)
        if (voiceEffectPage) {
            ink.text(canvas, "语音面板动效", 16f, palette.text, width * 0.5f, headerHeight * 0.5f)
        } else if (backgroundEffectPage) {
            ink.text(canvas, "键盘背景动效", 16f, palette.text, width * 0.5f, headerHeight * 0.5f)
        }
        card.set(ink.dp(10f), headerHeight, width - ink.dp(10f), height - ink.dp(8f))
        ink.round(canvas, card, 16f, palette.surface)
        if (tabs.selected == 0) { if (voiceEffectPage) drawVoiceEffectSettings(canvas) else if (backgroundEffectPage) drawBackgroundEffectSettings(canvas) else drawFeatures(canvas) } else drawToggles(canvas)
    }

    private fun cellWidth() = card.width() / columns

    private fun cellHeight() = minOf(ink.dp(86f), card.height() / maxOf(2, (features.size + columns - 1) / columns))

    private fun drawVoiceEffectSettings(canvas: Canvas) {
        val top = card.top + ink.dp(8f)
        ink.textStart(canvas, "跟随当前主题 · 选择实时音频可视化样式", 11f, palette.gloss,
            card.left + ink.dp(16f), top + ink.dp(22f), card.right - ink.dp(12f))

        val labels = VoicePanel.ANIMATION_STYLE_LABELS
        val rows = (labels.size + 1) / 2
        val rowTop = top + ink.dp(34f)
        val viewportBottom = card.bottom - ink.dp(8f)
        val viewportHeight = (viewportBottom - rowTop).coerceAtLeast(0f)
        val rowStep = ink.dp(64f)
        val itemHeight = ink.dp(56f)
        effectScroll.max = (rows * rowStep - viewportHeight).coerceAtLeast(0f)
        val gapX = ink.dp(7f)
        val cardWidth = (card.width() - ink.dp(20f) - gapX) / 2f

        canvas.save()
        canvas.clipRect(card.left, rowTop, card.right, viewportBottom)
        canvas.translate(0f, -effectScroll.offset)
        labels.forEachIndexed { index, label ->
            val column = index % 2
            val row = index / 2
            val left = card.left + ink.dp(10f) + column * (cardWidth + gapX)
            val y = rowTop + row * rowStep
            val bounds = RectF(left, y + (rowStep - itemHeight) / 2f,
                left + cardWidth, y + (rowStep + itemHeight) / 2f)
            val selected = (if (selectedVoiceStyle == -1) 8 else selectedVoiceStyle) == index

            ink.round(canvas, bounds, 11f, if (selected) palette.functionalKey else palette.surface)
            ink.fill.style = android.graphics.Paint.Style.STROKE
            ink.fill.strokeWidth = ink.dp(if (selected) 1.35f else 0.65f)
            ink.fill.color = if (selected) palette.brand else palette.faint
            canvas.drawRoundRect(bounds, ink.dp(11f), ink.dp(11f), ink.fill)
            ink.fill.style = android.graphics.Paint.Style.FILL

            drawVoiceStylePreview(canvas, bounds, index, selected)
            val textColor = if (selected) palette.brand else palette.text
            ink.textStart(canvas, label, 12.5f, textColor, bounds.left + ink.dp(43f),
                bounds.centerY() - ink.dp(1f), bounds.right - ink.dp(7f))
            if (selected) {
                ink.fill.color = palette.brand
                canvas.drawCircle(bounds.right - ink.dp(10f), bounds.top + ink.dp(10f), ink.dp(2.8f), ink.fill)
            }
        }
        canvas.restore()
    }

    /** 背景动效样式选择；内容超过可视区时在卡片内部滚动，不再挤出面板。 */
    private fun drawBackgroundEffectSettings(canvas: Canvas) {
        val labels = listOf("极光流彩", "深海蓝潮", "日落熔金", "翡翠幻境", "冰晶星雾", "魔幻粒子", "星云裂隙")
        val colors = listOf(
            intArrayOf(0xFF2DE2C5.toInt(), 0xFF3E8BFF.toInt(), 0xFF9B62FF.toInt()),
            intArrayOf(0xFF11B8A6.toInt(), 0xFF1D8FE8.toInt(), 0xFF35C6D8.toInt()),
            intArrayOf(0xFFFF8A4C.toInt(), 0xFFF04F8B.toInt(), 0xFFB45BE8.toInt()),
            intArrayOf(0xFF2DE2C5.toInt(), 0xFF43B66D.toInt(), 0xFF9B62FF.toInt()),
            intArrayOf(0xFFD8F3FF.toInt(), 0xFF87B8E8.toInt(), 0xFFB7A6F6.toInt()),
            intArrayOf(0xFF9CFFF0.toInt(), 0xFF8BB8FF.toInt(), 0xFFF49BFF.toInt()),
            intArrayOf(0xFF7F8CFF.toInt(), 0xFF7DE7D9.toInt(), 0xFFD17BFF.toInt()),
        )
        val top = card.top + ink.dp(10f)
        ink.textStart(canvas, "选择一种持续流动的背景色彩", 11f, palette.gloss,
            card.left + ink.dp(16f), top + ink.dp(14f), card.right - ink.dp(12f))
        val gapX = ink.dp(8f)
        val rowStep = ink.dp(74f)
        val itemHeight = ink.dp(64f)
        val rows = (labels.size + 1) / 2
        val rowTop = top + ink.dp(26f)
        val viewportBottom = card.bottom - ink.dp(92f)
        val viewportHeight = (viewportBottom - rowTop).coerceAtLeast(0f)
        effectScroll.max = (rows * rowStep - viewportHeight).coerceAtLeast(0f)
        val cardWidth = (card.width() - ink.dp(20f) - gapX) / 2f

        canvas.save()
        canvas.clipRect(card.left, rowTop, card.right, viewportBottom)
        canvas.translate(0f, -effectScroll.offset)
        labels.forEachIndexed { index, label ->
            val column = index % 2
            val row = index / 2
            val left = card.left + ink.dp(10f) + column * (cardWidth + gapX)
            val topY = rowTop + row * rowStep
            val bounds = RectF(left, topY + (rowStep - itemHeight) / 2f,
                left + cardWidth, topY + (rowStep + itemHeight) / 2f)
            val selected = selectedBackgroundStyle == index
            ink.round(canvas, bounds, 11f, if (selected) palette.functionalKey else palette.surface)
            ink.fill.style = android.graphics.Paint.Style.STROKE
            ink.fill.strokeWidth = ink.dp(if (selected) 1.4f else 0.7f)
            ink.fill.color = if (selected) palette.brand else palette.faint
            canvas.drawRoundRect(bounds, ink.dp(11f), ink.dp(11f), ink.fill)
            ink.fill.style = android.graphics.Paint.Style.FILL
            val swatch = RectF(bounds.left + ink.dp(10f), bounds.centerY() - ink.dp(10f),
                bounds.left + ink.dp(42f), bounds.centerY() + ink.dp(10f))
            ink.fill.shader = android.graphics.LinearGradient(swatch.left, swatch.top, swatch.right, swatch.bottom,
                colors[index], null, android.graphics.Shader.TileMode.CLAMP)
            canvas.drawRoundRect(swatch, ink.dp(7f), ink.dp(7f), ink.fill)
            ink.fill.shader = null
            ink.textStart(canvas, label, 12.5f, if (selected) palette.brand else palette.text,
                bounds.left + ink.dp(50f), bounds.centerY() + ink.dp(4f), bounds.right - ink.dp(7f))
            if (selected) {
                ink.fill.color = palette.brand
                canvas.drawCircle(bounds.right - ink.dp(10f), bounds.top + ink.dp(10f), ink.dp(2.8f), ink.fill)
            }
        }
        canvas.restore()
        val prefs = context.getSharedPreferences("dynamic_background", Context.MODE_PRIVATE)
        val controlTop = card.bottom - ink.dp(76f)
        val alpha = prefs.getInt("keyboard_key_alpha", 100).coerceIn(0, 100)
        val frosted = prefs.getBoolean("keyboard_key_frosted", false)
        ink.textStart(canvas, "按键透明度", 11f, palette.text, card.left + ink.dp(14f), controlTop + ink.dp(13f), card.right - ink.dp(80f))
        ink.text(canvas, "$alpha%", 11f, palette.brand, card.right - ink.dp(28f), controlTop + ink.dp(13f))
        val left = card.left + ink.dp(14f)
        val right = card.right - ink.dp(14f)
        ink.fill.color = palette.functionalKey
        ink.fill.strokeWidth = ink.dp(3f)
        ink.fill.strokeCap = android.graphics.Paint.Cap.ROUND
        canvas.drawLine(left, controlTop + ink.dp(31f), right, controlTop + ink.dp(31f), ink.fill)
        ink.fill.color = palette.brand
        canvas.drawLine(left, controlTop + ink.dp(31f), left + (right - left) * alpha / 100f, controlTop + ink.dp(31f), ink.fill)
        ink.textStart(canvas, "按键磨砂效果", 11f, palette.text, card.left + ink.dp(14f), controlTop + ink.dp(62f), card.right - ink.dp(92f))
        ink.round(canvas, RectF(card.right - ink.dp(68f), controlTop + ink.dp(48f), card.right - ink.dp(16f), controlTop + ink.dp(72f)), 12f,
            if (frosted) palette.brand else palette.functionalKey)
        ink.text(canvas, if (frosted) "开" else "关", 10f, if (frosted) palette.onBrand else palette.text,
            card.right - ink.dp(42f), controlTop + ink.dp(60f))
    }

    /** 迷你频谱预览使用当前主题色绘制，选中态不引入独立配色。 */
    private fun drawVoiceStylePreview(canvas: Canvas, bounds: RectF, index: Int, selected: Boolean) {
        val cx = bounds.left + ink.dp(22f)
        val cy = bounds.centerY()
        val accent = if (selected) palette.brand else palette.gloss
        val secondary = if (selected) palette.accent else palette.faint
        ink.fill.style = android.graphics.Paint.Style.FILL
        if (index == 1) {
            ink.fill.color = accent
            ink.fill.style = android.graphics.Paint.Style.STROKE
            ink.fill.strokeWidth = ink.dp(1.4f)
            canvas.drawCircle(cx, cy, ink.dp(5.5f), ink.fill)
            ink.fill.style = android.graphics.Paint.Style.FILL
            for (i in 0 until 8) {
                val angle = Math.PI * 2.0 * i / 8.0
                val x1 = cx + kotlin.math.cos(angle).toFloat() * ink.dp(7f)
                val y1 = cy + kotlin.math.sin(angle).toFloat() * ink.dp(7f)
                val x2 = cx + kotlin.math.cos(angle).toFloat() * ink.dp(10f)
                val y2 = cy + kotlin.math.sin(angle).toFloat() * ink.dp(10f)
                ink.fill.color = secondary
                ink.fill.strokeWidth = ink.dp(1.1f)
                canvas.drawLine(x1, y1, x2, y2, ink.fill)
            }
            return
        }
        if (index == 7) {
            val wave = android.graphics.Path()
            for (step in 0..28) {
                val u = step / 28f
                val x = bounds.left + ink.dp(8f) + u * ink.dp(28f)
                val y = cy + kotlin.math.sin(u * Math.PI * 3.0).toFloat() * ink.dp(5f) +
                    kotlin.math.sin(u * Math.PI * 6.0 + 0.8).toFloat() * ink.dp(2.2f)
                if (step == 0) wave.moveTo(x, y) else wave.lineTo(x, y)
            }
            ink.fill.style = android.graphics.Paint.Style.STROKE
            ink.fill.strokeCap = android.graphics.Paint.Cap.ROUND
            ink.fill.strokeJoin = android.graphics.Paint.Join.ROUND
            ink.fill.strokeWidth = ink.dp(3.2f)
            ink.fill.color = secondary
            ink.fill.alpha = 95
            canvas.drawPath(wave, ink.fill)
            ink.fill.strokeWidth = ink.dp(1.3f)
            ink.fill.color = accent
            ink.fill.alpha = 255
            canvas.drawPath(wave, ink.fill)
            ink.fill.style = android.graphics.Paint.Style.FILL
            return
        }
        val heights = when (index) {
            0 -> floatArrayOf(5f, 9f, 13f, 8f, 5f)
            2 -> floatArrayOf(4f, 10f, 14f, 9f, 4f)
            3 -> floatArrayOf(3f, 7f, 11f, 7f, 3f)
            4 -> floatArrayOf(7f, 12f, 9f, 12f, 7f)
            5 -> floatArrayOf(5f, 10f, 13f, 10f, 5f)
            6 -> floatArrayOf(4f, 8f, 11f, 8f, 4f)
            else -> floatArrayOf(5f, 11f, 6f, 13f, 8f)
        }
        for (i in heights.indices) {
            val x = cx + (i - 2) * ink.dp(4.2f)
            val half = ink.dp(heights[i] * 0.5f)
            ink.fill.color = if (i % 2 == 0) secondary else accent
            canvas.drawRoundRect(x - ink.dp(1f), cy - half, x + ink.dp(1f), cy + half,
                ink.dp(1f), ink.dp(1f), ink.fill)
        }
    }

    private fun drawFeatures(canvas: Canvas) {
        val top = card.top + ink.dp(8f)
        features.forEachIndexed { i, feature ->
            val cx = card.left + cellWidth() * (i % columns + 0.5f)
            val cy = top + cellHeight() * (i / columns + 0.5f)
            val color = if (feature.on) palette.brand else palette.text
            ink.icon(canvas, feature.icon, cx, cy - ink.dp(12f), 30f, color)
            if (feature.on) drawBadge(canvas, cx + ink.dp(18f), cy - ink.dp(26f))
            val label = if (feature.on) palette.brand else palette.gloss
            ink.text(canvas, feature.label, 13f, label, cx, cy + ink.dp(20f), maxWidth = cellWidth() - ink.dp(4f))
        }
    }

    private fun drawBadge(canvas: Canvas, cx: Float, cy: Float) {
        ink.fill.color = palette.brand
        canvas.drawCircle(cx, cy, ink.dp(7.5f), ink.fill)
        ink.icon(canvas, Icon.CHECK, cx, cy, 13f, palette.onBrand)
    }

    private fun rowHeight() = card.height() / toggles.size

    private fun drawToggles(canvas: Canvas) {
        toggles.forEachIndexed { i, toggle ->
            val top = card.top + rowHeight() * i
            val cy = top + rowHeight() / 2
            val left = card.left + ink.dp(20f)
            if (toggle.note.isEmpty()) {
                drawLeft(canvas, toggle.label, 16f, palette.text, left, cy)
            } else {
                drawLeft(canvas, toggle.label, 16f, palette.text, left, cy - ink.dp(10f))
                drawLeft(canvas, toggle.note, 12f, palette.gloss, left, cy + ink.dp(12f))
            }
            drawSwitch(canvas, card.right - ink.dp(44f), cy, toggle.on)
            if (i > 0) ink.line(canvas, left, top, card.right - ink.dp(20f), top, palette.faint)
        }
    }

    private fun drawLeft(canvas: Canvas, value: String, sp: Float, color: Int, left: Float, cy: Float) {
        val width = ink.measure(value, sp)
        ink.text(canvas, value, sp, color, left + width / 2, cy)
    }

    private fun drawSwitch(canvas: Canvas, cx: Float, cy: Float, on: Boolean) {
        knob.set(cx - ink.dp(22f), cy - ink.dp(13f), cx + ink.dp(22f), cy + ink.dp(13f))
        ink.round(canvas, knob, 13f, if (on) palette.brand else palette.functionalKey)
        val kx = if (on) cx + ink.dp(9f) else cx - ink.dp(9f)
        ink.fill.color = palette.surface
        canvas.drawCircle(kx, cy, ink.dp(10f), ink.fill)
    }

    override fun onTap(x: Float, y: Float) {
        when {
            isRightIcon(x, y) -> actions.openSettings()
            y < headerHeight -> if (!voiceEffectPage && !backgroundEffectPage) tabs.indexAt(x)?.let {
                tabs.selected = it
                lastTab = it
                voiceEffectPage = false
            }

            !card.contains(x, y) -> Unit
            tabs.selected == 0 && backgroundEffectPage -> {
                val controlTop = card.bottom - ink.dp(76f)
                val prefs = context.getSharedPreferences("dynamic_background", Context.MODE_PRIVATE)
                if (y >= controlTop + ink.dp(44f)) {
                    prefs.edit().putBoolean("keyboard_key_frosted", !prefs.getBoolean("keyboard_key_frosted", false)).apply()
                    context.sendBroadcast(Intent(app.qingjian.inputmethod.background.DynamicBackgroundActivity.ACTION_SETTINGS_CHANGED).setPackage(context.packageName))
                    invalidate()
                    return
                }
                if (y >= controlTop && y < controlTop + ink.dp(44f)) {
                    val value = ((x - card.left - ink.dp(14f)) / (card.width() - ink.dp(28f)).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    prefs.edit().putInt("keyboard_key_alpha", (value * 100).toInt()).apply()
                    context.sendBroadcast(Intent(app.qingjian.inputmethod.background.DynamicBackgroundActivity.ACTION_SETTINGS_CHANGED).setPackage(context.packageName))
                    invalidate()
                    return
                }
                val rowTop = card.top + ink.dp(10f + 26f)
                val rowStep = ink.dp(74f)
                val viewportBottom = card.bottom - ink.dp(92f)
                val gapX = ink.dp(8f)
                val cardWidth = (card.width() - ink.dp(20f) - gapX) / 2f
                if (y in rowTop..viewportBottom) {
                    val column = if (x < card.left + ink.dp(10f) + cardWidth) 0 else 1
                    val row = ((y + effectScroll.offset - rowTop) / rowStep).toInt()
                    val index = row * 2 + column
                    if (row in 0 until 4 && index in 0..6) {
                        if (selectedBackgroundStyle == index && backgroundEffectEnabled) {
                            selectedBackgroundStyle = -1
                            backgroundEffectEnabled = false
                            actions.setToggle(Settings.KEY_BACKGROUND_EFFECT, false)
                        } else {
                            selectedBackgroundStyle = index
                            backgroundEffectEnabled = true
                            actions.setChoice(Settings.KEY_BACKGROUND_STYLE, index.toString())
                            actions.setToggle(Settings.KEY_BACKGROUND_EFFECT, true)
                        }
                        invalidate()
                    }
                }
            }
            tabs.selected == 0 && voiceEffectPage -> {
                val rowTop = card.top + ink.dp(8f + 34f)
                val rowStep = ink.dp(64f)
                val viewportBottom = card.bottom - ink.dp(92f)
                val gapX = ink.dp(7f)
                val cardWidth = (card.width() - ink.dp(20f) - gapX) / 2f
                if (y in rowTop..viewportBottom) {
                    val column = if (x < card.left + ink.dp(10f) + cardWidth) 0 else 1
                    val row = ((y + effectScroll.offset - rowTop) / rowStep).toInt()
                    val index = row * 2 + column
                    if (row in 0 until 5 && index in VoicePanel.ANIMATION_STYLE_LABELS.indices) {
                        selectedVoiceStyle = if (index == 8) -1 else index
                        context.getSharedPreferences("voice", Context.MODE_PRIVATE).edit()
                            .putInt(VoicePanel.PREF_STYLE, selectedVoiceStyle).apply()
                    }
                }
            }
            tabs.selected == 0 -> {
                val row = ((y - card.top - ink.dp(8f)) / cellHeight()).toInt()
                val column = ((x - card.left) / cellWidth()).toInt()
                features.getOrNull(row * columns + column)?.run?.invoke(actions)
            }

            else -> toggles.getOrNull(((y - card.top) / rowHeight()).toInt())?.let {
                actions.setToggle(it.key, !it.on)
            }
        }
    }

    override fun onBack() {
        if (voiceEffectPage || backgroundEffectPage) {
            voiceEffectPage = false
            backgroundEffectPage = false
            invalidate()
        } else {
            super.onBack()
        }
    }

    private companion object {
        /** 上次停在哪一页；改开关会重建键盘，回来时还在这页。 */
        var lastTab = 0
    }
}
