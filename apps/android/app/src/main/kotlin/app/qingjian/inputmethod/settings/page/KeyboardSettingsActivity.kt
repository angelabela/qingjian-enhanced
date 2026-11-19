package app.qingjian.inputmethod.settings.page

import android.content.Intent
import android.view.View
import app.qingjian.inputmethod.R
import app.qingjian.inputmethod.keyboard.HapticStrength
import app.qingjian.inputmethod.settings.BottomAction
import app.qingjian.inputmethod.settings.GameKeyboard
import app.qingjian.inputmethod.settings.Settings
import app.qingjian.inputmethod.theme.TextSize

/**
 * 键盘二级页：管键盘本身——尺寸（高度、全面屏适配与两个角）、按键（震动、键面字号、气泡、符号面板布局）、
 * 手势（滑行、上滑、空格挪光标、长按空格说话、换语言保留中文），另有游戏键盘一页。改了马上写设置，开着的键盘跟着重建。
 */
class KeyboardSettingsActivity : PageActivity() {
    override val title = R.string.page_keyboard

    override fun content(): List<View> {
        val settings = Settings.load(this)
        return listOf(
            ui.group(
                getString(R.string.section_size),
                listOf(
                    ui.slider(
                        R.string.height_title, null,
                        Settings.HEIGHT_MIN, Settings.HEIGHT_MAX, settings.heightPercent, step = 1,
                        format = { "$it%" },
                    ) { prefs.edit().putInt(Settings.KEY_HEIGHT, it).apply() },
                    ui.toggle(R.string.fullscreen_title, null, settings.fullscreenAdapt) {
                        prefs.edit().putBoolean(Settings.KEY_FULLSCREEN, it).apply()
                        rebuild()
                    },
                ) + bottomPickers(settings),
                getString(R.string.height_note) + "\n" + getString(R.string.fullscreen_note),
            ),
            ui.group(
                R.string.section_keys,
                hapticRow(settings),
                ui.slider(
                    R.string.key_text_size_title, null,
                    1, 50, settings.keyFontSize, step = 1,
                    format = { it.toString() + "sp" },
                ) {
                    prefs.edit().putInt(Settings.KEY_KEY_FONT_SIZE, it).apply()
                },
                ui.toggle(R.string.popup_title, R.string.popup_note, settings.keyPopup) {
                    prefs.edit().putBoolean(Settings.KEY_POPUP, it).apply()
                },
                ui.picker(
                    R.string.key_skin_title,
                    settings.keySkin.label,
                    Settings.KeySkin.entries.map { it.label },
                    Settings.KeySkin.entries.indexOf(settings.keySkin),
                ) { index ->
                    prefs.edit().putString(Settings.KEY_KEY_SKIN, Settings.KeySkin.entries[index].name).apply()
                    rebuild()
                },
                arrangementPicker(settings),
                footer = R.string.arrangement_note,
            ),
            ui.group(
                R.string.section_gestures,
                ui.toggle(R.string.swipe_input_title, R.string.swipe_input_note, settings.swipeInput) {
                    prefs.edit().putBoolean(Settings.KEY_SWIPE_INPUT, it).apply()
                },
                ui.toggle(R.string.flick_symbols_title, R.string.flick_symbols_note, settings.flickSymbols) {
                    prefs.edit().putBoolean(Settings.KEY_FLICK_SYMBOLS, it).apply()
                },
                ui.toggle(R.string.long_press_upper_title, R.string.long_press_upper_note, settings.longPressUpper) {
                    prefs.edit().putBoolean(Settings.KEY_LONG_PRESS_UPPER, it).apply()
                },
                ui.toggle(R.string.flick_clear_title, R.string.flick_clear_note, settings.flickClear) {
                    prefs.edit().putBoolean(Settings.KEY_FLICK_CLEAR, it).apply()
                },
                ui.toggle(R.string.space_cursor_title, R.string.space_cursor_note, settings.spaceCursor) {
                    prefs.edit().putBoolean(Settings.KEY_SPACE_CURSOR, it).apply()
                },
                ui.toggle(R.string.voice_hold_space_title, R.string.voice_hold_space_note, settings.voiceHoldSpace) {
                    prefs.edit().putBoolean(Settings.KEY_VOICE_HOLD_SPACE, it).apply()
                },
                ui.toggle(R.string.keep_chinese_title, R.string.keep_chinese_note, settings.keepChinese) {
                    prefs.edit().putBoolean(Settings.KEY_KEEP_CHINESE, it).apply()
                },
            ),
            ui.group(
                "自定义按键与手势",
                listOf(
                    customGestureKeyPicker(settings),
                    customGestureActionPicker(
                        "按键上滑", Settings.KEY_CUSTOM_GESTURE_UP,
                        displayedGestureAction(settings, effectiveGestureKey(settings), upward = true),
                        effectiveGestureKey(settings),
                    ),
                    customGestureActionPicker(
                        "按键下滑", Settings.KEY_CUSTOM_GESTURE_DOWN,
                        displayedGestureAction(settings, effectiveGestureKey(settings), upward = false),
                        effectiveGestureKey(settings),
                    ),
                ),
                footer = "先选择要配置的按键，再分别设置上滑和下滑动作。选择“沿用默认”不会覆盖该按键原有行为。",
            ),
            ui.group(
                null,
                ui.link(R.string.page_game_keyboard, getString(if (GameKeyboard.load(this).enabled) R.string.value_on else R.string.value_off)) {
                    startActivity(Intent(this, GameKeyboardActivity::class.java))
                },
            ),
        )
    }

    // 从游戏键盘页回来，右边的开关状态跟着变
    override fun onRestart() {
        super.onRestart()
        rebuild()
    }

    /** 震动开关与强度合成一个分段：关 / 轻 / 标准 / 重；关掉时强度保留，再打开回到原来的。 */
    private fun hapticRow(settings: Settings): View {
        val options = listOf(getString(R.string.value_off)) + HapticStrength.entries.map { getString(it.label) }
        val selected = if (settings.haptic) settings.hapticStrength.ordinal + 1 else 0
        return ui.segmentedRow(R.string.haptic_title, options, selected) { index ->
            val editor = prefs.edit().putBoolean(Settings.KEY_HAPTIC, index > 0)
            if (index > 0) editor.putString(Settings.KEY_HAPTIC_STRENGTH, HapticStrength.entries[index - 1].key)
            editor.apply()
            rebuild()
        }
    }

    private data class GestureKeyOption(val id: String, val label: String)

    private fun gestureKeyOptions(): List<GestureKeyOption> = buildList {
        add(GestureKeyOption("NONE", "不指定"))
        add(GestureKeyOption("SPACE", "空格键"))
        add(GestureKeyOption("ENTER", "回车键"))
        add(GestureKeyOption("BACKSPACE", "删除键"))
        add(GestureKeyOption("SYMBOLS", "符号键"))
        add(GestureKeyOption("SHIFT", "Shift 键"))
        add(GestureKeyOption("LANGUAGE", "中英文切换键"))
        ('A'..'Z').forEach { add(GestureKeyOption("LETTER_$it", "$it 键")) }
    }

    /** 已有固定按键手势时，默认把它作为当前配置对象显示，避免页面重开后落回“不指定”。 */
    private fun effectiveGestureKey(settings: Settings): String {
        if (settings.customGestureKey != "NONE") return settings.customGestureKey
        return listOf(
            "SPACE" to (settings.spaceSwipeUp to settings.spaceSwipeDown),
            "ENTER" to (settings.enterSwipeUp to settings.enterSwipeDown),
            "BACKSPACE" to (settings.backspaceSwipeUp to settings.backspaceSwipeDown),
            "SYMBOLS" to (settings.symbolsSwipeUp to settings.symbolsSwipeDown),
        ).firstOrNull { (_, actions) ->
            actions.first != Settings.GestureAction.DEFAULT || actions.second != Settings.GestureAction.DEFAULT
        }?.first ?: "NONE"
    }

    /** 设置栏显示所选按键真正生效的动作：自定义动作优先；沿用默认时回退到该键原有手势。 */
    private fun displayedGestureAction(settings: Settings, selectedKey: String, upward: Boolean): Settings.GestureAction {
        val custom = if (settings.customGestureKey == selectedKey) {
            if (upward) settings.customGestureUp else settings.customGestureDown
        } else {
            Settings.GestureAction.DEFAULT
        }
        if (custom != Settings.GestureAction.DEFAULT) return custom

        val builtIn = when (selectedKey) {
            "SPACE" -> if (upward) settings.spaceSwipeUp else settings.spaceSwipeDown
            "ENTER" -> if (upward) settings.enterSwipeUp else settings.enterSwipeDown
            "BACKSPACE" -> if (upward) settings.backspaceSwipeUp else settings.backspaceSwipeDown
            "SYMBOLS" -> if (upward) settings.symbolsSwipeUp else settings.symbolsSwipeDown
            else -> Settings.GestureAction.DEFAULT
        }
        if (builtIn != Settings.GestureAction.DEFAULT) return builtIn

        // 同步显示键盘实际提供的内置手势，避免设置栏显示“沿用默认”却看不到真实动作。
        return when {
            selectedKey == "BACKSPACE" && settings.flickClear && upward -> Settings.GestureAction.CLEAR_ALL
            selectedKey == "BACKSPACE" && settings.flickClear && !upward -> Settings.GestureAction.RESTORE_CLEAR
            selectedKey == "SPACE" && !upward -> Settings.GestureAction.OPEN_CLIPBOARD
            selectedKey.startsWith("LETTER_") && !upward -> when (selectedKey.removePrefix("LETTER_")) {
                "Z" -> Settings.GestureAction.SELECT_ALL
                "X" -> Settings.GestureAction.CUT
                "C" -> Settings.GestureAction.COPY
                "V" -> Settings.GestureAction.PASTE
                else -> Settings.GestureAction.DEFAULT
            }
            else -> Settings.GestureAction.DEFAULT
        }
    }

    private fun customGestureKeyPicker(settings: Settings): View {
        val options = gestureKeyOptions()
        val selectedKey = effectiveGestureKey(settings)
        val selected = options.indexOfFirst { it.id == selectedKey }.coerceAtLeast(0)
        return ui.picker("选择自定义按键", options[selected].label, options.map { it.label }, selected) { index ->
            prefs.edit().putString(Settings.KEY_CUSTOM_GESTURE_KEY, options[index].id).apply()
            rebuild()
        }
    }

    private fun customGestureActionPicker(
        title: String,
        key: String,
        current: Settings.GestureAction,
        selectedKey: String,
    ): View {
        val options = Settings.GestureAction.entries
        return ui.picker(title, current.label, options.map { it.label }, options.indexOf(current)) { index ->
            prefs.edit()
                .putString(Settings.KEY_CUSTOM_GESTURE_KEY, selectedKey)
                .putString(key, options[index].name)
                .apply()
            rebuild()
        }
    }

    /** 全面屏底栏左右两个角放什么；关着全面屏适配就不显示。 */
    private fun bottomPickers(settings: Settings): List<View> {
        if (!settings.fullscreenAdapt) return emptyList()
        return listOf(
            bottomPicker(R.string.bottom_left, Settings.KEY_BOTTOM_LEFT, settings.bottomLeft, Settings.KEY_BOTTOM_RIGHT, settings.bottomRight),
            bottomPicker(R.string.bottom_right, Settings.KEY_BOTTOM_RIGHT, settings.bottomRight, Settings.KEY_BOTTOM_LEFT, settings.bottomLeft),
        )
    }

    private fun bottomPicker(title: Int, key: String, current: BottomAction, otherKey: String, other: BottomAction): View {
        val options = BottomAction.entries
        return ui.picker(title, getString(current.label), options.map { getString(it.label) }, options.indexOf(current)) { index ->
            val picked = options[index]
            val editor = prefs.edit().putString(key, picked.name)
            // 两边不能一样：选了另一边正放着的，就把这边原来的换过去
            if (picked == other && picked != BottomAction.NONE) editor.putString(otherKey, current.name)
            editor.apply()
            rebuild()
        }
    }

    /** 符号面板的排法：默认一套不动，另一套改了几个键的位置（见 [Settings.Arrangement]），键盘功能面板里也能切。 */
    private fun arrangementPicker(settings: Settings): View {
        val names = listOf(R.string.arrangement_standard, R.string.arrangement_left_thumb).map(::getString)
        val selected = settings.arrangement.ordinal
        return ui.picker(R.string.arrangement_title, names[selected], names, selected) {
            prefs.edit().putString(Settings.KEY_ARRANGEMENT, Settings.Arrangement.entries[it].name).apply()
            rebuild()
        }
    }
}
