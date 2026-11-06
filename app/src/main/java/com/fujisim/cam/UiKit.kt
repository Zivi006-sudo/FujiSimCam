package com.fujisim.cam

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.roundToInt

/** Small shared helpers for building the bottom-sheet panels in code. */
object UiKit {
    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).roundToInt()

    fun pill(ctx: Context, label: String, bg: String, fg: Int = Color.WHITE, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        setTextColor(fg)
        textSize = 14f
        setPadding(dp(ctx, 14), dp(ctx, 8), dp(ctx, 14), dp(ctx, 8))
        background = GradientDrawable().apply { cornerRadius = dp(ctx, 18).toFloat(); setColor(Color.parseColor(bg)) }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = dp(ctx, 8) }
    }

    fun setPillOn(v: TextView, on: Boolean) {
        v.setTextColor(if (on) Color.BLACK else Color.WHITE)
        (v.background as? GradientDrawable)?.setColor(Color.parseColor(if (on) "#FFC94A" else "#333333"))
    }

    fun header(ctx: Context, title: String, vararg buttons: View): LinearLayout {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(ctx).apply {
            text = title; setTextColor(Color.WHITE); textSize = 18f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.forEach { row.addView(it) }
        return row
    }

    fun note(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        setTextColor(Color.parseColor("#9A9A9A"))
        textSize = 12f
        setPadding(0, dp(ctx, 6), 0, dp(ctx, 6))
    }

    fun section(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        setTextColor(Color.parseColor("#FFC94A"))
        textSize = 13f
        letterSpacing = 0.08f
        setPadding(0, dp(ctx, 14), 0, dp(ctx, 4))
    }

    /** Bipolar or unipolar slider. Value range lo..hi, shown as a whole number via fmt. */
    fun slider(ctx: Context, label: String, lo: Float, hi: Float, value: Float, fmt: (Float) -> String, onValue: (Float) -> Unit): View {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(ctx, 4), 0, 0) }
        val tv = TextView(ctx).apply { setTextColor(Color.WHITE); textSize = 13f }
        val steps = 200
        fun at(p: Int) = lo + (hi - lo) * p / steps
        val sb = SeekBar(ctx)
        sb.max = steps
        sb.progress = (((value - lo) / (hi - lo)) * steps).roundToInt().coerceIn(0, steps)
        tv.text = "$label: ${fmt(at(sb.progress))}"
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar?, progress: Int, fromUser: Boolean) {
                tv.text = "$label: ${fmt(at(progress))}"
                if (fromUser) onValue(at(progress))
            }
            override fun onStartTrackingTouch(seek: SeekBar?) {}
            override fun onStopTrackingTouch(seek: SeekBar?) {}
        })
        box.addView(tv)
        box.addView(sb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return box
    }

    fun signed100(v: Float): String {
        val n = (v * 100).roundToInt()
        return if (n > 0) "+$n" else "$n"
    }
}
