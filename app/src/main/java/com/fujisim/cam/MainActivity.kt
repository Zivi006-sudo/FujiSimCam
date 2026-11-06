package com.fujisim.cam

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ExifInterface
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.TextUtils
import android.util.Size
import android.view.Choreographer
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.activity.result.PickVisualMediaRequest
import android.graphics.ImageDecoder
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import androidx.camera.extensions.ExtensionsManager
import android.content.ClipData
import android.content.ClipboardManager
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    companion object {
        const val FUJI = 0
        const val STYLES = 1
        const val NORMAL = 2
        val FAMILY_NAMES = arrayOf("FUJI", "STYLES", "NORMAL")
        const val YELLOW = "#FFC94A"
    }

    // Views
    private lateinit var previewView: PreviewView
    private lateinit var grid: GridOverlay
    private lateinit var recipeBar: LinearLayout
    private lateinit var recipeInfo: TextView
    private lateinit var originalBadge: TextView
    private lateinit var recTimer: TextView
    private lateinit var shutter: View
    private lateinit var thumb: ImageView
    private lateinit var flash: View
    private lateinit var editorContainer: LinearLayout
    private lateinit var sheetContent: FrameLayout
    private lateinit var zoomBar: LinearLayout
    private lateinit var gridBtn: TextView
    private lateinit var wbBtn: TextView
    private lateinit var lensBtn: TextView
    private lateinit var familyBtn: TextView
    private lateinit var logBtn: TextView
    private lateinit var modeViews: List<TextView>
    private lateinit var processingOverlay: View
    private lateinit var processingText: TextView
    private lateinit var processingBar: ProgressBar
    private lateinit var focusOverlay: FocusOverlay
    private lateinit var chipScroll: View
    private lateinit var stylesBar: View
    private lateinit var stylePad: StylePad
    private lateinit var paletteBar: SeekBar
    private lateinit var styleCategory: TextView
    private lateinit var styleNameBox: View
    private lateinit var styleName: TextView
    private lateinit var styleDots: TextView

    // State
    private lateinit var store: RecipeStore
    private lateinit var prefs: AppPrefs
    private var recipes: List<Recipe> = emptyList()
    private lateinit var current: Recipe       // selected saved recipe
    private lateinit var live: Recipe          // on screen (may include unsaved edits)
    private var family = FUJI
    private val stylesMode get() = family == STYLES
    private var currentStyle = PhotoStyle.STANDARD
    private var styleLive = StyleSettings()
    private var normal = NormalSettings()
    private var videoMode = false
    private var logMode = false
    private var hlgSupported = false
    private var editing = false
    private var showOriginal = false
    private var lookNow = Look()
    private var lutNow: LutImage? = null
    private var lens = LensSettings()
    private var wb = WbSettings()

    // Low-light: ISO bucket from the live camera results (0 bright .. 3 very dark)
    @Volatile private var isoBucket = 0
    private val isoDenoise get() = floatArrayOf(0.45f, 0.75f, 1.0f, 1.25f)[isoBucket]
    private val isoTemporal get() = floatArrayOf(0.3f, 0.5f, 0.7f, 0.85f)[isoBucket]

    // Camera
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var recordStart = 0L
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var awbModes: IntArray? = null
    private var lastCamKey = ""
    private var zoomRatio = 1f
    private var userEv = 0f            // on-screen exposure, added to the recipe's EV
    private var aeLocked = false
    private var highRes = false
    private var maxPhotoMp = 0
    private var edgeOffSupported = false
    private var lastUri: Uri? = null
    private var lastMime = "image/jpeg"
    private val previewShader = FilmShader()
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val thumbExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cardThumbs = HashMap<String, ImageView>()

    // Xiaomi camera extensions (Night / HDR / Bokeh / Auto)
    private var extensionsManager: ExtensionsManager? = null
    private var extTried = false
    private var availableExt: List<Int> = emptyList()
    private var extMode = ExtensionMode.NONE
    private lateinit var nightBtn: TextView

    // Histogram + highlight warning
    private lateinit var histogramView: HistogramView
    private var zebraOn = false
    private var histOn = false
    private val histExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var histBusy = false
    private val histTicker = object : Runnable {
        override fun run() {
            updateHistogram()
            mainHandler.postDelayed(this, 450)
        }
    }

    // Lens colour matching
    @Volatile private var activePhysicalId: String? = null
    private var lensNames: Map<String, String> = emptyMap()
    private var lensCorrection: Map<String, FloatArray> = emptyMap()

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else Toast.makeText(this, "Camera permission is needed", Toast.LENGTH_LONG).show()
        }
    private val importLut =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importLutFrom(uri)
        }
    private val pickScreenshot =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) readRecipeScreenshot(uri) }
    private val pickPhotoToEdit =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) reEditPhoto(uri) }
    private val audioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) Toast.makeText(this, "Videos will be recorded without sound", Toast.LENGTH_SHORT).show()
        }

    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val rotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                imageCapture?.targetRotation = rotation
                videoCapture?.targetRotation = rotation
            }
        }
    }

    // Level sensor
    private val sensorManager by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
    private val gravityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val gx = event.values[0]
            val gy = event.values[1]
            grid.rollDegrees = Math.toDegrees(atan2(gx.toDouble(), gy.toDouble())).toFloat()
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // Live camera results: ISO for adaptive noise reduction
    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val pid = runCatching { result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) }.getOrNull()
            if (pid != null && pid != activePhysicalId) {
                activePhysicalId = pid
                mainHandler.post { refreshLook() }
            }
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
            val bucket = when {
                iso <= 200 -> 0
                iso <= 800 -> 1
                iso <= 1600 -> 2
                else -> 3
            }
            if (bucket != isoBucket) {
                isoBucket = bucket
                mainHandler.post { refreshLook() }
            }
        }
    }

    // Moving grain in the viewfinder
    private var lastGrainFrame = 0L
    private val grainTicker = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (lookNow.grain > 0f && !showOriginal && frameTimeNanos - lastGrainFrame > 33_000_000L) {
                lastGrainFrame = frameTimeNanos
                pushPreviewShader(frameTimeNanos / 1e9f)
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private val recTick = object : Runnable {
        override fun run() {
            if (recording == null) return
            val s = (SystemClock.elapsedRealtime() - recordStart) / 1000
            recTimer.text = String.format(Locale.US, "● %02d:%02d", s / 60, s % 60)
            mainHandler.postDelayed(this, 500)
        }
    }

    private val thumbTicker = object : Runnable {
        override fun run() {
            refreshThumbnails()
            mainHandler.postDelayed(this, 6000)
        }
    }

    private val fadeInfo = Runnable { recipeInfo.animate().alpha(0f).setDuration(400).start() }

    // ------------------------------------------------------------------ Lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.preview)
        grid = findViewById(R.id.grid)
        recipeBar = findViewById(R.id.recipeBar)
        recipeInfo = findViewById(R.id.recipeInfo)
        originalBadge = findViewById(R.id.originalBadge)
        recTimer = findViewById(R.id.recTimer)
        shutter = findViewById(R.id.shutter)
        thumb = findViewById(R.id.thumb)
        flash = findViewById(R.id.flash)
        editorContainer = findViewById(R.id.editorContainer)
        sheetContent = findViewById(R.id.sheetContent)
        zoomBar = findViewById(R.id.zoomBar)
        gridBtn = findViewById(R.id.gridBtn)
        wbBtn = findViewById(R.id.wbBtn)
        lensBtn = findViewById(R.id.lensBtn)
        familyBtn = findViewById(R.id.familyBtn)
        logBtn = findViewById(R.id.logBtn)
        modeViews = listOf(findViewById(R.id.modeVideo), findViewById(R.id.modeFuji),
            findViewById(R.id.modeStyles), findViewById(R.id.modeNormal))
        processingOverlay = findViewById(R.id.processingOverlay)
        processingText = findViewById(R.id.processingText)
        processingBar = findViewById(R.id.processingBar)
        focusOverlay = findViewById(R.id.focusOverlay)
        chipScroll = findViewById(R.id.chipScroll)
        stylesBar = findViewById(R.id.stylesBar)
        stylePad = findViewById(R.id.stylePad)
        paletteBar = findViewById(R.id.paletteBar)
        styleCategory = findViewById(R.id.styleCategory)
        styleNameBox = findViewById(R.id.styleNameBox)
        styleName = findViewById(R.id.styleName)
        styleDots = findViewById(R.id.styleDots)

        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = PreviewView.ScaleType.FIT_START

        store = RecipeStore(this)
        prefs = AppPrefs(this)
        reloadRecipes()
        current = recipes.firstOrNull { it.id == store.selectedId } ?: recipes.first()
        live = current
        family = prefs.family.coerceIn(0, 2)
        currentStyle = prefs.selectedStyle
        styleLive = prefs.styleSettings(currentStyle)
        normal = prefs.normal
        grid.mode = GridOverlay.Mode.values().getOrElse(prefs.gridMode) { GridOverlay.Mode.OFF }
        highRes = prefs.highRes
        lens = prefs.lens
        wb = prefs.wb
        // Warm the LUT cache off the main thread so switching looks is instant.
        Thread {
            (FilmSim.values().mapNotNull { it.lut } + "natural").forEach { LutLibrary.get(applicationContext, "asset:$it") }
        }.start()

        nightBtn = findViewById(R.id.nightBtn)
        histogramView = findViewById(R.id.histogram)
        zebraOn = prefs.zebra
        histOn = prefs.histogram
        histogramView.visibility = if (histOn) View.VISIBLE else View.GONE
        thumb.setOnLongClickListener { pickImage(pickPhotoToEdit); true }
        Thread {
            val names = CameraReport.lenses(applicationContext).associate { it.id to it.label }
            runOnUiThread { lensNames = names; recomputeLensCorrection() }
        }.start()
        setupStyleControls()
        setupTopBar()
        setupModeBar()
        setupSheet()
        rebuildChips()
        updateModeButtons()

        previewView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> refreshLook() }

        shutter.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            if (videoMode) toggleRecording() else takePhoto()
        }
        findViewById<View>(R.id.editBtn).setOnClickListener { if (editing) closeEditor() else openEditor() }
        findViewById<View>(R.id.flipBtn).setOnClickListener {
            if (recording != null) return@setOnClickListener
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            zoomRatio = 1f
            resetFocus()
            startCamera()
        }
        thumb.setOnClickListener { openLast() }
        setupCompareButton()
        setupPreviewGestures()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    editing -> closeEditor()
                    recording != null -> toggleRecording()
                    else -> finish()
                }
            }
        })

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onStart() {
        super.onStart()
        orientationListener.enable()
    }

    override fun onResume() {
        super.onResume()
        updateLevelSensor()
        Choreographer.getInstance().postFrameCallback(grainTicker)
        mainHandler.postDelayed(thumbTicker, 1500)
        mainHandler.postDelayed(histTicker, 800)
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(gravityListener)
        Choreographer.getInstance().removeFrameCallback(grainTicker)
        mainHandler.removeCallbacks(thumbTicker)
        mainHandler.removeCallbacks(histTicker)
        if (recording != null) toggleRecording()
    }

    override fun onStop() {
        super.onStop()
        orientationListener.disable()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        thumbExecutor.shutdown()
        histExecutor.shutdown()
    }

    private fun updateLevelSensor() {
        sensorManager.unregisterListener(gravityListener)
        if (grid.mode == GridOverlay.Mode.THIRDS_LEVEL) {
            sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let {
                sensorManager.registerListener(gravityListener, it, SensorManager.SENSOR_DELAY_UI)
            }
        }
    }

    // ------------------------------------------------------------------ Top bar, mode bar, sheet

    private fun setupTopBar() {
        gridBtn.setOnClickListener {
            val modes = GridOverlay.Mode.values()
            grid.mode = modes[(grid.mode.ordinal + 1) % modes.size]
            prefs.gridMode = grid.mode.ordinal
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            showInfo(if (grid.mode == GridOverlay.Mode.OFF) "Grid off" else "Grid: ${grid.mode.label}")
            updateModeButtons()
            updateLevelSensor()
        }
        wbBtn.setOnClickListener {
            if (recording != null) return@setOnClickListener
            if (editing) closeEditor() else openWbPanel()
        }
        lensBtn.setOnClickListener {
            if (recording != null) return@setOnClickListener
            if (editing) closeEditor() else openLensPanel()
        }
        familyBtn.setOnClickListener {
            if (recording != null) return@setOnClickListener
            setFamily((family + 1) % 3, keepVideo = true)
        }
        logBtn.setOnClickListener {
            if (recording != null) return@setOnClickListener
            logMode = !logMode
            updateModeButtons()
            startCamera()
        }
        nightBtn.setOnClickListener {
            if (recording != null || availableExt.isEmpty()) return@setOnClickListener
            val cycle = listOf(ExtensionMode.NONE) + availableExt
            extMode = cycle[(cycle.indexOf(extMode).coerceAtLeast(0) + 1) % cycle.size]
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            showInfo(when (extMode) {
                ExtensionMode.NIGHT -> "Night mode (Xiaomi) — hold still while shooting"
                ExtensionMode.HDR -> "HDR (Xiaomi)"
                ExtensionMode.BOKEH -> "Bokeh — background blur (Xiaomi)"
                ExtensionMode.AUTO -> "Auto — Night/HDR when needed (Xiaomi)"
                else -> "Standard capture"
            })
            startCamera()
        }
        findViewById<View>(R.id.moreBtn).setOnClickListener {
            if (recording != null) return@setOnClickListener
            if (editing) closeEditor() else openSettings()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupModeBar() {
        // 0 = VIDEO, 1 = FUJI, 2 = STYLES, 3 = NORMAL
        modeViews.forEachIndexed { i, v -> v.setOnClickListener { selectModeIndex(i) } }
        val bar = findViewById<View>(R.id.modeBar)
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (abs(velocityX) < abs(velocityY)) return false
                val idx = currentModeIndex() + if (velocityX < 0) 1 else -1
                if (idx in 0..3) selectModeIndex(idx)
                return true
            }
        })
        bar.setOnTouchListener { _, e -> detector.onTouchEvent(e); false }
    }

    private fun currentModeIndex() = if (videoMode) 0 else family + 1

    private fun selectModeIndex(i: Int) {
        if (recording != null || i == currentModeIndex()) return
        modeViews[i].performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        if (editing) closeEditor()
        if (i == 0) setVideoMode(true) else setFamily(i - 1, keepVideo = false)
    }

    private fun setFamily(f: Int, keepVideo: Boolean) {
        family = f
        prefs.family = f
        lastCamKey = ""
        if (!keepVideo && videoMode) {
            setVideoMode(false)
        }
        rebuildChips()
        syncStyleControls()
        updateModeButtons()
        refreshLook(showInfoPill = true)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupSheet() {
        var startY = 0f
        findViewById<View>(R.id.sheetHandle).setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> startY = e.rawY
                MotionEvent.ACTION_MOVE -> editorContainer.translationY = max(0f, e.rawY - startY)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (e.rawY - startY > dp(90)) closeEditor()
                    else editorContainer.animate().translationY(0f).setDuration(150).start()
                }
            }
            true
        }
    }

    private fun showSheet(content: View) {
        editing = true
        sheetContent.removeAllViews()
        sheetContent.addView(content)
        editorContainer.visibility = View.VISIBLE
        editorContainer.translationY = dp(470).toFloat()
        editorContainer.animate().translationY(0f).setDuration(220).start()
    }

    private fun showInfo(text: String) {
        recipeInfo.text = text
        mainHandler.removeCallbacks(fadeInfo)
        recipeInfo.animate().cancel()
        recipeInfo.alpha = 1f
        mainHandler.postDelayed(fadeInfo, 2600)
    }

    private fun openSettings() {
        val panel = SettingsPanel(this, highRes, maxPhotoMp,
            onHighRes = { on ->
                if (on && maxPhotoMp in 1..19) {
                    Toast.makeText(this, "Xiaomi only gives apps up to ${maxPhotoMp}MP on this camera", Toast.LENGTH_LONG).show()
                }
                if (on != highRes) {
                    highRes = on
                    prefs.highRes = on
                    if (!videoMode) startCamera()
                }
            },
            extraRows = settingsExtraRows(),
            onClose = { closeEditor() })
        showSheet(panel.view)
    }

    /** Extra rows for the Settings sheet (filled in by later features). */
    private fun settingsExtraRows(): List<View> {
        val rows = mutableListOf<View>()
        // Display aids
        rows += UiKit.section(this, "VIEWFINDER")
        rows += toggleRow("Highlight warning (zebra stripes)", zebraOn) { on ->
            zebraOn = on; prefs.zebra = on; pushPreviewShader(0f)
        }
        rows += toggleRow("Live histogram", histOn) { on ->
            histOn = on; prefs.histogram = on
            histogramView.visibility = if (on) View.VISIBLE else View.GONE
        }

        // Editing
        rows += UiKit.section(this, "RE-EDIT PHOTOS")
        rows += UiKit.note(this, "Apply the current look (recipe, style or Normal) to any photo in your gallery. " +
            "Tip: long-press the thumbnail next to the shutter. Photos taken in this app keep a clean original, so re-editing them starts from the untouched image.")
        rows += toggleRow("Keep clean originals for re-editing", prefs.keepOriginals) { on -> prefs.keepOriginals = on }
        val origDir = File(filesDir, "originals")
        val usedMb = (origDir.listFiles()?.sumOf { it.length() } ?: 0L) / (1024 * 1024)
        rows += LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            addView(UiKit.pill(this@MainActivity, "Edit a gallery photo", "#2E4A6B") { closeEditor(); pickImage(pickPhotoToEdit) })
            addView(UiKit.pill(this@MainActivity, "Delete originals (${usedMb}MB)", "#333333") {
                origDir.listFiles()?.forEach { it.delete() }
                Toast.makeText(this@MainActivity, "Stored originals deleted", Toast.LENGTH_SHORT).show()
            })
        }

        // Night / HDR / Bokeh
        rows += UiKit.section(this, "NIGHT · HDR · BOKEH (XIAOMI)")
        rows += UiKit.note(this, if (availableExt.isEmpty())
            "Xiaomi doesn't offer Night, HDR or Bokeh to other apps on this camera. The app's own low-light noise reduction is still on."
        else "Available here: " + availableExt.joinToString(", ") { extName(it) } +
            ". Use the ☾ button at the top to switch. Your recipe or style is applied on top.")

        // Lens colour matching
        rows += UiKit.section(this, "LENS COLOUR MATCHING")
        rows += UiKit.note(this, "Point the camera at something plain white or grey (a wall or paper) so it fills the middle of the frame. " +
            "Measure the 1× lens first, then zoom to 0.6×, 3.2× and 5× and measure each. Recipes will then look the same on every lens.")
        val status = UiKit.note(this, lensStatusText())
        rows += status
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        btnRow.addView(UiKit.pill(this, "Measure this lens", "#2E4A6B") { measureLens(); status.text = lensStatusText() })
        btnRow.addView(UiKit.pill(this, "Clear", "#333333") {
            prefs.lensCalib = emptyMap(); prefs.lensCalibRef = null
            recomputeLensCorrection(); refreshLook(); status.text = lensStatusText()
        })
        rows += btnRow

        // RAW test
        rows += UiKit.section(this, "RAW TEST")
        rows += UiKit.note(this, "Checks which lenses give apps RAW (needed for the RAW colour pipeline). Send me a screenshot of the report.")
        rows += LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            addView(UiKit.pill(this@MainActivity, "Run camera report", "#2E4A6B") { showCameraReport() })
        }
        return rows
    }

    private fun toggleRow(label: String, on: Boolean, onToggle: (Boolean) -> Unit): View {
        @Suppress("UseSwitchCompatOrMaterialCode")
        val sw = android.widget.Switch(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 14f
            isChecked = on
            setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, v -> onToggle(v) }
        }
        return sw
    }

    private fun pickImage(launcher: androidx.activity.result.ActivityResultLauncher<PickVisualMediaRequest>) {
        launcher.launch(PickVisualMediaRequest.Builder()
            .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly).build())
    }

    // ------------------------------------------------------------------ Histogram

    private fun updateHistogram() {
        if (!histOn || histBusy || recording != null) return
        val frame = runCatching { previewView.bitmap }.getOrNull() ?: return
        val imgH = (frame.width / grid.imageAspect).roundToInt().coerceAtMost(frame.height).coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(Bitmap.createBitmap(frame, 0, 0, frame.width, imgH), 96,
            (96f * imgH / frame.width).roundToInt().coerceAtLeast(1), false)
        val look = lookNow
        val lut = lutNow
        histBusy = true
        histExecutor.execute {
            runCatching { histogramView.update(FilmShader.process(small, look, lut, 0.1f)) }
            histBusy = false
        }
    }

    // ------------------------------------------------------------------ Add recipe: screenshot / LUT

    private fun showAddRecipeMenu() {
        AlertDialog.Builder(this)
            .setTitle("Add a recipe")
            .setItems(arrayOf("From a screenshot of a recipe card", "Import a LUT (.cube)", "Copy the current recipe")) { _, which ->
                when (which) {
                    0 -> pickImage(pickScreenshot)
                    1 -> importLut.launch(arrayOf("*/*"))
                    else -> { saveRecipe(current.copy(name = current.name + " copy", credit = ""), true); openEditor() }
                }
            }
            .show()
    }

    private fun readRecipeScreenshot(uri: Uri) {
        showInfo("Reading recipe…")
        val image = runCatching { InputImage.fromFilePath(this, uri) }.getOrElse {
            Toast.makeText(this, "Couldn't open that image", Toast.LENGTH_LONG).show(); return
        }
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(image)
            .addOnSuccessListener { result ->
                val parsed = RecipeParser.parse(result.text)
                if (parsed == null) {
                    AlertDialog.Builder(this)
                        .setTitle("No recipe found")
                        .setMessage("I couldn't find recipe settings in that picture. Try a screenshot where the settings text is clear and not covered.")
                        .setPositiveButton("OK", null).show()
                    return@addOnSuccessListener
                }
                val r = parsed.recipe
                val msg = StringBuilder()
                msg.append(r.summary()).append("\n\n")
                msg.append("Found: ").append(parsed.found.joinToString(", ")).append('\n')
                if (parsed.missing.isNotEmpty()) msg.append("Not found (set to default): ").append(parsed.missing.joinToString(", "))
                val nameInput = android.widget.EditText(this).apply { setText(r.name); setSingleLine(true) }
                val box = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
                    addView(TextView(this@MainActivity).apply { text = msg; textSize = 13f })
                    addView(nameInput)
                }
                AlertDialog.Builder(this)
                    .setTitle("Recipe found")
                    .setView(box)
                    .setPositiveButton("Save") { _, _ ->
                        saveRecipe(r.copy(name = nameInput.text.toString().trim().ifBlank { r.name }), true)
                    }
                    .setNeutralButton("Save & edit") { _, _ ->
                        saveRecipe(r.copy(name = nameInput.text.toString().trim().ifBlank { r.name }), true)
                        openEditor()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Couldn't read text: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    // ------------------------------------------------------------------ Re-edit a photo

    private fun reEditPhoto(uri: Uri) {
        val look = lookNow
        val lut = lutNow
        val (name, desc) = currentLookLabel()
        var displayName = ""
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) displayName = c.getString(0)
            }
        }
        val original = File(File(filesDir, "originals"), displayName)
        processingOverlay.visibility = View.VISIBLE
        processingBar.isIndeterminate = true
        processingText.text = if (original.exists()) "Re-editing from the clean original…" else "Applying look…"
        cameraExecutor.execute {
            try {
                val source = if (original.exists()) ImageDecoder.createSource(original)
                else ImageDecoder.createSource(contentResolver, uri)
                val bmp = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val big = max(info.size.width, info.size.height)
                    if (big > 8192) decoder.setTargetSampleSize(((big + 8191) / 8192))
                }
                val src = if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
                val scale = (min(src.width, src.height) / 1080f).coerceAtLeast(1f)
                val out = FilmShader.process(src, look, lut, scale, 0)
                val saved = saveJpeg(out, "$name (re-edit): $desc", "FUJISIM_EDIT_${timestamp()}.jpg")
                val small = Bitmap.createScaledBitmap(out, 160, (160f * out.height / out.width).roundToInt(), true)
                runOnUiThread {
                    processingOverlay.visibility = View.GONE
                    processingBar.isIndeterminate = false
                    lastUri = saved; lastMime = "image/jpeg"
                    thumb.setImageBitmap(small)
                    Toast.makeText(this, "Saved a new copy with \"$name\"", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    processingOverlay.visibility = View.GONE
                    processingBar.isIndeterminate = false
                    Toast.makeText(this, "Couldn't edit that photo: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun currentLookLabel(): Pair<String, String> = when (family) {
        STYLES -> "${currentStyle.label} style" to styleSummary(styleLive)
        NORMAL -> "Normal" to normal.summary()
        else -> live.name to live.summary()
    }

    private fun extName(m: Int) = when (m) {
        ExtensionMode.NIGHT -> "Night"
        ExtensionMode.HDR -> "HDR"
        ExtensionMode.BOKEH -> "Bokeh"
        ExtensionMode.AUTO -> "Auto"
        else -> "Standard"
    }

    private fun lensName(id: String?) = if (id == null) "unknown" else (lensNames[id] ?: "camera $id")

    private fun lensStatusText(): String {
        val cal = prefs.lensCalib
        val ref = prefs.lensCalibRef
        val active = activePhysicalId
        val sb = StringBuilder("Active lens: ${lensName(active)}")
        if (active == null) sb.append(" (this phone doesn't report the active lens to apps)")
        if (cal.isEmpty()) sb.append("\nNo lenses measured yet.")
        else cal.keys.forEach { sb.append("\n✓ ${lensName(it)}${if (it == ref) " (reference)" else ""}") }
        return sb.toString()
    }

    /** Average the centre of the (unprocessed) preview frame and store it for the active lens. */
    private fun measureLens() {
        val id = activePhysicalId ?: "logical"
        val frame = runCatching { previewView.bitmap }.getOrNull()
        if (frame == null) { Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show(); return }
        val imgH = (frame.width / grid.imageAspect).roundToInt().coerceAtMost(frame.height)
        val cx = frame.width / 2; val cy = imgH / 2
        val r = (min(frame.width, imgH) * 0.12f).roundToInt().coerceAtLeast(4)
        var sr = 0.0; var sg = 0.0; var sb = 0.0; var n = 0
        val px = IntArray(1)
        var y = cy - r
        while (y < cy + r) {
            var x = cx - r
            while (x < cx + r) {
                frame.getPixels(px, 0, 1, x, y, 1, 1)
                fun lin(v: Int) = Math.pow(v / 255.0, 2.2)
                sr += lin(Color.red(px[0])); sg += lin(Color.green(px[0])); sb += lin(Color.blue(px[0])); n++
                x += 3
            }
            y += 3
        }
        if (n == 0) return
        val rgb = floatArrayOf((sr / n).toFloat(), (sg / n).toFloat(), (sb / n).toFloat())
        if (rgb.maxOrNull()!! < 0.02f) { Toast.makeText(this, "Too dark — point at something bright and white", Toast.LENGTH_LONG).show(); return }
        prefs.lensCalib = prefs.lensCalib + (id to rgb)
        if (prefs.lensCalibRef == null || zoomRatio in 0.95f..1.3f) {
            if (prefs.lensCalibRef == null || prefs.lensCalibRef == id || zoomRatio in 0.95f..1.3f) prefs.lensCalibRef = id
        }
        recomputeLensCorrection()
        refreshLook()
        Toast.makeText(this, "Measured ${lensName(id)}", Toast.LENGTH_SHORT).show()
    }

    /** Gains that make each measured lens match the reference lens. */
    private fun recomputeLensCorrection() {
        val cal = prefs.lensCalib
        val ref = prefs.lensCalibRef?.let { cal[it] }
        lensCorrection = if (ref == null) emptyMap() else cal.mapValues { (_, m) ->
            val g = FloatArray(3) { i -> (ref[i] / m[i].coerceAtLeast(1e-4f)) }
            val l = 0.2126f * g[0] + 0.7152f * g[1] + 0.0722f * g[2]
            FloatArray(3) { i -> (g[i] / l).coerceIn(0.8f, 1.25f) }
        }
    }

    private fun showCameraReport() {
        val extras = listOf(
            "Night/HDR/Bokeh/Auto for apps: " + (if (availableExt.isEmpty()) "none" else availableExt.joinToString { extName(it) }),
            "10-bit HLG video: " + (if (hlgSupported) "yes" else "unknown/no (check in VIDEO with LOG on)"),
            "Active lens now: ${lensName(activePhysicalId)} (id ${activePhysicalId ?: "—"})",
            "Phone sharpening off allowed: ${if (edgeOffSupported) "yes" else "no"}",
        )
        val text = CameraReport.text(this, extras)
        AlertDialog.Builder(this)
            .setTitle("Camera report")
            .setMessage(text)
            .setPositiveButton("Copy") { _, _ ->
                val cb = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("FujiSim camera report", text))
                Toast.makeText(this, "Copied — paste it to Claude", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ------------------------------------------------------------------ Camera

    private fun setVideoMode(on: Boolean) {
        if (on == videoMode || recording != null) return
        videoMode = on
        if (on && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        updateModeButtons()
        startCamera()
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun startCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            if (!extTried) {
                extTried = true
                val ef = ExtensionsManager.getInstanceAsync(this, provider)
                ef.addListener({
                    extensionsManager = runCatching { ef.get() }.getOrNull()
                    startCamera()
                }, ContextCompat.getMainExecutor(this))
                return@addListener
            }
            val baseSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            val em = extensionsManager
            availableExt = if (em == null) emptyList() else listOf(
                ExtensionMode.NIGHT, ExtensionMode.HDR, ExtensionMode.BOKEH, ExtensionMode.AUTO
            ).filter { runCatching { em.isExtensionAvailable(baseSelector, it) }.getOrDefault(false) }
            if (extMode !in availableExt) extMode = ExtensionMode.NONE
            val useExt = !videoMode && extMode != ExtensionMode.NONE && em != null
            val selector = if (useExt) em!!.getExtensionEnabledCameraSelector(baseSelector, extMode) else baseSelector
            val ratio = if (videoMode) AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
            else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            grid.imageAspect = if (videoMode) 9f / 16f else 3f / 4f

            val previewBuilder = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build())
            if (!useExt) Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(captureCallback)
            val preview = previewBuilder.build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            val useCase: UseCase
            if (videoMode) {
                val info = runCatching { selector.filter(provider.availableCameraInfos).firstOrNull() }.getOrNull()
                hlgSupported = info != null && runCatching {
                    Recorder.getVideoCapabilities(info).supportedDynamicRanges.contains(DynamicRange.HLG_10_BIT)
                }.getOrDefault(false)
                if (logMode && !hlgSupported) {
                    Toast.makeText(this, "10-bit log isn't available to apps on this camera — recording standard video", Toast.LENGTH_LONG).show()
                }
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD)))
                    .build()
                val builder = VideoCapture.Builder(recorder)
                if (logMode && hlgSupported) builder.setDynamicRange(DynamicRange.HLG_10_BIT)
                val vc = builder.build()
                videoCapture = vc
                imageCapture = null
                useCase = vc
            } else {
                val resSelector = if (highRes) {
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                        .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
                        .build()
                } else {
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(4000, 3000), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                        ).build()
                }
                val capBuilder = ImageCapture.Builder().setJpegQuality(95)
                if (!useExt) {
                    capBuilder.setResolutionSelector(resSelector).setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                }
                val capture = capBuilder.build()
                imageCapture = capture
                videoCapture = null
                useCase = capture
            }

            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, selector, preview, useCase)
            } catch (e: Exception) {
                if (videoMode && logMode) {
                    try {
                        val vc = VideoCapture.Builder(
                            Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.FHD)).build()
                        ).build()
                        videoCapture = vc
                        provider.unbindAll()
                        camera = provider.bindToLifecycle(this, selector, preview, vc)
                        hlgSupported = false
                        Toast.makeText(this, "Log mode not supported here — recording standard video", Toast.LENGTH_LONG).show()
                    } catch (e2: Exception) {
                        Toast.makeText(this, "Camera error: ${e2.message}", Toast.LENGTH_LONG).show()
                        return@addListener
                    }
                } else if (useExt) {
                    extMode = ExtensionMode.NONE
                    Toast.makeText(this, "That mode isn't available right now — switched back to standard", Toast.LENGTH_LONG).show()
                    mainHandler.post { startCamera() }
                    return@addListener
                } else {
                    Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_LONG).show()
                    return@addListener
                }
            }
            awbModes = readAwbModes()
            maxPhotoMp = readMaxMegapixels()
            edgeOffSupported = readEdgeOffSupported()
            lastCamKey = ""
            setupZoom()
            updateModeButtons()
            refreshLook()
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun readAwbModes(): IntArray? {
        val cam = camera ?: return null
        return runCatching {
            Camera2CameraInfo.from(cam.cameraInfo)
                .getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
        }.getOrNull()
    }

    /** Largest JPEG size the camera offers to apps (including high-resolution sizes), in megapixels. */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun readMaxMegapixels(): Int {
        val cam = camera ?: return 0
        return runCatching {
            val map = Camera2CameraInfo.from(cam.cameraInfo)
                .getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return 0
            val sizes = (map.getOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList()) +
                (map.getHighResolutionOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList())
            val best = sizes.maxOfOrNull { it.width.toLong() * it.height } ?: 0L
            (best / 1_000_000L).toInt()
        }.getOrDefault(0)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun readEdgeOffSupported(): Boolean {
        val cam = camera ?: return false
        return runCatching {
            Camera2CameraInfo.from(cam.cameraInfo)
                .getCameraCharacteristic(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
                ?.contains(CaptureRequest.EDGE_MODE_OFF) == true
        }.getOrDefault(false)
    }

    private var lastRecipeEv = 0f
    private var lastAwbMode = CaptureRequest.CONTROL_AWB_MODE_AUTO

    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyCameraSettings(awbMode: Int, ev: Float) {
        lastRecipeEv = ev
        lastAwbMode = awbMode
        val cam = camera ?: return
        val total = ev + userEv
        val key = "$awbMode|$total|$aeLocked|${lens.naturalDetail}"
        if (key == lastCamKey) return
        lastCamKey = key
        val exposure = cam.cameraInfo.exposureState
        if (exposure.isExposureCompensationSupported) {
            val step = exposure.exposureCompensationStep.toFloat()
            if (step > 0f) {
                val range = exposure.exposureCompensationRange
                cam.cameraControl.setExposureCompensationIndex((total / step).roundToInt().coerceIn(range.lower, range.upper))
            }
        }
        runCatching {
            val opts = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, awbMode)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, aeLocked)
            if (lens.naturalDetail && edgeOffSupported) {
                opts.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
            }
            Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(opts.build())
        }
    }

    private fun reapplyCameraSettings() = applyCameraSettings(lastAwbMode, lastRecipeEv)

    private fun resetFocus() {
        userEv = 0f
        aeLocked = false
        focusOverlay.clear()
        camera?.cameraControl?.cancelFocusAndMetering()
        reapplyCameraSettings()
    }

    // ------------------------------------------------------------------ Zoom & gestures

    private fun setupZoom() {
        val cam = camera ?: return
        val state = cam.cameraInfo.zoomState
        state.removeObservers(this)
        val zs = state.value
        val minZ = zs?.minZoomRatio ?: 1f
        val maxZ = zs?.maxZoomRatio ?: 1f
        zoomRatio = zoomRatio.coerceIn(minZ, maxZ)
        cam.cameraControl.setZoomRatio(zoomRatio)

        zoomBar.removeAllViews()
        val stops = mutableListOf<Float>()
        if (minZ < 0.95f) stops += (minZ * 10f).roundToInt() / 10f
        listOf(1f, 2f, 3.2f, 5f).filter { it <= maxZ + 0.01f }.forEach { stops += it }
        for (z in stops) {
            val tv = TextView(this).apply {
                text = formatZoom(z)
                tag = z
                textSize = 12f
                setTextColor(Color.WHITE)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    zoomRatio = z
                    camera?.cameraControl?.setZoomRatio(z)
                }
            }
            zoomBar.addView(tv)
        }
        zoomBar.visibility = if (stops.size > 1 || maxZ > 1.05f) View.VISIBLE else View.GONE
        state.observe(this) { z -> highlightZoom(z.zoomRatio) }
    }

    private fun formatZoom(z: Float): String {
        val r = (z * 10f).roundToInt() / 10f
        return if (abs(r - r.roundToInt()) < 0.05f) "${r.roundToInt()}×" else "$r×"
    }

    private fun highlightZoom(current: Float) {
        var nearest: View? = null
        var best = Float.MAX_VALUE
        for (i in 0 until zoomBar.childCount) {
            val v = zoomBar.getChildAt(i)
            val z = v.tag as? Float ?: continue
            val d = abs(z - current)
            if (d < best) { best = d; nearest = v }
        }
        for (i in 0 until zoomBar.childCount) {
            val v = zoomBar.getChildAt(i) as TextView
            val z = v.tag as? Float ?: continue
            val active = v == nearest
            v.setTextColor(if (active) Color.BLACK else Color.WHITE)
            v.background = if (active) GradientDrawable().apply {
                cornerRadius = dp(14).toFloat(); setColor(Color.parseColor(YELLOW))
            } else null
            v.text = if (active && best > 0.05f) formatZoom(current) else formatZoom(z)
        }
    }

    private fun focusAt(x: Float, y: Float, lock: Boolean) {
        val cam = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point).disableAutoCancel().build()
        cam.cameraControl.startFocusAndMetering(action)
        aeLocked = false
        reapplyCameraSettings()
        if (lock) {
            previewView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            mainHandler.postDelayed({
                aeLocked = true
                reapplyCameraSettings()
            }, 700)
        }
        focusOverlay.setLocked(lock)
        focusOverlay.showAt(x, y)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPreviewGestures() {
        var scaling = false
        var dragEv = false
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val cam = camera ?: return false
                val zs = cam.cameraInfo.zoomState.value ?: return false
                zoomRatio = (zs.zoomRatio * detector.scaleFactor).coerceIn(zs.minZoomRatio, zs.maxZoomRatio)
                cam.cameraControl.setZoomRatio(zoomRatio)
                scaling = true
                return true
            }
        })
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (editing) { closeEditor(); return true }
                if (!scaling) focusAt(e.x, e.y, lock = false)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                resetFocus()
                showInfo("Auto focus & exposure")
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (!scaling) focusAt(e.x, e.y, lock = true)
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (scaling || e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (dragEv || (abs(dy) > abs(dx) * 1.3f && abs(dy) > dp(12))) {
                    dragEv = true
                    userEv = (userEv + distanceY / previewView.height * 4f).coerceIn(-2f, 2f)
                    val snapped = (userEv * 10f).roundToInt() / 10f
                    focusOverlay.setEv(snapped)
                    reapplyCameraSettings()
                    return true
                }
                return false
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (scaling || dragEv || e1 == null || recording != null || editing) return false
                val dx = e2.x - e1.x
                if (abs(dx) > dp(60) && abs(velocityX) > abs(velocityY) * 1.5f) {
                    stepLook(if (dx < 0) 1 else -1)
                    return true
                }
                return false
            }
        })
        previewView.setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) { scaling = false; dragEv = false }
            scaleDetector.onTouchEvent(event)
            if (event.pointerCount > 1) scaling = true
            gestures.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP) v.performClick()
            true
        }
    }

    /** Swipe on the viewfinder: next/previous recipe, style or preset. */
    private fun stepLook(dir: Int) {
        previewView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        when (family) {
            STYLES -> {
                val all = PhotoStyle.values()
                selectStyle(all[(currentStyle.ordinal + dir + all.size) % all.size])
            }
            NORMAL -> {
                val all = listOf(NormalPreset("Default", NormalSettings())) + prefs.normalPresets
                val i = all.indexOfFirst { it.settings == normal }
                val next = all[((if (i < 0) 0 else i) + dir + all.size) % all.size]
                selectNormal(next.settings, next.name)
            }
            else -> {
                val i = recipes.indexOfFirst { it.id == current.id }
                selectRecipe(recipes[(i + dir + recipes.size) % recipes.size])
            }
        }
    }

    private fun selectRecipe(r: Recipe) {
        current = r
        live = r
        store.selectedId = r.id
        rebuildChips()
        refreshLook(showInfoPill = true)
    }

    private fun selectNormal(s: NormalSettings, name: String) {
        normal = s
        prefs.normal = s
        rebuildChips()
        refreshLook()
        showInfo("$name\n${s.summary()}")
    }

    private fun selectStyle(st: PhotoStyle) {
        currentStyle = st
        prefs.selectedStyle = st
        styleLive = prefs.styleSettings(st)
        syncStyleControls()
        refreshLook()
    }

    private fun setupStyleControls() {
        stylePad.onChange = { c, t ->
            styleLive = styleLive.copy(color = c, tone = t)
            prefs.saveStyleSettings(currentStyle, styleLive)
            refreshLook()
        }
        paletteBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                styleLive = styleLive.copy(palette = progress / 100f)
                prefs.saveStyleSettings(currentStyle, styleLive)
                refreshLook()
            }
            override fun onStartTrackingTouch(seek: SeekBar?) {}
            override fun onStopTrackingTouch(seek: SeekBar?) {}
        })
        syncStyleControls()
    }

    private fun syncStyleControls() {
        val accent = Color.parseColor(currentStyle.accentHex)
        stylePad.accent = accent
        stylePad.color = styleLive.color
        stylePad.tone = styleLive.tone
        stylePad.invalidate()
        paletteBar.progress = (styleLive.palette * 100).roundToInt()
        paletteBar.progressDrawable = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.parseColor("#4A4A4A"), accent)
        ).apply { cornerRadius = dp(8).toFloat() }
        paletteBar.thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        styleCategory.text = currentStyle.category.name
        styleName.text = currentStyle.label.uppercase(Locale.US)
        styleDots.text = PhotoStyle.values().joinToString(" ") { if (it == currentStyle) "●" else "•" }
    }

    // ------------------------------------------------------------------ Looks

    private fun reloadRecipes() {
        recipes = store.loadCustom() + BuiltIns.list
    }

    /** Build the look for the current family (without lens/WB-wheel/ISO extras). */
    private fun baseLook(): Pair<Look, Pair<Int, Float>> = when (family) {
        STYLES -> Look.fromStyle(currentStyle, styleLive) to (CaptureRequest.CONTROL_AWB_MODE_AUTO to 0f)
        NORMAL -> Look.fromNormal(normal) to (CaptureRequest.CONTROL_AWB_MODE_AUTO to 0f)
        else -> {
            val plan = WhiteBalance.plan(live, awbModes, wb.useRecipe)
            Look.fromRecipe(live, plan.gains) to (plan.awbMode to live.ev)
        }
    }

    private fun finishLook(base: Look): Look {
        val tint = WbWheel.gains(wb.wheelX, wb.wheelY)
        var lk = base.withLens(lens).let { it.copy(wb = FloatArray(3) { i -> it.wb[i] * tint[i] }) }
        if (lk.denoise > 0f) lk = lk.copy(denoise = isoDenoise)
        val corr = activePhysicalId?.let { lensCorrection[it] }
        if (corr != null && lensFacing == CameraSelector.LENS_FACING_BACK) {
            lk = lk.copy(wb = FloatArray(3) { i -> lk.wb[i] * corr[i] })
        }
        return lk
    }

    /** Rebuild the current look and push it everywhere. */
    private fun refreshLook(showInfoPill: Boolean = false) {
        val (base, cam) = baseLook()
        applyCameraSettings(cam.first, cam.second)
        lookNow = finishLook(base)
        lutNow = LutLibrary.get(this, lookNow.lut)
        wbBtn.text = if (wb.useRecipe && family == FUJI) "WB·R" else "WB"
        wbBtn.setTextColor(if (wb.wheelX != 0f || wb.wheelY != 0f || (wb.useRecipe && family == FUJI)) Color.parseColor(YELLOW) else Color.WHITE)
        val lensOn = lens.mist > 0f || lens.vignette > 0f || lens.halation > 0f || lens.cornerSoft > 0f
        lensBtn.setTextColor(if (lensOn) Color.parseColor(YELLOW) else Color.WHITE)
        if (showInfoPill) {
            when (family) {
                FUJI -> {
                    val credit = if (live.credit.isNotBlank()) "  ·  by ${live.credit}" else ""
                    showInfo("${live.name}$credit\n${live.summary()}")
                }
                NORMAL -> showInfo("NORMAL\n${normal.summary()}")
                else -> {}
            }
        }
        pushPreviewShader(SystemClock.elapsedRealtime() / 1000f)
    }

    private fun styleSummary(s: StyleSettings): String {
        fun pct(v: Float) = "${if (v > 0) "+" else ""}${(v * 100).roundToInt()}"
        return "Tone ${pct(s.tone)} · Colour ${pct(s.color)} · Palette ${(s.palette * 100).roundToInt()}% · " +
            "Grain ${(s.grain * 100).roundToInt()} · Detail ${pct(s.detail)}"
    }

    private fun pushPreviewShader(time: Float) {
        val w = previewView.width.toFloat().coerceAtLeast(1f)
        val h = previewView.height.toFloat().coerceAtLeast(1f)
        previewShader.set(lookNow, lutNow, w, h, 1f, time, if (showOriginal) 0f else 1f, zebraOn && !showOriginal)
        previewView.setRenderEffect(previewShader.renderEffect())
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    // ------------------------------------------------------------------ Carousel

    /** A look card: small live preview thumbnail + name. */
    private fun card(key: String?, label: String, selected: Boolean, onClick: () -> Unit, onLong: (() -> Unit)?): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#262626"))
                if (selected) setStroke(dp(2), Color.parseColor(YELLOW))
            }
            clipToOutline = true
            if (selected) setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        box.addView(img, LinearLayout.LayoutParams(dp(66), dp(50)))
        if (key == null) {
            img.setImageDrawable(null)
            box.addView(TextView(this).apply {
                text = "+"
                setTextColor(Color.WHITE); textSize = 20f; gravity = Gravity.CENTER
            }.also { (img.parent as LinearLayout).removeView(img) }, LinearLayout.LayoutParams(dp(66), dp(50)).apply { })
            box.getChildAt(0).background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat(); setStroke(dp(1), Color.parseColor("#666666"))
            }
        } else {
            cardThumbs[key] = img
        }
        box.addView(TextView(this).apply {
            text = label
            textSize = 11f
            setTextColor(if (selected) Color.parseColor(YELLOW) else Color.parseColor("#DDDDDD"))
            maxWidth = dp(84)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        })
        box.setOnClickListener {
            if (editing || recording != null) return@setOnClickListener
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onClick()
        }
        if (onLong != null) box.setOnLongClickListener {
            if (!editing && recording == null) onLong()
            true
        }
        return box
    }

    private fun rebuildChips() {
        recipeBar.removeAllViews()
        cardThumbs.clear()
        when (family) {
            FUJI -> {
                for (r in recipes) {
                    recipeBar.addView(card(r.id, if (r.builtIn) r.name else "★ ${r.name}", r.id == current.id,
                        { if (r.id == current.id) refreshLook(showInfoPill = true) else selectRecipe(r) },
                        { selectRecipe(r); openEditor() }))
                }
                recipeBar.addView(card(null, "Add recipe", false, { showAddRecipeMenu() }, null))
            }
            NORMAL -> {
                val presets = listOf(NormalPreset("Default", NormalSettings())) + prefs.normalPresets
                presets.forEachIndexed { idx, p ->
                    recipeBar.addView(card("preset:$idx", p.name, p.settings == normal,
                        { selectNormal(p.settings, p.name) },
                        if (idx == 0) null else { { confirmDeletePreset(p) } }))
                }
                recipeBar.addView(card(null, "Adjust", false, { openEditor() }, null))
            }
            else -> {}
        }
        mainHandler.postDelayed({ refreshThumbnails() }, 300)
    }

    private fun confirmDeletePreset(p: NormalPreset) {
        AlertDialog.Builder(this)
            .setTitle("Delete \"${p.name}\"?")
            .setPositiveButton("Delete") { _, _ ->
                prefs.normalPresets = prefs.normalPresets.filter { it != p }
                rebuildChips()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Render each card's thumbnail from the current camera frame with that card's look. */
    private fun refreshThumbnails() {
        if (recording != null || cardThumbs.isEmpty() || isFinishing || family == STYLES) return
        val frame = runCatching { previewView.bitmap }.getOrNull() ?: return
        val imgH = (frame.width / grid.imageAspect).roundToInt().coerceAtMost(frame.height)
        val cropped = Bitmap.createBitmap(frame, 0, 0, frame.width, imgH.coerceAtLeast(1))
        val small = Bitmap.createScaledBitmap(cropped, 132, (132f * cropped.height / cropped.width).roundToInt().coerceAtLeast(1), true)
        val jobs: List<Pair<String, Look>> = when (family) {
            FUJI -> recipes.map { r -> r.id to Look.fromRecipe(r, WhiteBalance.plan(r, awbModes, wb.useRecipe).gains).copy(grain = 0f) }
            NORMAL -> (listOf(NormalSettings()) + prefs.normalPresets.map { it.settings })
                .mapIndexed { i, s -> "preset:$i" to Look.fromNormal(s) }
            else -> emptyList()
        }
        val ctx = applicationContext
        thumbExecutor.execute {
            for ((key, look) in jobs) {
                val out = runCatching {
                    FilmShader.process(small, look, LutLibrary.get(ctx, look.lut), 0.15f)
                }.getOrNull() ?: continue
                runOnUiThread { cardThumbs[key]?.setImageBitmap(out) }
            }
        }
    }

    private fun updateModeButtons() {
        val idx = currentModeIndex()
        modeViews.forEachIndexed { i, v ->
            v.setTextColor(if (i == idx) Color.parseColor(YELLOW) else Color.parseColor("#BBBBBB"))
        }
        gridBtn.setTextColor(if (grid.mode != GridOverlay.Mode.OFF) Color.parseColor(YELLOW) else Color.WHITE)
        familyBtn.visibility = if (videoMode) View.VISIBLE else View.GONE
        nightBtn.visibility = if (!videoMode && availableExt.isNotEmpty()) View.VISIBLE else View.GONE
        nightBtn.text = when (extMode) {
            ExtensionMode.NIGHT -> "☾"
            ExtensionMode.HDR -> "HDR"
            ExtensionMode.BOKEH -> "◉"
            ExtensionMode.AUTO -> "A"
            else -> "☾"
        }
        nightBtn.setTextColor(if (extMode != ExtensionMode.NONE) Color.parseColor(YELLOW) else Color.parseColor("#888888"))
        familyBtn.text = "${FAMILY_NAMES[family]} ▾"
        logBtn.visibility = if (videoMode) View.VISIBLE else View.GONE
        logBtn.text = when {
            !logMode -> "LOG"
            hlgSupported -> "LOG 10b"
            else -> "LOG std"
        }
        logBtn.setTextColor(if (logMode) Color.parseColor(YELLOW) else Color.parseColor("#888888"))
        chipScroll.visibility = if (family == STYLES) View.GONE else View.VISIBLE
        stylesBar.visibility = if (family == STYLES) View.VISIBLE else View.GONE
        styleCategory.visibility = if (family == STYLES && recording == null) View.VISIBLE else View.GONE
        styleNameBox.visibility = if (family == STYLES) View.VISIBLE else View.GONE
        if (family == STYLES) syncStyleControls()
        shutter.setBackgroundResource(
            when {
                recording != null -> R.drawable.shutter_recording
                videoMode -> R.drawable.shutter_video
                else -> R.drawable.shutter
            }
        )
    }

    // ------------------------------------------------------------------ Panels

    private fun openEditor() {
        if (recording != null) return
        val view = when (family) {
            STYLES -> StylePanel(this, currentStyle, styleLive,
                onChange = { s ->
                    styleLive = s
                    prefs.saveStyleSettings(currentStyle, s)
                    refreshLook()
                },
                onClose = { closeEditor() }).view
            NORMAL -> AdjustPanel(this, normal,
                onChange = { s ->
                    normal = s
                    prefs.normal = s
                    refreshLook()
                },
                onSavePreset = { name, s ->
                    prefs.normalPresets = prefs.normalPresets + NormalPreset(name, s)
                    Toast.makeText(this, "Saved preset \"$name\"", Toast.LENGTH_SHORT).show()
                    rebuildChips()
                },
                onClose = { closeEditor() }).view
            else -> EditorPanel(this, current,
                onChange = { live = it; refreshLook() },
                onSave = { r, asNew -> saveRecipe(r, asNew) },
                onDelete = { deleteRecipe(it) },
                onClose = { closeEditor() },
                onImportLut = { importLut.launch(arrayOf("*/*")) }).view
        }
        showSheet(view)
    }

    private fun openWbPanel() {
        if (recording != null) return
        val panel = WbPanel(this, wb,
            onChange = {
                wb = it
                prefs.wb = it
                lastCamKey = ""
                refreshLook()
            },
            onClose = { closeEditor() })
        showSheet(panel.view)
    }

    private fun openLensPanel() {
        if (recording != null) return
        val panel = LensPanel(this, lens,
            onChange = {
                lens = it
                prefs.lens = it
                lastCamKey = ""
                refreshLook()
            },
            onClose = { closeEditor() })
        showSheet(panel.view)
    }

    private fun importLutFrom(uri: Uri) {
        try {
            val ref = LutLibrary.import(this, uri)
            var name = "Imported LUT"
            runCatching {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) name = c.getString(0).substringBeforeLast('.')
                }
            }
            val r = Recipe(name = name, film = FilmSim.NONE, lut = ref, credit = "Imported LUT")
            saveRecipe(r, true)
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't import LUT: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun closeEditor() {
        editing = false
        editorContainer.animate().translationY(dp(470).toFloat()).setDuration(180).withEndAction {
            if (!editing) {
                editorContainer.visibility = View.GONE
                sheetContent.removeAllViews()
                editorContainer.translationY = 0f
            }
        }.start()
        when (family) {
            FUJI -> live = current
            STYLES -> { styleLive = prefs.styleSettings(currentStyle); syncStyleControls() }
            else -> { normal = prefs.normal; rebuildChips() }
        }
        refreshLook()
    }

    private fun saveRecipe(r: Recipe, asNew: Boolean) {
        val custom = store.loadCustom().toMutableList()
        val toSave = if (asNew || r.builtIn) r.copy(id = UUID.randomUUID().toString(), builtIn = false) else r
        val idx = custom.indexOfFirst { it.id == toSave.id }
        if (idx >= 0) custom[idx] = toSave else custom.add(0, toSave)
        store.saveCustom(custom)
        reloadRecipes()
        current = toSave
        live = toSave
        store.selectedId = toSave.id
        closeEditor()
        rebuildChips()
        Toast.makeText(this, "Saved \"${toSave.name}\"", Toast.LENGTH_SHORT).show()
    }

    private fun deleteRecipe(r: Recipe) {
        store.saveCustom(store.loadCustom().filter { it.id != r.id })
        reloadRecipes()
        current = recipes.first()
        live = current
        store.selectedId = current.id
        closeEditor()
        rebuildChips()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupCompareButton() {
        findViewById<View>(R.id.compareBtn).setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    showOriginal = true
                    originalBadge.visibility = View.VISIBLE
                    pushPreviewShader(0f)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    showOriginal = false
                    originalBadge.visibility = View.GONE
                    pushPreviewShader(0f)
                }
            }
            true
        }
    }

    // ------------------------------------------------------------------ Photo

    private fun takePhoto() {
        val capture = imageCapture ?: return
        shutter.isEnabled = false
        flash.alpha = 0.85f
        flash.animate().alpha(0f).setDuration(250).start()

        val look = lookNow
        val lut = lutNow
        val (name, desc) = currentLookLabel()
        val keepOriginal = prefs.keepOriginals
        val previewWidth = previewView.width.coerceAtLeast(1)

        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val rotation = image.imageInfo.rotationDegrees
                    val raw = try { image.toBitmap() } finally { image.close() }
                    val src = limitSize(raw, 8192)
                    val scale = (min(src.width, src.height).toFloat() / previewWidth).coerceAtLeast(1f)
                    val fileName = "FUJISIM_${timestamp()}.jpg"
                    if (keepOriginal) runCatching { saveOriginal(src, rotation, fileName) }
                    val out = FilmShader.process(src, look, lut, scale, rotation)
                    src.recycle()
                    val uri = saveJpeg(out, "$name: $desc", fileName)
                    val small = Bitmap.createScaledBitmap(out, 160, (160f * out.height / out.width).roundToInt(), true)
                    runOnUiThread {
                        lastUri = uri
                        lastMime = "image/jpeg"
                        thumb.setImageBitmap(small)
                        shutter.isEnabled = true
                    }
                } catch (e: Throwable) {
                    runOnUiThread {
                        shutter.isEnabled = true
                        Toast.makeText(this@MainActivity, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                runOnUiThread {
                    shutter.isEnabled = true
                    Toast.makeText(this@MainActivity, "Capture failed: ${exception.message}", Toast.LENGTH_LONG).show()
                }
            }
        })
    }

    /** Keep within GPU texture limits; 50MP (8160×6144) fits without scaling. */
    private fun limitSize(src: Bitmap, maxSide: Int): Bitmap {
        val sc = min(1f, maxSide.toFloat() / max(src.width, src.height))
        if (sc >= 1f) return src
        val m = Matrix().apply { postScale(sc, sc) }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        src.recycle()
        return out
    }

    private fun timestamp() = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    /** Clean, un-styled copy kept privately so the photo can be re-edited with another look later. */
    private fun saveOriginal(src: Bitmap, rotation: Int, fileName: String) {
        val dir = File(filesDir, "originals").apply { mkdirs() }
        val f = File(dir, fileName)
        f.outputStream().use { src.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        val orientation = when (rotation) {
            90 -> ExifInterface.ORIENTATION_ROTATE_90
            180 -> ExifInterface.ORIENTATION_ROTATE_180
            270 -> ExifInterface.ORIENTATION_ROTATE_270
            else -> ExifInterface.ORIENTATION_NORMAL
        }
        ExifInterface(f.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
    }

    private fun saveJpeg(bmp: Bitmap, description: String, fileName: String = "FUJISIM_${timestamp()}.jpg"): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FujiSim")
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create gallery entry")
        resolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            ?: throw IllegalStateException("Could not write photo")
        runCatching {
            resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, description)
                exif.setAttribute(ExifInterface.TAG_SOFTWARE, "FujiSim Cam")
                exif.saveAttributes()
            }
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    // ------------------------------------------------------------------ Video

    @SuppressLint("MissingPermission")
    private fun toggleRecording() {
        val active = recording
        if (active != null) {
            active.stop()
            recording = null
            updateModeButtons()
            return
        }
        val vc = videoCapture ?: return
        val raw = File(cacheDir, "raw_${timestamp()}.mp4")
        val look = lookNow
        val lut = lutNow
        val temporal = isoTemporal
        var pending = vc.output.prepareRecording(this, FileOutputOptions.Builder(raw).build())
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending = pending.withAudioEnabled()
        }
        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    recordStart = SystemClock.elapsedRealtime()
                    recTimer.visibility = View.VISIBLE
                    mainHandler.post(recTick)
                }
                is VideoRecordEvent.Finalize -> {
                    recTimer.visibility = View.GONE
                    recording = null
                    updateModeButtons()
                    if (raw.exists() && raw.length() > 0) {
                        styleVideo(raw, look, lut, temporal)
                    } else {
                        Toast.makeText(this, "Recording failed (error ${event.error})", Toast.LENGTH_LONG).show()
                    }
                }
                else -> {}
            }
        }
        updateModeButtons()
    }

    private fun styleVideo(raw: File, look: Look, lut: LutImage?, temporal: Float) {
        val styled = File(cacheDir, "styled_${timestamp()}.mp4")
        processingOverlay.visibility = View.VISIBLE
        processingBar.progress = 0
        processingText.text = "Applying look to video… 0%"
        VideoStyler.style(this, raw, styled, look, lut, temporal,
            onProgress = { p ->
                processingBar.progress = p
                processingText.text = "Applying look to video… $p%"
            },
            onDone = { error ->
                if (error == null) {
                    try {
                        val uri = saveVideo(styled)
                        lastUri = uri
                        lastMime = "video/mp4"
                        runCatching {
                            ThumbnailUtils.createVideoThumbnail(styled, android.util.Size(160, 160), null)
                        }.getOrNull()?.let { thumb.setImageBitmap(it) }
                        Toast.makeText(this, "Video saved to Movies/FujiSim", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, "Could not save video: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(this, "Video processing failed: ${error.message}", Toast.LENGTH_LONG).show()
                }
                raw.delete()
                styled.delete()
                processingOverlay.visibility = View.GONE
            })
    }

    private fun saveVideo(file: File): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "FUJISIM_${timestamp()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/FujiSim")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create gallery entry")
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            ?: throw IllegalStateException("Could not write video")
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    private fun openLast() {
        val uri = lastUri
        if (uri == null) {
            Toast.makeText(this, "Photos: Pictures/FujiSim · Videos: Movies/FujiSim", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, lastMime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
    }
}
