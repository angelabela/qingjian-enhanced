package app.qingjian.inputmethod.scan

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 持续扫码页：识别结果复制后继续预览；只有点关闭才退出。 */
class BarcodeScannerActivity : ComponentActivity() {
    private lateinit var preview: PreviewView
    private lateinit var scanArea: ScanAreaView
    private lateinit var scanner: BarcodeScanner
    private val executor = Executors.newSingleThreadExecutor()
    private val processing = AtomicBoolean(false)
    private val lastSeen = mutableMapOf<String, Long>()
    private var cameraProvider: ProcessCameraProvider? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .build()
        scanner = BarcodeScanning.getClient(options)

        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        scanArea = ScanAreaView(this)
        val root = FrameLayout(this)
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))
        root.addView(scanArea, FrameLayout.LayoutParams(-1, -1))
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(0xCC101522.toInt())
            addView(TextView(this@BarcodeScannerActivity).apply {
                text = "二维码 / 条形码 · 持续扫描"
                textSize = 16f
                setTextColor(-1)
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
            addView(TextView(this@BarcodeScannerActivity).apply {
                text = "关闭"
                textSize = 15f
                setTextColor(-1)
                gravity = Gravity.CENTER
                setPadding(dp(14), 0, dp(8), 0)
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(-2, dp(44)))
        }
        root.addView(header, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        val hint = TextView(this).apply {
            text = "拖动扫描框可移动，拖动右下角可调整大小。识别后自动复制，不会停止扫描。"
            textSize = 12f
            setTextColor(-1)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            setBackgroundColor(0xAA101522.toInt())
        }
        root.addView(hint, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera()
            else { Toast.makeText(this, "需要摄像头权限才能扫码", Toast.LENGTH_LONG).show(); finish() }
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider
            val cameraPreview = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { proxy ->
                val media = proxy.image
                if (media == null || !processing.compareAndSet(false, true)) {
                    proxy.close()
                } else {
                    val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                    scanner.process(image)
                        .addOnSuccessListener { codes ->
                            val area = scanArea.normalizedRect()
                            codes.forEach { barcode ->
                                val raw = barcode.rawValue?.trim().orEmpty()
                                val box = barcode.boundingBox ?: return@forEach
                                if (raw.isBlank()) return@forEach
                                val cx = box.exactCenterX() / image.width.toFloat()
                                val cy = box.exactCenterY() / image.height.toFloat()
                                // ROI 坐标归一化；扫描框可拖动/缩放，框外的码不触发复制。
                                if (!area.contains(cx, cy)) return@forEach
                                val now = System.currentTimeMillis()
                                val previous = lastSeen[raw] ?: 0L
                                // 每帧刷新最后可见时间：静止在镜头里的同一个码不会周期性重复提示；
                                // 移出扫描框超过间隔后再放回来，会再次复制。
                                lastSeen[raw] = now
                                if (previous != 0L && now - previous < DEDUPE_MS) return@forEach
                                if (lastSeen.size > 100) lastSeen.entries.removeAll { now - it.value > 30_000L }
                                copyResult(raw)
                            }
                        }
                        .addOnCompleteListener {
                            processing.set(false)
                            proxy.close()
                        }
                }
            }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, cameraPreview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun copyResult(value: String) {
        runOnUiThread {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("扫码结果", value))
            Toast.makeText(this, "已复制：$value", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        scanner.close()
        executor.shutdown()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    /** 扫描框可拖动；拖右下角圆点调整大小。 */
    private class ScanAreaView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val rect = RectF()
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF55E6D0.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.5f * density
        }
        private val shade = Paint().apply { color = 0x55000000 }
        private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF55E6D0.toInt() }
        private var downX = 0f
        private var downY = 0f
        private var start = RectF()
        private var resizing = false

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            if (rect.isEmpty) rect.set(w * .12f, h * .30f, w * .88f, h * .68f)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawRect(0f, 0f, width.toFloat(), rect.top, shade)
            canvas.drawRect(0f, rect.top, rect.left, rect.bottom, shade)
            canvas.drawRect(rect.right, rect.top, width.toFloat(), rect.bottom, shade)
            canvas.drawRect(0f, rect.bottom, width.toFloat(), height.toFloat(), shade)
            canvas.drawRoundRect(rect, 12f * density, 12f * density, stroke)
            canvas.drawCircle(rect.right, rect.bottom, 10f * density, handle)
        }

        fun normalizedRect(): RectF {
            if (width <= 0 || height <= 0) return RectF(0f, 0f, 1f, 1f)
            return RectF(rect.left / width, rect.top / height, rect.right / width, rect.bottom / height)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x; downY = event.y; start = RectF(rect)
                    resizing = start.let { event.x >= it.right - 28f * density && event.y >= it.bottom - 28f * density }
                    return rect.contains(event.x, event.y)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - downX; val dy = event.y - downY
                    if (resizing) {
                        rect.right = (start.right + dx).coerceIn(start.left + 120f * density, width.toFloat())
                        rect.bottom = (start.bottom + dy).coerceIn(start.top + 100f * density, height.toFloat())
                    } else {
                        val w = start.width(); val h = start.height()
                        val left = (start.left + dx).coerceIn(0f, width - w)
                        val top = (start.top + dy).coerceIn(0f, height - h)
                        rect.set(left, top, left + w, top + h)
                    }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
            }
            return true
        }
    }

    companion object {
        private const val REQUEST_CAMERA = 2601
        private const val DEDUPE_MS = 1400L
    }
}
