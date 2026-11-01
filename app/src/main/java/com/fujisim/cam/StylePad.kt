package com.fujisim.cam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * iPhone-style pad. Horizontal = Colour (grey → the style's own colour), vertical = Tone (darker → brighter).
 * The pad is tinted with the current style's accent colour, like on iPhone.
 */
class StylePad @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {
    var color = 0f   // -1..1
    var tone = 0f    // -1..1
    var accent: Int = Color.WHITE
        set(v) { field = v; invalidate() }
    var onChange: ((Float, Float) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 255, 255) }
    private val centre = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 1.5f * density
    }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    private fun square(): RectF {
        val side = min(width, height).toFloat()
        val l = (width - side) / 2f
        val t = (height - side) / 2f
        return RectF(l, t, l + side, t + side)
    }

    override fun onDraw(canvas: Canvas) {
        val r = square()
        val radius = r.width() * 0.16f
        fill.shader = LinearGradient(r.left, 0f, r.right, 0f,
            Color.parseColor("#5A5A5A"), accent, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(r, radius, radius, fill)
        shade.shader = LinearGradient(0f, r.top, 0f, r.bottom,
            Color.argb(0, 0, 0, 0), Color.argb(170, 0, 0, 0), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(r, radius, radius, shade)

        // dot matrix
        val n = 9
        val step = r.width() / (n + 1)
        val dr = r.width() / 90f
        for (i in 1..n) for (j in 1..n) {
            canvas.drawCircle(r.left + i * step, r.top + j * step, dr, dot)
        }
        canvas.drawCircle(r.centerX(), r.centerY(), dr * 2.2f, centre)

        val x = r.left + step + (color + 1f) / 2f * (r.width() - 2 * step)
        val y = r.top + step + (1f - (tone + 1f) / 2f) * (r.height() - 2 * step)
        canvas.drawCircle(x, y, r.width() / 16f, knob)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val r = square()
        val step = r.width() / 10f
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                color = (((event.x - r.left - step) / (r.width() - 2 * step)) * 2f - 1f).coerceIn(-1f, 1f)
                tone = ((1f - (event.y - r.top - step) / (r.height() - 2 * step)) * 2f - 1f).coerceIn(-1f, 1f)
                invalidate()
                onChange?.invoke(color, tone)
            }
        }
        return true
    }
}
