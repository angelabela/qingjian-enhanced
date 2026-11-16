package app.qingjian.inputmethod.scan

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.camera.camera2.interop.Camera2Interop
import android.hardware.camera2.CaptureRequest
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 持续扫码页：仅识别扫描框内的码，复制并震动提示后继续预览。 */
class BarcodeScannerActivity : ComponentActivity() {
    private lateinit var preview: PreviewView
    private lateinit var scanArea: ScanAreaView
    private lateinit var scanner: BarcodeScanner
    private val executor = Executors.newSingleThreadExecutor()
    private val processing = AtomicBoolean(false)
    private val lastSeen = mutableMapOf<String, Long>()
    private var cameraProvider: ProcessCameraProvider? = null
    private val floatingMode by lazy { ScanPreferences.isFloating(this) }
    private val transformFactory = ImageProxyTransformFactory().apply {
        setUsingCropRect(true)
        setUsingRotationDegrees(true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 悬浮模式只启动非焦点系统悬浮服务，Activity 随即退出，不再覆盖或抢占宿主输入框。
        if (floatingMode) {
            if (!Settings.canDrawOverlays(this)) {
                startActivityForResult(
                    android.content.Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${packageName}"),
                    ),
                    REQUEST_OVERLAY,
                )
            } else {
                startFloatingOverlay()
            }
            return
        }
        val options = BarcodeScannerOptions.Builder()
            // 明确启用二维码和常用一维码格式，避免只依赖默认格式集合。
            .setBarcodeFormats(
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_CODE_93,
                Barcode.FORMAT_CODABAR,
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_ITF,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E,
                Barcode.FORMAT_PDF417,
                Barcode.FORMAT_AZTEC,
                Barcode.FORMAT_DATA_MATRIX,
            )
            .build()
        scanner = BarcodeScanning.getClient(options)

        preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
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
                text = if (floatingMode) "扫码窗口 · 按住标题可移动" else "二维码 / 条形码 · 持续扫描"
                textSize = 16f
                setTextColor(-1)
                if (floatingMode) {
                    var downRawX = 0f
                    var downRawY = 0f
                    var startX = 0
                    var startY = 0
                    setOnTouchListener { _, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                downRawX = event.rawX
                                downRawY = event.rawY
                                val p = window.attributes
                                startX = p.x
                                startY = p.y
                                true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                val p = window.attributes
                                p.x = startX + (event.rawX - downRawX).toInt()
                                p.y = startY + (event.rawY - downRawY).toInt()
                                window.attributes = p
                                true
                            }
                            else -> true
                        }
                    }
                }
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
        // 状态栏高度由系统 Insets 提供，标题栏向下避让，不再与状态栏文字重叠。
        ViewCompat.setOnApplyWindowInsetsListener(header) { view, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(dp(16), dp(10) + top, dp(16), dp(10))
            insets
        }
        root.addView(header, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        val hint = TextView(this).apply {
            text = if (floatingMode) "仅识别框内二维码和条码。可拖动标题移动窗口，拖动扫描框可调整识别区域；识别结果直接发送到当前输入框。" else "仅识别框内二维码和条码。拖动扫描框可移动，拖右下角可调整大小；识别结果直接发送到当前输入框。"
            textSize = 12f
            setTextColor(-1)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            setBackgroundColor(0xAA101522.toInt())
        }
        root.addView(hint, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
        configureFloatingWindow()
        ViewCompat.requestApplyInsets(header)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                if (floatingMode) startFloatingOverlay() else startCamera()
            } else {
                Toast.makeText(this, "需要摄像头权限才能扫码", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OVERLAY) {
            if (Settings.canDrawOverlays(this)) startFloatingOverlay()
            else {
                Toast.makeText(this, "需要允许青简显示悬浮窗，才能使用不抢焦点的扫码模式", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun startFloatingOverlay() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
            return
        }
        val serviceIntent = android.content.Intent(this, ScanOverlayService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        finish()
    }

    /**
     * 请求相机连续自动对焦与高质量边缘/降噪处理。由设备 ISP 执行，
     * Camera2 不支持这些模式时 CameraX 会按设备能力回退，不影响扫码流程。
     */
    private fun applyScanCaptureQuality(builder: Preview.Builder) {
        Camera2Interop.Extender(builder)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            .setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
            .setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
    }

    private fun applyScanCaptureQuality(builder: ImageAnalysis.Builder) {
        Camera2Interop.Extender(builder)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            .setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
            .setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider
            val previewBuilder = Preview.Builder()
            applyScanCaptureQuality(previewBuilder)
            val cameraPreview = previewBuilder.build().also {
                it.setSurfaceProvider(preview.surfaceProvider)
            }
            val analysisBuilder = ImageAnalysis.Builder()
                // 1080p 分析提高远距离/小尺寸码的有效像素；只保留最新帧避免堆积。
                .setTargetResolution(Size(1920, 1080))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            applyScanCaptureQuality(analysisBuilder)
            val analysis = analysisBuilder.build()
            analysis.setAnalyzer(executor) { proxy ->
                val media = proxy.image
                if (media == null || !processing.compareAndSet(false, true)) {
                    proxy.close()
                } else {
                    val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                    scanner.process(image)
                        .addOnSuccessListener { codes ->
                            val previewTransform = preview.outputTransform
                            if (previewTransform != null) {
                                val imageTransform = transformFactory.getOutputTransform(proxy)
                                val coordinateTransform = CoordinateTransform(imageTransform, previewTransform)
                                val roi = scanArea.boundsInView()
                                codes.forEach { barcode ->
                                    val raw = barcode.rawValue?.trim().orEmpty()
                                    val box = barcode.boundingBox ?: return@forEach
                                    if (raw.isBlank() || !ScanPreferences.accepts(this@BarcodeScannerActivity, raw)) return@forEach
                                    // 把相机图像中的条码框转换为屏幕预览坐标，再判断是否落入可视扫描框。
                                    val mapped = RectF(box)
                                    coordinateTransform.mapRect(mapped)
                                    if (!roi.contains(mapped.centerX(), mapped.centerY())) return@forEach

                                    val now = System.currentTimeMillis()
                                    val previous = lastSeen[raw] ?: 0L
                                    // 静止码只提示一次；离开识别区超过间隔后再次放入，可再次触发。
                                    lastSeen[raw] = now
                                    if (previous != 0L && now - previous < DEDUPE_MS) return@forEach
                                    if (lastSeen.size > 100) {
                                        lastSeen.entries.removeAll { now - it.value > 30_000L }
                                    }
                                    copyResult(raw)
                                }
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

    /** 悬浮模式以小窗覆盖输入区域；拖动标题栏可自由移动。 */
    private fun configureFloatingWindow() {
        if (!floatingMode) return
        val width = minOf(dp(360), resources.displayMetrics.widthPixels - dp(24))
        val height = minOf(dp(520), resources.displayMetrics.heightPixels - dp(96))
        window.setBackgroundDrawable(ColorDrawable(0xFF101522.toInt()))
        val params = window.attributes
        params.width = width.coerceAtLeast(dp(260))
        params.height = height.coerceAtLeast(dp(320))
        params.gravity = Gravity.CENTER
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND
        params.dimAmount = 0.24f
        window.attributes = params
        window.setLayout(params.width, params.height)
    }

    private fun copyResult(value: String) {
        runOnUiThread {
            // 剪贴板作为兼容性备份；正常路径由输入法服务直接提交到当前编辑框。
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("扫码结果", value))
            sendBroadcast(
                android.content.Intent(ScanPreferences.ACTION_RESULT)
                    .setPackage(packageName)
                    .putExtra(ScanPreferences.EXTRA_TEXT, value)
                    .putExtra(ScanPreferences.EXTRA_ENTER, floatingMode && ScanPreferences.autoEnter(this)),
            )
            if (ScanPreferences.vibrate(this)) {
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (vibrator?.hasVibrator() == true) {
                    vibrator.vibrate(VibrationEffect.createOneShot(70L, VibrationEffect.DEFAULT_AMPLITUDE))
                }
            }
            Toast.makeText(this, "已识别并发送到输入框：$value", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        if (::scanner.isInitialized) scanner.close()
        executor.shutdown()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    /** 扫描框可拖动；拖右下角圆点调整大小；保存归一化区域，下一次打开恢复。 */
    private class ScanAreaView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        private val rect = RectF()
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF55E6D0.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * density
        }
        private val shade = Paint().apply { color = 0x55000000 }
        private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF55E6D0.toInt() }
        private var downX = 0f
        private var downY = 0f
        private var start = RectF()
        private var resizing = false

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            if (w <= 0 || h <= 0) return
            if (rect.isEmpty) {
                val left = prefs.getFloat("left", .12f) * w
                val top = prefs.getFloat("top", .30f) * h
                val right = prefs.getFloat("right", .88f) * w
                val bottom = prefs.getFloat("bottom", .68f) * h
                rect.set(left, top, right, bottom)
                clampRect()
            }
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

        /** 扫描框坐标与 PreviewView 共享同一视图坐标系。 */
        fun boundsInView(): RectF = RectF(rect)

        private fun clampRect() {
            val minW = 120f * density
            val minH = 50f * density
            if (width <= 0 || height <= 0) return
            rect.left = rect.left.coerceIn(0f, (width - minW).coerceAtLeast(0f))
            rect.top = rect.top.coerceIn(0f, (height - minH).coerceAtLeast(0f))
            rect.right = rect.right.coerceIn(rect.left + minW, width.toFloat())
            rect.bottom = rect.bottom.coerceIn(rect.top + minH, height.toFloat())
        }

        private fun saveRect() {
            if (width <= 0 || height <= 0) return
            prefs.edit()
                .putFloat("left", rect.left / width)
                .putFloat("top", rect.top / height)
                .putFloat("right", rect.right / width)
                .putFloat("bottom", rect.bottom / height)
                .apply()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    start = RectF(rect)
                    resizing = event.x >= start.right - 28f * density &&
                        event.y >= start.bottom - 28f * density
                    return rect.contains(event.x, event.y)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (resizing) {
                        rect.right = (start.right + dx).coerceIn(start.left + 120f * density, width.toFloat())
                        rect.bottom = (start.bottom + dy).coerceIn(start.top + 50f * density, height.toFloat())
                    } else {
                        val w = start.width()
                        val h = start.height()
                        val left = (start.left + dx).coerceIn(0f, width - w)
                        val top = (start.top + dy).coerceIn(0f, height - h)
                        rect.set(left, top, left + w, top + h)
                    }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    saveRect()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> return true
            }
            return true
        }

        companion object {
            private const val PREFS = "barcode_scan_area"
        }
    }

    companion object {
        private const val REQUEST_CAMERA = 2601
        private const val REQUEST_OVERLAY = 2603
        private const val DEDUPE_MS = 1400L
    }
}
