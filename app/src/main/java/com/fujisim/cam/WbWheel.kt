package com.fujisim.cam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * White-balance colour wheel. Centre = no change. Drag toward a colour to tint the scene that way
 * (toward orange = warmer, toward blue = cooler, green/magenta = tint). Distance = strength.
 */
class WbWheel(context: Context) : View(context) {
    var x01 = 0f   // -1..1
    var y01 = 0f   // -1..1
    var onChange: ((Float, Float) -> Unit)? = null

    private val wheel = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.argb(120, 255, 255, 255)
    }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val knobRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.BLACK
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val side = (w * 0.62f).toInt()
        setMeasuredDimension(w, side)
    }

    private fun radius() = min(width, height) / 2f - 8f

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = radius()
        val hues = IntArray(13) { Color.HSVToColor(floatArrayOf((it * 30f) % 360f, 0.85f, 1f)) }
        wheel.shader = SweepGradient(cx, cy, hues, null)
        canvas.drawCircle(cx, cy, r, wheel)
        fade.shader = RadialGradient(cx, cy, r, Color.argb(255, 235, 235, 235), Color.argb(0, 235, 235, 235), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, fade)
        canvas.drawCircle(cx, cy, r, ring)
        canvas.drawCircle(cx, cy, 6f, ring)
        val kx = cx + x01 * r
        val ky = cy + y01 * r
        canvas.drawCircle(kx, ky, 16f, knob)
        canvas.drawCircle(kx, ky, 16f, knobRing)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val r = radius()
                var dx = (event.x - width / 2f) / r
                var dy = (event.y - height / 2f) / r
                val d = hypot(dx, dy)
                if (d > 1f) { dx /= d; dy /= d }
                x01 = dx; y01 = dy
                invalidate()
                onChange?.invoke(x01, y01)
            }
        }
        return true
    }

    companion object {
        /** RGB gains for a wheel position: tint toward the colour under the knob, normalised to keep brightness. */
        fun gains(x: Float, y: Float): FloatArray {
            val d = hypot(x, y).coerceAtMost(1f)
            if (d < 0.02f) return floatArrayOf(1f, 1f, 1f)
            // SweepGradient starts at 3 o'clock and goes clockwise (screen y down).
            var deg = Math.toDegrees(atan2(y.toDouble(), x.toDouble())).toFloat()
            if (deg < 0) deg += 360f
            val c = Color.HSVToColor(floatArrayOf(deg, 1f, 1f))
            val t = floatArrayOf(Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f)
            val mean = (t[0] + t[1] + t[2]) / 3f
            val k = 0.45f * d
            val g = FloatArray(3) { 1f + k * (t[it] - mean) }
            val l = 0.2126f * g[0] + 0.7152f * g[1] + 0.0722f * g[2]
            return FloatArray(3) { g[it] / l }
        }
    }
}
