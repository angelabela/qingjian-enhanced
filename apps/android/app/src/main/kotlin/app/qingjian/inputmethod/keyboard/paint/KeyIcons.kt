package app.qingjian.inputmethod.keyboard.paint

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import app.qingjian.inputmethod.keyboard.ShiftState
import app.qingjian.inputmethod.keyboard.layout.Key

/** 功能键的矢量图标，画在 24×24 的格子里再按 [size] 缩放；不用字体里的符号，各家系统字体画出来不一样。 */
object KeyIcons {
    private val path = Path()

    /** Shift 键按 [shift] 画：亮着（单次或锁定）实心；锁定的小点由 [KeyPainter] 画在键角上。 */
    fun draw(canvas: Canvas, key: Key, cx: Float, cy: Float, size: Float, paint: Paint, shift: ShiftState = ShiftState.OFF) {
        val build = when (key) {
            Key.Shift -> ::shift
            Key.Backspace -> ::backspace
            is Key.Enter -> ::enter
            else -> return
        }
        val filled = shift.on
        path.rewind()
        build(path)
        val scale = size / 24f
        canvas.save()
        canvas.translate(cx - size / 2, cy - size / 2)
        canvas.scale(scale, scale)
        paint.strokeWidth = 1.8f
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        paint.style = if (filled && key == Key.Shift) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        canvas.restore()
    }

    private fun shift(p: Path) {
        p.moveTo(12f, 4f)
        p.lineTo(20f, 12.5f)
        p.lineTo(15.5f, 12.5f)
        p.lineTo(15.5f, 19.5f)
        p.lineTo(8.5f, 19.5f)
        p.lineTo(8.5f, 12.5f)
        p.lineTo(4f, 12.5f)
        p.close()
    }

    private fun backspace(p: Path) {
        p.moveTo(9f, 5.5f)
        p.lineTo(21f, 5.5f)
        p.lineTo(21f, 18.5f)
        p.lineTo(9f, 18.5f)
        p.lineTo(3f, 12f)
        p.close()
        p.moveTo(12f, 9.5f)
        p.lineTo(17f, 14.5f)
        p.moveTo(17f, 9.5f)
        p.lineTo(12f, 14.5f)
    }

    /** Z/X/C/V 分别画出全选、剪切、复制、粘贴图标，不再共用同一个下滑箭头。 */
    fun drawEditShortcut(canvas: Canvas, cx: Float, cy: Float, size: Float, paint: Paint, key: Char) {
        path.rewind()
        when (key) {
            'z' -> { // 全选：四角括号包围选区
                path.moveTo(4f, 9f); path.lineTo(4f, 4f); path.lineTo(9f, 4f)
                path.moveTo(15f, 4f); path.lineTo(20f, 4f); path.lineTo(20f, 9f)
                path.moveTo(20f, 15f); path.lineTo(20f, 20f); path.lineTo(15f, 20f)
                path.moveTo(9f, 20f); path.lineTo(4f, 20f); path.lineTo(4f, 15f)
                path.moveTo(8f, 12f); path.lineTo(12f, 8f); path.lineTo(16f, 12f)
                path.lineTo(12f, 16f); path.close()
            }
            'x' -> { // 剪切：剪刀刀刃
                path.moveTo(8f, 8f); path.lineTo(18f, 18f)
                path.moveTo(8f, 16f); path.lineTo(18f, 6f)
            }
            'c' -> { // 复制：两张错位叠放的纸张
                path.moveTo(8f, 7f); path.lineTo(18f, 7f); path.lineTo(18f, 20f); path.lineTo(8f, 20f); path.close()
                path.moveTo(5f, 16f); path.lineTo(5f, 4f); path.lineTo(15f, 4f)
            }
            'v' -> { // 粘贴：剪贴板夹子与纸张横线
                path.moveTo(8f, 6f); path.lineTo(5f, 6f); path.lineTo(5f, 21f); path.lineTo(19f, 21f); path.lineTo(19f, 6f); path.lineTo(16f, 6f)
                path.moveTo(9f, 4f); path.lineTo(15f, 4f); path.lineTo(16f, 8f); path.lineTo(8f, 8f); path.close()
                path.moveTo(8f, 12f); path.lineTo(16f, 12f)
                path.moveTo(8f, 16f); path.lineTo(16f, 16f)
            }
            else -> return
        }
        val scale = size / 24f
        canvas.save()
        canvas.translate(cx - size / 2f, cy - size / 2f)
        canvas.scale(scale, scale)
        val oldStyle = paint.style
        val oldWidth = paint.strokeWidth
        val oldCap = paint.strokeCap
        val oldJoin = paint.strokeJoin
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.2f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(path, paint)
        if (key == 'x') {
            canvas.drawCircle(5f, 7f, 2.3f, paint)
            canvas.drawCircle(5f, 17f, 2.3f, paint)
        }
        paint.style = oldStyle
        paint.strokeWidth = oldWidth
        paint.strokeCap = oldCap
        paint.strokeJoin = oldJoin
        canvas.restore()
    }

    private fun enter(p: Path) {
        p.moveTo(19f, 6f)
        p.lineTo(19f, 14f)
        p.lineTo(6f, 14f)
        p.moveTo(10f, 9.5f)
        p.lineTo(5.5f, 14f)
        p.lineTo(10f, 18.5f)
    }
}
