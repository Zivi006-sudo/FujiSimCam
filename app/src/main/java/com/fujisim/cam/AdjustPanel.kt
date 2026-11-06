package com.fujisim.cam

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/** NORMAL mode: base (Natural/Phone), tone sliders, Shadows/Midtones/Highlights colour wheels, presets. */
class AdjustPanel(
    private val ctx: Context,
    initial: NormalSettings,
    private val onChange: (NormalSettings) -> Unit,
    private val onSavePreset: (String, NormalSettings) -> Unit,
    private val onClose: () -> Unit,
) {
    private var s = initial
    val view: View = build()

    private fun dp(v: Int) = UiKit.dp(ctx, v)

    private fun update(n: NormalSettings) { s = n; onChange(n) }

    private fun build(): View {
        val scroll = ScrollView(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(28))
        }
        scroll.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        col.addView(UiKit.header(ctx, "Adjust",
            UiKit.pill(ctx, "Save preset", "#2E4A6B") { askName() },
            UiKit.pill(ctx, "Reset", "#333333") { update(NormalSettings(naturalBase = s.naturalBase)); rebuild() },
            UiKit.pill(ctx, "Done", "#FFC94A", Color.BLACK) { onClose() }))

        // Base
        col.addView(UiKit.section(ctx, "BASE"))
        val baseRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        lateinit var natBtn: TextView
        lateinit var phoneBtn: TextView
        natBtn = UiKit.pill(ctx, "Natural", "#333333") {
            update(s.copy(naturalBase = true)); UiKit.setPillOn(natBtn, true); UiKit.setPillOn(phoneBtn, false)
        }
        phoneBtn = UiKit.pill(ctx, "Phone", "#333333") {
            update(s.copy(naturalBase = false)); UiKit.setPillOn(natBtn, false); UiKit.setPillOn(phoneBtn, true)
        }
        UiKit.setPillOn(natBtn, s.naturalBase); UiKit.setPillOn(phoneBtn, !s.naturalBase)
        baseRow.addView(natBtn); baseRow.addView(phoneBtn)
        col.addView(baseRow)
        col.addView(UiKit.note(ctx, "Natural: soft highlight roll-off, gentle contrast, accurate calmer colour — a pro-camera look. Phone: Xiaomi's colours untouched."))

        // Tone
        col.addView(UiKit.section(ctx, "TONE"))
        col.addView(UiKit.slider(ctx, "Exposure", -2f, 2f, s.exposure, { String.format(Locale.US, "%+.1f EV", it) }) { update(s.copy(exposure = it)) })
        col.addView(UiKit.slider(ctx, "Contrast", -1f, 1f, s.contrast, UiKit::signed100) { update(s.copy(contrast = it)) })
        col.addView(UiKit.slider(ctx, "Highlights", -1f, 1f, s.highlights, UiKit::signed100) { update(s.copy(highlights = it)) })
        col.addView(UiKit.slider(ctx, "Shadows", -1f, 1f, s.shadows, UiKit::signed100) { update(s.copy(shadows = it)) })
        col.addView(UiKit.slider(ctx, "Saturation", -1f, 1f, s.saturation, UiKit::signed100) { update(s.copy(saturation = it)) })
        col.addView(UiKit.slider(ctx, "Vibrance", -1f, 1f, s.vibrance, UiKit::signed100) { update(s.copy(vibrance = it)) })

        // Colour grading
        col.addView(UiKit.section(ctx, "COLOUR GRADING"))
        col.addView(UiKit.note(ctx, "Drag a wheel toward a colour to tint just that part of the picture. The slider under each wheel brightens or darkens it."))
        val wheels = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        wheels.addView(wheelColumn("Shadows", s.sx, s.sy, s.sl,
            { x, y -> update(s.copy(sx = x, sy = y)) }, { update(s.copy(sl = it)) }),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        wheels.addView(wheelColumn("Midtones", s.mx, s.my, s.ml,
            { x, y -> update(s.copy(mx = x, my = y)) }, { update(s.copy(ml = it)) }),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        wheels.addView(wheelColumn("Highlights", s.hx, s.hy, s.hl,
            { x, y -> update(s.copy(hx = x, hy = y)) }, { update(s.copy(hl = it)) }),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(wheels)
        return scroll
    }

    private var rebuildHook: (() -> Unit)? = null
    private fun rebuild() { rebuildHook?.invoke() ?: onClose() }

    private fun wheelColumn(
        label: String, x: Float, y: Float, lum: Float,
        onWheel: (Float, Float) -> Unit, onLum: (Float) -> Unit,
    ): View {
        val c = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(3), 0, dp(3), 0) }
        c.addView(TextView(ctx).apply { text = label; setTextColor(Color.WHITE); textSize = 12f; gravity = Gravity.CENTER })
        val w = WbWheel(ctx).apply {
            heightRatio = 0.95f
            x01 = x; y01 = y
            onChange = { a, b -> onWheel(a, b) }
        }
        c.addView(w, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        c.addView(UiKit.slider(ctx, "Lum", -1f, 1f, lum, UiKit::signed100) { onLum(it) })
        return c
    }

    private fun askName() {
        val input = EditText(ctx).apply { hint = "Preset name"; setSingleLine(true) }
        AlertDialog.Builder(ctx)
            .setTitle("Save preset")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim().ifBlank { "My look" }
                onSavePreset(name, s)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
