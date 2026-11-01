package com.fujisim.cam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Composition grid + level, drawn over the image area of the viewfinder. */
class GridOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Mode(val label: String) {
        OFF("Grid"), THIRDS("3×3"), THIRDS_LEVEL("3×3 + Level"), FOUR("4×4"), SQUARE("1:1")
    }

    var mode: Mode = Mode.OFF
        set(v) { field = v; invalidate() }

    /** Width / height of the camera image as shown (portrait), e.g. 3/4 for photo, 9/16 for video. */
    var imageAspect: Float = 3f / 4f
        set(v) { field = v; invalidate() }

    /** Device roll in degrees (0 = upright portrait). */
    var rollDegrees: Float = 0f
        set(v) {
            field = v
            if (mode == Mode.THIRDS_LEVEL) invalidate()
        }

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 255, 255, 255)
        strokeWidth = 2f
    }
    private val shade = Paint().apply { color = Color.argb(120, 0, 0, 0) }
    private val level = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 5f }

    private fun frame(): RectF {
        val w = width.toFloat()
        val h = height.toFloat()
        return if (w / h > imageAspect) {
            val fw = h * imageAspect
            RectF((w - fw) / 2f, 0f, (w + fw) / 2f, h)
        } else {
            val fh = w / imageAspect
            RectF(0f, (h - fh) / 2f, w, (h + fh) / 2f)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (mode == Mode.OFF) return
        val f = frame()
        when (mode) {
            Mode.THIRDS, Mode.THIRDS_LEVEL -> divide(canvas, f, 3)
            Mode.FOUR -> divide(canvas, f, 4)
            Mode.SQUARE -> {
                val side = min(f.width(), f.height())
                val sq = RectF(f.centerX() - side / 2, f.centerY() - side / 2, f.centerX() + side / 2, f.centerY() + side / 2)
                canvas.drawRect(f.left, f.top, f.right, sq.top, shade)
                canvas.drawRect(f.left, sq.bottom, f.right, f.bottom, shade)
                canvas.drawRect(f.left, sq.top, sq.left, sq.bottom, shade)
                canvas.drawRect(sq.right, sq.top, f.right, sq.bottom, shade)
                divide(canvas, sq, 3)
            }
            else -> {}
        }
        if (mode == Mode.THIRDS_LEVEL) drawLevel(canvas, f)
    }

    private fun divide(canvas: Canvas, f: RectF, n: Int) {
        for (i in 1 until n) {
            val x = f.left + f.width() * i / n
            val y = f.top + f.height() * i / n
            canvas.drawLine(x, f.top, x, f.bottom, line)
            canvas.drawLine(f.left, y, f.right, y, line)
        }
    }

    private fun drawLevel(canvas: Canvas, f: RectF) {
        val snapped = (rollDegrees / 90f).roundToInt() * 90f
        val off = rollDegrees - snapped
        val isLevel = abs(off) < 1f
        level.color = if (isLevel) Color.parseColor("#5BE37D") else Color.parseColor("#FFC94A")
        val len = min(f.width(), f.height()) * 0.35f
        canvas.save()
        canvas.translate(f.centerX(), f.centerY())
        canvas.rotate(rollDegrees)
        canvas.drawLine(-len, 0f, -len * 0.25f, 0f, level)
        canvas.drawLine(len * 0.25f, 0f, len, 0f, level)
        canvas.restore()
        // fixed reference ticks
        level.color = Color.argb(160, 255, 255, 255)
        canvas.save()
        canvas.translate(f.centerX(), f.centerY())
        canvas.rotate(snapped)
        canvas.drawLine(-len * 0.2f, 0f, len * 0.2f, 0f, line)
        canvas.restore()
    }
}
