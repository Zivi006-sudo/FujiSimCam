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
import android.widget.Switch
import android.widget.TextView
import kotlin.math.roundToInt

/** Lens character: soft glow (Pro-Mist), vignette, halation, corner softness, natural detail. Works with any look. */
class LensPanel(
    private val ctx: Context,
    initial: LensSettings,
    private val onChange: (LensSettings) -> Unit,
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
            text = "Lens character"
            setTextColor(Color.WHITE)
            textSize = 18f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(pill("Reset", "#333333", Color.WHITE) {
            s = LensSettings(naturalDetail = s.naturalDetail); onChange(s); onClose()
        })
        header.addView(pill("Done", "#FFC94A", Color.BLACK) { onClose() })
        col.addView(header)

        col.addView(slider("Sun glow (golden bloom on sunlit areas)", s.sunGlow) { s = s.copy(sunGlow = it); onChange(s) })
        col.addView(slider("Soft glow (Pro-Mist)", s.mist) { s = s.copy(mist = it); onChange(s) })
        col.addView(slider("Vignette", s.vignette) { s = s.copy(vignette = it); onChange(s) })
        col.addView(slider("Halation (red glow on bright edges)", s.halation) { s = s.copy(halation = it); onChange(s) })
        col.addView(slider("Corner softness", s.cornerSoft) { s = s.copy(cornerSoft = it); onChange(s) })

        @Suppress("UseSwitchCompatOrMaterialCode")
        val sw = Switch(ctx).apply {
            text = "Natural detail (turn off phone oversharpening)"
            setTextColor(Color.WHITE)
            textSize = 14f
            isChecked = s.naturalDetail
            setPadding(0, dp(14), 0, 0)
            setOnCheckedChangeListener { _, on -> s = s.copy(naturalDetail = on); onChange(s) }
        }
        col.addView(sw)
        col.addView(TextView(ctx).apply {
            text = "These apply on top of every recipe and style, in photos, video and the viewfinder."
            setTextColor(Color.parseColor("#888888"))
            textSize = 11f
            setPadding(0, dp(12), 0, 0)
        })
        return scroll
    }

    private fun pill(label: String, bg: String, fg: Int, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        setTextColor(fg)
        textSize = 14f
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(Color.parseColor(bg)) }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = dp(8) }
    }

    private fun slider(label: String, value: Float, onValue: (Float) -> Unit): View {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(2)) }
        val tv = TextView(ctx).apply { setTextColor(Color.WHITE); textSize = 14f }
        val sb = SeekBar(ctx)
        sb.max = 100
        sb.progress = (value * 100).roundToInt().coerceIn(0, 100)
        tv.text = "$label: ${sb.progress}"
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar?, progress: Int, fromUser: Boolean) {
                tv.text = "$label: $progress"
                if (fromUser) onValue(progress / 100f)
            }
            override fun onStartTrackingTouch(seek: SeekBar?) {}
            override fun onStopTrackingTouch(seek: SeekBar?) {}
        })
        box.addView(tv)
        box.addView(sb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return box
    }
}
