package app.qingjian.inputmethod.scan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Dialog
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Outline
import android.graphics.Path
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
import android.view.ViewOutlineProvider
import android.view.ViewGroup
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
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
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
    private lateinit var headerWindowParams: WindowManager.LayoutParams
    /** 标题栏+预览作为一个整体移动；root 的 y 仅表示预览起点。 */
    private var overlayTopY = 0
    private lateinit var headerView: View
    private var headerDialog: Dialog? = null
    private lateinit var root: FrameLayout
    private lateinit var preview: PreviewView
    private lateinit var scanArea: ScanAreaView
    private lateinit var scanner: BarcodeScanner
    private lateinit var textRecognizer: TextRecognizer
    private var imageCapture: ImageCapture? = null
    @Volatile private var ocrMode = false
    private lateinit var captureButton: ScanToolbarIconView
    private lateinit var modeButton: ScanToolbarIconView
    private lateinit var shapeButton: ScanToolbarIconView
    private var wideScanShape = true
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
        // 悬浮窗每次新建都以连续扫码为默认模式；识图模式只在当前窗口内由用户主动切换。
        ocrMode = false
        wideScanShape = windowPrefs.getBoolean(KEY_WIDE_SCAN_SHAPE, true)
        windowPrefs.edit().putBoolean(KEY_OCR_MODE, false).apply()
        textRecognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

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
            background = GradientDrawable().apply {
                setColor(0x01000000)
                // 相机区域顶部保持直边，与标题栏下边缘拼成一体；只圆润窗口底部。
                cornerRadii = floatArrayOf(
                    0f, 0f, 0f, 0f,
                    dp(18).toFloat(), dp(18).toFloat(),
                    dp(18).toFloat(), dp(18).toFloat(),
                )
                setStroke(dp(1), 0x66FFFFFF)
            }
            clipToOutline = true
            clipChildren = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
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
        prefixInput.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN &&
                (headerWindowParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0) {
                headerWindowParams.flags = headerWindowParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                updateHeaderWindowLayout()
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
                headerWindowParams.flags = headerWindowParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                updateHeaderWindowLayout()
            }
        }
        prefixInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                finishPrefixEditing()
                true
            } else false
        }

        // 标题栏用图标切换模式：扫码状态显示相机图标，识图状态显示二维码图标。
        modeButton = ScanToolbarIconView(this, PanelIcons.Icon.CAMERA, "切换到拍照识图").apply {
            setOnClickListener {
                setOcrMode(!ocrMode)
                Toast.makeText(
                    this@ScanOverlayService,
                    if (ocrMode) "已切换到拍照识图，点击相机快门拍照" else "已切换到连续扫码",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }
        // 横向布局时用独立弹性间隔把工具按钮推到右侧，前缀区本身只占实际内容宽度。
        val headerSpacer = View(this)
        toolbar.addView(modeButton, LinearLayout.LayoutParams(dp(40), dp(40)))
        shapeButton = ScanToolbarIconView(
            this,
            if (wideScanShape) PanelIcons.Icon.SCAN_WIDE else PanelIcons.Icon.SCAN_RECTANGLE,
            if (wideScanShape) "切换为方形扫描区域" else "切换为长方形扫描区域",
        ).apply {
            setOnClickListener { setWideScanShape(!wideScanShape) }
        }
        toolbar.addView(shapeButton, LinearLayout.LayoutParams(dp(40), dp(40)))
        torchButton = ScanToolbarIconView(this, PanelIcons.Icon.FLASHLIGHT, "打开手电筒").apply {
            setOnClickListener { finishPrefixEditing(); toggleTorch() }
        }
        toolbar.addView(torchButton, LinearLayout.LayoutParams(dp(40), dp(40)))
        captureButton = ScanToolbarIconView(this, PanelIcons.Icon.CAMERA, "拍照识别文字").apply {
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
        val closeButton = ScanToolbarIconView(this, PanelIcons.Icon.CLOSE, "关闭扫码窗口").apply {
            setOnClickListener { finishPrefixEditing(); stopSelf() }
        }
        toolbar.addView(closeButton, LinearLayout.LayoutParams(dp(36), dp(40)))
        // 空白标题栏/行区域可直接拖动；按钮和输入框保留各自点击、编辑行为。
        attachHeaderDrag(prefixRow)
        attachHeaderDrag(toolbar)

        val header = object : LinearLayout(this) {
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
            setPadding(dp(7), dp(2), dp(7), dp(2))
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    (themePalette?.surface ?: 0xFF303846.toInt()).let { (it and 0x00FFFFFF) or (0xB0 shl 24) },
                    (themePalette?.board ?: 0xFF252D39.toInt()).let { (it and 0x00FFFFFF) or (0xB8 shl 24) },
                ),
            ).apply {
                // 顶部保留两个圆角，底边保持直线并与预览重叠，避免透出底色形成缝隙。
                cornerRadii = floatArrayOf(
                    dp(16).toFloat(), dp(16).toFloat(),
                    dp(16).toFloat(), dp(16).toFloat(),
                    0f, 0f, 0f, 0f,
                )
                setStroke(dp(1), 0x66FFFFFF)
            }
            // 使用显式凸轮廓裁剪，而不是依赖透明背景 drawable 自动生成 Outline。
            // 全透明 drawable 在部分 Android 窗口实现中没有有效 outline，移动窗口时会露出矩形角。
            clipToOutline = true
            clipChildren = true
            outlineProvider = topRoundedOutlineProvider(dp(16).toFloat())
            addView(prefixRow)
            addView(headerSpacer)
            addView(toolbar)
        }
        attachHeaderDrag(header)
        // Switch 自身必须保留触摸序列；不能把它交给会消费事件的标题栏拖动监听。
        // 标题栏独立成一个小型悬浮窗口：BLUR_BEHIND 只覆盖标题栏范围，
        // 避免把整块相机预览也一起模糊。
        headerView = header

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
        // 拍照按钮属于相机画面，采用常见相机底部中央快门布局。
        root.addView(captureButton, FrameLayout.LayoutParams(dp(60), dp(60), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(14)
        })
        attachCaptureButtonDrag()
        setOcrMode(ocrMode)
        setWideScanShape(wideScanShape, persist = false)

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
            x = dp(windowPrefs.getInt(KEY_WINDOW_X_DP, 16))
            y = dp(windowPrefs.getInt(KEY_WINDOW_TOP_DP, 120))
        }
        headerWindowParams = WindowManager.LayoutParams(
            windowParams.width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = windowParams.x
            y = 0
            // 不启用 FLAG_BLUR_BEHIND：它会按窗口矩形扩散模糊，越过顶部圆角。
            // 只使用 Window.setBackgroundBlurRadius，由圆角 Window 背景限定模糊范围。
        }
        // 标题栏使用独立 Window 的系统背景模糊；相机预览在另一个 Window 中，
        // 因此不会被模糊。圆角遮罩由 Dialog decor 与 headerView 双层裁剪。
        windowManager.addView(root, windowParams)
        // 预览根视图加入 Window 后再恢复快门坐标，避免首次 post 时尚未测量导致回退到默认位置。
        restoreCaptureButtonPosition()
        headerDialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar).apply {
            setContentView(headerView)
            setCancelable(false)
            window?.apply {
                setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                // Dialog 窗口自身也必须使用带透明圆角的遮罩；只裁剪 headerView 不够，
                // 系统 BLUR_BEHIND 仍会按矩形 Window bounds 模糊圆角外区域。
                val roundedWindowMask = roundedHeaderMask()
                setBackgroundDrawable(roundedWindowMask)
                addFlags(
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                )
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setDimAmount(0f)
                setElevation(0f)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // 只启用窗口背景模糊，不启用矩形范围的 BLUR_BEHIND；
                    // Android 会按 roundedWindowMask 的圆角边界裁剪模糊区域。
                    clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    attributes = headerWindowParams
                    setBackgroundBlurRadius((24f * resources.displayMetrics.density).toInt())
                } else {
                    clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    attributes = headerWindowParams
                }
            }
            show()
            window?.apply {
                setLayout(headerWindowParams.width, WindowManager.LayoutParams.WRAP_CONTENT)
                // 保持窗口范围仅覆盖标题栏；圆角与描边由标题栏 View 自己裁剪。
                decorView.setPadding(0, 0, 0, 0)
                // decor 层沿用窗口级圆角遮罩；全透明矩形 decor 会使部分系统将背景模糊扩展到矩形边界。
                setBackgroundDrawable(roundedHeaderMask())
                // decor 本身不再绘制第二层矩形蒙版；窗口背景只负责给系统模糊提供圆角 alpha 形状。
                decorView.background = ColorDrawable(Color.TRANSPARENT)
                // 对 Dialog 的 decor 层也执行轮廓裁剪，避免系统模糊和内容绘制越过顶部圆角。
                decorView.clipToOutline = true
                decorView.elevation = 0f
                decorView.translationZ = 0f
                decorView.setLayerType(View.LAYER_TYPE_NONE, null)
                (decorView as? ViewGroup)?.clipChildren = true
                decorView.outlineProvider = topRoundedOutlineProvider(16f * resources.displayMetrics.density)
                decorView.invalidateOutline()
                attributes = headerWindowParams
            }
        }
        headerView.post {
            val measuredHeaderHeight = headerView.height.takeIf { it > 0 } ?: headerView.measuredHeight
            if (measuredHeaderHeight > 0) {
                headerWindowParams.height = measuredHeaderHeight
                val overlap = (resources.displayMetrics.density).toInt().coerceAtLeast(1)
                overlayTopY = (windowParams.y - measuredHeaderHeight + overlap).coerceAtLeast(0)
            }
            updateOverlayPositions()
        }
    }

    /** 标题栏所有区域长按后都可拖动，普通点击仍交给原控件处理。 */
    /**
     * 标题栏空白区域按下即可开始跟手拖动，不再等待长按计时；
     * 子控件（模式、闪光灯、关闭、前缀输入）不挂拖动监听，点击行为互不干扰。
     */
    private fun attachHeaderDrag(view: View) {
        var dragging = false
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startGroupY = 0
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        view.isClickable = true
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = false
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = windowParams.x
                    startGroupY = overlayTopY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging &&
                        (kotlin.math.abs(event.rawX - downRawX) > slop ||
                         kotlin.math.abs(event.rawY - downRawY) > slop)) {
                        dragging = true
                        finishPrefixEditing()
                    }
                    if (dragging) {
                        windowParams.x = (startX + event.rawX - downRawX).toInt()
                            .coerceIn(0, (resources.displayMetrics.widthPixels - windowParams.width).coerceAtLeast(0))
                        setOverlayTop(startGroupY + (event.rawY - downRawY).toInt())
                        updateOverlayPositions()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) saveWindowPosition()
                    dragging = false
                    true
                }
                else -> true
            }
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

    /** 以标题栏顶部为唯一坐标，预览始终从标题栏底边下方 1px 开始。 */
    private fun setOverlayTop(requestedTop: Int) {
        if (!::windowParams.isInitialized || !::headerWindowParams.isInitialized) return
        val headerHeight = (headerView.height.takeIf { it > 0 } ?: headerWindowParams.height).coerceAtLeast(0)
        val overlap = resources.displayMetrics.density.toInt().coerceAtLeast(1)
        val combinedHeight = (headerHeight + windowParams.height - overlap).coerceAtLeast(headerHeight)
        val maxTop = (resources.displayMetrics.heightPixels - combinedHeight).coerceAtLeast(0)
        overlayTopY = requestedTop.coerceIn(0, maxTop)
        windowParams.y = overlayTopY + headerHeight - overlap
    }

    /** 同步更新相机内容窗与独立标题栏窗，二者始终作为一个整体移动。 */
    private fun updateOverlayPositions() {
        if (::headerWindowParams.isInitialized && ::headerView.isInitialized) {
            val measuredHeight = (headerView.height.takeIf { it > 0 } ?: headerView.measuredHeight)
                .coerceAtLeast(0)
            val headerHeight = measuredHeight.takeIf { it > 0 } ?: headerWindowParams.height
            headerWindowParams.x = windowParams.x
            headerWindowParams.height = headerHeight
            headerWindowParams.y = overlayTopY
            headerWindowParams.width = windowParams.width
            setOverlayTop(overlayTopY)
        }
        // 预览先更新，标题栏后更新并保持在上层；1px 重叠消除接缝但不会允许预览越过标题栏。
        runCatching { windowManager.updateViewLayout(root, windowParams) }
        if (::headerWindowParams.isInitialized && ::headerView.isInitialized) updateHeaderWindowLayout()
    }

    /** 通过标题栏 Dialog 的 Window 更新位置、尺寸与焦点标志。 */
    private fun updateHeaderWindowLayout() {
        val window = headerDialog?.window ?: return
        runCatching {
            window.attributes = headerWindowParams
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                window.setBackgroundBlurRadius((24f * resources.displayMetrics.density).toInt())
            }
            window.setLayout(headerWindowParams.width, headerWindowParams.height)
            // 窗口移动/缩放会重新布局 decor；主动刷新自定义裁剪轮廓，避免圆角外的矩形残留。
            window.decorView.invalidateOutline()
            headerView.invalidateOutline()
        }
    }

    /**
     * 标题栏窗口背景完全透明时，系统的 BACKGROUND outline 可能为空，clipToOutline 实际不会裁剪。
     * 这里显式提供只有顶部圆角、底边为直线的凸路径；不改变窗口透明度，也不靠半透明遮挡。
     */
    /**
     * Window 背景模糊使用窗口背景 Drawable 的 alpha 遮罩，而不是子 View 的 clipToOutline。
     * 1/255 的非零 alpha 让系统识别圆角形状，视觉上近乎透明；圆角外 alpha 为 0。
     */
    private fun roundedHeaderMask() = GradientDrawable().apply {
        // 极低 alpha 仅用于系统背景模糊识别窗口轮廓；不在 decor 上重复绘制蒙版。
        setColor(0x01000000)
        val radius = 16f * resources.displayMetrics.density
        cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
    }

    private fun topRoundedOutlineProvider(radiusPx: Float): ViewOutlineProvider =
        object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val width = view.width
                val height = view.height
                if (width <= 0 || height <= 0) {
                    outline.setEmpty()
                    return
                }
                val radius = radiusPx.coerceAtMost(minOf(width, height) / 2f)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val path = Path().apply {
                        moveTo(0f, radius)
                        quadTo(0f, 0f, radius, 0f)
                        lineTo(width - radius, 0f)
                        quadTo(width.toFloat(), 0f, width.toFloat(), radius)
                        lineTo(width.toFloat(), height.toFloat())
                        lineTo(0f, height.toFloat())
                        close()
                    }
                    outline.setPath(path)
                } else {
                    outline.setRoundRect(0, 0, width, height, radius)
                }
                outline.alpha = 1f
            }
        }

    private fun setWideScanShape(wide: Boolean, persist: Boolean = true) {
        wideScanShape = wide
        if (persist) windowPrefs.edit().putBoolean(KEY_WIDE_SCAN_SHAPE, wide).apply()
        if (::shapeButton.isInitialized) {
            shapeButton.setIcon(
                if (wide) PanelIcons.Icon.SCAN_WIDE else PanelIcons.Icon.SCAN_RECTANGLE,
                if (wide) "切换为方形扫描区域" else "切换为长方形扫描区域",
            )
        }
        if (::scanArea.isInitialized) scanArea.post { if (wideScanShape == wide) scanArea.setWideShape(wide) }
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
                moveStartY = overlayTopY
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
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setTargetResolution(Size(1920, 1080))
                .build()
            imageCapture = capture
            analysis.setAnalyzer(executor) { proxy ->
                val media = proxy.image
                if (ocrMode) {
                    // 拍照识图模式下不运行条码识别，释放每一帧，等待用户主动拍照。
                    proxy.close()
                } else if (media == null || !processing.compareAndSet(false, true)) {
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
            camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, cameraPreview, analysis, capture)
            torchButton.isEnabled = camera?.cameraInfo?.hasFlashUnit() == true
            torchButton.alpha = if (torchButton.isEnabled) 1f else 0.4f
            torchButton.contentDescription = if (torchButton.isEnabled) "打开手电筒" else "当前摄像头没有闪光灯"
        }, ContextCompat.getMainExecutor(this))
    }

    /** 两种扫描模式明确互斥：拍照识图时暂停条码分析并显示拍照按钮。 */
    private fun setOcrMode(enabled: Boolean) {
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
        val capture = imageCapture ?: run {
            Toast.makeText(this, "相机尚未准备好，请稍后再试", Toast.LENGTH_SHORT).show()
            return
        }
        if (!processing.compareAndSet(false, true)) return
        capture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: androidx.camera.core.ImageProxy) {
                val media = image.image
                if (media == null) {
                    image.close()
                    processing.set(false)
                    android.os.Handler(mainLooper).post {
                        Toast.makeText(this@ScanOverlayService, "照片读取失败，请重试", Toast.LENGTH_SHORT).show()
                    }
                    return
                }
                textRecognizer.process(InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees))
                    .addOnSuccessListener { result ->
                        val recognized = result.text.trim()
                        android.os.Handler(mainLooper).post {
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
                            Toast.makeText(this@ScanOverlayService, "文字识别失败，请重试", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .addOnCompleteListener {
                        image.close()
                        processing.set(false)
                    }
            }

            override fun onError(exception: ImageCaptureException) {
                processing.set(false)
                android.os.Handler(mainLooper).post {
                    Toast.makeText(this@ScanOverlayService, "拍照失败：${exception.message ?: "请重试"}", Toast.LENGTH_SHORT).show()
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
        if (::textRecognizer.isInitialized) textRecognizer.close()
        saveWindowSize()
        runCatching { headerDialog?.dismiss() }
        headerDialog = null
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

        fun setWideShape(wide: Boolean) {
            if (width <= 0 || height <= 0) return
            val centerX = if (rect.isEmpty) width / 2f else rect.centerX()
            val centerY = if (rect.isEmpty) height / 2f else rect.centerY()
            val targetWidth = width * if (wide) 0.82f else 0.62f
            val targetHeight = height * if (wide) 0.34f else 0.56f
            rect.set(
                centerX - targetWidth / 2f,
                centerY - targetHeight / 2f,
                centerX + targetWidth / 2f,
                centerY + targetHeight / 2f,
            )
            clampRect()
            saveRect()
            invalidate()
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
        private const val KEY_WINDOW_X_DP = "window_x_dp"
        private const val KEY_WINDOW_TOP_DP = "window_top_dp"
        private const val KEY_CAPTURE_X_RATIO = "capture_x_ratio"
        private const val KEY_CAPTURE_Y_RATIO = "capture_y_ratio"
        private const val KEY_OCR_MODE = "ocr_mode"
        private const val KEY_WIDE_SCAN_SHAPE = "wide_scan_shape"
        const val ACTION_STOP = "app.qingjian.inputmethod.action.STOP_SCAN_OVERLAY"
        private const val DUPLICATE_SUPPRESS_MS = 1_500L
    }
}
