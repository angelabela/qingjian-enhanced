package app.qingjian.inputmethod.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.CursorAnchorInfo
import kotlin.math.roundToInt

/** 在外部编辑器真实插入光标处绘制非交互辉光；不修改宿主应用的光标样式。 */
internal class CursorGlowOverlay(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val view = GlowView(context)
    private val width = dp(52f)
    private val height = dp(64f)
    private val params = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
    }

    fun update(info: CursorAnchorInfo) {
        if (!Settings.canDrawOverlays(context)) {
            hide()
            return
        }
        if (info.insertionMarkerFlags and CursorAnchorInfo.FLAG_HAS_INVISIBLE_REGION != 0) {
            hide()
            return
        }
        val marker = floatArrayOf(
            info.insertionMarkerHorizontal,
            info.insertionMarkerTop,
            info.insertionMarkerHorizontal,
            info.insertionMarkerBottom,
        )
        info.matrix.mapPoints(marker)
        if (marker.any { !it.isFinite() } || marker[0] < 0f || marker[1] < 0f) {
            hide()
            return
        }
        val centerX = marker[0].roundToInt()
        val centerY = ((marker[1] + marker[3]) / 2f).roundToInt()
        val metrics = context.resources.displayMetrics
        params.x = (centerX - width / 2).coerceIn(0, (metrics.widthPixels - width).coerceAtLeast(0))
        params.y = (centerY - height / 2).coerceIn(0, (metrics.heightPixels - height).coerceAtLeast(0))
        runCatching {
            if (view.isAttachedToWindow) windowManager.updateViewLayout(view, params)
            else windowManager.addView(view, params)
        }
    }

    fun hide() {
        runCatching {
            if (view.isAttachedToWindow) windowManager.removeView(view)
        }
    }

    fun destroy() = hide()

    private fun dp(value: Float) = (value * context.resources.displayMetrics.density).roundToInt()

    private class GlowView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
        private val caret = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF9AF4FF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2.2f * density
            strokeCap = Paint.Cap.ROUND
        }
        private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
            strokeCap = Paint.Cap.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = 25f * density
            halo.shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(0x9952DFFF.toInt(), 0x4452DFFF, 0x0052DFFF),
                floatArrayOf(0f, 0.48f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, radius, halo)
            val top = 9f * density
            val bottom = height - top
            canvas.drawRoundRect(cx - 2.2f * density, top, cx + 2.2f * density, bottom, 3f * density, 3f * density, caret)
            canvas.drawRoundRect(cx - 0.8f * density, top + 1f * density, cx + 0.8f * density, bottom - 1f * density, 2f * density, 2f * density, core)
        }
    }
}
