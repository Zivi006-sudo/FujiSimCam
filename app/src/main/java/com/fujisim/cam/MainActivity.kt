package com.fujisim.cam

import android.Manifest
import android.annotation.SuppressLint
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
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
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
import android.util.Size
import android.view.Choreographer
import android.view.GestureDetector
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
    private lateinit var editorContainer: FrameLayout
    private lateinit var zoomBar: LinearLayout
    private lateinit var gridBtn: TextView
    private lateinit var lookModeBtn: TextView
    private lateinit var logBtn: TextView
    private lateinit var photoModeBtn: TextView
    private lateinit var videoModeBtn: TextView
    private lateinit var processingOverlay: View
    private lateinit var processingText: TextView
    private lateinit var processingBar: ProgressBar
    private lateinit var focusOverlay: FocusOverlay
    private lateinit var resBtn: TextView
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
    private var stylesMode = false
    private var currentStyle = PhotoStyle.STANDARD
    private var styleLive = StyleSettings()
    private var videoMode = false
    private var logMode = false
    private var hlgSupported = false
    private var editing = false
    private var showOriginal = false
    private var lookNow = Look()
    private var lutNow: LutImage? = null
    private var lens = LensSettings()

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
    private val mainHandler = Handler(Looper.getMainLooper())

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else Toast.makeText(this, "Camera permission is needed", Toast.LENGTH_LONG).show()
        }
    private val importLut =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importLutFrom(uri)
        }
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
        zoomBar = findViewById(R.id.zoomBar)
        gridBtn = findViewById(R.id.gridBtn)
        lookModeBtn = findViewById(R.id.lookModeBtn)
        logBtn = findViewById(R.id.logBtn)
        photoModeBtn = findViewById(R.id.photoModeBtn)
        videoModeBtn = findViewById(R.id.videoModeBtn)
        processingOverlay = findViewById(R.id.processingOverlay)
        processingText = findViewById(R.id.processingText)
        processingBar = findViewById(R.id.processingBar)
        focusOverlay = findViewById(R.id.focusOverlay)
        resBtn = findViewById(R.id.resBtn)
        chipScroll = findViewById(R.id.chipScroll)
        stylesBar = findViewById(R.id.stylesBar)
        stylePad = findViewById(R.id.stylePad)
        paletteBar = findViewById(R.id.paletteBar)
        styleCategory = findViewById(R.id.styleCategory)
        styleNameBox = findViewById(R.id.styleNameBox)
        styleName = findViewById(R.id.styleName)
        styleDots = findViewById(R.id.styleDots)

        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER

        store = RecipeStore(this)
        prefs = AppPrefs(this)
        reloadRecipes()
        current = recipes.firstOrNull { it.id == store.selectedId } ?: recipes.first()
        live = current
        stylesMode = prefs.stylesMode
        currentStyle = prefs.selectedStyle
        styleLive = prefs.styleSettings(currentStyle)
        grid.mode = GridOverlay.Mode.values().getOrElse(prefs.gridMode) { GridOverlay.Mode.OFF }
        highRes = prefs.highRes
        lens = prefs.lens
        // Warm the LUT cache off the main thread so switching recipes is instant.
        Thread {
            FilmSim.values().mapNotNull { it.lut }.forEach { LutLibrary.get(applicationContext, "asset:$it") }
        }.start()
        setupStyleControls()

        rebuildChips()
        updateModeButtons()

        previewView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> refreshLook() }

        shutter.setOnClickListener { if (videoMode) toggleRecording() else takePhoto() }
        findViewById<View>(R.id.editBtn).setOnClickListener { if (editing) closeEditor() else openEditor() }
        findViewById<View>(R.id.flipBtn).setOnClickListener {
            if (recording != null) return@setOnClickListener
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            zoomRatio = 1f
            resetFocus()
            startCamera()
        }
        thumb.setOnClickListener { openLast() }
        gridBtn.setOnClickListener {
            val modes = GridOverlay.Mode.values()
            grid.mode = modes[(grid.mode.ordinal + 1) % modes.size]
            prefs.gridMode = grid.mode.ordinal
            updateModeButtons()
            updateLevelSensor()
        }
        lookModeBtn.setOnClickListener {
            if (recording != null || editing) return@setOnClickListener
            stylesMode = !stylesMode
            prefs.stylesMode = stylesMode
            lastCamKey = ""
            rebuildChips()
            updateModeButtons()
            refreshLook()
        }
        resBtn.setOnClickListener {
            if (recording != null) return@setOnClickListener
            if (!highRes && maxPhotoMp in 1..19) {
                Toast.makeText(this, "Xiaomi only gives apps up to ${maxPhotoMp}MP on this camera", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            highRes = !highRes
            prefs.highRes = highRes
            startCamera()
        }
        findViewById<View>(R.id.lensBtn).setOnClickListener {
            if (recording != null) return@setOnClickListener
            if (editing) closeEditor() else openLensPanel()
        }
        logBtn.setOnClickListener {
            if (recording != null) return@setOnClickListener
            logMode = !logMode
            updateModeButtons()
            startCamera()
        }
        photoModeBtn.setOnClickListener { setVideoMode(false) }
        videoModeBtn.setOnClickListener { setVideoMode(true) }
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
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(gravityListener)
        Choreographer.getInstance().removeFrameCallback(grainTicker)
        if (recording != null) toggleRecording()
    }

    override fun onStop() {
        super.onStop()
        orientationListener.disable()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private fun updateLevelSensor() {
        sensorManager.unregisterListener(gravityListener)
        if (grid.mode == GridOverlay.Mode.THIRDS_LEVEL) {
            sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let {
                sensorManager.registerListener(gravityListener, it, SensorManager.SENSOR_DELAY_UI)
            }
        }
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

    private fun startCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            val ratio = if (videoMode) AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
            else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            grid.imageAspect = if (videoMode) 9f / 16f else 3f / 4f

            val preview = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build())
                .build()
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
                val capture = ImageCapture.Builder()
                    .setResolutionSelector(resSelector)
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setJpegQuality(95)
                    .build()
                imageCapture = capture
                videoCapture = null
                useCase = capture
            }

            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, selector, preview, useCase)
            } catch (e: Exception) {
                // Fallback: e.g. HLG combo not supported — retry with standard video.
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
                // Skip the phone's edge sharpening for a natural, camera-like look.
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
                cornerRadius = dp(14).toFloat(); setColor(Color.parseColor("#FFC94A"))
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
            // Let metering settle on the new spot, then freeze exposure too.
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
                if (!scaling) focusAt(e.x, e.y, lock = false)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                resetFocus()
                Toast.makeText(this@MainActivity, "Auto focus & exposure", Toast.LENGTH_SHORT).show()
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
                    // drag up = brighter; full screen height ≈ 4 EV
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

    /** Swipe on the viewfinder: next/previous style (or recipe in Fuji mode). */
    private fun stepLook(dir: Int) {
        if (stylesMode) {
            val all = PhotoStyle.values()
            val next = all[(currentStyle.ordinal + dir + all.size) % all.size]
            selectStyle(next)
        } else {
            val i = recipes.indexOfFirst { it.id == current.id }
            val r = recipes[(i + dir + recipes.size) % recipes.size]
            current = r
            live = r
            store.selectedId = r.id
            rebuildChips()
            refreshLook()
        }
    }

    private fun selectStyle(st: PhotoStyle) {
        currentStyle = st
        prefs.selectedStyle = st
        styleLive = prefs.styleSettings(st)
        rebuildChips()
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

    /** Push the current style's colours/values into the on-screen pad, slider and labels. */
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

    /** Rebuild the current look from the recipe/style on screen and push it everywhere. */
    private fun refreshLook() {
        recipeInfo.visibility = if (stylesMode) View.GONE else View.VISIBLE
        if (stylesMode) {
            lookNow = Look.fromStyle(currentStyle, styleLive)
            applyCameraSettings(CaptureRequest.CONTROL_AWB_MODE_AUTO, 0f)
            recipeInfo.text = "${currentStyle.label} style\n" + styleSummary(styleLive)
        } else {
            val plan = WhiteBalance.plan(live, awbModes)
            lookNow = Look.fromRecipe(live, plan.gains)
            applyCameraSettings(plan.awbMode, live.ev)
            val credit = if (live.credit.isNotBlank()) "  ·  by ${live.credit}" else ""
            recipeInfo.text = "${live.name}$credit\n${live.summary()}"
        }
        lookNow = lookNow.withLens(lens)
        lutNow = LutLibrary.get(this, lookNow.lut)
        pushPreviewShader(SystemClock.elapsedRealtime() / 1000f)
    }

    private fun styleSummary(s: StyleSettings): String {
        fun pct(v: Float) = "${if (v > 0) "+" else ""}${(v * 100).roundToInt()}"
        return "Tone ${pct(s.tone)} · Colour ${pct(s.color)} · Palette ${(s.palette * 100).roundToInt()}% · Detail ${pct(s.detail)}"
    }

    private fun pushPreviewShader(time: Float) {
        val w = previewView.width.toFloat().coerceAtLeast(1f)
        val h = previewView.height.toFloat().coerceAtLeast(1f)
        previewShader.set(lookNow, lutNow, w, h, 1f, time, if (showOriginal) 0f else 1f)
        previewView.setRenderEffect(previewShader.renderEffect())
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun chip(label: String, selected: Boolean, onClick: () -> Unit, onLong: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(if (selected) Color.BLACK else Color.WHITE)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(if (selected) Color.parseColor("#FFC94A") else Color.parseColor("#2A2A2A"))
            }
            setOnClickListener { if (!editing && recording == null) onClick() }
            setOnLongClickListener {
                if (!editing && recording == null) { onClick(); openEditor() }
                true
            }
        }

    private fun rebuildChips() {
        recipeBar.removeAllViews()
        val lp = { LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) } }
        if (stylesMode) {
            for (st in PhotoStyle.values()) {
                recipeBar.addView(chip(st.label, st == currentStyle, { selectStyle(st) }, {}), lp())
            }
        } else {
            for (r in recipes) {
                recipeBar.addView(chip(if (r.builtIn) r.name else "★ ${r.name}", r.id == current.id, {
                    current = r
                    live = r
                    store.selectedId = r.id
                    rebuildChips()
                    refreshLook()
                }, {}), lp())
            }
        }
    }

    private fun updateModeButtons() {
        lookModeBtn.text = if (stylesMode) "STYLES" else "FUJI"
        gridBtn.text = grid.mode.label
        photoModeBtn.setTextColor(if (!videoMode) Color.parseColor("#FFC94A") else Color.WHITE)
        videoModeBtn.setTextColor(if (videoMode) Color.parseColor("#FFC94A") else Color.WHITE)
        logBtn.visibility = if (videoMode) View.VISIBLE else View.GONE
        resBtn.visibility = if (videoMode) View.GONE else View.VISIBLE
        resBtn.text = if (highRes) "${if (maxPhotoMp > 0) maxPhotoMp else 50}MP" else "12MP"
        resBtn.setTextColor(if (highRes) Color.parseColor("#FFC94A") else Color.WHITE)
        chipScroll.visibility = if (stylesMode) View.GONE else View.VISIBLE
        stylesBar.visibility = if (stylesMode) View.VISIBLE else View.GONE
        styleCategory.visibility = if (stylesMode) View.VISIBLE else View.GONE
        styleNameBox.visibility = if (stylesMode) View.VISIBLE else View.GONE
        if (stylesMode) syncStyleControls()
        logBtn.text = when {
            !logMode -> "LOG off"
            hlgSupported -> "LOG 10-bit"
            else -> "LOG (std)"
        }
        logBtn.setTextColor(if (logMode) Color.parseColor("#FFC94A") else Color.WHITE)
        shutter.setBackgroundResource(
            when {
                recording != null -> R.drawable.shutter_recording
                videoMode -> R.drawable.shutter_video
                else -> R.drawable.shutter
            }
        )
    }

    private fun openEditor() {
        if (recording != null) return
        editing = true
        val view = if (stylesMode) {
            StylePanel(this, currentStyle, styleLive,
                onChange = { s ->
                    styleLive = s
                    prefs.saveStyleSettings(currentStyle, s)
                    refreshLook()
                },
                onClose = { closeEditor() }).view
        } else {
            EditorPanel(this, current,
                onChange = { live = it; refreshLook() },
                onSave = { r, asNew -> saveRecipe(r, asNew) },
                onDelete = { deleteRecipe(it) },
                onClose = { closeEditor() },
                onImportLut = { importLut.launch(arrayOf("*/*")) }).view
        }
        editorContainer.removeAllViews()
        editorContainer.addView(view)
        editorContainer.visibility = View.VISIBLE
    }

    private fun openLensPanel() {
        if (recording != null) return
        editing = true
        val panel = LensPanel(this, lens,
            onChange = {
                lens = it
                prefs.lens = it
                lastCamKey = ""
                refreshLook()
            },
            onClose = { closeEditor() })
        editorContainer.removeAllViews()
        editorContainer.addView(panel.view)
        editorContainer.visibility = View.VISIBLE
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
        editorContainer.visibility = View.GONE
        editorContainer.removeAllViews()
        if (!stylesMode) live = current else {
            styleLive = prefs.styleSettings(currentStyle)
            syncStyleControls()
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
        val name = if (stylesMode) "${currentStyle.label} style" else live.name
        val desc = if (stylesMode) styleSummary(styleLive) else live.summary()
        val previewWidth = previewView.width.coerceAtLeast(1)

        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val rotation = image.imageInfo.rotationDegrees
                    val raw = try { image.toBitmap() } finally { image.close() }
                    val src = limitSize(raw, 8192)
                    val scale = (min(src.width, src.height).toFloat() / previewWidth).coerceAtLeast(1f)
                    val out = FilmShader.process(src, look, lut, scale, rotation)
                    src.recycle()
                    val uri = saveJpeg(out, "$name: $desc")
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

    private fun timestamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun saveJpeg(bmp: Bitmap, description: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "FUJISIM_${timestamp()}.jpg")
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
                        styleVideo(raw, look, lut)
                    } else {
                        Toast.makeText(this, "Recording failed (error ${event.error})", Toast.LENGTH_LONG).show()
                    }
                }
                else -> {}
            }
        }
        updateModeButtons()
    }

    private fun styleVideo(raw: File, look: Look, lut: LutImage?) {
        val styled = File(cacheDir, "styled_${timestamp()}.mp4")
        processingOverlay.visibility = View.VISIBLE
        processingBar.progress = 0
        processingText.text = "Applying look to video… 0%"
        VideoStyler.style(this, raw, styled, look, lut,
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
