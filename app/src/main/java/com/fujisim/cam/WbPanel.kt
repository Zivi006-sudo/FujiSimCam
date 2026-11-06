package com.fujisim.cam

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/** White balance: Auto (default) vs the recipe's fixed WB, plus a colour wheel to fine-tune for the scene. */
class WbPanel(
    private val ctx: Context,
    initial: WbSettings,
    private val onChange: (WbSettings) -> Unit,
    private val onClose: () -> Unit,
) {
    private var s = initial
    private lateinit var autoBtn: TextView
    private lateinit var recipeBtn: TextView
    private lateinit var wheel: WbWheel
    val view: View = build()

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).roundToInt()

    private fun build(): View {
        val scroll = ScrollView(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(24))
        }
        scroll.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(ctx).apply {
            text = "White balance"
            setTextColor(Color.WHITE); textSize = 18f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(pill("Reset", "#333333", Color.WHITE) {
            s = s.copy(wheelX = 0f, wheelY = 0f); wheel.x01 = 0f; wheel.y01 = 0f; wheel.invalidate(); onChange(s)
        })
        header.addView(pill("Done", "#FFC94A", Color.BLACK) { onClose() })
        col.addView(header)

        val modes = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(4))
        }
        autoBtn = pill("Auto", "#333333", Color.WHITE) { s = s.copy(useRecipe = false); refreshModes(); onChange(s) }
        recipeBtn = pill("Recipe's WB", "#333333", Color.WHITE) { s = s.copy(useRecipe = true); refreshModes(); onChange(s) }
        modes.addView(autoBtn); modes.addView(recipeBtn)
        col.addView(modes)
        col.addView(TextView(ctx).apply {
            text = "Auto adapts to any light (best indoors). Recipe's WB uses the recipe's fixed Kelvin — best outdoors in daylight. The recipe's Red/Blue shift applies in both."
            setTextColor(Color.parseColor("#AAAAAA")); textSize = 12f; gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(8))
        })

        wheel = WbWheel(ctx).apply {
            x01 = s.wheelX; y01 = s.wheelY
            onChange = { x, y -> s = s.copy(wheelX = x, wheelY = y); this@WbPanel.onChange(s) }
        }
        col.addView(wheel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(TextView(ctx).apply {
            text = "Drag toward a colour to tint the scene: orange = warmer, blue = cooler, green / magenta = tint. Centre = no change."
            setTextColor(Color.parseColor("#888888")); textSize = 11f; gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })
        refreshModes()
        return scroll
    }

    private fun refreshModes() {
        fun style(v: TextView, on: Boolean) {
            v.setTextColor(if (on) Color.BLACK else Color.WHITE)
            (v.background as GradientDrawable).setColor(Color.parseColor(if (on) "#FFC94A" else "#333333"))
        }
        style(autoBtn, !s.useRecipe)
        style(recipeBtn, s.useRecipe)
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
}
