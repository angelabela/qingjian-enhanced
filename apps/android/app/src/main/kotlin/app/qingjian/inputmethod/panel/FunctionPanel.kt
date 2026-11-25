package app.qingjian.inputmethod.panel

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import app.qingjian.inputmethod.mascot.FloatingMascotService
import app.qingjian.inputmethod.mascot.MascotPermissionActivity
import app.qingjian.inputmethod.candidates.CloudSuggestionsView
import app.qingjian.inputmethod.background.DynamicBackgroundActivity
import app.qingjian.inputmethod.scan.BarcodeScannerActivity
import app.qingjian.inputmethod.sms.SmsCodeSettingsActivity
import app.qingjian.inputmethod.sms.SmsCodeReceiver
import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import kotlin.math.roundToInt
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
    private var textEffectPage = false
    private var aiLoadingEffectPage = false
    private var selectedAiLoadingStyle = Settings.preferences(context).getInt(Settings.KEY_AI_LOADING_EFFECT, 0).coerceIn(0, 15)
    /** 0 候选区，1 键盘区；两个范围分别保存预设。 */
    private var selectedTextTarget = 0
    private var selectedTextPreset = Settings.TextEffectPreset.of(
        Settings.preferences(context).getString(Settings.KEY_CANDIDATE_TEXT_PRESET, null).let { it }
    )
    private var selectedCandidateFontSize = Settings.preferences(context)
        .getInt(Settings.KEY_CANDIDATE_FONT_SIZE, 20).coerceIn(1, 50)
    private var selectedFontWeight = Settings.FontWeight.of(
        Settings.preferences(context).getString(Settings.KEY_FONT_WEIGHT, null))
    private var adjustingCandidateFontSize = false
    private var adjustingTextSlider = -1
    private var selectedVoiceStyle = context.getSharedPreferences("voice", Context.MODE_PRIVATE).getInt(VoicePanel.PREF_STYLE, VoicePanel.ANIMATION_DISABLED)
    private var voiceGlassStrength = context.getSharedPreferences("voice", Context.MODE_PRIVATE)
        .getInt(VoicePanel.PREF_GLASS_STRENGTH, VoicePanel.DEFAULT_GLASS_STRENGTH).coerceIn(1, 50)
    private var selectedVoiceSpectrumGradient = context.getSharedPreferences("voice", Context.MODE_PRIVATE)
        .getInt(VoicePanel.PREF_SPECTRUM_GRADIENT, 0).coerceIn(0, 5)
    private var adjustingVoiceGlass = false

    private fun textEffectsActive(): Boolean {
        val prefs = Settings.preferences(context)
        return Settings.TextEffectPreset.of(prefs.getString(Settings.KEY_CANDIDATE_TEXT_PRESET, null)) != Settings.TextEffectPreset.STANDARD ||
            Settings.TextEffectPreset.of(prefs.getString(Settings.KEY_KEYBOARD_TEXT_PRESET, null)) != Settings.TextEffectPreset.STANDARD ||
            prefs.getInt(Settings.KEY_CANDIDATE_FONT_SIZE, 20) != 20 ||
            prefs.getInt(Settings.KEY_KEY_FONT_SIZE, 20) != 20 ||
            prefs.getInt(Settings.KEY_CANDIDATE_TEXT_COLOR, 0) != 0 ||
            prefs.getInt(Settings.KEY_KEYBOARD_TEXT_COLOR, 0) != 0 ||
            Settings.FontWeight.of(prefs.getString(Settings.KEY_CANDIDATE_FONT_WEIGHT, prefs.getString(Settings.KEY_FONT_WEIGHT, null))) != Settings.FontWeight.NORMAL ||
            Settings.FontWeight.of(prefs.getString(Settings.KEY_KEYBOARD_FONT_WEIGHT, prefs.getString(Settings.KEY_FONT_WEIGHT, null))) != Settings.FontWeight.NORMAL
    }

    private fun keyEffectsActive(): Boolean {
        val prefs = context.getSharedPreferences("dynamic_background", Context.MODE_PRIVATE)
        return prefs.getInt("keyboard_key_alpha", 100) < 100 ||
            prefs.getBoolean("keyboard_key_frosted", false) ||
            prefs.getBoolean("keyboard_key_marquee", false) ||
            prefs.getBoolean("keyboard_key_edge_glow", false) ||
            prefs.getBoolean("keyboard_key_3d", false) ||
            prefs.getInt("keyboard_key_border_index", 0) != 0 ||
            prefs.getInt("keyboard_key_background_index", 0) != 0
    }

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
                Feature(
                    Icon.WAVEFORM,
                    "悬浮小精灵",
                    on = context.getSharedPreferences(FloatingMascotService.PREFS, Context.MODE_PRIVATE)
                        .getBoolean(FloatingMascotService.KEY_ENABLED, false),
                ) {
                    val prefs = context.getSharedPreferences(FloatingMascotService.PREFS, Context.MODE_PRIVATE)
                    val enabled = prefs.getBoolean(FloatingMascotService.KEY_ENABLED, false)
                    if (enabled) {
                        prefs.edit().putBoolean(FloatingMascotService.KEY_ENABLED, false).apply()
                        context.stopService(Intent(context, FloatingMascotService::class.java))
                        CloudSuggestionsView.hideAllFloatingTips()
                    } else if (android.provider.Settings.canDrawOverlays(context)) {
                        prefs.edit().putBoolean(FloatingMascotService.KEY_ENABLED, true).apply()
                        context.startForegroundService(Intent(context, FloatingMascotService::class.java))
                    } else {
                        context.startActivity(
                            Intent(context, MascotPermissionActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                    invalidate()
                },
                Feature(if (smsEnabled) Icon.LOCK else Icon.UNLOCK, "验证码", on = smsEnabled) {
                    context.startActivity(Intent(context, SmsCodeSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
                Feature(Icon.PALETTE, "文字效果", on = textEffectsActive()) {
                    val textPrefs = Settings.preferences(context)
                    selectedTextTarget = 0
                    selectedTextPreset = Settings.TextEffectPreset.of(
                        textPrefs.getString(Settings.KEY_CANDIDATE_TEXT_PRESET, null))
                    selectedCandidateFontSize = textPrefs.getInt(Settings.KEY_CANDIDATE_FONT_SIZE, 20).coerceIn(1, 50)
                    selectedFontWeight = Settings.FontWeight.of(textPrefs.getString(Settings.KEY_FONT_WEIGHT, null))
                    textEffectPage = true
                    voiceEffectPage = false
                    invalidate()
                },
                Feature(Icon.WAVEFORM, "AI等待特效", on = selectedAiLoadingStyle in 0..15) {
                    selectedAiLoadingStyle = Settings.preferences(context).getInt(Settings.KEY_AI_LOADING_EFFECT, 0).coerceIn(0, 15)
                    aiLoadingEffectPage = true
                    voiceEffectPage = false
                    textEffectPage = false
                    effectScroll.reset()
                    invalidate()
                },
                Feature(Icon.PALETTE, "背景特效") { openPanel(PanelKind.DYNAMIC_BACKGROUND) },
                Feature(Icon.TUNE, "按键效果", on = keyEffectsActive()) { openPanel(PanelKind.KEY_EFFECTS) },
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
                Feature(Icon.WAVEFORM, "语音动效", on = selectedVoiceStyle != VoicePanel.ANIMATION_DISABLED) {
                    selectedVoiceStyle = context.getSharedPreferences("voice", Context.MODE_PRIVATE)
                        .getInt(VoicePanel.PREF_STYLE, VoicePanel.ANIMATION_DISABLED)
                    voiceEffectPage = true
                    effectScroll.reset()
                    invalidate()
                },
                Feature(Icon.GLOBE, "切换输入法") { switchInputMethod() },
                Feature(Icon.SETTINGS, "设置") { openSettings() },
            ) + listOfNotNull(
                actions.gameKeyboard?.let { on -> Feature(Icon.GAMEPAD, "游戏键盘", on = on) { toggleGameKeyboard() } },
            )
        }

    fun refreshState() {
        val prefs = Settings.preferences(context)
        selectedAiLoadingStyle = prefs.getInt(Settings.KEY_AI_LOADING_EFFECT, 0).coerceIn(0, 15)
        selectedVoiceStyle = context.getSharedPreferences("voice", Context.MODE_PRIVATE)
            .getInt(VoicePanel.PREF_STYLE, VoicePanel.ANIMATION_DISABLED)
        selectedVoiceSpectrumGradient = context.getSharedPreferences("voice", Context.MODE_PRIVATE)
            .getInt(VoicePanel.PREF_SPECTRUM_GRADIENT, 0).coerceIn(0, 5)
        selectedCandidateFontSize = prefs.getInt(Settings.KEY_CANDIDATE_FONT_SIZE, 20).coerceIn(1, 50)
        selectedFontWeight = Settings.FontWeight.of(prefs.getString(Settings.KEY_FONT_WEIGHT, null))
        invalidate()
    }

    private val toggles
        get() = listOf(
            Toggle(Settings.KEY_HAPTIC, "按键震动", "", settings.haptic),
            Toggle(Settings.KEY_POPUP, "按键气泡", "按下字母时在上面放大显示", settings.keyPopup),
            Toggle(Settings.KEY_FULLSCREEN, "全面屏适配", "手势条那一截不留白，两个角各放一个键", settings.fullscreenAdapt),
            Toggle(Settings.KEY_KEEP_CHINESE, "换语言时保留中文", "开着时总在中文和选的语言之间切", settings.keepChinese),
            Toggle(Settings.KEY_VOICE_HOLD_SPACE, "长按空格说话", "按住空格键启动语音输入", settings.voiceHoldSpace),
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
        (voiceEffectPage || textEffectPage || aiLoadingEffectPage) &&
            x >= card.left && x <= card.right &&
            y >= card.top + ink.dp(38f) && y <= card.bottom - ink.dp(6f)
    }

    private val columns = 5

    override fun onDraw(canvas: Canvas) {
        drawHeader(canvas, if (voiceEffectPage || textEffectPage || aiLoadingEffectPage) null else tabs, Icon.SETTINGS)
        if (voiceEffectPage) {
            ink.text(canvas, "语音面板动效", 16f, palette.text, width * 0.5f, headerHeight * 0.5f)
        } else if (textEffectPage) {
            ink.text(canvas, "文字效果", 16f, palette.text, width * 0.5f, headerHeight * 0.5f)
        } else if (aiLoadingEffectPage) {
            ink.text(canvas, "AI等待特效", 16f, palette.text, width * 0.5f, headerHeight * 0.5f)
        }
        card.set(ink.dp(10f), headerHeight, width - ink.dp(10f), height - ink.dp(8f))
        ink.round(canvas, card, 16f, palette.surface)
        if (tabs.selected == 0) {
            if (voiceEffectPage) drawVoiceEffectSettings(canvas)
            else if (textEffectPage) drawTextEffectSettings(canvas)
            else if (aiLoadingEffectPage) drawAiLoadingEffectSettings(canvas)
            else drawFeatures(canvas)
        } else drawToggles(canvas)
    }

    override fun accessibilityElements(): List<AccessibilityElement> {
        if (width <= 0 || height <= 0 || voiceEffectPage || textEffectPage || aiLoadingEffectPage) return emptyList()
        return if (tabs.selected == 0) {
            val top = card.top + ink.dp(8f)
            features.mapIndexed { index, feature ->
                val row = index / columns
                val column = index % columns
                AccessibilityElement(
                    label = feature.label,
                    bounds = RectF(
                        card.left + cellWidth() * column,
                        top + cellHeight() * row,
                        card.left + cellWidth() * (column + 1),
                        top + cellHeight() * (row + 1),
                    ),
                    selected = feature.on,
                )
            }
        } else {
            toggles.mapIndexed { index, toggle ->
                val top = card.top + rowHeight() * index
                AccessibilityElement(
                    label = toggle.label + if (toggle.on) "，已开启" else "，已关闭",
                    bounds = RectF(card.left, top, card.right, top + rowHeight()),
                    selected = toggle.on,
                )
            }
        }
    }

    private fun cellWidth() = card.width() / columns

    private fun cellHeight() = minOf(ink.dp(86f), card.height() / maxOf(2, (features.size + columns - 1) / columns))

    private fun drawVoiceEffectSettings(canvas: Canvas) {
        val top = card.top + ink.dp(8f)
        ink.textStart(canvas, "再次点击已选效果可关闭；关闭后语音面板不显示动效", 11f, palette.gloss,
            card.left + ink.dp(16f), top + ink.dp(22f), card.right - ink.dp(12f))

        val labels = VoicePanel.ANIMATION_STYLE_LABELS
        val rows = (labels.size + 1) / 2
        val rowTop = top + ink.dp(34f)
        // 底部预留频谱渐变选择器与磨砂强度滑动条，样式列表独立滚动。
        val viewportBottom = card.bottom - ink.dp(136f)
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
            val selected = (if (selectedVoiceStyle == -1) VoicePanel.ANIMATION_RANDOM_INDEX else selectedVoiceStyle) == index

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

        // 频谱渐变颜色：默认彩虹 + 五套独立配色，点击后立即写入语音偏好设置。
        val gradientLabelY = card.bottom - ink.dp(126f)
        ink.textStart(canvas, "频谱渐变颜色", 10.5f, palette.gloss,
            card.left + ink.dp(14f), gradientLabelY, card.right - ink.dp(10f))
        val swatchLeft = card.left + ink.dp(12f)
        val swatchRight = card.right - ink.dp(12f)
        val swatchGap = ink.dp(4f)
        val swatchWidth = (swatchRight - swatchLeft - swatchGap * 5f) / 6f
        val swatchTop = card.bottom - ink.dp(119f)
        val swatchBottom = card.bottom - ink.dp(96f)
        PhysicsWaveView.SPECTRUM_GRADIENT_COLORS.forEachIndexed { index, stops ->
            val left = swatchLeft + index * (swatchWidth + swatchGap)
            val bounds = RectF(left, swatchTop, left + swatchWidth, swatchBottom)
            ink.fill.style = android.graphics.Paint.Style.STROKE
            ink.fill.strokeWidth = ink.dp(if (selectedVoiceSpectrumGradient == index) 1.5f else 0.55f)
            ink.fill.color = if (selectedVoiceSpectrumGradient == index) palette.brand else palette.faint
            canvas.drawRoundRect(bounds, ink.dp(5f), ink.dp(5f), ink.fill)
            ink.fill.style = android.graphics.Paint.Style.FILL
            val inset = ink.dp(2f)
            ink.fill.shader = android.graphics.LinearGradient(bounds.left + inset, 0f, bounds.right - inset, 0f,
                stops, null, android.graphics.Shader.TileMode.CLAMP)
            canvas.drawRoundRect(RectF(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset),
                ink.dp(4f), ink.dp(4f), ink.fill)
            ink.fill.shader = null
            ink.text(canvas, listOf("默认", "海洋", "日落", "极光", "霓虹", "冰晶")[index], 8.5f,
                if (selectedVoiceSpectrumGradient == index) palette.brand else palette.gloss,
                bounds.centerX(), card.bottom - ink.dp(85f))
        }

        // 固定在样式列表下方的磨砂强度滑动条（1–50），拖动时即时保存。
        val labelY = card.bottom - ink.dp(50f)
        ink.textStart(canvas, "语音面板磨砂强度", 12f, palette.text,
            card.left + ink.dp(16f), labelY, card.right - ink.dp(64f))
        ink.text(canvas, "$voiceGlassStrength/50", 11f, palette.gloss,
            card.right - ink.dp(32f), labelY)
        val left = card.left + ink.dp(18f)
        val right = card.right - ink.dp(18f)
        val y = card.bottom - ink.dp(22f)
        val track = RectF(left, y - ink.dp(2f), right, y + ink.dp(2f))
        ink.round(canvas, track, 2f, palette.faint)
        val progressX = left + (right - left) * ((voiceGlassStrength - 1) / 49f)
        val progress = RectF(left, y - ink.dp(2f), progressX, y + ink.dp(2f))
        ink.round(canvas, progress, 2f, palette.brand)
        ink.fill.color = palette.brand
        canvas.drawCircle(progressX, y, ink.dp(6f), ink.fill)
    }

    /** 迷你频谱预览使用当前主题色绘制，选中态不引入独立配色。 */
    private fun drawVoiceStylePreview(canvas: Canvas, bounds: RectF, index: Int, selected: Boolean) {
        val cx = bounds.left + ink.dp(22f)
        val cy = bounds.centerY()
        val accent = if (selected) palette.brand else palette.gloss
        val secondary = if (selected) palette.accent else palette.faint
        ink.fill.style = android.graphics.Paint.Style.FILL
        if (index == 10) {
            // 中心对称频谱
            val heights = floatArrayOf(3f, 6f, 10f, 7f, 4f)
            heights.forEachIndexed { i, height ->
                val x = cx + (i - 2) * ink.dp(4.2f)
                val half = ink.dp(height)
                ink.fill.color = if (i % 2 == 0) secondary else accent
                canvas.drawRoundRect(x - ink.dp(1f), cy - half, x + ink.dp(1f), cy + half,
                    ink.dp(1f), ink.dp(1f), ink.fill)
            }
            return
        }
        if (index == 11) {
            // 双层交错频谱
            val heights = floatArrayOf(5f, 9f, 13f, 8f, 5f)
            heights.forEachIndexed { i, height ->
                val x = cx + (i - 2) * ink.dp(4.2f)
                ink.fill.color = secondary
                ink.fill.alpha = 70
                canvas.drawRoundRect(x - ink.dp(1.6f), cy - ink.dp(height + 3f), x + ink.dp(1.6f), cy + ink.dp(height + 3f),
                    ink.dp(1.5f), ink.dp(1.5f), ink.fill)
                ink.fill.color = accent
                ink.fill.alpha = 255
                canvas.drawRoundRect(x - ink.dp(1f), cy - ink.dp(height), x + ink.dp(1f), cy + ink.dp(height),
                    ink.dp(1f), ink.dp(1f), ink.fill)
            }
            return
        }
        if (index == 12) {
            // 粒子矩阵
            for (column in 0..6) for (row in 0..4) {
                ink.fill.color = if ((column + row) % 3 == 0) accent else secondary
                ink.fill.alpha = if (row <= (column % 4) + 1) 230 else 38
                canvas.drawCircle(cx - ink.dp(10f) + column * ink.dp(3.2f),
                    cy + ink.dp(8f) - row * ink.dp(4f), ink.dp(0.9f), ink.fill)
            }
            ink.fill.alpha = 255
            return
        }
        if (index == 13) {
            // 阶梯频谱
            val heights = intArrayOf(2, 4, 6, 5, 3)
            for (i in heights.indices) for (step in 0 until heights[i]) {
                ink.fill.color = if (step % 2 == 0) accent else secondary
                val x = cx + (i - 2) * ink.dp(4.2f)
                val y = cy + ink.dp(10f) - step * ink.dp(3f)
                canvas.drawRoundRect(x - ink.dp(1f), y - ink.dp(2f), x + ink.dp(1f), y,
                    ink.dp(0.8f), ink.dp(0.8f), ink.fill)
            }
            return
        }
        if (index == 14) {
            // 透视空间隧道
            ink.fill.style = android.graphics.Paint.Style.STROKE
            for (i in 0..3) {
                val inset = ink.dp(i * 3f)
                ink.fill.color = if (i % 2 == 0) accent else secondary
                ink.fill.alpha = 210 - i * 35
                canvas.drawRoundRect(RectF(cx - ink.dp(12f) + inset, cy - ink.dp(11f) + inset,
                    cx + ink.dp(12f) - inset, cy + ink.dp(11f) - inset),
                    ink.dp(2f), ink.dp(2f), ink.fill)
            }
            ink.fill.alpha = 255
            ink.fill.style = android.graphics.Paint.Style.FILL
            return
        }
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
        if (index == 8 || index == 9) {
            val wave = android.graphics.Path()
            for (step in 0..24) {
                val u = step / 24f
                val x = bounds.left + ink.dp(8f) + u * ink.dp(28f)
                val cycles = if (index == 8) 2.5 else 3.2
                val y = cy + kotlin.math.sin(u * Math.PI * cycles).toFloat() *
                    ink.dp(if (index == 8) 5.5f else 3.8f)
                if (step == 0) wave.moveTo(x, y) else wave.lineTo(x, y)
            }
            ink.fill.style = android.graphics.Paint.Style.STROKE
            ink.fill.strokeCap = android.graphics.Paint.Cap.ROUND
            ink.fill.strokeJoin = android.graphics.Paint.Join.ROUND
            ink.fill.strokeWidth = ink.dp(2.8f)
            ink.fill.color = secondary
            ink.fill.alpha = 80
            canvas.drawPath(wave, ink.fill)
            ink.fill.strokeWidth = ink.dp(1.2f)
            ink.fill.color = accent
            ink.fill.alpha = 255
            canvas.drawPath(wave, ink.fill)
            if (index == 9) {
                for (i in 0..4) {
                    val x = cx - ink.dp(9f) + i * ink.dp(4.5f)
                    val barH = ink.dp(floatArrayOf(3f, 7f, 11f, 6f, 4f)[i])
                    ink.fill.strokeWidth = ink.dp(1.4f)
                    canvas.drawLine(x, cy + ink.dp(12f) - barH, x, cy + ink.dp(12f) + barH, ink.fill)
                }
            }
            ink.fill.style = android.graphics.Paint.Style.FILL
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


    /** 六套文字效果预设；候选区与键盘区独立存储，切换范围不会覆盖另一边。 */
    private fun textLuminance(color: Int): Float {
        fun channel(value: Int): Float {
            val c = value / 255f
            return if (c <= 0.04045f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        }
        return 0.2126f * channel(android.graphics.Color.red(color)) +
            0.7152f * channel(android.graphics.Color.green(color)) +
            0.0722f * channel(android.graphics.Color.blue(color))
    }

    private fun textColorKey() = if (selectedTextTarget == 0) Settings.KEY_CANDIDATE_TEXT_COLOR else Settings.KEY_KEYBOARD_TEXT_COLOR
    private fun textSizeKey() = if (selectedTextTarget == 0) Settings.KEY_CANDIDATE_FONT_SIZE else Settings.KEY_KEY_FONT_SIZE
    private fun textWeightKey() = if (selectedTextTarget == 0) Settings.KEY_CANDIDATE_FONT_WEIGHT else Settings.KEY_KEYBOARD_FONT_WEIGHT
    private fun currentTextColor(): Int = Settings.preferences(context).getInt(textColorKey(), 0).takeIf { it != 0 } ?: palette.text
    private fun currentTextSize(): Int = Settings.preferences(context).getInt(textSizeKey(),
        if (selectedTextTarget == 0) selectedCandidateFontSize else Settings.preferences(context).getInt(Settings.KEY_KEY_FONT_SIZE, 20)).coerceIn(1, 50)
    private fun currentTextWeight(): Settings.FontWeight = Settings.FontWeight.of(
        Settings.preferences(context).getString(textWeightKey(), Settings.preferences(context).getString(Settings.KEY_FONT_WEIGHT, null)))

    private fun pairedTextKey(key: String): String? = when (key) {
        Settings.KEY_CANDIDATE_TEXT_COLOR -> Settings.KEY_KEYBOARD_TEXT_COLOR
        Settings.KEY_KEYBOARD_TEXT_COLOR -> Settings.KEY_CANDIDATE_TEXT_COLOR
        Settings.KEY_CANDIDATE_FONT_SIZE -> Settings.KEY_KEY_FONT_SIZE
        Settings.KEY_KEY_FONT_SIZE -> Settings.KEY_CANDIDATE_FONT_SIZE
        Settings.KEY_CANDIDATE_FONT_WEIGHT -> Settings.KEY_KEYBOARD_FONT_WEIGHT
        Settings.KEY_KEYBOARD_FONT_WEIGHT -> Settings.KEY_CANDIDATE_FONT_WEIGHT
        Settings.KEY_CANDIDATE_TEXT_PRESET -> Settings.KEY_KEYBOARD_TEXT_PRESET
        Settings.KEY_KEYBOARD_TEXT_PRESET -> Settings.KEY_CANDIDATE_TEXT_PRESET
        else -> null
    }

    private fun syncTextEffectsEnabled(): Boolean =
        Settings.preferences(context).getBoolean("text_effect_sync_targets", false)

    private fun setTextInt(key: String, value: Int) {
        actions.setIntChoice(key, value)
        if (syncTextEffectsEnabled()) pairedTextKey(key)?.let { actions.setIntChoice(it, value) }
    }

    private fun setTextChoice(key: String, value: String) {
        actions.setChoice(key, value)
        if (syncTextEffectsEnabled()) pairedTextKey(key)?.let { actions.setChoice(it, value) }
    }

    private fun drawTextEffectSettings(canvas: Canvas) {
        val viewportTop = card.top + ink.dp(6f)
        val viewportBottom = card.bottom - ink.dp(6f)
        val targetTopForScroll = card.top + ink.dp(8f + 28f)
        val presetRowTop = targetTopForScroll + ink.dp(254f)
        val rowStepForScroll = ink.dp(82f)
        val contentBottom = presetRowTop + ((Settings.TextEffectPreset.entries.size + 1) / 2) * rowStepForScroll
        effectScroll.max = (contentBottom - viewportBottom).coerceAtLeast(0f)
        canvas.save()
        canvas.clipRect(card.left, viewportTop, card.right, viewportBottom)
        canvas.translate(0f, -effectScroll.offset);
        val top = card.top + ink.dp(8f)
        ink.textStart(canvas, "分别调节候选区与键盘区的字体颜色、字号和粗细", 11f, palette.gloss,
            card.left + ink.dp(14f), top + ink.dp(18f), card.right - ink.dp(12f))
        val targetTop = top + ink.dp(28f)
        val gap = ink.dp(8f)
        val targetWidth = (card.width() - ink.dp(28f) - gap) / 2f
        listOf("候选区", "键盘区").forEachIndexed { index, label ->
            val bounds = RectF(card.left + ink.dp(14f) + index * (targetWidth + gap), targetTop,
                card.left + ink.dp(14f) + index * (targetWidth + gap) + targetWidth, targetTop + ink.dp(36f))
            val selected = selectedTextTarget == index
            ink.round(canvas, bounds, 10f, if (selected) palette.brand else palette.functionalKey)
            ink.text(canvas, label, 12f, if (selected) palette.onBrand else palette.text, bounds.centerX(), bounds.centerY())
        }
        val controlsTop = targetTop + ink.dp(48f)
        val left = card.left + ink.dp(14f)
        val right = card.right - ink.dp(14f)
        val textColor = currentTextColor()
        val textSize = currentTextSize()
        val weight = currentTextWeight()

        // RGB 色带：触摸时按色相连续取色，颜色即时写入当前区域。
        ink.textStart(canvas, "字体颜色 RGB", 11.5f, palette.text,
            card.left + ink.dp(14f), controlsTop + ink.dp(18f), card.right - ink.dp(112f))
        // 恢复按钮单独留出上下空间，不再与标题和色带挤在一起。
        val restoreThemeRect = RectF(card.right - ink.dp(98f), controlsTop + ink.dp(4f),
            card.right - ink.dp(10f), controlsTop + ink.dp(32f))
        ink.round(canvas, restoreThemeRect, 9f, palette.functionalKey)
        ink.text(canvas, "恢复主题色", 9.5f, palette.text, restoreThemeRect.centerX(), restoreThemeRect.centerY())
        val colorY = controlsTop + ink.dp(49f)
        val colorRect = RectF(left, colorY - ink.dp(6f), right, colorY + ink.dp(6f))
        val rainbow = intArrayOf(0xFFFF3030.toInt(), 0xFFFFD430.toInt(), 0xFF55E85A.toInt(),
            0xFF25DDE8.toInt(), 0xFF397BFF.toInt(), 0xFFB34DFF.toInt(), 0xFFFF3030.toInt())
        ink.fill.shader = android.graphics.LinearGradient(colorRect.left, 0f, colorRect.right, 0f,
            rainbow, null, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRoundRect(colorRect, ink.dp(6f), ink.dp(6f), ink.fill)
        ink.fill.shader = null
        ink.fill.style = android.graphics.Paint.Style.STROKE
        ink.fill.strokeWidth = ink.dp(1f)
        ink.fill.color = palette.text
        canvas.drawRoundRect(colorRect, ink.dp(6f), ink.dp(6f), ink.fill)
        ink.fill.style = android.graphics.Paint.Style.FILL
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(textColor, hsv)
        val colorMarker = left + (right - left) * (hsv[0] / 360f)
        ink.fill.color = android.graphics.Color.WHITE
        canvas.drawCircle(colorMarker, colorY, ink.dp(7f), ink.fill)
        ink.fill.color = textColor
        canvas.drawCircle(colorMarker, colorY, ink.dp(4.5f), ink.fill)

        ink.textStart(canvas, "字体字号", 11.5f, palette.text,
            card.left + ink.dp(14f), controlsTop + ink.dp(83f), card.right - ink.dp(64f))
        ink.text(canvas, "${textSize}sp", 11f, palette.brand, card.right - ink.dp(28f), controlsTop + ink.dp(83f))
        val sizeY = controlsTop + ink.dp(101f)
        ink.fill.color = palette.functionalKey
        ink.fill.strokeWidth = ink.dp(4f)
        ink.fill.strokeCap = android.graphics.Paint.Cap.ROUND
        canvas.drawLine(left, sizeY, right, sizeY, ink.fill)
        val sizeX = left + (right - left) * ((textSize - 1) / 49f)
        ink.fill.color = palette.brand
        canvas.drawLine(left, sizeY, sizeX, sizeY, ink.fill)
        canvas.drawCircle(sizeX, sizeY, ink.dp(5f), ink.fill)

        ink.textStart(canvas, "字体粗细", 11.5f, palette.text,
            card.left + ink.dp(14f), controlsTop + ink.dp(133f), card.right - ink.dp(64f))
        ink.text(canvas, "${weight.label} · ${weight.weight}", 11f, palette.brand, card.right - ink.dp(28f), controlsTop + ink.dp(133f))
        val weightY = controlsTop + ink.dp(151f)
        canvas.drawLine(left, weightY, right, weightY, ink.fill.apply { color = palette.functionalKey; strokeWidth = ink.dp(4f); strokeCap = android.graphics.Paint.Cap.ROUND })
        val weightIndex = Settings.FontWeight.entries.indexOf(weight).coerceAtLeast(0)
        val weightX = left + (right - left) * (weightIndex.toFloat() / (Settings.FontWeight.entries.size - 1).coerceAtLeast(1))
        ink.fill.color = palette.brand
        canvas.drawLine(left, weightY, weightX, weightY, ink.fill)
        canvas.drawCircle(weightX, weightY, ink.dp(5f), ink.fill)

        val labels = Settings.TextEffectPreset.entries
        val syncY = controlsTop + ink.dp(184f)
        ink.textStart(canvas, "按键区与候选区同步调节", 11.5f, palette.text,
            card.left + ink.dp(14f), syncY + ink.dp(4f), card.right - ink.dp(70f))
        drawSwitch(canvas, card.right - ink.dp(38f), syncY, syncTextEffectsEnabled())

        val rowTop = targetTop + ink.dp(254f)
        val rowStep = ink.dp(82f)
        val itemGap = ink.dp(8f)
        val itemWidth = (card.width() - ink.dp(24f) - itemGap) / 2f
        labels.forEachIndexed { index, preset ->
            val column = index % 2
            val row = index / 2
            val itemLeft = card.left + ink.dp(12f) + column * (itemWidth + itemGap)
            val bounds = RectF(itemLeft, rowTop + row * rowStep,
                itemLeft + itemWidth, rowTop + row * rowStep + ink.dp(74f))
            val selected = selectedTextPreset == preset
            ink.round(canvas, bounds, 11f, if (selected) palette.functionalKey else palette.surface)
            ink.fill.style = android.graphics.Paint.Style.STROKE
            ink.fill.strokeWidth = ink.dp(if (selected) 1.3f else 0.6f)
            ink.fill.color = if (selected) palette.brand else palette.faint
            canvas.drawRoundRect(bounds, 11f, 11f, ink.fill)
            ink.fill.style = android.graphics.Paint.Style.FILL
            val sample = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                this.textSize = ink.dp((24f * textSize / 20f).coerceIn(12f, 42f))
                textAlign = android.graphics.Paint.Align.CENTER
                color = textColor
                typeface = weight.typeface
                isFakeBoldText = weight.weight >= 600
            }
            val sampleX = bounds.centerX()
            val sampleY = bounds.top + ink.dp(35f)
            when (preset) {
                Settings.TextEffectPreset.BOLD_DEPTH -> { sample.isFakeBoldText = true; sample.setShadowLayer(1.2f * ink.dp(1f), 0f, ink.dp(1f), 0x66000000) }
                Settings.TextEffectPreset.SOFT_GLOW -> sample.setShadowLayer(ink.dp(4f), 0f, 0f, textColor)
                Settings.TextEffectPreset.NEON_OUTLINE -> { sample.style = android.graphics.Paint.Style.STROKE; sample.strokeWidth = ink.dp(1f); sample.color = textColor }
                Settings.TextEffectPreset.CONTRAST_OUTLINE -> {
                    sample.style = android.graphics.Paint.Style.STROKE
                    sample.strokeWidth = ink.dp(1.4f)
                    sample.color = if (textLuminance(textColor) > 0.5f) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                }
                Settings.TextEffectPreset.COLOR_GRADIENT -> sample.shader = android.graphics.LinearGradient(sampleX - ink.dp(25f), 0f, sampleX + ink.dp(25f), 0f,
                    intArrayOf(0xFF45D7FF.toInt(), 0xFF8B65FF.toInt(), 0xFFFF6FB5.toInt()), null, android.graphics.Shader.TileMode.CLAMP)
                else -> Unit
            }
            canvas.drawText("青简 Aa", sampleX, sampleY, sample)
            if (preset == Settings.TextEffectPreset.NEON_OUTLINE || preset == Settings.TextEffectPreset.CONTRAST_OUTLINE) {
                sample.style = android.graphics.Paint.Style.FILL
                sample.color = textColor
                canvas.drawText("青简 Aa", sampleX, sampleY, sample)
            }
            sample.clearShadowLayer()
            sample.shader = null
            ink.text(canvas, preset.label, 10.5f, if (selected) palette.brand else palette.text,
                bounds.centerX(), bounds.bottom - ink.dp(9f), maxWidth = itemWidth - ink.dp(10f))
            if (selected) {
                ink.fill.color = palette.brand
                canvas.drawCircle(bounds.right - ink.dp(8f), bounds.top + ink.dp(8f), ink.dp(3f), ink.fill)
            }
        }
        canvas.restore()
    }

    /** AI 等待提示特效选择；预览只画在面板卡片中，实际效果仅附着于输入框等待文字。 */
    private fun drawAiLoadingEffectSettings(canvas: Canvas) {
        val labels = listOf(
            "345 流光渐变文字", "霓虹彩色发光文字", "彩虹渐变动态文字", "简约彩色光点 + 文字", "渐变 + 光点 + 扫光组合",
            "单色呼吸文字", "银灰金属流光", "白色下划线扫光", "单色光点脉冲", "打字机光标闪烁",
            "极光呼吸光带", "星尘流光点阵", "液态霓虹脉冲", "全息扫描光束", "数字矩阵闪烁", "字符跳动弹跳文字",
        )
        val descriptions = listOf(
            "紫蓝粉平滑流动", "霓虹色与柔和彩色底光", "完整彩虹色带持续流动", "单色光点呼吸，文字清晰", "渐变文字叠加光点与扫光",
            "灰白亮度缓慢呼吸", "银灰明暗连续流动", "单色文字与移动下划线", "单色圆点柔和脉冲", "末尾光标明暗闪烁",
            "青绿蓝紫色雾状流动", "星点闪烁并沿文字掠过", "液态色带与明暗脉冲", "彩色高光逐字扫描", "绿色数字与矩阵脉冲", "字符依次上下弹跳，伴随柔和渐变",
        )
        val colors = listOf(
            intArrayOf(0xFF8B72FF.toInt(), 0xFF42B8F5.toInt(), 0xFFFF78B7.toInt()),
            intArrayOf(0xFF00E5FF.toInt(), 0xFFFF35C8.toInt(), 0xFFFFD54F.toInt()),
            intArrayOf(0xFFFF3B5C.toInt(), 0xFFFFA52F.toInt(), 0xFF45E879.toInt(), 0xFF36CFFF.toInt(), 0xFFD65BFF.toInt()),
            intArrayOf(palette.brand, palette.accent, palette.text),
            intArrayOf(0xFF8B72FF.toInt(), 0xFF42B8F5.toInt(), 0xFFFF78B7.toInt()),
            intArrayOf(0xFF737B84.toInt(), 0xFFE1E5E9.toInt(), 0xFF737B84.toInt()),
            intArrayOf(0xFF858585.toInt(), 0xFFF0F0F0.toInt(), 0xFF858585.toInt()),
            intArrayOf(0xFFB8BEC7.toInt(), 0xFFE9EDF1.toInt(), 0xFFB8BEC7.toInt()),
            intArrayOf(0xFF8A929A.toInt(), 0xFFF2F4F6.toInt(), 0xFF8A929A.toInt()),
            intArrayOf(0xFF7C858E.toInt(), 0xFFE7EBEF.toInt(), 0xFF7C858E.toInt()),
            intArrayOf(0xFF2DE2C5.toInt(), 0xFF3E8BFF.toInt(), 0xFF9B62FF.toInt()),
            intArrayOf(0xFFC7D7FF.toInt(), 0xFF8BA7FF.toInt(), 0xFFFFFFFF.toInt()),
            intArrayOf(0xFF00E5FF.toInt(), 0xFF8B5CFF.toInt(), 0xFFFF4FC8.toInt()),
            intArrayOf(0xFFF6E7FF.toInt(), 0xFF6DE7FF.toInt(), 0xFFA88BFF.toInt()),
            intArrayOf(0xFF49FF88.toInt(), 0xFFA8FFB9.toInt(), 0xFF2BCA70.toInt()),
            intArrayOf(0xFF38E8FF.toInt(), 0xFF8B72FF.toInt(), 0xFFFF78B7.toInt()),
        )
        ink.textStart(canvas, "仅改变 AI 一键生成时输入框内的等待提示", 11f, palette.gloss,
            card.left + ink.dp(14f), card.top + ink.dp(24f), card.right - ink.dp(12f))
        val rowTop = card.top + ink.dp(38f)
        val rowStep = ink.dp(68f)
        val gapX = ink.dp(7f)
        val itemHeight = ink.dp(60f)
        val itemWidth = (card.width() - ink.dp(20f) - gapX) / 2f
        effectScroll.max = (((labels.size + 1) / 2) * rowStep - (card.bottom - rowTop - ink.dp(6f))).coerceAtLeast(0f)
        canvas.save()
        canvas.clipRect(card.left, rowTop, card.right, card.bottom - ink.dp(6f))
        canvas.translate(0f, -effectScroll.offset)
        labels.forEachIndexed { index, label ->
            val col = index % 2
            val row = index / 2
            val left = card.left + ink.dp(10f) + col * (itemWidth + gapX)
            val top = rowTop + row * rowStep
            val bounds = RectF(left, top, left + itemWidth, top + itemHeight)
            val selected = selectedAiLoadingStyle == index
            ink.round(canvas, bounds, 10f, if (selected) palette.functionalKey else palette.surface)
            ink.fill.style = android.graphics.Paint.Style.STROKE
            ink.fill.strokeWidth = ink.dp(if (selected) 1.3f else 0.65f)
            ink.fill.color = if (selected) palette.brand else palette.faint
            canvas.drawRoundRect(bounds, ink.dp(10f), ink.dp(10f), ink.fill)
            ink.fill.style = android.graphics.Paint.Style.FILL
            val swatch = RectF(bounds.left + ink.dp(8f), bounds.top + ink.dp(9f),
                bounds.right - ink.dp(8f), bounds.top + ink.dp(29f))
            val stops = colors[index]
            ink.fill.shader = android.graphics.LinearGradient(swatch.left, 0f, swatch.right, 0f,
                stops, null, android.graphics.Shader.TileMode.CLAMP)
            canvas.drawRoundRect(swatch, ink.dp(6f), ink.dp(6f), ink.fill)
            ink.fill.shader = null
            ink.textStart(canvas, label, 10.5f, if (selected) palette.brand else palette.text,
                bounds.left + ink.dp(7f), bounds.top + ink.dp(43f), bounds.right - ink.dp(5f))
            ink.textStart(canvas, descriptions[index], 9f, palette.gloss,
                bounds.left + ink.dp(7f), bounds.top + ink.dp(55f), bounds.right - ink.dp(5f))
            if (selected) {
                ink.fill.color = palette.brand
                canvas.drawCircle(bounds.right - ink.dp(8f), bounds.top + ink.dp(8f), ink.dp(2.5f), ink.fill)
            }
        }
        canvas.restore()
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (textEffectPage && tabs.selected == 0) {
            val controlsTop = card.top + ink.dp(8f + 28f + 48f)
            val contentY = event.y + effectScroll.offset
            val slider = when {
                contentY in (controlsTop + ink.dp(34f))..(controlsTop + ink.dp(62f)) -> 0
                contentY in (controlsTop + ink.dp(87f))..(controlsTop + ink.dp(115f)) -> 1
                contentY in (controlsTop + ink.dp(137f))..(controlsTop + ink.dp(165f)) -> 2
                else -> -1
            }
            val sliderTrackY = when (slider) {
                0 -> controlsTop + ink.dp(49f)
                1 -> controlsTop + ink.dp(101f)
                2 -> controlsTop + ink.dp(151f)
                else -> -1f
            }
            val trackLeft = card.left + ink.dp(14f)
            val trackRight = card.right - ink.dp(14f)
            val nearTrack = slider >= 0 &&
                kotlin.math.abs(event.y - (sliderTrackY - effectScroll.offset)) <= ink.dp(9f) &&
                event.x in (trackLeft - ink.dp(4f))..(trackRight + ink.dp(4f))
            if (event.actionMasked == MotionEvent.ACTION_DOWN && card.contains(event.x, event.y) && nearTrack) {
                adjustingTextSlider = slider
                parent?.requestDisallowInterceptTouchEvent(true)
                updateTextSlider(event.x, slider)
                return true
            }
            if (adjustingTextSlider >= 0) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_MOVE -> updateTextSlider(event.x, adjustingTextSlider)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        updateTextSlider(event.x, adjustingTextSlider)
                        adjustingTextSlider = -1
                        parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
                return true
            }
        }
        if (voiceEffectPage && tabs.selected == 0) {
            val sliderTop = card.bottom - ink.dp(68f)
            if (event.actionMasked == MotionEvent.ACTION_DOWN &&
                card.contains(event.x, event.y) && event.y >= sliderTop
            ) {
                adjustingVoiceGlass = true
                updateVoiceGlassStrength(event.x)
                return true
            }
            if (adjustingVoiceGlass) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_MOVE -> updateVoiceGlassStrength(event.x)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        updateVoiceGlassStrength(event.x)
                        adjustingVoiceGlass = false
                    }
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateTextSlider(x: Float, slider: Int) {
        val left = card.left + ink.dp(14f)
        val right = card.right - ink.dp(14f)
        val fraction = ((x - left) / (right - left).coerceAtLeast(1f)).coerceIn(0f, 1f)
        when (slider) {
            0 -> {
                val color = android.graphics.Color.HSVToColor(floatArrayOf(fraction * 360f, 0.86f, 1f))
                setTextInt(textColorKey(), color)
            }
            1 -> setTextInt(textSizeKey(), (1 + fraction * 49f).roundToInt().coerceIn(1, 50))
            2 -> {
                val index = (fraction * (Settings.FontWeight.entries.size - 1)).roundToInt()
                setTextChoice(textWeightKey(), Settings.FontWeight.entries[index].name)
            }
        }
        invalidate()
    }

    private fun updateCandidateFontSize(x: Float) {
        val left = card.left + ink.dp(18f)
        val right = card.right - ink.dp(18f)
        val fraction = ((x - left) / (right - left).coerceAtLeast(1f)).coerceIn(0f, 1f)
        val value = (1 + fraction * 49f).roundToInt().coerceIn(1, 50)
        if (value == selectedCandidateFontSize) return
        selectedCandidateFontSize = value
        actions.setIntChoice(Settings.KEY_CANDIDATE_FONT_SIZE, value)
        invalidate()
    }

    private fun updateVoiceGlassStrength(x: Float) {
        val left = card.left + ink.dp(18f)
        val right = card.right - ink.dp(18f)
        val fraction = ((x - left) / (right - left).coerceAtLeast(1f)).coerceIn(0f, 1f)
        val value = (1 + fraction * 49f).roundToInt().coerceIn(1, 50)
        if (value == voiceGlassStrength) return
        voiceGlassStrength = value
        context.getSharedPreferences("voice", Context.MODE_PRIVATE).edit()
            .putInt(VoicePanel.PREF_GLASS_STRENGTH, value).apply()
        invalidate()
    }

    override fun onTap(x: Float, y: Float) {
        when {
            isRightIcon(x, y) -> actions.openSettings()
            y < headerHeight -> if (!voiceEffectPage && !textEffectPage && !aiLoadingEffectPage) tabs.indexAt(x)?.let {
                tabs.selected = it
                lastTab = it
                voiceEffectPage = false
            }

            !card.contains(x, y) -> Unit
            tabs.selected == 0 && aiLoadingEffectPage -> {
                val rowTop = card.top + ink.dp(38f)
                val rowStep = ink.dp(68f)
                val gapX = ink.dp(7f)
                val itemWidth = (card.width() - ink.dp(20f) - gapX) / 2f
                if (y >= rowTop - effectScroll.offset) {
                    val row = ((y + effectScroll.offset - rowTop) / rowStep).toInt()
                    val col = if (x < card.left + ink.dp(10f) + itemWidth) 0 else 1
                    val index = row * 2 + col
                    if (index in 0 until 16) {
                        selectedAiLoadingStyle = index
                        Settings.preferences(context).edit()
                            .putInt(Settings.KEY_AI_LOADING_EFFECT, index).apply()
                        invalidate()
                    }
                }
            }
            tabs.selected == 0 && textEffectPage -> {
                val contentY = y + effectScroll.offset
                val targetTop = card.top + ink.dp(8f + 28f)
                if (contentY in targetTop..(targetTop + ink.dp(36f))) {
                    selectedTextTarget = if (x < card.centerX()) 0 else 1
                    selectedTextPreset = Settings.TextEffectPreset.of(Settings.preferences(context).getString(
                        if (selectedTextTarget == 0) Settings.KEY_CANDIDATE_TEXT_PRESET else Settings.KEY_KEYBOARD_TEXT_PRESET, null))
                    selectedCandidateFontSize = Settings.preferences(context).getInt(Settings.KEY_CANDIDATE_FONT_SIZE, 20).coerceIn(1, 50)
                    effectScroll.reset()
                    invalidate()
                    return
                }
                val controlsTop = targetTop + ink.dp(48f)
                val restoreThemeRect = RectF(card.right - ink.dp(98f), controlsTop + ink.dp(4f),
                    card.right - ink.dp(10f), controlsTop + ink.dp(32f))
                if (restoreThemeRect.contains(x, contentY)) {
                    // 写入 0 表示不保存自定义色，绘制与输入时自动跟随当前主题色。
                    setTextInt(textColorKey(), 0)
                    invalidate()
                    return
                }
                val slider = when {
                    contentY in (controlsTop + ink.dp(34f))..(controlsTop + ink.dp(62f)) -> 0
                    contentY in (controlsTop + ink.dp(87f))..(controlsTop + ink.dp(115f)) -> 1
                    contentY in (controlsTop + ink.dp(137f))..(controlsTop + ink.dp(165f)) -> 2
                    else -> -1
                }
                if (slider >= 0) {
                    updateTextSlider(x, slider)
                    return
                }
                val syncY = controlsTop + ink.dp(184f)
                if (contentY in (syncY - ink.dp(16f))..(syncY + ink.dp(16f))) {
                    val enable = !syncTextEffectsEnabled()
                    Settings.preferences(context).edit().putBoolean("text_effect_sync_targets", enable).apply()
                    if (enable) {
                        val sourceKeys = listOf(textColorKey(), textSizeKey(), textWeightKey(),
                            if (selectedTextTarget == 0) Settings.KEY_CANDIDATE_TEXT_PRESET else Settings.KEY_KEYBOARD_TEXT_PRESET)
                        sourceKeys.forEach { key ->
                            val paired = pairedTextKey(key) ?: return@forEach
                            when (key) {
                                Settings.KEY_CANDIDATE_TEXT_COLOR, Settings.KEY_KEYBOARD_TEXT_COLOR,
                                Settings.KEY_CANDIDATE_FONT_SIZE, Settings.KEY_KEY_FONT_SIZE ->
                                    actions.setIntChoice(paired, Settings.preferences(context).getInt(key, 20))
                                else -> actions.setChoice(paired, Settings.preferences(context).getString(key, null)
                                    ?: if (key.contains("PRESET")) Settings.TextEffectPreset.STANDARD.name else Settings.FontWeight.NORMAL.name)
                            }
                        }
                    }
                    invalidate()
                    return
                }
                val rowTop = targetTop + ink.dp(254f)
                val rowStep = ink.dp(82f)
                val row = ((contentY - rowTop) / rowStep).toInt()
                val column = if (x < card.centerX()) 0 else 1
                val index = row * 2 + column
                val preset = Settings.TextEffectPreset.entries.getOrNull(index)
                if (preset != null && contentY >= rowTop &&
                    contentY < rowTop + ((Settings.TextEffectPreset.entries.size + 1) / 2) * rowStep &&
                    x in (card.left + ink.dp(12f))..(card.right - ink.dp(12f))) {
                    selectedTextPreset = preset
                    setTextChoice(if (selectedTextTarget == 0) Settings.KEY_CANDIDATE_TEXT_PRESET else Settings.KEY_KEYBOARD_TEXT_PRESET, preset.name)
                    invalidate()
                }
            }
            tabs.selected == 0 && voiceEffectPage -> {
                val swatchTop = card.bottom - ink.dp(119f)
                val swatchBottom = card.bottom - ink.dp(84f)
                if (y in swatchTop..swatchBottom) {
                    val left = card.left + ink.dp(12f)
                    val gap = ink.dp(4f)
                    val itemWidth = (card.width() - ink.dp(24f) - gap * 5f) / 6f
                    val index = ((x - left) / (itemWidth + gap)).toInt()
                    if (index in 0..5) {
                        selectedVoiceSpectrumGradient = index
                        context.getSharedPreferences("voice", Context.MODE_PRIVATE).edit()
                            .putInt(VoicePanel.PREF_SPECTRUM_GRADIENT, index).apply()
                        invalidate()
                    }
                    return
                }
                val rowTop = card.top + ink.dp(8f + 34f)
                val rowStep = ink.dp(64f)
                val viewportBottom = card.bottom - ink.dp(136f)
                val gapX = ink.dp(7f)
                val cardWidth = (card.width() - ink.dp(20f) - gapX) / 2f
                if (y in rowTop..viewportBottom) {
                    val column = if (x < card.left + ink.dp(10f) + cardWidth) 0 else 1
                    val row = ((y + effectScroll.offset - rowTop) / rowStep).toInt()
                    val index = row * 2 + column
                    if (row in 0 until ((VoicePanel.ANIMATION_STYLE_LABELS.size + 1) / 2) && index in VoicePanel.ANIMATION_STYLE_LABELS.indices) {
                        selectedVoiceStyle = when {
                            selectedVoiceStyle == VoicePanel.ANIMATION_DISABLED -> if (index == VoicePanel.ANIMATION_RANDOM_INDEX) -1 else index
                            (index == VoicePanel.ANIMATION_RANDOM_INDEX && selectedVoiceStyle == -1) || (index == selectedVoiceStyle) -> VoicePanel.ANIMATION_DISABLED
                            index == VoicePanel.ANIMATION_RANDOM_INDEX -> -1
                            else -> index
                        }
                        context.getSharedPreferences("voice", Context.MODE_PRIVATE).edit()
                            .putInt(VoicePanel.PREF_STYLE, selectedVoiceStyle).apply()
                        invalidate()
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
        if (voiceEffectPage || textEffectPage || aiLoadingEffectPage) {
            val leavingTextEffects = textEffectPage
            voiceEffectPage = false
            textEffectPage = false
            aiLoadingEffectPage = false
            if (leavingTextEffects) {
                effectScroll.reset()
                // 只刷新输入法绘制设置，不关闭整个功能面板。
                actions.refreshKeyboardSettings()
            }
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
