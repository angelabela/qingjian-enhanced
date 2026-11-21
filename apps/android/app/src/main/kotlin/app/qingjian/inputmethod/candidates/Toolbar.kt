package app.qingjian.inputmethod.candidates

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import android.text.TextPaint
import android.text.TextUtils
import app.qingjian.inputmethod.R
import app.qingjian.inputmethod.panel.base.PanelIcons
import app.qingjian.inputmethod.panel.base.PanelIcons.Icon
import app.qingjian.inputmethod.settings.ToolbarTool
import app.qingjian.inputmethod.theme.Palette

/**
 * 不在组句时候选条的样子：左边 logo 进功能面板、右边收起键盘，两头固定、各隔一道竖线，宽度一样；
 * 中间是用户定制的 [tools]，一屏最多放 [VISIBLE_SLOTS] 个，多出来的横着滑出来，不满时平分中间那段。
 */
class Toolbar(
    context: Context,
    private val palette: Palette,
    private val density: Float,
    private val barHeight: Float,
    private val tools: List<ToolbarTool>,
) {
    private val logo = context.getDrawable(R.drawable.ic_brand)
    private val logoSlot = 62f * density
    private val hideSlot = 62f * density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val logoGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.15f * density }
    private val line = Paint().apply { color = palette.faint }
    private val noticeText = TextPaint(Paint.ANTI_ALIAS_FLAG)

    /** 中间那排滑过去的距离（像素）。 */
    var offset = 0f
        private set

    private fun middleWidth(viewWidth: Int) = viewWidth - logoSlot - hideSlot

    private fun middleSlot(viewWidth: Int) = middleWidth(viewWidth) / tools.size.coerceIn(1, VISIBLE_SLOTS)

    fun maxOffset(viewWidth: Int) = (middleSlot(viewWidth) * tools.size - middleWidth(viewWidth)).coerceAtLeast(0f)

    fun scrollTo(value: Float, viewWidth: Int) {
        offset = value.coerceIn(0f, maxOffset(viewWidth))
    }

    fun onLogo(x: Float) = x < logoSlot

    fun onHide(x: Float, viewWidth: Int) = x > viewWidth - hideSlot

    /** 中间那排 [x] 处的工具；落在空处（全移除了）为 null。 */
    fun toolAt(x: Float, viewWidth: Int): ToolbarTool? {
        if (tools.isEmpty()) return null
        return tools[((x - logoSlot + offset) / middleSlot(viewWidth)).toInt().coerceIn(0, tools.size - 1)]
    }

    /** 工具栏位置上临时放的一句提示（功能要先去设置里配好）：左边提示图标，中间一行字，右边 ›，整条可点。 */
    fun drawNotice(canvas: Canvas, viewWidth: Int, message: String) {
        val cy = barHeight / 2
        stroke.color = palette.brand
        PanelIcons.draw(canvas, Icon.INFO, 30 * density, cy, 22 * density, stroke)
        noticeText.color = palette.text
        noticeText.textSize = 13 * density
        val left = 52 * density
        val right = viewWidth - 44 * density
        val shown = TextUtils.ellipsize(message, noticeText, right - left, TextUtils.TruncateAt.END).toString()
        canvas.drawText(shown, left, cy - (noticeText.descent() + noticeText.ascent()) / 2, noticeText)
        stroke.color = palette.gloss
        PanelIcons.draw(canvas, Icon.CHEVRON, viewWidth - 26 * density, cy, 20 * density, stroke)
    }

    fun draw(canvas: Canvas, viewWidth: Int) {
        val cy = barHeight / 2
        // 周期动画：先完整旋转一圈，再出现明显的品牌色双环发光，随后短暂停留并重复。
        val logoSize = (30 * density).toInt()
        val logoCentre = (logoSlot / 2).toInt()
        val phase = (SystemClock.uptimeMillis() % LOGO_CYCLE_MS).toFloat() / LOGO_CYCLE_MS
        val spinEnd = 0.46f
        val glowEnd = 0.72f
        val spinning = phase < spinEnd
        val rotation = if (spinning) (phase / spinEnd) * 360f else 360f
        val glowProgress = if (phase in spinEnd..glowEnd) {
            ((phase - spinEnd) / (glowEnd - spinEnd)).coerceIn(0f, 1f)
        } else if (phase > glowEnd) {
            (1f - (phase - glowEnd) / (1f - glowEnd)).coerceIn(0f, 1f) * 0.22f
        } else 0f
        val glowWave = sin(glowProgress * PI).toFloat().coerceAtLeast(0f)
        logoGlow.color = palette.brand
        logoGlow.strokeWidth = (1.4f + glowWave * 3.4f) * density
        logoGlow.alpha = (glowWave * 220f).toInt().coerceIn(0, 220)
        logoGlow.setShadowLayer(5f * density * glowWave, 0f, 0f, palette.brand)
        if (glowWave > 0f) {
            canvas.drawCircle(logoCentre.toFloat(), cy, (19f + glowWave * 5f) * density, logoGlow)
            logoGlow.alpha = (glowWave * 125f).toInt()
            canvas.drawCircle(logoCentre.toFloat(), cy, (23f + glowWave * 7f) * density, logoGlow)
        }
        logoGlow.clearShadowLayer()
        canvas.save()
        canvas.rotate(rotation, logoCentre.toFloat(), cy)
        logo?.alpha = 255
        logo?.setBounds(
            logoCentre - logoSize / 2, (cy - logoSize / 2).toInt(),
            logoCentre + logoSize / 2, (cy + logoSize / 2).toInt(),
        )
        logo?.draw(canvas)
        canvas.restore()

        stroke.color = palette.gloss
        val slot = middleSlot(viewWidth)
        val divider = viewWidth - hideSlot
        canvas.save()
        canvas.clipRect(logoSlot, 0f, divider, barHeight)
        tools.forEachIndexed { i, tool ->
            PanelIcons.draw(canvas, tool.icon, logoSlot + slot * (i + 0.5f) - offset, cy, 26 * density, stroke)
        }
        canvas.restore()
        line.strokeWidth = density
        canvas.drawLine(logoSlot, cy - 12 * density, logoSlot, cy + 12 * density, line)
        canvas.drawLine(divider, cy - 12 * density, divider, cy + 12 * density, line)
        PanelIcons.draw(canvas, Icon.HIDE, viewWidth - hideSlot / 2, cy, 24 * density, stroke)
    }

    private companion object {
        const val VISIBLE_SLOTS = 5
        const val LOGO_CYCLE_MS = 3_600L
    }
}
