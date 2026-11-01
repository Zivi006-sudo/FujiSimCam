package com.fujisim.cam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.util.Locale

/** Draws the yellow focus box, the ☀ exposure slider next to it, and the AE/AF LOCK badge. */
class FocusOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var fx = -1f
    private var fy = -1f
    private var ev = 0f
    private var locked = false
    private var visibleUntil = 0L

    private val density = resources.displayMetrics.density
    private val yellow = Color.parseColor("#FFC94A")
    private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = yellow; style = Paint.Style.STROKE; strokeWidth = 2.5f * density
    }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = yellow; strokeWidth = 1.5f * density }
    private val sun = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = yellow; strokeWidth = 2f * density }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = yellow; textSize = 13f * density; isFakeBoldText = true
    }
    private val badgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = yellow }
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; textSize = 12f * density; isFakeBoldText = true; textAlign = Paint.Align.CENTER
    }

    /** Show the box at (x, y). */
    fun showAt(x: Float, y: Float) {
        fx = x; fy = y
        poke()
    }

    fun setEv(value: Float) {
        ev = value
        poke()
    }

    fun setLocked(value: Boolean) {
        locked = value
        poke()
    }

    fun clear() {
        fx = -1f; fy = -1f; locked = false; ev = 0f
        invalidate()
    }

    private fun poke() {
        visibleUntil = System.currentTimeMillis() + 2500
        invalidate()
        postDelayed({ invalidate() }, 2600)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (locked) {
            val text = "AE/AF LOCK"
            val w = badgeText.measureText(text) + 20 * density
            val cx = width / 2f
            val top = 112 * density
            canvas.drawRoundRect(cx - w / 2, top, cx + w / 2, top + 24 * density, 6 * density, 6 * density, badgeBg)
            canvas.drawText(text, cx, top + 17 * density, badgeText)
        }
        val showBox = fx >= 0 && (locked || System.currentTimeMillis() < visibleUntil)
        if (!showBox) {
            if (ev != 0f) drawEvLabel(canvas, width - 70 * density, 100 * density)
            return
        }
        val half = 36 * density
        canvas.drawRect(fx - half, fy - half, fx + half, fy + half, box)

        // Exposure slider to the right of the box
        val sx = fx + half + 18 * density
        val len = 70 * density
        val sy = fy - (ev / 2f) * len
        canvas.drawLine(sx, fy - len, sx, sy - 12 * density, track)
        canvas.drawLine(sx, sy + 12 * density, sx, fy + len, track)
        canvas.drawCircle(sx, sy, 5 * density, sun)
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val r1 = 8 * density
            val r2 = 11 * density
            canvas.drawLine(
                sx + (r1 * Math.cos(a)).toFloat(), sy + (r1 * Math.sin(a)).toFloat(),
                sx + (r2 * Math.cos(a)).toFloat(), sy + (r2 * Math.sin(a)).toFloat(), sun
            )
        }
        if (ev != 0f) drawEvLabel(canvas, sx + 14 * density, sy + 5 * density)
    }

    private fun drawEvLabel(canvas: Canvas, x: Float, y: Float) {
        canvas.drawText(String.format(Locale.US, "%+.1f EV", ev), x, y, label)
    }
}
