package com.fujisim.cam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A passport/ID photo size. [headFrac] = chin-to-crown height as a share of the photo height. */
data class PhotoSpec(val label: String, val wMm: Float, val hMm: Float, val headFrac: Float, val crownTop: Float) {
    val aspect get() = wMm / hMm
    fun pixels(dpi: Int = 300) = (wMm / 25.4f * dpi).roundToInt() to (hMm / 25.4f * dpi).roundToInt()
}

object Passport {
    val SPECS = listOf(
        PhotoSpec("35×45 mm", 35f, 45f, 0.72f, 0.08f),
        PhotoSpec("2×2 in (US)", 50.8f, 50.8f, 0.60f, 0.12f),
        PhotoSpec("33×48 mm", 33f, 48f, 0.68f, 0.09f),
        PhotoSpec("40×50 mm", 40f, 50f, 0.70f, 0.09f),
    )

    val COLORS = listOf(
        "White" to Color.WHITE,
        "Off-white" to Color.rgb(242, 242, 238),
        "Light grey" to Color.rgb(217, 217, 217),
        "Light blue" to Color.rgb(190, 216, 240),
        "Blue" to Color.rgb(67, 142, 219),
        "Red" to Color.rgb(210, 35, 42),
    )

    /** Head height (crown to chin) from the face box: the detector's box stops around the hairline. */
    private const val HEAD_PER_BOX = 1.32f

    private val accurate by lazy {
        FaceDetection.getClient(FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE).build())
    }
    val fast by lazy {
        FaceDetection.getClient(FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).build())
    }
    private val segmenter by lazy {
        Segmentation.getClient(SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build())
    }

    class Result(val photo: Bitmap, val warning: String?)

    /**
     * Find the face, crop to the spec with the head at the right size, replace the background.
     * Runs on a background thread. Throws with a readable message if no face is found.
     */
    fun make(src: Bitmap, spec: PhotoSpec, bg: Int): Result {
        val faces = Tasks.await(accurate.process(InputImage.fromBitmap(src, 0)))
        val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
            ?: throw IllegalStateException("No face found — try again facing the camera")
        val warning = check(face)
        val box = face.boundingBox
        val (outW, outH) = spec.pixels()

        // Work at twice the output size for clean edges, then scale down.
        val workW = outW * 2; val workH = outH * 2
        val head = box.height() * HEAD_PER_BOX
        val crown = box.bottom - head
        val s = spec.headFrac * workH / head                   // source px -> work px
        val cropW = workW / s; val cropH = workH / s
        val left = box.exactCenterX() - cropW / 2f
        val top = crown - spec.crownTop * workH / s

        val work = Bitmap.createBitmap(workW, workH, Bitmap.Config.ARGB_8888)
        // Only draw the part of the crop that lies inside the photo; the rest stays transparent (becomes background).
        val iL = max(left, 0f); val iT = max(top, 0f)
        val iR = min(left + cropW, src.width.toFloat()); val iB = min(top + cropH, src.height.toFloat())
        if (iR <= iL || iB <= iT) throw IllegalStateException("Face is too close to the edge — step back a little")
        Canvas(work).drawBitmap(src,
            Rect(iL.roundToInt(), iT.roundToInt(), iR.roundToInt(), iB.roundToInt()),
            RectF((iL - left) * s, (iT - top) * s, (iR - left) * s, (iB - top) * s), Paint(Paint.FILTER_BITMAP_FLAG))

        val mask = Tasks.await(segmenter.process(InputImage.fromBitmap(work, 0)))
        val mw = mask.width; val mh = mask.height
        val buf = mask.buffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val raw = FloatArray(mw * mh).also { buf.get(it) }
        val conf = resample(blur3(raw, mw, mh), mw, mh, workW, workH)

        val px = IntArray(workW * workH)
        work.getPixels(px, 0, workW, 0, 0, workW, workH)
        // Old background colour (to remove its tint from hair edges)
        var br = 0.0; var bgc = 0.0; var bb = 0.0; var bn = 0
        for (i in px.indices) {
            if (conf[i] < 0.08f && (px[i] ushr 24) > 0) {
                br += px[i] shr 16 and 255; bgc += px[i] shr 8 and 255; bb += px[i] and 255; bn++
            }
        }
        val oR = if (bn > 0) (br / bn).toFloat() else 128f
        val oG = if (bn > 0) (bgc / bn).toFloat() else 128f
        val oB = if (bn > 0) (bb / bn).toFloat() else 128f
        val nR = Color.red(bg).toFloat(); val nG = Color.green(bg).toFloat(); val nB = Color.blue(bg).toFloat()
        for (i in px.indices) {
            val c = px[i]
            var m = smooth(0.22f, 0.78f, conf[i])
            if ((c ushr 24) == 0) m = 0f                       // outside the original photo
            if (m <= 0.001f) { px[i] = bg or (0xFF shl 24); continue }
            var r = (c shr 16 and 255).toFloat(); var g = (c shr 8 and 255).toFloat(); var b = (c and 255).toFloat()
            if (m < 0.98f && m > 0.15f) {                       // un-mix the old background from soft edges
                r = ((r - (1f - m) * oR) / m).coerceIn(0f, 255f)
                g = ((g - (1f - m) * oG) / m).coerceIn(0f, 255f)
                b = ((b - (1f - m) * oB) / m).coerceIn(0f, 255f)
            }
            val k = if (m < 0.98f) m else 1f
            px[i] = Color.rgb((r * k + nR * (1f - k)).roundToInt(), (g * k + nG * (1f - k)).roundToInt(), (b * k + nB * (1f - k)).roundToInt())
        }
        work.setPixels(px, 0, workW, 0, 0, workW, workH)
        val out = Bitmap.createScaledBitmap(work, outW, outH, true)
        work.recycle()
        return Result(out, warning)
    }

    /** A 4×6 inch (300 dpi) print sheet filled with copies and light cut lines. */
    fun sheet(photo: Bitmap): Bitmap {
        val sw = 1800; val sh = 1200; val gap = 36
        val out = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        cv.drawColor(Color.WHITE)
        val cols = max(1, (sw - gap) / (photo.width + gap))
        val rows = max(1, (sh - gap) / (photo.height + gap))
        val x0 = (sw - (cols * photo.width + (cols - 1) * gap)) / 2f
        val y0 = (sh - (rows * photo.height + (rows - 1) * gap)) / 2f
        val line = Paint().apply { color = Color.rgb(200, 200, 200); strokeWidth = 1f }
        for (r in 0 until rows) for (c in 0 until cols) {
            val x = x0 + c * (photo.width + gap); val y = y0 + r * (photo.height + gap)
            cv.drawBitmap(photo, x, y, null)
            cv.drawRect(x - 1, y - 1, x + photo.width, y + photo.height, line.apply { style = Paint.Style.STROKE })
        }
        return out
    }

    /** Pose problems worth mentioning (null if fine). */
    fun check(face: Face): String? = when {
        abs(face.headEulerAngleZ) > 6f -> "Head is tilted — keep it level"
        abs(face.headEulerAngleY) > 9f -> "Face is turned — look straight at the camera"
        else -> null
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun blur3(a: FloatArray, w: Int, h: Int): FloatArray {
        val o = FloatArray(a.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f; var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until w && yy in 0 until h) { s += a[yy * w + xx]; n++ }
            }
            o[y * w + x] = s / n
        }
        return o
    }

    /** Bilinear resample of a mask to the work size (no-op if equal). */
    private fun resample(a: FloatArray, w: Int, h: Int, ow: Int, oh: Int): FloatArray {
        if (w == ow && h == oh) return a
        val o = FloatArray(ow * oh)
        for (y in 0 until oh) {
            val fy = (y + 0.5f) * h / oh - 0.5f
            val y0 = fy.toInt().coerceIn(0, h - 1); val y1 = min(y0 + 1, h - 1); val ty = (fy - y0).coerceIn(0f, 1f)
            for (x in 0 until ow) {
                val fx = (x + 0.5f) * w / ow - 0.5f
                val x0 = fx.toInt().coerceIn(0, w - 1); val x1 = min(x0 + 1, w - 1); val tx = (fx - x0).coerceIn(0f, 1f)
                val top = a[y0 * w + x0] * (1 - tx) + a[y0 * w + x1] * tx
                val bot = a[y1 * w + x0] * (1 - tx) + a[y1 * w + x1] * tx
                o[y * ow + x] = top * (1 - ty) + bot * ty
            }
        }
        return o
    }
}

/** On-screen guide: the photo frame, a head outline and a live hint. */
class PassportOverlay @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var spec: PhotoSpec = Passport.SPECS[0]; set(v) { field = v; invalidate() }
    var imageRect: RectF = RectF(); set(v) { field = v; invalidate() }
    var hint: String = ""; set(v) { field = v; invalidate() }
    var good = false; set(v) { field = v; invalidate() }

    private val shade = Paint().apply { color = Color.argb(140, 0, 0, 0) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }

    /** The photo frame in view coordinates. */
    fun frame(): RectF {
        val r = if (imageRect.isEmpty) RectF(0f, 0f, width.toFloat(), height.toFloat()) else imageRect
        var fw = r.width() * 0.74f
        var fh = fw / spec.aspect
        if (fh > r.height() * 0.84f) { fh = r.height() * 0.84f; fw = fh * spec.aspect }
        val cx = r.centerX(); val cy = r.centerY() - r.height() * 0.02f
        return RectF(cx - fw / 2f, cy - fh / 2f, cx + fw / 2f, cy + fh / 2f)
    }

    /** Where the head (crown to chin) should be, in view coordinates. */
    fun headRect(): RectF {
        val f = frame()
        val hh = f.height() * spec.headFrac
        val top = f.top + f.height() * spec.crownTop
        val hw = hh * 0.74f
        return RectF(f.centerX() - hw / 2f, top, f.centerX() + hw / 2f, top + hh)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val f = frame()
        val path = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            addRect(f, Path.Direction.CW)
        }
        canvas.drawPath(path, shade)
        val col = if (good) Color.rgb(90, 220, 120) else Color.WHITE
        stroke.color = col; dash.color = col
        canvas.drawRect(f, stroke)
        val h = headRect()
        canvas.drawOval(h, dash)
        // shoulders
        val sy = h.bottom + h.height() * 0.22f
        canvas.drawLine(f.left + f.width() * 0.06f, f.bottom - f.height() * 0.02f, h.left - h.width() * 0.1f, sy, dash)
        canvas.drawLine(f.right - f.width() * 0.06f, f.bottom - f.height() * 0.02f, h.right + h.width() * 0.1f, sy, dash)
        // eye line
        val ey = h.top + h.height() * 0.48f
        canvas.drawLine(h.left - h.width() * 0.15f, ey, h.right + h.width() * 0.15f, ey, dash)
        text.textSize = resources.displayMetrics.density * 15f
        text.color = col
        canvas.drawText(hint, f.centerX(), f.top - text.textSize * 0.7f, text)
        text.textSize = resources.displayMetrics.density * 11f
        text.color = Color.argb(200, 255, 255, 255)
        canvas.drawText(spec.label, f.centerX(), f.bottom + text.textSize * 1.6f, text)
    }
}
