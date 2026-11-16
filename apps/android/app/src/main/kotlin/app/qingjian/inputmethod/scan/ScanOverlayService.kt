package app.qingjian.inputmethod.scan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Size
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Switch
import android.widget.Toast
import androidx.camera.core.Camera
import androidx.camera.camera2.interop.Camera2Interop
import android.hardware.camera2.CaptureRequest
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import app.qingjian.inputmethod.R
import app.qingjian.inputmethod.theme.Palette
import app.qingjian.inputmethod.panel.base.PanelIcons
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 真正的系统悬浮扫码窗：TYPE_APPLICATION_OVERLAY + NOT_FOCUSABLE，
 * 不接管焦点，宿主输入框和输入法 InputConnection 保持不变。
 */
class ScanOverlayService : Service(), LifecycleOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private lateinit var windowManager: WindowManager
    private lateinit var windowParams: WindowManager.LayoutParams
    private lateinit var root: FrameLayout
    private lateinit var preview: PreviewView
    private lateinit var scanArea: ScanAreaView
    private lateinit var scanner: BarcodeScanner
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var torchEnabled = false
    private val themePalette by lazy { runCatching { Palette.of(this) }.getOrNull() }
    private lateinit var torchButton: ScanToolbarIconView
    private var prefixEditor: EditText? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val processing = AtomicBoolean(false)
    // 同一条码在连续视频帧中会反复命中，短时间内只广播和震动一次。
    private val lastDeliveredAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val windowPrefs by lazy { getSharedPreferences(WINDOW_PREFS, MODE_PRIVATE) }
    private val transformFactory = ImageProxyTransformFactory().apply {
        setUsingCropRect(true)
        setUsingRotationDegrees(true)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        windowManager = getSystemService(WindowManager::class.java)
        scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(
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
            ).build(),
        )
        buildOverlay()
        startCamera()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    private fun buildOverlay() {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // 不再使用半透明预览；悬浮窗内部显示完整、清晰的相机画面。
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            alpha = 1f
            setBackgroundColor(Color.TRANSPARENT)
        }
        scanArea = ScanAreaView(this)
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            clipToOutline = false
            clipChildren = false
        }
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))
        root.addView(scanArea, FrameLayout.LayoutParams(-1, -1))

        // 自适应紧凑标题栏：宽度足够时前缀、过滤开关和图标保持同一行；不足时才换成两行。
        val prefixRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val prefixInput = EditText(this).apply {
            setSingleLine(true)
            textSize = 13f
            setTextColor(themePalette?.text ?: 0xFFFFFFFF.toInt())
            setHintTextColor((themePalette?.text ?: 0xFFFFFFFF.toInt()).let { (it and 0x00FFFFFF) or 0x88000000 })
            hint = "输入扫码前缀"
            setPadding(dp(9), 0, dp(7), 0)
            background = GradientDrawable().apply {
                setColor(themePalette?.surface ?: 0xFF101522.toInt())
                cornerRadius = dp(7).toFloat()
                setStroke(dp(1), 0x44FFFFFF)
            }
            setText(ScanPreferences.prefix(this@ScanOverlayService))
            imeOptions = EditorInfo.IME_ACTION_DONE
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        prefixEditor = prefixInput
        prefixRow.addView(prefixInput, LinearLayout.LayoutParams(0, dp(36), 1f))

        val prefixSwitch = Switch(this).apply {
            text = "过滤"
            textSize = 11f
            setTextColor(themePalette?.text ?: 0xFFFFFFFF.toInt())
            isChecked = ScanPreferences.prefixEnabled(this@ScanOverlayService)
            setOnCheckedChangeListener { _, enabled ->
                finishPrefixEditing()
                getSharedPreferences(ScanPreferences.PREFS, MODE_PRIVATE).edit()
                    .putBoolean(ScanPreferences.KEY_PREFIX_ENABLED, enabled).apply()
            }
        }
        prefixRow.addView(prefixSwitch, LinearLayout.LayoutParams(-2, dp(38)).apply { marginStart = dp(5) })

        prefixInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // 每次输入即时保存，不依赖软键盘的完成/回车键。
                getSharedPreferences(ScanPreferences.PREFS, MODE_PRIVATE).edit()
                    .putString(ScanPreferences.KEY_PREFIX, s?.toString()?.trim().orEmpty()).apply()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        prefixInput.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN &&
                (windowParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0) {
                windowParams.flags = windowParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                runCatching { windowManager.updateViewLayout(root, windowParams) }
                prefixInput.post {
                    prefixInput.requestFocus()
                    (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                        ?.showSoftInput(prefixInput, InputMethodManager.SHOW_IMPLICIT)
                }
            }
            false
        }
        prefixInput.setOnFocusChangeListener { _, focused ->
            if (!focused && ::windowParams.isInitialized) {
                (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.hideSoftInputFromWindow(prefixInput.windowToken, 0)
                windowParams.flags = windowParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                runCatching { windowManager.updateViewLayout(root, windowParams) }
            }
        }
        prefixInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                finishPrefixEditing()
                true
            } else false
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }
        toolbar.addView(ScanToolbarIconView(this, PanelIcons.Icon.DRAG, "拖动悬浮窗").apply {
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) finishPrefixEditing()
                moveWindow(event)
            }
        }, LinearLayout.LayoutParams(dp(36), dp(40)))
        torchButton = ScanToolbarIconView(this, PanelIcons.Icon.FLASHLIGHT, "打开手电筒").apply {
            setOnClickListener { finishPrefixEditing(); toggleTorch() }
        }
        toolbar.addView(torchButton, LinearLayout.LayoutParams(dp(40), dp(40)))
        toolbar.addView(ScanToolbarIconView(this, PanelIcons.Icon.CLOSE, "关闭扫码窗口").apply {
            setOnClickListener { finishPrefixEditing(); stopSelf() }
        }, LinearLayout.LayoutParams(dp(36), dp(40)))

        val header = object : LinearLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
                val compact = availableWidth < dp(330)
                orientation = if (compact) VERTICAL else HORIZONTAL
                prefixRow.layoutParams = if (compact) {
                    LayoutParams(LayoutParams.MATCH_PARENT, dp(40))
                } else {
                    LayoutParams(0, dp(40), 1f)
                }
                toolbar.layoutParams = if (compact) {
                    LayoutParams(LayoutParams.MATCH_PARENT, dp(40))
                } else {
                    LayoutParams(LayoutParams.WRAP_CONTENT, dp(40))
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }.apply {
            setPadding(dp(7), dp(2), dp(7), dp(2))
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(themePalette?.surface ?: 0xFF101522.toInt())
            addView(prefixRow)
            addView(toolbar)
        }
        root.addView(header, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        val resize = TextView(this).apply {
            text = "↘"
            textSize = 25f
            // 仅保留中性白色的缩放提示，不给相机画面叠加主题色底块。
            setTextColor(0xCCFFFFFF.toInt())
            gravity = Gravity.CENTER
            setBackgroundColor(Color.TRANSPARENT)
            setOnTouchListener { _, event -> resizeWindow(event) }
        }
        root.addView(resize, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.RIGHT or Gravity.BOTTOM))

        val maxWidth = (resources.displayMetrics.widthPixels - dp(24)).coerceAtLeast(dp(220))
        val maxHeight = (resources.displayMetrics.heightPixels - dp(100)).coerceAtLeast(dp(220))
        val width = dp(windowPrefs.getInt(KEY_WIDTH_DP, 340)).coerceIn(dp(220), maxWidth)
        val height = dp(windowPrefs.getInt(KEY_HEIGHT_DP, 360)).coerceIn(dp(220), maxHeight)
        windowParams = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(120)
        }
        windowManager.addView(root, windowParams)
    }

    private fun finishPrefixEditing() {
        val editor = prefixEditor ?: return
        if (!editor.hasFocus()) return
        editor.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(editor.windowToken, 0)
    }

    /** 与青简 App 面板共用 PanelIcons 的 24dp 线框图标绘制风格。 */
    private inner class ScanToolbarIconView(
        context: Context,
        private val icon: PanelIcons.Icon,
        description: String,
    ) : View(context) {
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = themePalette?.text ?: 0xFFFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 1.8f * resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        init {
            contentDescription = description
            isClickable = true
            isFocusable = true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            iconPaint.color = if (isEnabled) {
                themePalette?.text ?: 0xFFFFFFFF.toInt()
            } else 0x66FFFFFF
            PanelIcons.draw(
                canvas, icon, width / 2f, height / 2f,
                21f * resources.displayMetrics.density, iconPaint,
            )
        }
    }

    private var moveRawX = 0f
    private var moveRawY = 0f
    private var moveStartX = 0
    private var moveStartY = 0

    private fun moveWindow(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                moveRawX = event.rawX
                moveRawY = event.rawY
                moveStartX = windowParams.x
                moveStartY = windowParams.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                windowParams.x = (moveStartX + event.rawX - moveRawX).toInt()
                    .coerceIn(0, (resources.displayMetrics.widthPixels - windowParams.width).coerceAtLeast(0))
                windowParams.y = (moveStartY + event.rawY - moveRawY).toInt()
                    .coerceIn(0, (resources.displayMetrics.heightPixels - windowParams.height).coerceAtLeast(0))
                runCatching { windowManager.updateViewLayout(root, windowParams) }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }

    private var resizeRawX = 0f
    private var resizeRawY = 0f
    private var resizeStartW = 0
    private var resizeStartH = 0

    private fun resizeWindow(event: MotionEvent): Boolean {
        val density = resources.displayMetrics.density
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resizeRawX = event.rawX
                resizeRawY = event.rawY
                resizeStartW = windowParams.width
                resizeStartH = windowParams.height
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val maxW = resources.displayMetrics.widthPixels - windowParams.x
                val maxH = resources.displayMetrics.heightPixels - windowParams.y
                val minSize = (220 * density).toInt()
                windowParams.width = (resizeStartW + event.rawX - resizeRawX).toInt()
                    .coerceIn(minSize, maxOf(minSize, maxW))
                windowParams.height = (resizeStartH + event.rawY - resizeRawY).toInt()
                    .coerceIn(minSize, maxOf(minSize, maxH))
                runCatching { windowManager.updateViewLayout(root, windowParams) }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                saveWindowSize()
                return true
            }
        }
        return true
    }

    private fun saveWindowSize() {
        if (!::windowParams.isInitialized) return
        val density = resources.displayMetrics.density.coerceAtLeast(0.1f)
        windowPrefs.edit()
            .putInt(KEY_WIDTH_DP, (windowParams.width / density).toInt())
            .putInt(KEY_HEIGHT_DP, (windowParams.height / density).toInt())
            .apply()
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
            if (lifecycle.currentState == Lifecycle.State.DESTROYED) return@addListener
            val provider = future.get()
            cameraProvider = provider
            val previewBuilder = Preview.Builder()
                .setTargetResolution(Size(1920, 1080))
            applyScanCaptureQuality(previewBuilder)
            val cameraPreview = previewBuilder.build().also {
                it.setSurfaceProvider(preview.surfaceProvider)
            }
            val analysisBuilder = ImageAnalysis.Builder()
                .setTargetResolution(Size(1920, 1080))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            applyScanCaptureQuality(analysisBuilder)
            val analysis = analysisBuilder.build()
            analysis.setAnalyzer(executor) { proxy ->
                val media = proxy.image
                if (media == null || !processing.compareAndSet(false, true)) {
                    proxy.close()
                } else {
                    scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                        .addOnSuccessListener { codes ->
                            val output = preview.outputTransform
                            if (output != null) {
                                val transform = CoordinateTransform(transformFactory.getOutputTransform(proxy), output)
                                val roi = scanArea.boundsInView()
                                codes.forEach { code ->
                                    val value = code.rawValue?.trim().orEmpty()
                                    val bounds = code.boundingBox ?: return@forEach
                                    if (value.isBlank() || !ScanPreferences.accepts(this@ScanOverlayService, value)) return@forEach
                                    val mapped = RectF(bounds)
                                    transform.mapRect(mapped)
                                    if (!roi.contains(mapped)) return@forEach
                                    val now = android.os.SystemClock.elapsedRealtime()
                                    val previous = lastDeliveredAt[value]
                                    if (previous == null || now - previous >= DUPLICATE_SUPPRESS_MS) {
                                        lastDeliveredAt[value] = now
                                        deliverResult(value)
                                    }
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
            camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, cameraPreview, analysis)
            torchButton.isEnabled = camera?.cameraInfo?.hasFlashUnit() == true
            torchButton.alpha = if (torchButton.isEnabled) 1f else 0.4f
            torchButton.contentDescription = if (torchButton.isEnabled) "打开手电筒" else "当前摄像头没有闪光灯"
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleTorch() {
        val activeCamera = camera ?: return
        if (!activeCamera.cameraInfo.hasFlashUnit()) {
            Toast.makeText(this, "当前摄像头没有闪光灯", Toast.LENGTH_SHORT).show()
            return
        }
        torchEnabled = !torchEnabled
        activeCamera.cameraControl.enableTorch(torchEnabled)
        torchButton.contentDescription = if (torchEnabled) "关闭手电筒" else "打开手电筒"
        torchButton.isSelected = torchEnabled
    }

    private fun deliverResult(value: String) {
        android.os.Handler(mainLooper).post {
            sendBroadcast(
                Intent(ScanPreferences.ACTION_RESULT)
                    .setPackage(packageName)
                    .putExtra(ScanPreferences.EXTRA_TEXT, value)
                    // 只有悬浮扫描模式才允许自动回车；普通扫描模式保留原有输入行为。
                    .putExtra(ScanPreferences.EXTRA_ENTER, ScanPreferences.autoEnter(this)),
            )
            if (ScanPreferences.vibrate(this)) {
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (vibrator?.hasVibrator() == true) {
                    vibrator.vibrate(VibrationEffect.createOneShot(55L, VibrationEffect.DEFAULT_AMPLITUDE))
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "扫码悬浮窗", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder.setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("青简正在持续扫码")
            .setContentText("悬浮扫码窗不会切换当前输入框焦点")
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        cameraProvider?.unbindAll()
        if (::scanner.isInitialized) scanner.close()
        saveWindowSize()
        if (::root.isInitialized) runCatching { windowManager.removeView(root) }
        executor.shutdown()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private inner class ScanAreaView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val prefs = getSharedPreferences("barcode_scan_area", MODE_PRIVATE)
        private val rect = RectF()
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = themePalette?.brand ?: 0xFF55E6D0.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 3f * density
        }
        private val outsideShade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xB8000000.toInt()
            style = Paint.Style.FILL
        }
        private val insideGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = (themePalette?.brand ?: 0xFF55E6D0.toInt()).let { (it and 0x00FFFFFF) or 0x18000000 }
            style = Paint.Style.FILL
        }
        private var downX = 0f
        private var downY = 0f
        private var start = RectF()
        private var resizing = false
        private var touchInScanArea = false
        private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val activeCamera = camera ?: return false
                val zoom = activeCamera.cameraInfo.zoomState.value ?: return false
                val next = (zoom.zoomRatio * detector.scaleFactor)
                    .coerceIn(zoom.minZoomRatio, zoom.maxZoomRatio)
                activeCamera.cameraControl.setZoomRatio(next)
                return true
            }
        })

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            if (w <= 0 || h <= 0) return
            if (rect.isEmpty) {
                rect.set(
                    prefs.getFloat("left", .12f) * w,
                    prefs.getFloat("top", .30f) * h,
                    prefs.getFloat("right", .88f) * w,
                    prefs.getFloat("bottom", .68f) * h,
                )
                clampRect()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // 使用偶奇填充只压暗扫描框外部；框内保持清晰，并叠加轻微主题色高亮。
            val mask = android.graphics.Path().apply {
                fillType = android.graphics.Path.FillType.EVEN_ODD
                addRect(0f, 0f, width.toFloat(), height.toFloat(), android.graphics.Path.Direction.CW)
                addRect(rect, android.graphics.Path.Direction.CW)
            }
            canvas.drawPath(mask, outsideShade)
            // 只绘制直角矩形边框，去掉重复圆角轮廓。
            canvas.drawRect(rect, insideGlow)
            canvas.drawRect(rect, stroke)
        }

        fun boundsInView(): RectF = RectF(rect)

        private fun clampRect() {
            val minW = 100f * density
            val minH = 48f * density
            if (width <= 0 || height <= 0) return
            rect.left = rect.left.coerceIn(0f, (width - minW).coerceAtLeast(0f))
            rect.top = rect.top.coerceIn(0f, (height - minH).coerceAtLeast(0f))
            rect.right = rect.right.coerceIn(rect.left + minW, width.toFloat())
            rect.bottom = rect.bottom.coerceIn(rect.top + minH, height.toFloat())
        }

        private fun saveRect() {
            if (width <= 0 || height <= 0) return
            prefs.edit().putFloat("left", rect.left / width).putFloat("top", rect.top / height)
                .putFloat("right", rect.right / width).putFloat("bottom", rect.bottom / height).apply()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            scaleDetector.onTouchEvent(event)
            // 双指捏合只调整镜头变焦倍率，不改变悬浮窗尺寸；窗口大小与相机视野倍率互不绑定。
            if (event.pointerCount > 1 || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN ||
                event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) touchInScanArea = false
                return true
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    start = RectF(rect)
                    resizing = event.x >= start.right - 28f * density && event.y >= start.bottom - 28f * density
                    touchInScanArea = rect.contains(event.x, event.y)
                    // 整个预览层都接收触摸，以便在扫描框之外也能双指缩放镜头。
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!touchInScanArea) return true
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (resizing) {
                        rect.right = (start.right + dx).coerceIn(start.left + 100f * density, width.toFloat())
                        rect.bottom = (start.bottom + dy).coerceIn(start.top + 48f * density, height.toFloat())
                    } else {
                        val w = start.width()
                        val h = start.height()
                        rect.set(
                            (start.left + dx).coerceIn(0f, (width - w).coerceAtLeast(0f)),
                            (start.top + dy).coerceIn(0f, (height - h).coerceAtLeast(0f)),
                            0f,
                            0f,
                        )
                        rect.right = rect.left + w
                        rect.bottom = rect.top + h
                    }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> { if (touchInScanArea) saveRect(); touchInScanArea = false; return true }
                MotionEvent.ACTION_CANCEL -> return true
            }
            return true
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 2921
        private const val CHANNEL_ID = "qingjian_scan_overlay"
        private const val WINDOW_PREFS = "scan_overlay_window"
        private const val KEY_WIDTH_DP = "width_dp"
        private const val KEY_HEIGHT_DP = "height_dp"
        private const val DUPLICATE_SUPPRESS_MS = 1_500L
    }
}
