package app.qingjian.inputmethod.scan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
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
import android.view.ViewOutlineProvider
import android.view.ViewGroup
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
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
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
import com.google.zxing.BarcodeFormat as ZxingBarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.InvertedLuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ResultPoint
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import app.qingjian.inputmethod.R
import app.qingjian.inputmethod.core.AppLog
import app.qingjian.inputmethod.theme.Palette
import app.qingjian.inputmethod.panel.base.PanelIcons
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 单窗口系统悬浮扫码窗：默认 NOT_FOCUSABLE，不干扰宿主输入框；
 * 仅编辑扫码前缀时临时取得焦点并显示系统输入法。
 */
class ScanOverlayService : Service(), LifecycleOwner {
    /** 同时写入 Android logcat 与设置中可打包的本机诊断日志；不记录扫码文本内容。 */
    private fun scanDiag(message: String, warning: Boolean = false) {
        val line = "[SCAN_DIAG] $message"
        if (warning) { AppLog.warn(line); android.util.Log.w(TAG, line) }
        else { AppLog.info(line); android.util.Log.i(TAG, line) }
    }
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private lateinit var windowManager: WindowManager
    /** 悬浮窗 View 必须使用关联显示器的 WindowContext，避免 Android 16 浮动工具栏取 Display 时崩溃。 */
    private lateinit var overlayContext: Context
    private lateinit var windowParams: WindowManager.LayoutParams
    private var overlayTopY = 0
    private lateinit var headerView: View
    private lateinit var overlayRoot: FrameLayout
    private lateinit var root: FrameLayout
    private lateinit var preview: PreviewView
    private lateinit var scanArea: ScanAreaView
    private lateinit var previewResizeDetector: ScaleGestureDetector
    private lateinit var scanner: BarcodeScanner
    private lateinit var textRecognizer: TextRecognizer
    private var imageCapture: ImageCapture? = null
    @Volatile private var ocrMode = false
    private lateinit var captureButton: ScanToolbarIconView
    private lateinit var modeButton: ScanToolbarIconView
    private lateinit var shapeButton: ScanToolbarIconView
    private var squareScanShape = true
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var torchEnabled = false
    private val themePalette by lazy { runCatching { Palette.of(this) }.getOrNull() }

    /** 把当前主题品牌色融入磨砂玻璃底色，同时保留主题表面的明暗层次。 */
    private fun adaptiveGlassColor(base: Int, brand: Int, mix: Float, alpha: Int): Int {
        val amount = mix.coerceIn(0f, 1f)
        val red = (Color.red(base) * (1f - amount) + Color.red(brand) * amount).toInt()
        val green = (Color.green(base) * (1f - amount) + Color.green(brand) * amount).toInt()
        val blue = (Color.blue(base) * (1f - amount) + Color.blue(brand) * amount).toInt()
        return Color.argb(alpha.coerceIn(0, 255), red, green, blue)
    }

    private lateinit var torchButton: ScanToolbarIconView
    private var prefixEditor: EditText? = null
    private var prefixEditingRequested = false
    private var prefixEditorHadFocus = false
    private var prefixImeRequested = false
    private val executor = Executors.newSingleThreadExecutor()
    private val zxingFallbackExecutor = Executors.newSingleThreadExecutor()
    private val processing = AtomicBoolean(false)
    private val lifecycleGuard = Any()
    private val clientsClosed = AtomicBoolean(false)
    private val analyzedFrameCount = java.util.concurrent.atomic.AtomicLong()
    private val barcodeResultFrameCount = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var shuttingDown = false
    @Volatile private var startupFailed = false
    private var analysisUseCase: ImageAnalysis? = null
    // 同一条码在连续视频帧中会反复命中，短时间内只广播和震动一次。
    private val lastDeliveredAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** 首次命中先暂存，要求相邻视频帧重复识别到同一内容且位置稳定后才提交，过滤单帧误读。 */
    private data class StableBarcodeHit(val lastAt: Long, val count: Int, val centerX: Float, val centerY: Float)
    private val stableBarcodeHits = java.util.concurrent.ConcurrentHashMap<String, StableBarcodeHit>()
    @Volatile private var cameraWarmupUntil = 0L
    private val windowPrefs by lazy { getSharedPreferences(WINDOW_PREFS, MODE_PRIVATE) }
    private val transformFactory = ImageProxyTransformFactory().apply {
        setUsingCropRect(true)
        setUsingRotationDegrees(true)
    }

    override fun onCreate() {
        super.onCreate()
        var startupStage = "initialization"
        try {
            scanDiag("onCreate pid=${android.os.Process.myPid()}")
            prefixEditingRequested = false
            prefixEditorHadFocus = false
            ScanPreferences.setPrefixEditorActive(false)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            startupStage = "notification channel"
            createNotificationChannel()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                startupFailed = true
                scanDiag("startup stopped: CAMERA permission missing", warning = true)
                stopSelf()
                return
            }
            startupStage = "camera foreground service"
            try {
                startForeground(NOTIFICATION_ID, buildNotification())
            } catch (error: SecurityException) {
                startupFailed = true
                scanDiag("foreground service rejected: ${error.javaClass.simpleName}: ${error.message}", warning = true)
                AppLog.error("[SCAN_DIAG] foreground service stack=${android.util.Log.getStackTraceString(error)}")
                stopSelf()
                return
            }
            startupStage = "overlay window context"
            overlayContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // A Service Context may not be associated with a display on Android 11+.
                // Bind explicitly to the default display before creating the overlay WindowContext.
                val displayManager = getSystemService(android.hardware.display.DisplayManager::class.java)
                val display = displayManager?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
                    ?: throw IllegalStateException("Default display is unavailable for scan overlay")
                createDisplayContext(display)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            } else this
            windowManager = overlayContext.getSystemService(WindowManager::class.java)
            scanDiag("overlay window context created sdk=${Build.VERSION.SDK_INT} displayAssociated=${runCatching { overlayContext.display != null }.getOrDefault(false)}")
            startupStage = "barcode recognizers"
            ocrMode = false
            squareScanShape = windowPrefs.getBoolean(KEY_SQUARE_SCAN_SHAPE, false)
            windowPrefs.edit().putBoolean(KEY_OCR_MODE, false).apply()
            textRecognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(
                Barcode.FORMAT_QR_CODE, Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39, Barcode.FORMAT_CODE_93,
                Barcode.FORMAT_CODABAR, Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_ITF,
                Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E, Barcode.FORMAT_PDF417, Barcode.FORMAT_AZTEC, Barcode.FORMAT_DATA_MATRIX,
            ).build())
            startupStage = "overlay view creation"
            buildOverlay()
            startupStage = "camera provider request"
            startCamera()
            scanDiag("scanner startup sequence accepted")
        } catch (error: Exception) {
            startupFailed = true
            val detail = "startup failed stage=$startupStage error=${error.javaClass.simpleName}: ${error.message}"
            scanDiag(detail, warning = true)
            android.util.Log.e(TAG, detail, error)
            AppLog.error("[SCAN_DIAG] $detail\n${android.util.Log.getStackTraceString(error)}")
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (startupFailed) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            scanDiag("explicit ACTION_STOP received", warning = true)
            stopSelf()
            return START_NOT_STICKY
        }
        scanDiag("onStartCommand startId=$startId flags=$flags action=${intent?.action} shuttingDown=$shuttingDown")
        // 相机悬浮服务不应在系统回收后自动重启并重新打开相机；由用户再次主动点击扫码启动。
        return START_NOT_STICKY
    }

    private fun buildOverlay() {
        val viewContext = overlayContext
        val density = viewContext.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        preview = PreviewView(viewContext).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            // 不再使用半透明预览；悬浮窗内部显示完整、清晰的相机画面。
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            alpha = 1f
            setBackgroundColor(Color.TRANSPARENT)
        }
        scanArea = ScanAreaView(viewContext)
        root = FrameLayout(viewContext).apply {
            background = GradientDrawable().apply {
                setColor(0x01000000)
                // 相机区域顶部保持直边，与标题栏下边缘拼成一体；只圆润窗口底部。
                cornerRadii = floatArrayOf(
                    0f, 0f, 0f, 0f,
                    dp(18).toFloat(), dp(18).toFloat(),
                    dp(18).toFloat(), dp(18).toFloat(),
                )
                // 标题栏下沿由预览画面覆盖 1dp，避免两块背景边线形成视觉缝隙。
            }
            clipToOutline = true
            clipChildren = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
        }
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))
        root.addView(scanArea, FrameLayout.LayoutParams(-1, -1))

        // 自适应紧凑标题栏：宽度足够时前缀、过滤开关和图标保持同一行；不足时才换成两行。
        val prefixRow = LinearLayout(viewContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val prefixInput = EditText(viewContext).apply {
            setSingleLine(true)
            textSize = 13f
            setTextColor(themePalette?.text ?: 0xFFFFFFFF.toInt())
            setHintTextColor(themePalette?.gloss ?: themePalette?.text ?: 0xFFFFFFFF.toInt())
            hint = "输入扫码前缀"
            setPadding(dp(9), 0, dp(7), 0)
            // 输入框与标题栏共用背景，不再单独绘制色块或边框。
            setBackgroundColor(Color.TRANSPARENT)
            setText(ScanPreferences.prefix(this@ScanOverlayService))
            imeOptions = EditorInfo.IME_ACTION_DONE
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        prefixEditor = prefixInput
        prefixRow.addView(prefixInput, LinearLayout.LayoutParams(dp(112), dp(36)))

        val prefixSwitch = Switch(viewContext).apply {
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

        // 前缀框按实际文字宽度伸缩，避免短前缀占满整行，也避免长前缀被挤得看不清。
        fun resizePrefixEditor() {
            val contentWidth = prefixInput.paint.measureText(prefixInput.text?.toString().orEmpty())
            val hintWidth = prefixInput.paint.measureText(prefixInput.hint?.toString().orEmpty())
            val target = (maxOf(contentWidth, hintWidth) + dp(20)).toInt().coerceIn(dp(96), dp(156))
            val params = prefixInput.layoutParams as? LinearLayout.LayoutParams ?: return
            if (params.width != target || params.weight != 0f) {
                params.width = target
                params.weight = 0f
                prefixInput.layoutParams = params
            }
        }
        prefixInput.post { resizePrefixEditor() }
        prefixInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                resizePrefixEditor()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        // 第一次触摸按下就先把窗口切换为可聚焦，避免等 ACTION_UP 的 click 回调后
        // 才更新 WindowManager flags，导致用户必须再次点击才能真正进入输入状态。
        fun beginPrefixEditing() {
            scanDiag("prefix activate requested=$prefixEditingRequested focused=${prefixInput.hasFocus()} windowFocusable=${windowParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0} textLength=${prefixInput.text?.length ?: 0}")
            if (prefixEditingRequested) return
            prefixEditingRequested = true
            prefixEditorHadFocus = false
            prefixImeRequested = false
            ScanPreferences.setPrefixEditorActive(true)
            runCatching {
                windowParams.flags = windowParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                windowParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                windowManager.updateViewLayout(overlayRoot, windowParams)
                overlayRoot.requestFocusFromTouch()
                prefixInput.requestFocusFromTouch()
                prefixInput.requestFocus()
                scanDiag("prefix focus requested root=${overlayRoot.hasFocus()} editor=${prefixInput.hasFocus()} window=${overlayRoot.hasWindowFocus()}")
                fun requestPrefixImeWhenWindowReady(attempt: Int) {
                    prefixInput.postDelayed({
                        if (!prefixEditingRequested || prefixEditor !== prefixInput) return@postDelayed
                        if (!overlayRoot.hasWindowFocus()) {
                            if (attempt < 15) requestPrefixImeWhenWindowReady(attempt + 1)
                            else scanDiag("prefix window focus timeout attempts=${attempt + 1}", warning = true)
                            return@postDelayed
                        }
                        runCatching {
                            overlayRoot.requestFocusFromTouch()
                            prefixInput.requestFocusFromTouch()
                            prefixInput.requestFocus()
                            val imm = overlayContext.getSystemService(InputMethodManager::class.java)
                            val accepted = imm?.showSoftInput(prefixInput, InputMethodManager.SHOW_IMPLICIT) == true
                            prefixImeRequested = accepted
                            scanDiag("prefix IME request accepted=$accepted active=${imm?.isActive(prefixInput)} editorFocused=${prefixInput.hasFocus()} windowFocused=${overlayRoot.hasWindowFocus()} attempt=${attempt + 1}")
                            if (!accepted && attempt < 15) requestPrefixImeWhenWindowReady(attempt + 1)
                        }.onFailure {
                            prefixImeRequested = false
                            if (attempt < 15) requestPrefixImeWhenWindowReady(attempt + 1)
                            else scanDiag("prefix editor focus/IME request failed: ${it.javaClass.simpleName}", warning = true)
                        }
                    }, 60L)
                }
                requestPrefixImeWhenWindowReady(0)
            }.onFailure {
                prefixEditingRequested = false
                ScanPreferences.setPrefixEditorActive(false)
                prefixImeRequested = false
                scanDiag("prefix editor window activation failed: ${it.javaClass.simpleName}", warning = true)
            }
        }
        prefixInput.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    prefixInput.parent?.requestDisallowInterceptTouchEvent(true)
                    beginPrefixEditing()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    prefixInput.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        prefixInput.setOnClickListener { beginPrefixEditing() }
        prefixInput.setOnFocusChangeListener { _, focused ->
            scanDiag("prefix editor focus changed focused=$focused requested=$prefixEditingRequested imeRequested=$prefixImeRequested")
            if (focused) prefixEditorHadFocus = true
        }
        prefixInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                finishPrefixEditing()
                true
            } else false
        }

        // 标题栏用图标切换模式：扫码状态显示相机图标，识图状态显示二维码图标。
        modeButton = ScanToolbarIconView(viewContext, PanelIcons.Icon.CAMERA, "切换到拍照识图").apply {
            setOnClickListener {
                setOcrMode(!ocrMode)
                Toast.makeText(
                    this@ScanOverlayService,
                    if (ocrMode) "已切换到拍照识图，点击相机快门拍照" else "已切换到连续扫码",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }

        val toolbar = LinearLayout(viewContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }
        // 横向布局时用独立弹性间隔把工具按钮推到右侧，前缀区本身只占实际内容宽度。
        val headerSpacer = View(viewContext)
        toolbar.addView(modeButton, LinearLayout.LayoutParams(dp(40), dp(40)))
        shapeButton = ScanToolbarIconView(
            this,
            if (squareScanShape) PanelIcons.Icon.SCAN_SQUARE else PanelIcons.Icon.SCAN_RECTANGLE,
            if (squareScanShape) "切换为自定义长方形扫描区域" else "切换为正方形扫描区域",
        ).apply {
            setOnClickListener { setScanShape(!squareScanShape) }
        }
        toolbar.addView(shapeButton, LinearLayout.LayoutParams(dp(40), dp(40)))
        torchButton = ScanToolbarIconView(viewContext, PanelIcons.Icon.FLASHLIGHT, "打开手电筒").apply {
            setOnClickListener { finishPrefixEditing(); toggleTorch() }
        }
        toolbar.addView(torchButton, LinearLayout.LayoutParams(dp(40), dp(40)))
        captureButton = ScanToolbarIconView(viewContext, PanelIcons.Icon.CAMERA, "拍照识别文字").apply {
            setIconColor(0xFFFFFFFF.toInt())
            visibility = if (ocrMode) View.VISIBLE else View.GONE
            isEnabled = ocrMode
            alpha = if (ocrMode) 1f else 0f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC17212F.toInt())
                setStroke(dp(2), 0xEEFFFFFF.toInt())
            }
            setOnClickListener {
                finishPrefixEditing()
                if (ocrMode) capturePhotoForOcr()
            }
        }
        val closeButton = ScanToolbarIconView(viewContext, PanelIcons.Icon.CLOSE, "关闭扫码窗口").apply {
            setOnClickListener { scanDiag("close button tapped", warning = true); finishPrefixEditing(); stopSelf() }
        }
        toolbar.addView(closeButton, LinearLayout.LayoutParams(dp(36), dp(40)))
        // 整个标题栏由 DraggableHeaderLayout 统一处理拖动；子控件短按仍由自身接收。

        val header = object : DraggableHeaderLayout(viewContext) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
                // 340dp 默认窗口可容纳紧凑单行；只有小于约 300dp 才换成两行。
                val compact = availableWidth < dp(300)
                orientation = if (compact) VERTICAL else HORIZONTAL
                prefixRow.layoutParams = if (compact) {
                    LayoutParams(LayoutParams.MATCH_PARENT, dp(40))
                } else {
                    LayoutParams(LayoutParams.WRAP_CONTENT, dp(40))
                }
                headerSpacer.visibility = if (compact) View.GONE else View.VISIBLE
                headerSpacer.layoutParams = if (compact) {
                    LayoutParams(0, 0)
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
            // 标题栏使用完全不透明的主题表面色，避免相机画面从标题栏透出。
            setPadding(dp(7), dp(2), dp(7), 0)
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(themePalette?.surface ?: 0xFF303846.toInt())
                cornerRadii = floatArrayOf(
                    dp(16).toFloat(), dp(16).toFloat(),
                    dp(16).toFloat(), dp(16).toFloat(),
                    0f, 0f, 0f, 0f,
                )
                setStroke(dp(1), themePalette?.brand ?: 0xFF55E6D0.toInt())
            }
            // 使用显式凸轮廓裁剪，而不是依赖透明背景 drawable 自动生成 Outline。
            // 全透明 drawable 在部分 Android 窗口实现中没有有效 outline，移动窗口时会露出矩形角。
            clipToOutline = true
            clipChildren = true
            outlineProvider = headerOutlineProvider(dp(16).toFloat())
            addView(prefixRow)
            addView(headerSpacer)
            addView(toolbar)
        }
        // Switch 自身必须保留触摸序列；不能把它交给会消费事件的标题栏拖动监听。
        // 标题栏与相机预览属于同一窗口，不启用系统级窗口后方模糊，避免影响整块屏幕。
        headerView = header

        val resize = TextView(viewContext).apply {
            text = "↘"
            textSize = 25f
            // 仅保留中性白色的缩放提示，不给相机画面叠加主题色底块。
            setTextColor(0xCCFFFFFF.toInt())
            gravity = Gravity.CENTER
            setBackgroundColor(Color.TRANSPARENT)
            setOnTouchListener { _, event -> resizeWindow(event) }
        }
        root.addView(resize, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.RIGHT or Gravity.BOTTOM))
        // 拍照按钮属于相机画面，采用常见相机底部中央快门布局。
        root.addView(captureButton, FrameLayout.LayoutParams(dp(60), dp(60), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(14)
        })
        attachCaptureButtonDrag()
        setOcrMode(ocrMode)
        setScanShape(squareScanShape, persist = false)

        val maxWidth = (resources.displayMetrics.widthPixels - dp(24)).coerceAtLeast(dp(220))
        val maxPreviewHeight = (resources.displayMetrics.heightPixels - dp(220)).coerceAtLeast(dp(220))
        val maxWidthByHeight = (maxPreviewHeight * 3f / 4f).toInt().coerceAtLeast(dp(220))
        val width = dp(windowPrefs.getInt(KEY_WIDTH_DP, 340)).coerceIn(dp(220), minOf(maxWidth, maxWidthByHeight))
        val previewHeight = (width * 4f / 3f).toInt()
        val headerHeightEstimate = dp(if (width < dp(300)) 82 else 42)
        val seamOverlap = dp(1)
        windowParams = WindowManager.LayoutParams(
            width, previewHeight + headerHeightEstimate - seamOverlap,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(windowPrefs.getInt(KEY_WINDOW_X_DP, 16))
            y = dp(windowPrefs.getInt(KEY_WINDOW_TOP_DP, 120))
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
        previewResizeDetector = ScaleGestureDetector(
            viewContext,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    resizePreviewByScale(detector.scaleFactor)
                    return true
                }
            },
        )
        overlayRoot = object : FrameLayout(viewContext) {
            override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
                // 缩放手势只在相机预览区生效；标题栏按钮、前缀输入框仍保留原有点击与拖动。
                val pointersInsidePreview = ::headerView.isInitialized &&
                    (0 until event.pointerCount).all { event.getY(it) >= headerView.bottom }
                if (pointersInsidePreview || previewResizeDetector.isInProgress) {
                    previewResizeDetector.onTouchEvent(event)
                }
                return previewResizeDetector.isInProgress || super.onInterceptTouchEvent(event)
            }
        }.apply {
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            clipChildren = false
            clipToPadding = false
            setBackgroundColor(Color.TRANSPARENT)
        }
        overlayRoot.addView(headerView, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        overlayRoot.addView(root, FrameLayout.LayoutParams(-1, -1, Gravity.TOP).apply {
            topMargin = (headerHeightEstimate - seamOverlap).coerceAtLeast(0)
        })
        overlayRoot.alpha = 0f
        overlayRoot.scaleX = 0.94f
        overlayRoot.scaleY = 0.94f
        windowManager.addView(overlayRoot, windowParams)
        scanDiag("single overlay attached width=${windowParams.width} height=${windowParams.height} type=${windowParams.type}")
        overlayRoot.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(220L)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.35f))
            .start()
        restoreCaptureButtonPosition()
        var appliedHeaderHeight = headerHeightEstimate
        headerView.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val actualHeaderHeight = (bottom - top).coerceAtLeast(0)
            if (actualHeaderHeight > 0 && actualHeaderHeight != appliedHeaderHeight) {
                // 标题栏换行或尺寸变化时，预览区顶边始终精确贴合标题栏底边。
                val oldPreviewTop = (appliedHeaderHeight - seamOverlap).coerceAtLeast(0)
                val newPreviewTop = (actualHeaderHeight - seamOverlap).coerceAtLeast(0)
                val previewHeight = (windowParams.height - oldPreviewTop).coerceAtLeast(dp(220))
                val previewParams = root.layoutParams as? FrameLayout.LayoutParams
                if (previewParams != null && previewParams.topMargin != newPreviewTop) {
                    previewParams.topMargin = newPreviewTop
                    root.layoutParams = previewParams
                }
                windowParams.height = previewHeight + newPreviewTop
                appliedHeaderHeight = actualHeaderHeight
                scanDiag("header-preview bounds synchronized headerHeight=$actualHeaderHeight previewTopMargin=${previewParams?.topMargin}")
                runCatching { windowManager.updateViewLayout(overlayRoot, windowParams) }
                    .onFailure { scanDiag("header-preview layout update failed: ${it.javaClass.simpleName}", warning = true) }
            }
        }
    }

    /**
     * 子控件先正常处理点击；手指移动超过 touch slop 才由父容器拦截并拖动整个单窗口。
     * 模式、闪光灯、关闭按钮和前缀输入框因此既能短按操作，也能按住拖动。
     */
    private open inner class DraggableHeaderLayout(context: Context) : LinearLayout(context) {
        private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        private var dragging = false
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startGroupY = 0
        init { isClickable = true }

        private fun captureDragStart(event: MotionEvent) {
            downRawX = event.rawX
            downRawY = event.rawY
            startX = windowParams.x
            startGroupY = windowParams.y
        }

        private fun updateDrag(event: MotionEvent) {
            windowParams.x = (startX + event.rawX - downRawX).toInt()
                .coerceIn(0, (resources.displayMetrics.widthPixels - windowParams.width).coerceAtLeast(0))
            setOverlayTop(startGroupY + (event.rawY - downRawY).toInt())
            updateOverlayPositions()
        }

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = false
                    captureDragStart(event)
                    return false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging &&
                        (kotlin.math.abs(event.rawX - downRawX) > touchSlop ||
                         kotlin.math.abs(event.rawY - downRawY) > touchSlop)) {
                        dragging = true
                        finishPrefixEditing()
                        return true
                    }
                    return dragging
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return dragging
            }
            return dragging
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // 空白区域没有子 View 成为 touch target，后续 MOVE 不一定再经过 onIntercept；
                    // 因此父容器自己也完整处理空白区域的长按拖动序列。
                    if (!dragging) captureDragStart(event)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging &&
                        (kotlin.math.abs(event.rawX - downRawX) > touchSlop ||
                         kotlin.math.abs(event.rawY - downRawY) > touchSlop)) {
                        dragging = true
                        finishPrefixEditing()
                    }
                    if (dragging) updateDrag(event)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) saveWindowPosition()
                    dragging = false
                    return true
                }
            }
            return true
        }
    }

    private fun saveWindowPosition() {
        if (!::windowParams.isInitialized) return
        val density = resources.displayMetrics.density.coerceAtLeast(0.1f)
        windowPrefs.edit()
            .putInt(KEY_WINDOW_X_DP, (windowParams.x / density).toInt())
            .putInt(KEY_WINDOW_TOP_DP, (windowParams.y / density).toInt())
            .apply()
    }

    /** 恢复拍照按钮在预览区域内的位置；比例坐标可适配窗口尺寸变化。 */
    private fun restoreCaptureButtonPosition() {
        if (!::root.isInitialized || !::captureButton.isInitialized ||
            captureButton.visibility != View.VISIBLE ||
            !windowPrefs.contains(KEY_CAPTURE_X_RATIO) || !windowPrefs.contains(KEY_CAPTURE_Y_RATIO)) return
        root.post {
            if (root.width <= 0 || root.height <= 0 || captureButton.width <= 0 || captureButton.height <= 0) {
                // 首次布局或从扫码模式切到拍照模式时，等预览与快门完成测量再恢复。
                root.post { restoreCaptureButtonPosition() }
                return@post
            }
            val maxX = (root.width - captureButton.width).coerceAtLeast(0)
            val maxY = (root.height - captureButton.height).coerceAtLeast(0)
            val x = (windowPrefs.getFloat(KEY_CAPTURE_X_RATIO, 0.5f) * maxX).toInt().coerceIn(0, maxX)
            val y = (windowPrefs.getFloat(KEY_CAPTURE_Y_RATIO, 1f) * maxY).toInt().coerceIn(0, maxY)
            (captureButton.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                params.gravity = Gravity.TOP or Gravity.START
                params.leftMargin = x
                params.topMargin = y
                params.rightMargin = 0
                params.bottomMargin = 0
                captureButton.layoutParams = params
            }
        }
    }

    /** 拍照按钮可在预览区拖动；靠近底部中央时吸附回标准快门位置。 */
    private fun attachCaptureButtonDrag() {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0f
        var startY = 0f
        var dragging = false
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        captureButton.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = view.x
                    startY = view.y
                    dragging = false
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) {
                        dragging = true
                        view.isPressed = false
                    }
                    if (dragging) {
                        val maxX = (root.width - view.width).coerceAtLeast(0)
                        val maxY = (root.height - view.height).coerceAtLeast(0)
                        val x = (startX + dx).toInt().coerceIn(0, maxX)
                        val y = (startY + dy).toInt().coerceIn(0, maxY)
                        val params = view.layoutParams as FrameLayout.LayoutParams
                        params.gravity = Gravity.TOP or Gravity.START
                        params.leftMargin = x
                        params.topMargin = y
                        params.rightMargin = 0
                        params.bottomMargin = 0
                        view.layoutParams = params
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                        view.performClick()
                        return@setOnTouchListener true
                    }
                    val params = view.layoutParams as FrameLayout.LayoutParams
                    val centerX = params.leftMargin + view.width / 2f
                    val bottomDistance = root.height - (params.topMargin + view.height)
                    var savedX = params.leftMargin.toFloat()
                    var savedY = params.topMargin.toFloat()
                    if (kotlin.math.abs(centerX - root.width / 2f) <= root.width * 0.18f &&
                        bottomDistance <= root.height * 0.22f) {
                        params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                        params.leftMargin = 0
                        params.topMargin = 0
                        params.rightMargin = 0
                        params.bottomMargin = (14f * resources.displayMetrics.density).toInt()
                        savedX = (root.width - view.width) / 2f
                        savedY = (root.height - view.height - params.bottomMargin).coerceAtLeast(0).toFloat()
                        view.layoutParams = params
                    }
                    val maxX = (root.width - view.width).coerceAtLeast(1)
                    val maxY = (root.height - view.height).coerceAtLeast(1)
                    windowPrefs.edit()
                        .putFloat(KEY_CAPTURE_X_RATIO, savedX / maxX)
                        .putFloat(KEY_CAPTURE_Y_RATIO, savedY / maxY)
                        .apply()
                    dragging = false
                    view.isPressed = false
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    view.isPressed = false
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    true
                }
                else -> true
            }
        }
    }

    /** 单窗口统一维护坐标，预览始终位于标题栏下方。 */
    private fun setOverlayTop(requestedTop: Int) {
        if (!::windowParams.isInitialized || !::overlayRoot.isInitialized) return
        val displayHeight = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.height()
        } else {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            metrics.heightPixels
        }
        overlayTopY = requestedTop.coerceIn(0, (displayHeight - windowParams.height).coerceAtLeast(0))
        windowParams.y = overlayTopY
    }

    private fun updateOverlayPositions() {
        if (!::overlayRoot.isInitialized || !::windowParams.isInitialized) return
        setOverlayTop(windowParams.y)
        runCatching { windowManager.updateViewLayout(overlayRoot, windowParams) }
    }

    /** 标题栏在统一窗口中仍保留顶部圆角裁剪轮廓。 */
    private fun headerOutlineProvider(radiusPx: Float): ViewOutlineProvider =
        object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (view.width <= 0 || view.height <= 0) {
                    outline.setEmpty()
                    return
                }
                val radius = radiusPx.coerceAtMost(view.width / 2f)
                outline.setRoundRect(0, 0, view.width, view.height + radius.toInt(), radius)
                outline.alpha = 1f
            }
        }

    private fun setScanShape(square: Boolean, persist: Boolean = true) {
        squareScanShape = square
        if (persist) windowPrefs.edit().putBoolean(KEY_SQUARE_SCAN_SHAPE, square).apply()
        if (::shapeButton.isInitialized) {
            shapeButton.setIcon(
                if (square) PanelIcons.Icon.SCAN_SQUARE else PanelIcons.Icon.SCAN_RECTANGLE,
                if (square) "切换为自定义长方形扫描区域" else "切换为正方形扫描区域",
            )
        }
        if (::scanArea.isInitialized) scanArea.post {
            // 强制按当前开关重建扫描框：首次创建时 squareMode 的默认值并不代表
            // SharedPreferences 中上一次保存的形状，不能因状态值相同而跳过矩形恢复。
            if (squareScanShape == square) scanArea.setShape(square, force = true)
        }
    }

    private fun finishPrefixEditing() {
        scanDiag("finish prefix editing requested=$prefixEditingRequested hadFocus=$prefixEditorHadFocus imeRequested=$prefixImeRequested")
        val editor = prefixEditor
        // 预览层也会调用本方法。只有用户确实编辑过前缀时，才允许隐藏软键盘；
        // 否则悬浮标题栏的 windowToken 可能连带关闭微信/Excel 当前使用的输入法。
        val wasEditing = prefixEditingRequested || prefixEditorHadFocus || editor?.hasFocus() == true
        if (!wasEditing) return

        prefixEditingRequested = false
        prefixEditorHadFocus = false
        prefixImeRequested = false
        ScanPreferences.setPrefixEditorActive(false)
        if (editor?.hasFocus() == true) {
            runCatching {
                (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.hideSoftInputFromWindow(editor.windowToken, 0)
            }
            editor.clearFocus()
        }
        if (::windowParams.isInitialized && ::overlayRoot.isInitialized) {
            windowParams.flags = windowParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            updateOverlayPositions()
        }
    }

    /** 与青简 App 面板共用 PanelIcons 的 24dp 线框图标绘制风格。 */
    private inner class ScanToolbarIconView(
        context: Context,
        private var icon: PanelIcons.Icon,
        description: String,
    ) : View(context) {
        private var iconColorOverride: Int? = null
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

        fun setIcon(next: PanelIcons.Icon, description: String) {
            icon = next
            contentDescription = description
            invalidate()
        }

        fun setIconColor(color: Int?) {
            iconColorOverride = color
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            iconPaint.color = iconColorOverride ?: if (isEnabled) {
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
                setOverlayTop(moveStartY + (event.rawY - moveRawY).toInt())
                updateOverlayPositions()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }

    private var resizeRawX = 0f
    private var resizeRawY = 0f
    private var resizeStartW = 0

    private fun resizeWindow(event: MotionEvent): Boolean {
        val density = resources.displayMetrics.density
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resizeRawX = event.rawX
                resizeRawY = event.rawY
                resizeStartW = windowParams.width
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val headerHeight = if (::headerView.isInitialized) headerView.height else (42 * density).toInt()
                val availableHeight = (resources.displayMetrics.heightPixels - windowParams.y - headerHeight - (100 * density).toInt())
                    .coerceAtLeast((220 * density).toInt())
                val maxW = minOf(
                    resources.displayMetrics.widthPixels - windowParams.x,
                    (availableHeight * 3f / 4f).toInt(),
                ).coerceAtLeast((220 * density).toInt())
                val minSize = (220 * density).toInt()
                val deltaX = event.rawX - resizeRawX
                val deltaY = (event.rawY - resizeRawY) * 0.75f
                val delta = if (kotlin.math.abs(deltaX) >= kotlin.math.abs(deltaY)) deltaX else deltaY
                windowParams.width = (resizeStartW + delta).toInt().coerceIn(minSize, maxOf(minSize, maxW))
                windowParams.height = (windowParams.width * 4f / 3f).toInt() + headerHeight - (1 * density).toInt()
                updateOverlayPositions()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                saveWindowSize()
                return true
            }
        }
        return true
    }

    /** 在预览区域双指捏合缩放；始终保持竖屏 3:4 相机画幅，不把画面拉伸成浮框任意比例。 */
    private fun resizePreviewByScale(scaleFactor: Float) {
        if (!::windowParams.isInitialized || !::headerView.isInitialized) return
        val density = resources.displayMetrics.density.coerceAtLeast(0.1f)
        val seamOverlap = density.toInt().coerceAtLeast(1)
        val headerHeight = headerView.height.coerceAtLeast((42 * density).toInt())
        val availableHeight = (resources.displayMetrics.heightPixels - windowParams.y - headerHeight - (100 * density).toInt())
            .coerceAtLeast((220 * density).toInt())
        val minWidth = (220 * density).toInt()
        val maxWidth = minOf(
            resources.displayMetrics.widthPixels - windowParams.x,
            (availableHeight * 3f / 4f).toInt(),
        ).coerceAtLeast(minWidth)
        val newWidth = (windowParams.width * scaleFactor).toInt().coerceIn(minWidth, maxWidth)
        windowParams.width = newWidth
        windowParams.height = (newWidth * 4f / 3f).toInt() + headerHeight - seamOverlap
        updateOverlayPositions()
    }

    private fun saveWindowSize() {
        if (!::windowParams.isInitialized) return
        val density = resources.displayMetrics.density.coerceAtLeast(0.1f)
        windowPrefs.edit()
            .putInt(KEY_WIDTH_DP, (windowParams.width / density).toInt())
            .putInt(KEY_HEIGHT_DP, ((windowParams.height - (if (::headerView.isInitialized) headerView.height else 0)).coerceAtLeast(0) / density).toInt())
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

    private fun closeMlKitClients() {
        if (!clientsClosed.compareAndSet(false, true)) return
        if (::scanner.isInitialized) runCatching { scanner.close() }
        if (::textRecognizer.isInitialized) runCatching { textRecognizer.close() }
    }

    private fun startCamera() {
        if (shuttingDown) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (shuttingDown || lifecycle.currentState == Lifecycle.State.DESTROYED) return@addListener
            try {
            val provider = future.get()
            cameraProvider = provider
            val previewBuilder = Preview.Builder()
                .setTargetResolution(Size(1440, 1080))
            applyScanCaptureQuality(previewBuilder)
            val cameraPreview = previewBuilder.build().also {
                it.setSurfaceProvider(preview.surfaceProvider)
            }
            val analysisBuilder = ImageAnalysis.Builder()
                // 分析帧提升到 720p，改善小型二维码和细条形码的有效像素；仍只处理最新帧。
                .setTargetResolution(Size(1920, 1080))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            applyScanCaptureQuality(analysisBuilder)
            val analysis = analysisBuilder.build()
            analysisUseCase = analysis
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetResolution(Size(1440, 1080))
                .build()
            imageCapture = capture
            analysis.setAnalyzer(executor) { proxy ->
                val media = proxy.image
                val accepted = synchronized(lifecycleGuard) {
                    !shuttingDown && !ocrMode && media != null && processing.compareAndSet(false, true)
                }
                if (!accepted || media == null) {
                    // 识图模式暂停条码分析；忙碌、无图像或服务退出时立即释放当前帧。
                    runCatching { proxy.close() }
                } else {
                    try {
                        val frameNumber = analyzedFrameCount.incrementAndGet()
                        if (frameNumber == 1L || frameNumber % 120L == 0L) {
                            scanDiag("camera analysis active frames=$frameNumber size=${proxy.width}x${proxy.height} rotation=${proxy.imageInfo.rotationDegrees} ocrMode=$ocrMode")
                        }
                        val deferredFrameRelease = AtomicBoolean(false)
                        scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                            .addOnSuccessListener { codes ->
                                if (codes.isNotEmpty()) {
                                    val hits = barcodeResultFrameCount.incrementAndGet()
                                    scanDiag("ML Kit returned ${codes.size} barcode candidates; hitFrames=$hits")
                                }
                                // ML Kit 没有命中时，交给 ZXing Java 再尝试一次；其反色与两种二值化策略
                                // 可补足低对比、反白、细条码等场景。异步解码期间保留 ImageProxy。
                                if (codes.isEmpty() && android.os.SystemClock.elapsedRealtime() >= cameraWarmupUntil) {
                                    deferredFrameRelease.set(true)
                                    try {
                                        zxingFallbackExecutor.execute {
                                            var releaseOnWorker = true
                                            try {
                                                val decoded = decodeWithZxing(proxy)
                                                if (decoded != null) {
                                                    val (value, bounds) = decoded
                                                    ContextCompat.getMainExecutor(this@ScanOverlayService).execute {
                                                        try {
                                                            // 变换与扫描框判断需要预览 View；在主线程处理完再释放 ImageProxy。
                                                            handleZxingFallback(proxy, value, bounds)
                                                        } finally {
                                                            processing.set(false)
                                                            runCatching { proxy.close() }
                                                            if (shuttingDown) closeMlKitClients()
                                                        }
                                                    }
                                                    releaseOnWorker = false
                                                }
                                            } catch (error: Throwable) {
                                                android.util.Log.w(TAG, "ZXing fallback decode failed", error)
                                            } finally {
                                                if (releaseOnWorker) {
                                                    processing.set(false)
                                                    runCatching { proxy.close() }
                                                    if (shuttingDown) closeMlKitClients()
                                                }
                                            }
                                        }
                                    } catch (error: Throwable) {
                                        deferredFrameRelease.set(false)
                                        android.util.Log.w(TAG, "ZXing fallback executor unavailable", error)
                                    }
                                    if (deferredFrameRelease.get()) return@addOnSuccessListener
                                }
                                // 窗口关闭或相机解绑后，迟到的识别回调不再访问 View。
                                if (lifecycle.currentState == Lifecycle.State.DESTROYED ||
                                    !::preview.isInitialized || !::scanArea.isInitialized) return@addOnSuccessListener
                                runCatching {
                                    if (android.os.SystemClock.elapsedRealtime() < cameraWarmupUntil) return@runCatching
                                    val output = preview.outputTransform ?: return@runCatching
                                    val transform = CoordinateTransform(transformFactory.getOutputTransform(proxy), output)
                                    val roi = scanArea.boundsInView()
                                    codes.forEach { code ->
                                        val value = code.rawValue?.trim().orEmpty()
                                        val bounds = code.boundingBox ?: return@forEach
                                        if (value.isBlank() || !ScanPreferences.accepts(this@ScanOverlayService, value)) return@forEach
                                        val mapped = RectF(bounds)
                                        transform.mapRect(mapped)
                                        if (!roi.contains(mapped.centerX(), mapped.centerY())) return@forEach
                                        if (!hasStableBarcode(value, mapped.centerX(), mapped.centerY())) return@forEach
                                        scanDiag("stable barcode accepted center=" + mapped.centerX().toInt() + "," + mapped.centerY().toInt())
                                        if (shouldDeliverCode(value)) deliverResult(value)
                                    }
                                }.onFailure { android.util.Log.w(TAG, "Barcode result mapping failed", it) }
                            }
                            .addOnFailureListener { error ->
                                scanDiag("ML Kit frame analysis failed: ${error.javaClass.simpleName}: ${error.message}", warning = true)
                            }
                            .addOnCompleteListener {
                                if (!deferredFrameRelease.get()) {
                                    processing.set(false)
                                    runCatching { proxy.close() }
                                    if (shuttingDown) closeMlKitClients()
                                }
                            }
                    } catch (error: Throwable) {
                        processing.set(false)
                        runCatching { proxy.close() }
                        android.util.Log.e(TAG, "Unable to submit barcode frame to ML Kit", error)
                        if (shuttingDown) closeMlKitClients()
                    }
                }
            }
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, cameraPreview, analysis, capture)
            scanDiag("camera bound lens=back preview=1440x1080 analysis=1920x1080 capture=1440x1080")
            cameraWarmupUntil = android.os.SystemClock.elapsedRealtime() + CAMERA_WARMUP_MS
            // 对准用户实际调整的扫描框中心触发一次主动对焦；之后仍保持连续自动对焦。
            preview.post {
                if (shuttingDown || preview.width <= 0 || preview.height <= 0) return@post
                runCatching {
                    val roi = scanArea.boundsInView()
                    val point = preview.meteringPointFactory.createPoint(roi.centerX(), roi.centerY())
                    val action = FocusMeteringAction.Builder(point)
                        .setAutoCancelDuration(3, TimeUnit.SECONDS)
                        .build()
                    camera?.cameraControl?.startFocusAndMetering(action)
                }.onFailure { android.util.Log.w(TAG, "Initial scan focus request failed", it) }
            }
            torchButton.isEnabled = camera?.cameraInfo?.hasFlashUnit() == true
            torchButton.alpha = if (torchButton.isEnabled) 1f else 0.4f
            torchButton.contentDescription = if (torchButton.isEnabled) "打开手电筒" else "当前摄像头没有闪光灯"
            } catch (error: Exception) {
                val detail = "camera binding failed: ${error.javaClass.simpleName}: ${error.message}"
                scanDiag(detail, warning = true)
                android.util.Log.e(TAG, detail, error)
                AppLog.error("[SCAN_DIAG] $detail\n${android.util.Log.getStackTraceString(error)}")
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** ZXing 兜底：按 Y 平面行跨度复制紧凑灰度帧，先自适应阈值，再全局直方图，并额外尝试反色。 */
    private fun decodeWithZxing(proxy: androidx.camera.core.ImageProxy): Pair<String, RectF>? {
        val plane = proxy.planes.firstOrNull() ?: return null
        val width = proxy.width
        val height = proxy.height
        if (width < 2 || height < 2) return null
        val luminance = ByteArray(width * height)
        val buffer = plane.buffer.duplicate()
        for (y in 0 until height) {
            val rowStart = y * plane.rowStride
            for (x in 0 until width) {
                val offset = rowStart + x * plane.pixelStride
                if (offset < buffer.limit()) luminance[y * width + x] = buffer.get(offset)
            }
        }
        val source = PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false)
        val formats = listOf(
            ZxingBarcodeFormat.QR_CODE, ZxingBarcodeFormat.CODE_128, ZxingBarcodeFormat.CODE_39,
            ZxingBarcodeFormat.CODE_93, ZxingBarcodeFormat.CODABAR, ZxingBarcodeFormat.EAN_13,
            ZxingBarcodeFormat.EAN_8, ZxingBarcodeFormat.ITF, ZxingBarcodeFormat.UPC_A,
            ZxingBarcodeFormat.UPC_E, ZxingBarcodeFormat.PDF_417, ZxingBarcodeFormat.AZTEC,
            ZxingBarcodeFormat.DATA_MATRIX,
        )
        val hints = mapOf<DecodeHintType, Any>(
            DecodeHintType.POSSIBLE_FORMATS to formats,
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.ALSO_INVERTED to true,
        )
        val reader = MultiFormatReader()
        reader.setHints(hints)
        val variants = listOf(source, InvertedLuminanceSource(source))
        for (variant in variants) {
            for (binarizer in listOf<(com.google.zxing.LuminanceSource) -> com.google.zxing.Binarizer>(
                ::HybridBinarizer, ::GlobalHistogramBinarizer,
            )) {
                try {
                    val result = reader.decodeWithState(BinaryBitmap(binarizer(variant)))
                    val points = result.resultPoints
                    if (points.size >= 2) {
                        val left = points.minOf { it.x }
                        val top = points.minOf { it.y }
                        val right = points.maxOf { it.x }
                        val bottom = points.maxOf { it.y }
                        if (right > left && bottom > top) return result.text.trim() to RectF(left, top, right, bottom)
                    }
                    if (result.text.isNotBlank()) return result.text.trim() to RectF(0f, 0f, width.toFloat(), height.toFloat())
                } catch (_: Exception) {
                    // 当前二值化/反色策略未命中，继续尝试下一种。
                } finally {
                    reader.reset()
                }
            }
        }
        return null
    }

    /** ZXing 返回的是图像坐标；沿用与 ML Kit 相同的预览变换、扫描框过滤和双帧稳定策略。 */
    private fun handleZxingFallback(proxy: androidx.camera.core.ImageProxy, value: String, bounds: RectF) {
        if (shuttingDown || lifecycle.currentState == Lifecycle.State.DESTROYED ||
            !::preview.isInitialized || !::scanArea.isInitialized ||
            !ScanPreferences.accepts(this, value)
        ) return
        runCatching {
            val output = preview.outputTransform ?: return
            val transform = CoordinateTransform(transformFactory.getOutputTransform(proxy), output)
            val mapped = RectF(bounds)
            transform.mapRect(mapped)
            val roi = scanArea.boundsInView()
            if (!roi.contains(mapped.centerX(), mapped.centerY())) return
            if (!hasStableBarcode(value, mapped.centerX(), mapped.centerY())) return
            scanDiag("ZXing fallback accepted center=${mapped.centerX().toInt()},${mapped.centerY().toInt()}")
            if (shouldDeliverCode(value)) deliverResult(value)
        }.onFailure { android.util.Log.w(TAG, "ZXing fallback mapping failed", it) }
    }

    /** 两种扫描模式明确互斥：拍照识图时暂停条码分析并显示拍照按钮。 */
    private fun setOcrMode(enabled: Boolean) {
        scanDiag("scan mode changed mode=" + (if (enabled) "ocr" else "barcode"))
        ocrMode = enabled
        windowPrefs.edit().putBoolean(KEY_OCR_MODE, enabled).apply()
        if (::modeButton.isInitialized) {
            modeButton.setIcon(
                if (enabled) PanelIcons.Icon.QR_SCAN else PanelIcons.Icon.CAMERA,
                if (enabled) "切换到连续扫码" else "切换到拍照识图",
            )
        }
        if (::captureButton.isInitialized) {
            captureButton.visibility = if (enabled) View.VISIBLE else View.GONE
            captureButton.isEnabled = enabled
            captureButton.alpha = if (enabled) 1f else 0f
            if (enabled) {
                // 按钮从 GONE 切为 VISIBLE 后才有真实尺寸；等布局完成再按已保存比例恢复。
                captureButton.post { restoreCaptureButtonPosition() }
            }
        }
        if (::scanArea.isInitialized) {
            // 拍照识图模式不显示扫码框；切回连续扫码时恢复。
            scanArea.visibility = if (enabled) View.GONE else View.VISIBLE
            scanArea.invalidate()
        }
    }

    /** 拍照识图仅在本机运行 ML Kit；识别完成后将整段文字写入当前输入框。 */
    private fun capturePhotoForOcr() {
        if (shuttingDown) return
        val capture = imageCapture ?: run {
            Toast.makeText(this, "相机尚未准备好，请稍后再试", Toast.LENGTH_SHORT).show()
            return
        }
        if (!processing.compareAndSet(false, true)) return
        capture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: androidx.camera.core.ImageProxy) {
                if (shuttingDown) {
                    runCatching { image.close() }
                    processing.set(false)
                    closeMlKitClients()
                    return
                }
                val media = image.image
                if (media == null) {
                    runCatching { image.close() }
                    processing.set(false)
                    if (shuttingDown) closeMlKitClients()
                    android.os.Handler(mainLooper).post {
                        if (!shuttingDown) Toast.makeText(this@ScanOverlayService, "照片读取失败，请重试", Toast.LENGTH_SHORT).show()
                    }
                    return
                }
                textRecognizer.process(InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees))
                    .addOnSuccessListener { result ->
                        val recognized = result.text.trim()
                        android.os.Handler(mainLooper).post {
                            if (shuttingDown) return@post
                            if (recognized.isBlank()) {
                                Toast.makeText(this@ScanOverlayService, "没有识别到文字，请调整角度后重拍", Toast.LENGTH_SHORT).show()
                            } else {
                                deliverResult(recognized)
                                Toast.makeText(this@ScanOverlayService, "已识别文字并粘贴到输入框", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .addOnFailureListener {
                        android.os.Handler(mainLooper).post {
                            if (!shuttingDown) {
                                Toast.makeText(this@ScanOverlayService, "文字识别失败，请重试", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .addOnCompleteListener {
                        runCatching { image.close() }
                        processing.set(false)
                        if (shuttingDown) closeMlKitClients()
                    }
            }

            override fun onError(exception: ImageCaptureException) {
                processing.set(false)
                if (shuttingDown) closeMlKitClients()
                android.os.Handler(mainLooper).post {
                    if (!shuttingDown) Toast.makeText(this@ScanOverlayService, "拍照失败：${exception.message ?: "请重试"}", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun toggleTorch() {
        val activeCamera = camera ?: return
        if (!activeCamera.cameraInfo.hasFlashUnit()) {
            Toast.makeText(this, "当前摄像头没有闪光灯", Toast.LENGTH_SHORT).show()
            return
        }
        torchEnabled = !torchEnabled
        activeCamera.cameraControl.enableTorch(torchEnabled)
        torchButton.setIcon(
            if (torchEnabled) PanelIcons.Icon.FLASHLIGHT_ON else PanelIcons.Icon.FLASHLIGHT,
            if (torchEnabled) "关闭手电筒" else "打开手电筒",
        )
        torchButton.isSelected = torchEnabled
    }

    /**
     * 对同一内容做跨帧确认，要求短时间内连续两帧命中且中心位置稳定。
     * 单帧 ML Kit 偶发的错误字符不会直接写入输入框。
     */
    private fun hasStableBarcode(value: String, centerX: Float, centerY: Float): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        val previous = stableBarcodeHits[value]
        val tolerance = (minOf(preview.width, preview.height) * 0.10f).coerceAtLeast(24f)
        val stable = previous != null &&
            now - previous.lastAt <= STABLE_HIT_WINDOW_MS &&
            kotlin.math.hypot((centerX - previous.centerX).toDouble(), (centerY - previous.centerY).toDouble()) <= tolerance
        val count = if (stable) previous!!.count + 1 else 1
        stableBarcodeHits[value] = StableBarcodeHit(now, count, centerX, centerY)
        if (stableBarcodeHits.size > MAX_TRACKED_CODES) {
            val cutoff = now - DUPLICATE_CACHE_TTL_MS
            stableBarcodeHits.entries.removeAll { it.value.lastAt < cutoff }
        }
        return count >= STABLE_HIT_COUNT
    }

    /**
     * 抑制连续帧重复结果，并限制缓存容量；持续扫描大量不同条码时不会无限增长。
     * 同一条码在抑制窗口内不刷新时间戳，避免连续命中导致永远无法再次提交。
     */
    private fun shouldDeliverCode(value: String): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        // 按完整原始内容去重，不使用前缀/后缀包含关系。
        val exactKey = value
        val previous = lastDeliveredAt[exactKey]
        if (previous != null && now - previous < DUPLICATE_SUPPRESS_MS) return false
        lastDeliveredAt[exactKey] = now
        if (lastDeliveredAt.size > MAX_TRACKED_CODES) {
            val cutoff = now - DUPLICATE_CACHE_TTL_MS
            lastDeliveredAt.entries.removeAll { it.value < cutoff }
            val excess = lastDeliveredAt.size - MAX_TRACKED_CODES
            if (excess > 0) lastDeliveredAt.keys.take(excess).forEach(lastDeliveredAt::remove)
        }
        return true
    }

    private fun deliverResult(value: String) {
        if (shuttingDown) return
        android.os.Handler(mainLooper).post {
            if (shuttingDown) return@post
            scanDiag("broadcast scan result length=${value.length} editorPrefixActive=${ScanPreferences.isPrefixEditorActive()}")
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
        scanDiag("onDestroy shuttingDown=$shuttingDown processing=${processing.get()} prefixEditing=$prefixEditingRequested rootAttached=${::root.isInitialized && root.isAttachedToWindow}", warning = true)
        synchronized(lifecycleGuard) { shuttingDown = true }
        prefixEditingRequested = false
        prefixEditorHadFocus = false
        ScanPreferences.setPrefixEditorActive(false)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        runCatching { analysisUseCase?.clearAnalyzer() }
        runCatching { cameraProvider?.unbindAll() }
        // 正在处理的 ML Kit Task 完成后才关闭识别器，避免 close 与识别任务竞争。
        if (!processing.get()) closeMlKitClients()
        saveWindowSize()
        if (::overlayRoot.isInitialized) runCatching { windowManager.removeView(overlayRoot) }
        executor.shutdown()
        zxingFallbackExecutor.shutdown()
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
        private var squareMode = false
        private var touchInScanArea = false
        private var downZoomRatio = 1f
        private var verticalZoomGesture = false
        private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
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

        fun setShape(square: Boolean, force: Boolean = false) {
            if (width <= 0 || height <= 0 || (!force && square == squareMode)) return
            val centerX = if (rect.isEmpty) width / 2f else rect.centerX()
            val centerY = if (rect.isEmpty) height / 2f else rect.centerY()
            if (square) {
                // 只有用户从长方形主动切到正方形时才保存当前框；
                // 窗口重建时 rect 先读到的是上次显示的正方形，不能覆盖已保存的长方形。
                if (!force) saveCustomRect()
                val side = minOf(width * 0.68f, height * 0.68f)
                rect.set(centerX - side / 2f, centerY - side / 2f,
                    centerX + side / 2f, centerY + side / 2f)
            } else if (prefs.contains("custom_left") && prefs.contains("custom_top") &&
                prefs.contains("custom_right") && prefs.contains("custom_bottom")) {
                rect.set(
                    prefs.getFloat("custom_left", .12f) * width,
                    prefs.getFloat("custom_top", .30f) * height,
                    prefs.getFloat("custom_right", .88f) * width,
                    prefs.getFloat("custom_bottom", .68f) * height,
                )
            } else {
                val targetWidth = width * 0.76f
                val targetHeight = height * 0.38f
                rect.set(centerX - targetWidth / 2f, centerY - targetHeight / 2f,
                    centerX + targetWidth / 2f, centerY + targetHeight / 2f)
            }
            squareMode = square
            clampRect()
            saveRect()
            invalidate()
        }

        private fun saveCustomRect() {
            if (width <= 0 || height <= 0) return
            prefs.edit()
                .putFloat("custom_left", rect.left / width)
                .putFloat("custom_top", rect.top / height)
                .putFloat("custom_right", rect.right / width)
                .putFloat("custom_bottom", rect.bottom / height)
                .apply()
        }

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
            if (!squareMode) saveCustomRect()
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
                    // 在悬浮窗预览/扫描区域点击即结束前缀编辑；输入已由 TextWatcher 即时保存，无需回车确认。
                    finishPrefixEditing()
                    downX = event.x
                    downY = event.y
                    start = RectF(rect)
                    downZoomRatio = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                    verticalZoomGesture = false
                    resizing = event.x >= start.right - 28f * density && event.y >= start.bottom - 28f * density
                    touchInScanArea = rect.contains(event.x, event.y)
                    // 整个预览层都接收触摸：双指捏合缩放；单指上下滑变焦；横向拖动仍可调整扫描框。
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    // 上下滑变焦仅允许从扫描框外开始；扫描框内部的上下拖动只用于移动/调整扫描框。
                    if (!touchInScanArea && !verticalZoomGesture &&
                        kotlin.math.abs(dy) > touchSlop &&
                        kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.15f
                    ) {
                        verticalZoomGesture = true
                    }
                    if (verticalZoomGesture) {
                        val activeCamera = camera ?: return true
                        val zoom = activeCamera.cameraInfo.zoomState.value ?: return true
                        // 单指纵向变焦采用相对倍率曲线：手指上移即放大，下移即缩小。
                        // 以按下时倍率为锚点，避免每帧累加误差；手指回到起点也会回到原倍率。
                        val travel = (height * 0.55f).coerceAtLeast(1f)
                        val scale = kotlin.math.exp((-dy / travel).toDouble()).toFloat()
                        val next = (downZoomRatio * scale)
                            .coerceIn(zoom.minZoomRatio, zoom.maxZoomRatio)
                        activeCamera.cameraControl.setZoomRatio(next)
                        return true
                    }
                    if (!touchInScanArea) return true
                    if (resizing) {
                        if (squareMode) {
                            val maxSide = minOf(width - start.left, height - start.top)
                            val side = (start.width() + maxOf(dx, dy)).coerceIn(100f * density, maxSide)
                            rect.right = start.left + side
                            rect.bottom = start.top + side
                        } else {
                            rect.right = (start.right + dx).coerceIn(start.left + 100f * density, width.toFloat())
                            rect.bottom = (start.bottom + dy).coerceIn(start.top + 48f * density, height.toFloat())
                        }
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
                MotionEvent.ACTION_UP -> {
                    if (touchInScanArea && !verticalZoomGesture) saveRect()
                    touchInScanArea = false
                    verticalZoomGesture = false
                    return true
                }
                MotionEvent.ACTION_CANCEL -> { verticalZoomGesture = false; return true }
            }
            return true
        }
    }

    companion object {
        private const val TAG = "QingjianScan"
        private const val NOTIFICATION_ID = 2921
        private const val CHANNEL_ID = "qingjian_scan_overlay"
        private const val WINDOW_PREFS = "scan_overlay_window"
        private const val KEY_WIDTH_DP = "width_dp"
        private const val KEY_HEIGHT_DP = "height_dp"
        private const val KEY_WINDOW_X_DP = "window_x_dp"
        private const val KEY_WINDOW_TOP_DP = "window_top_dp"
        private const val KEY_CAPTURE_X_RATIO = "capture_x_ratio"
        private const val KEY_CAPTURE_Y_RATIO = "capture_y_ratio"
        private const val KEY_OCR_MODE = "ocr_mode"
        private const val KEY_SQUARE_SCAN_SHAPE = "square_scan_shape"
        const val ACTION_STOP = "app.qingjian.inputmethod.action.STOP_SCAN_OVERLAY"
        private const val DUPLICATE_SUPPRESS_MS = 1_500L
        private const val DUPLICATE_CACHE_TTL_MS = 15_000L
        private const val MAX_TRACKED_CODES = 128
        private const val CAMERA_WARMUP_MS = 300L
        private const val STABLE_HIT_WINDOW_MS = 900L
        private const val STABLE_HIT_COUNT = 2
    }
}
