package app.qingjian.inputmethod.theme

import android.content.Context
import app.qingjian.inputmethod.settings.Settings

/**
 * App 设置界面直接沿用当前键盘主题的颜色系统，避免设置面板仍固定使用素笺黑白配色。
 * 所有主次文字、卡片、栏和强调色均从 Rust 主题 Palette 获取。
 */
class AppColors(
    val background: Int,
    val surface: Int,
    val bar: Int,
    val text: Int,
    val secondary: Int,
    val faint: Int,
    val divider: Int,
    val brand: Int,
    val onBrand: Int,
    val brandSoft: Int,
    val tile: Int,
    val tileMuted: Int,
    val fill: Int,
    val fresh: Int,
    val danger: Int,
    val dark: Boolean,
) {
    companion object {
        fun of(context: Context): AppColors {
            val settings = Settings.load(context)
            val palette = Palette.of(settings.theme, settings.appearance.dark(context))
            val board = palette.board
            val surface = palette.surface
            val brand = palette.brand
            return AppColors(
                background = board,
                surface = surface,
                bar = withAlpha(board, 0xF0),
                text = palette.text,
                secondary = readable(palette.gloss, palette.text, board, 4.0),
                faint = readable(palette.faint, palette.gloss, board, 2.8),
                divider = blend(board, surface, 0.22f),
                brand = brand,
                onBrand = palette.onBrand,
                brandSoft = blend(board, brand, if (palette.dark) 0.28f else 0.14f),
                tile = brand,
                tileMuted = palette.functionalKey,
                fill = palette.functionalKey,
                fresh = palette.fresh,
                danger = if (palette.dark) 0xFFFF7777.toInt() else 0xFFB3261E.toInt(),
                dark = palette.dark,
            )
        }

        private fun withAlpha(color: Int, alpha: Int) =
            (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)

        private fun blend(from: Int, to: Int, amount: Float): Int {
            val t = amount.coerceIn(0f, 1f)
            fun channel(shift: Int) = ((((from ushr shift) and 0xFF) * (1f - t)) +
                (((to ushr shift) and 0xFF) * t)).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }

        private fun readable(candidate: Int, fallback: Int, background: Int, minimum: Double): Int =
            if (contrast(candidate, background) >= minimum) candidate else fallback

        private fun contrast(first: Int, second: Int): Double {
            fun luminance(color: Int): Double {
                fun linear(value: Int): Double {
                    val channel = value / 255.0
                    return if (channel <= 0.04045) channel / 12.92 else Math.pow((channel + 0.055) / 1.055, 2.4)
                }
                return 0.2126 * linear((color shr 16) and 0xFF) +
                    0.7152 * linear((color shr 8) and 0xFF) +
                    0.0722 * linear(color and 0xFF)
            }
            val a = luminance(first)
            val b = luminance(second)
            return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
        }
    }
}
