package app.qingjian.inputmethod.settings.page

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.qingjian.inputmethod.R
import app.qingjian.inputmethod.core.store.ThemeChoice
import app.qingjian.inputmethod.core.store.Themes
import app.qingjian.inputmethod.settings.Settings
import app.qingjian.inputmethod.settings.widget.ThemePreview
import app.qingjian.inputmethod.theme.Appearance
import app.qingjian.inputmethod.theme.Palette

/**
 * 外观二级页（对应 PC 设置程序的「外观」页）：当前主题的预览、深浅色与主题卡片。
 * 主题与键盘配色都来自 PC 的主题库，键盘、候选条、面板与浮动编辑条一起换。改了马上写设置，开着的键盘跟着重建。
 */
class AppearanceActivity : PageActivity() {
    override val title = R.string.page_appearance

    override fun content(): List<View> {
        val settings = Settings.load(this)
        val dark = settings.appearance.dark(this)
        return listOf(
            spaced(preview(settings, dark), 20),
            ui.group(
                null,
                ui.segmentedRow(R.string.section_appearance, Appearance.entries.map { getString(it.label) }, settings.appearance.ordinal) {
                    prefs.edit().putString(Settings.KEY_APPEARANCE, Appearance.entries[it].key).apply()
                    Appearance.entries[it].applyToApp(this)
                    rebuild()
                },
                footer = R.string.appearance_note,
            ),
            ui.group(
                null,
                listOf(fontControls(settings)),
                footer = getString(R.string.font_weight_note),
            ),
            ui.group(
                "文字效果",
                ui.picker(
                    "候选栏选中框",
                    listOf("柔光呼吸", "霓虹描边", "流光扫过", "实心强调", "简洁边框")[
                        prefs.getInt(Settings.KEY_CANDIDATE_SELECTION_EFFECT, 0).coerceIn(0, 4)
                    ],
                    listOf("柔光呼吸", "霓虹描边", "流光扫过", "实心强调", "简洁边框"),
                    prefs.getInt(Settings.KEY_CANDIDATE_SELECTION_EFFECT, 0).coerceIn(0, 4),
                ) { index ->
                    prefs.edit().putInt(Settings.KEY_CANDIDATE_SELECTION_EFFECT, index).apply()
                    rebuild()
                },
                footer = "调整键盘左右滑动与空格左右滑动预选候选词时的选中框样式。",
            ),
            ui.sectionTitle(R.string.section_theme),
            themeGrid(settings, dark),
            ui.group(
                null,
                ui.picker(
                    R.string.key_skin_title,
                    settings.keySkin.label,
                    Settings.KeySkin.entries.map { it.label },
                    Settings.KeySkin.entries.indexOf(settings.keySkin),
                ) { index ->
                    prefs.edit().putString(Settings.KEY_KEY_SKIN, Settings.KeySkin.entries[index].name).apply()
                    rebuild()
                },
                footer = R.string.arrangement_note,
            ),

        )
    }

    /** 当前主题、外观与已保存文字设置下的候选条和键盘预览。 */
    private fun preview(settings: Settings, dark: Boolean): View {
        val density = resources.displayMetrics.density
        val strip = Themes.preview(settings.theme, dark, density, settings.candidateFontSize / 20f, settings.candidateFontWeight.weight)
        return framed(ThemePreview(this, strip, Palette.of(settings.theme, dark), aspect = 0.42f, keyRadiusDp = 6f), selected = false)
    }

    /** 字号与字重并排显示，各占一半宽度；字重仍映射到现有九档设置，不改变渲染选项。 */
    private fun fontControls(settings: Settings): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        val size = ui.slider(
            R.string.section_text_size, null,
            1, 50, settings.candidateFontSize, step = 1,
            format = { "${it}sp" },
        ) { value ->
            prefs.edit().putInt(Settings.KEY_CANDIDATE_FONT_SIZE, value).apply()
            rebuild()
        }
        val weight = ui.slider(
            R.string.section_font_weight, null,
            100, 900, settings.candidateFontWeight.weight, step = 100,
            format = { value -> Settings.FontWeight.entries.minByOrNull { kotlin.math.abs(it.weight - value) }?.label.orEmpty() },
        ) { value ->
            val selected = Settings.FontWeight.entries.minByOrNull { kotlin.math.abs(it.weight - value) }
                ?: Settings.FontWeight.NORMAL
            prefs.edit().putString(Settings.KEY_CANDIDATE_FONT_WEIGHT, selected.name).apply()
            rebuild()
        }
        addView(size, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(weight, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    }

    /** 主题卡片两列排：缩小的预览加名字，选中的描品牌色边。 */
    private fun themeGrid(settings: Settings, dark: Boolean): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(4))
        }
        // 樱雾粉与樱花同属粉色系，默认从主题选择列表收起；若用户当前正在使用樱雾粉则保留卡片，避免隐藏当前主题。
        val visibleThemes = Themes.choices.filterNot { it.id == "rose" && settings.theme != it.id }
        visibleThemes.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, theme ->
                row.addView(themeCard(theme, theme.id == settings.theme, dark), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                    if (i == 1) marginStart = ui.dp(12)
                })
            }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = ui.dp(12) })
            column.addView(row)
        }
        return ui.card(column)
    }

    private fun themeCard(theme: ThemeChoice, selected: Boolean, dark: Boolean): View {
        val strip = Themes.preview(theme.id, dark, resources.displayMetrics.density * 0.62f, 1f)
        val preview = ThemePreview(this, strip, Palette.of(theme.id, dark), aspect = 0.4f, keyRadiusDp = 3f)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, ui.dp(14))
            minimumHeight = ui.dp(48)
            isFocusable = true
            isClickable = true
            isSelected = selected
            contentDescription = if (selected) "${theme.name}，当前主题" else "${theme.name}，应用主题"
            setOnClickListener {
                prefs.edit().putString(Settings.KEY_THEME, theme.id).apply()
                rebuild()
            }
            addView(framed(preview, selected), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(ui.text(theme.name, 14f, if (selected) ui.colors.brand else ui.colors.text, bold = selected).apply {
                setPadding(0, ui.dp(8), 0, 0)
            })
        }
    }

    /** 圆角裁好的预览；选中时外面一圈品牌色。 */
    private fun framed(preview: View, selected: Boolean): View {
        val radius = 10f * resources.displayMetrics.density
        val border = if (selected) ui.dp(2) else maxOf(1, ui.dp(1) / 2)
        return FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = radius
                setColor(ui.colors.surface)
                setStroke(border, if (selected) ui.colors.brand else ui.colors.divider)
            }
            setPadding(border, border, border, border)
            clipToOutline = true
            addView(preview, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }
}
