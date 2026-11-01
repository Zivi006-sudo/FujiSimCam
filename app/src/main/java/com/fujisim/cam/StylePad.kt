package com.fujisim.cam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View

/** iPhone-style square pad: horizontal = Colour (muted → rich), vertical = Tone (darker → brighter). */
class StylePad(context: Context) : View(context) {
    var color = 0f   // -1..1
    var tone = 0f    // -1..1
    var onChange: ((Float, Float) -> Unit)? = null

    private val bg = Paint()
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 255, 255, 255); strokeWidth = 2f }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFC94A") }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 255, 255, 255); textSize = 30f }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val side = (w * 0.7f).toInt()
        setMeasuredDimension(w, side)
    }

    override fun onDraw(canvas: Canvas) {
        val side = height.toFloat()
        val left = (width - side) / 2f
        bg.shader = LinearGradient(left, 0f, left + side, side,
            Color.parseColor("#2B2B2B"), Color.parseColor("#4A3A22"), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(left, 0f, left + side, side, 24f, 24f, bg)
        for (i in 1..3) {
            val p = side * i / 4f
            canvas.drawLine(left + p, 0f, left + p, side, grid)
            canvas.drawLine(left, p, left + side, p, grid)
        }
        canvas.drawText("Tone", left + 12f, 36f, text)
        canvas.drawText("Colour", left + side - 110f, side - 14f, text)
        val x = left + (color + 1f) / 2f * side
        val y = (1f - (tone + 1f) / 2f) * side
        canvas.drawCircle(x, y, 20f, dot)
        canvas.drawCircle(x, y, 26f, ring)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val side = height.toFloat()
        val left = (width - side) / 2f
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                color = (((event.x - left) / side) * 2f - 1f).coerceIn(-1f, 1f)
                tone = ((1f - event.y / side) * 2f - 1f).coerceIn(-1f, 1f)
                invalidate()
                onChange?.invoke(color, tone)
            }
        }
        return true
    }
}
