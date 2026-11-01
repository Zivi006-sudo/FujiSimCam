package com.fujisim.cam

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.roundToInt

/** Editor for iPhone-style Photographic Styles: pad + Palette, Grain and Detail. Saves as you go. */
class StylePanel(
    private val ctx: Context,
    private val style: PhotoStyle,
    initial: StyleSettings,
    private val onChange: (StyleSettings) -> Unit,
    private val onClose: () -> Unit,
) {
    private var s = initial
    val view: View = build()

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).roundToInt()

    private fun build(): View {
        val scroll = ScrollView(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(28))
        }
        scroll.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(ctx).apply {
            text = style.label
            setTextColor(Color.WHITE)
            textSize = 18f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(pill("Reset", "#333333") {
            s = StyleSettings()
            onChange(s)
            onClose()
        })
        header.addView(pill("Done", "#FFC94A", Color.BLACK) { onClose() })
        col.addView(header)

        val pad = StylePad(ctx).apply {
            color = s.color
            tone = s.tone
            onChange = { c, t -> update(s.copy(color = c, tone = t)) }
        }
        col.addView(pad, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12); bottomMargin = dp(8)
        })

        col.addView(slider("Palette (style strength)", 0f, 1f, s.palette, true) { update(s.copy(palette = it)) })
        col.addView(slider("Texture: Grain", 0f, 1f, s.grain, true) { update(s.copy(grain = it)) })
        col.addView(slider("Texture: Detail (smooth ↔ crisp)", -1f, 1f, s.detail, false) { update(s.copy(detail = it)) })
        return scroll
    }

    private fun update(n: StyleSettings) {
        s = n
        onChange(n)
    }

    private fun pill(label: String, bg: String, fg: Int = Color.WHITE, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        setTextColor(fg)
        textSize = 14f
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(Color.parseColor(bg)) }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = dp(8) }
    }

    private fun slider(label: String, lo: Float, hi: Float, value: Float, percent: Boolean, onValue: (Float) -> Unit): View {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(2)) }
        val tv = TextView(ctx).apply { setTextColor(Color.WHITE); textSize = 14f }
        val steps = 100
        fun at(p: Int) = lo + (hi - lo) * p / steps
        fun fmt(v: Float) = if (percent) "${(v * 100).roundToInt()}%" else String.format("%+.0f", v * 100)
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
}
