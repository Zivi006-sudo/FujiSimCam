package com.fujisim.cam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.ExifInterface
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.File
import kotlin.math.roundToInt

/**
 * Captures one RAW (DNG) frame with Camera2 directly (CameraX 1.3 has no RAW support).
 * Runs a short hidden preview so auto-exposure/white-balance/focus settle first.
 * The caller must release CameraX before calling and rebind afterwards.
 */
class RawCapture(private val ctx: Context) {

    class Result(val dng: File?, val error: Throwable?, val iso: Int)

    @SuppressLint("MissingPermission")
    fun capture(cameraId: String, zoomRatio: Float, ev: Float, deviceOrientation: Int, out: File, done: (Result) -> Unit) {
        val thread = HandlerThread("raw").apply { start() }
        val handler = Handler(thread.looper)
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var reader: ImageReader? = null
        var previewTex: SurfaceTexture? = null
        var finished = false

        fun finish(r: Result) {
            if (finished) return
            finished = true
            runCatching { session?.close() }
            runCatching { device?.close() }
            runCatching { reader?.close() }
            runCatching { previewTex?.release() }
            thread.quitSafely()
            done(r)
        }

        try {
            val chars = cm.getCameraCharacteristics(cameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: throw IllegalStateException("No stream configuration")
            val size = map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height }
                ?: throw IllegalStateException("RAW not available on this camera")
            val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val rotation = (sensorOrientation + deviceOrientation) % 360
            val exifOrientation = when (rotation) {
                90 -> ExifInterface.ORIENTATION_ROTATE_90
                180 -> ExifInterface.ORIENTATION_ROTATE_180
                270 -> ExifInterface.ORIENTATION_ROTATE_270
                else -> ExifInterface.ORIENTATION_NORMAL
            }
            val evStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: (1f / 3f)
            val evRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            val evIndex = (ev / evStep).roundToInt().let { if (evRange != null) it.coerceIn(evRange.lower, evRange.upper) else it }
            val zoomRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            val zoom = if (zoomRange != null) zoomRatio.coerceIn(zoomRange.lower, zoomRange.upper) else 1f

            val rawReader = ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, 2)
            reader = rawReader
            val tex = SurfaceTexture(false).apply { setDefaultBufferSize(640, 480) }
            previewTex = tex
            val previewSurface = Surface(tex)

            var image: Image? = null
            var result: TotalCaptureResult? = null
            fun tryWrite() {
                val img = image ?: return
                val res = result ?: return
                try {
                    DngCreator(chars, res).use { dng ->
                        dng.setOrientation(exifOrientation)
                        out.outputStream().use { dng.writeImage(it, img) }
                    }
                    val iso = res.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
                    img.close()
                    finish(Result(out, null, iso))
                } catch (e: Throwable) {
                    img.close()
                    finish(Result(null, e, 0))
                }
            }
            rawReader.setOnImageAvailableListener({ r ->
                val img = r.acquireNextImage() ?: return@setOnImageAvailableListener
                if (image == null) { image = img; tryWrite() } else img.close()
            }, handler)

            handler.postDelayed({ finish(Result(null, IllegalStateException("RAW capture timed out"), 0)) }, 8000)

            cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    device = cam
                    @Suppress("DEPRECATION")
                    cam.createCaptureSession(listOf(previewSurface, rawReader.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            session = s
                            fun common(b: CaptureRequest.Builder) {
                                b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evIndex)
                                runCatching { b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom) }
                            }
                            val pb = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(previewSurface); common(this)
                            }
                            var frames = 0
                            var shot = false
                            s.setRepeatingRequest(pb.build(), object : CameraCaptureSession.CaptureCallback() {
                                override fun onCaptureCompleted(sess: CameraCaptureSession, req: CaptureRequest, r: TotalCaptureResult) {
                                    frames++
                                    if (shot) return
                                    val ae = r.get(CaptureResult.CONTROL_AE_STATE)
                                    val aeOk = ae == null || ae == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                                        ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED || ae == CaptureResult.CONTROL_AE_STATE_LOCKED
                                    if ((aeOk && frames >= 8) || frames >= 40) {
                                        shot = true
                                        val sb = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                            addTarget(rawReader.surface); common(this)
                                        }
                                        sess.capture(sb.build(), object : CameraCaptureSession.CaptureCallback() {
                                            override fun onCaptureCompleted(s2: CameraCaptureSession, rq: CaptureRequest, res: TotalCaptureResult) {
                                                result = res; tryWrite()
                                            }
                                        }, handler)
                                    }
                                }
                            }, handler)
                        }

                        override fun onConfigureFailed(s: CameraCaptureSession) {
                            finish(Result(null, IllegalStateException("RAW session failed"), 0))
                        }
                    }, handler)
                }

                override fun onDisconnected(cam: CameraDevice) { finish(Result(null, IllegalStateException("Camera disconnected"), 0)) }
                override fun onError(cam: CameraDevice, error: Int) { finish(Result(null, IllegalStateException("Camera error $error"), 0)) }
            }, handler)
        } catch (e: Throwable) {
            finish(Result(null, e, 0))
        }
    }
}
