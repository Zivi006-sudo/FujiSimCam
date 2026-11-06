package com.fujisim.cam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.sqrt

/** Small live luminance histogram (computed from the graded preview), with clipping hints. */
class HistogramView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var bins = FloatArray(64)
    private var clipHi = 0f
    private var clipLo = 0f
    private val bg = Paint().apply { color = Color.argb(110, 0, 0, 0) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 235, 235, 235) }
    private val warn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF5A4A") }

    fun update(bmp: Bitmap) {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val b = FloatArray(64)
        var hi = 0; var lo = 0
        for (c in px) {
            val l = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c))
            b[(l / 256f * 64f).toInt().coerceIn(0, 63)]++
            if (l >= 250f) hi++
            if (l <= 4f) lo++
        }
        var m = 1f
        for (v in b) m = max(m, v)
        for (i in b.indices) b[i] = sqrt(b[i] / m)   // sqrt makes small bins visible
        bins = b
        clipHi = hi.toFloat() / px.size
        clipLo = lo.toFloat() / px.size
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawRoundRect(0f, 0f, w, h, 10f, 10f, bg)
        val path = Path().apply {
            moveTo(4f, h - 4f)
            for (i in bins.indices) lineTo(4f + (w - 8f) * i / (bins.size - 1), h - 4f - (h - 8f) * bins[i])
            lineTo(w - 4f, h - 4f); close()
        }
        canvas.drawPath(path, fill)
        if (clipHi > 0.01f) canvas.drawRect(w - 7f, 3f, w - 3f, h - 3f, warn)   // blown highlights
        if (clipLo > 0.03f) canvas.drawRect(3f, 3f, 7f, h - 3f, warn)          // crushed shadows
    }
}
