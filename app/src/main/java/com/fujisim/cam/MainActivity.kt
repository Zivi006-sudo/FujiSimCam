package com.fujisim.cam

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
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
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var recipeBar: LinearLayout
    private lateinit var recipeInfo: TextView
    private lateinit var originalBadge: TextView
    private lateinit var shutter: View
    private lateinit var thumb: ImageView
    private lateinit var flash: View
    private lateinit var editorContainer: FrameLayout

    private lateinit var store: RecipeStore
    private var recipes: List<Recipe> = emptyList()
    private lateinit var current: Recipe          // selected (saved) recipe
    private lateinit var live: Recipe             // what's on screen (may include unsaved edits)
    private var editing = false
    private var showOriginal = false

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var awbModes: IntArray? = null
    private var lastCamKey = ""
    private var lastUri: Uri? = null
    private val previewShader = FilmShader()
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else Toast.makeText(this, "Camera permission is needed", Toast.LENGTH_LONG).show()
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
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.preview)
        recipeBar = findViewById(R.id.recipeBar)
        recipeInfo = findViewById(R.id.recipeInfo)
        originalBadge = findViewById(R.id.originalBadge)
        shutter = findViewById(R.id.shutter)
        thumb = findViewById(R.id.thumb)
        flash = findViewById(R.id.flash)
        editorContainer = findViewById(R.id.editorContainer)

        // TextureView mode so the RenderEffect (our film shader) applies to the camera image.
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER

        store = RecipeStore(this)
        reloadRecipes()
        current = recipes.firstOrNull { it.id == store.selectedId } ?: recipes.first()
        live = current
        rebuildChips()

        previewView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyRecipe(live) }

        shutter.setOnClickListener { takePhoto() }
        findViewById<View>(R.id.editBtn).setOnClickListener { if (editing) closeEditor() else openEditor() }
        findViewById<View>(R.id.flipBtn).setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            startCamera()
        }
        thumb.setOnClickListener { openLastPhoto() }
        setupCompareButton()
        setupTapToFocus()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (editing) closeEditor() else finish()
            }
        })

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onStart() {
        super.onStart()
        orientationListener.enable()
    }

    override fun onStop() {
        super.onStop()
        orientationListener.disable()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    // ---------------------------------------------------------------- Camera

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val previewSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()
            val preview = Preview.Builder().setResolutionSelector(previewSelector).build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            val captureSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(Size(4000, 3000), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                )
                .build()
            val capture = ImageCapture.Builder()
                .setResolutionSelector(captureSelector)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setJpegQuality(95)
                .build()
            imageCapture = capture

            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, selector, preview, capture)
                awbModes = readAwbModes()
                lastCamKey = ""
                applyRecipe(live)
            } catch (e: Exception) {
                Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_LONG).show()
            }
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

    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyCameraSettings(r: Recipe, plan: WhiteBalance.Plan) {
        val cam = camera ?: return
        val key = "${plan.awbMode}|${r.ev}"
        if (key == lastCamKey) return
        lastCamKey = key

        val exposure = cam.cameraInfo.exposureState
        if (exposure.isExposureCompensationSupported) {
            val step = exposure.exposureCompensationStep.toFloat()
            if (step > 0f) {
                val range = exposure.exposureCompensationRange
                val idx = (r.ev / step).roundToInt().coerceIn(range.lower, range.upper)
                cam.cameraControl.setExposureCompensationIndex(idx)
            }
        }
        runCatching {
            val options = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, plan.awbMode)
                .build()
            Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(options)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTapToFocus() {
        previewView.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                val action = FocusMeteringAction.Builder(point)
                    .setAutoCancelDuration(4, TimeUnit.SECONDS)
                    .build()
                camera?.cameraControl?.startFocusAndMetering(action)
                v.performClick()
            }
            true
        }
    }

    // ---------------------------------------------------------------- Recipes

    private fun reloadRecipes() {
        recipes = store.loadCustom() + BuiltIns.list
    }

    private fun applyRecipe(r: Recipe) {
        live = r
        val plan = WhiteBalance.plan(r, awbModes)
        val w = previewView.width.toFloat().coerceAtLeast(1f)
        val h = previewView.height.toFloat().coerceAtLeast(1f)
        previewShader.set(r, plan.gains, w, h, 1f, if (showOriginal) 0f else 1f)
        previewView.setRenderEffect(previewShader.renderEffect())
        val credit = if (r.credit.isNotBlank()) "  ·  by ${r.credit}" else ""
        recipeInfo.text = "${r.name}$credit\n${r.summary()}"
        applyCameraSettings(r, plan)
    }

    private fun select(r: Recipe) {
        current = r
        store.selectedId = r.id
        applyRecipe(r)
        rebuildChips()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun rebuildChips() {
        recipeBar.removeAllViews()
        for (r in recipes) {
            val selected = r.id == current.id
            val chip = TextView(this).apply {
                text = if (r.builtIn) r.name else "★ ${r.name}"
                textSize = 13f
                setTextColor(if (selected) Color.BLACK else Color.WHITE)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(if (selected) Color.parseColor("#FFC94A") else Color.parseColor("#2A2A2A"))
                }
                setOnClickListener { if (!editing) select(r) }
                setOnLongClickListener {
                    if (!editing) { select(r); openEditor() }
                    true
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            recipeBar.addView(chip, lp)
        }
    }

    private fun openEditor() {
        editing = true
        val panel = EditorPanel(
            this, current,
            onChange = { applyRecipe(it) },
            onSave = { r, asNew -> saveRecipe(r, asNew) },
            onDelete = { deleteRecipe(it) },
            onClose = { closeEditor() },
        )
        editorContainer.removeAllViews()
        editorContainer.addView(panel.view)
        editorContainer.visibility = View.VISIBLE
    }

    private fun closeEditor() {
        editing = false
        editorContainer.visibility = View.GONE
        editorContainer.removeAllViews()
        applyRecipe(current)
    }

    private fun saveRecipe(r: Recipe, asNew: Boolean) {
        val custom = store.loadCustom().toMutableList()
        val toSave = if (asNew || r.builtIn) r.copy(id = UUID.randomUUID().toString(), builtIn = false) else r
        val idx = custom.indexOfFirst { it.id == toSave.id }
        if (idx >= 0) custom[idx] = toSave else custom.add(0, toSave)
        store.saveCustom(custom)
        reloadRecipes()
        current = toSave
        store.selectedId = toSave.id
        closeEditor()
        rebuildChips()
        Toast.makeText(this, "Saved \"${toSave.name}\"", Toast.LENGTH_SHORT).show()
    }

    private fun deleteRecipe(r: Recipe) {
        store.saveCustom(store.loadCustom().filter { it.id != r.id })
        reloadRecipes()
        current = recipes.first()
        store.selectedId = current.id
        closeEditor()
        rebuildChips()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupCompareButton() {
        findViewById<View>(R.id.compareBtn).setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    showOriginal = true
                    originalBadge.visibility = View.VISIBLE
                    applyRecipe(live)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    showOriginal = false
                    originalBadge.visibility = View.GONE
                    applyRecipe(live)
                }
            }
            true
        }
    }

    // ---------------------------------------------------------------- Capture

    private fun takePhoto() {
        val capture = imageCapture ?: return
        shutter.isEnabled = false
        flash.alpha = 0.85f
        flash.animate().alpha(0f).setDuration(250).start()

        val recipe = live
        val awb = awbModes
        val previewWidth = previewView.width.coerceAtLeast(1)

        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val rotation = image.imageInfo.rotationDegrees
                    val raw = try { image.toBitmap() } finally { image.close() }
                    val upright = prepare(raw, rotation)
                    val scale = (min(upright.width, upright.height).toFloat() / previewWidth).coerceAtLeast(1f)
                    val plan = WhiteBalance.plan(recipe, awb)
                    val out = FilmShader.process(upright, recipe, plan.gains, scale)
                    val uri = saveJpeg(out, recipe)
                    val small = Bitmap.createScaledBitmap(out, 160, (160f * out.height / out.width).roundToInt(), true)
                    runOnUiThread {
                        lastUri = uri
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

    /** Rotate upright and keep within GPU texture limits. */
    private fun prepare(src: Bitmap, rotation: Int): Bitmap {
        val maxSide = 4096f
        val sc = min(1f, maxSide / max(src.width, src.height))
        if (rotation == 0 && sc >= 1f) return src
        val m = Matrix().apply {
            postRotate(rotation.toFloat())
            postScale(sc, sc)
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private fun saveJpeg(bmp: Bitmap, recipe: Recipe): Uri {
        val name = "FUJISIM_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
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
                exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, "${recipe.name}: ${recipe.summary()}")
                exif.setAttribute(ExifInterface.TAG_SOFTWARE, "FujiSim Cam")
                exif.saveAttributes()
            }
        }

        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    private fun openLastPhoto() {
        val uri = lastUri
        if (uri == null) {
            Toast.makeText(this, "Photos are saved to Pictures/FujiSim", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/jpeg")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
    }
}
