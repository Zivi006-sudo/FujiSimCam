package com.fujisim.cam

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import kotlin.math.roundToInt

/** Bottom sheet for editing a recipe. Every change is previewed live through onChange. */
class EditorPanel(
    private val ctx: Context,
    initial: Recipe,
    private val onChange: (Recipe) -> Unit,
    private val onSave: (Recipe, Boolean) -> Unit,
    private val onDelete: (Recipe) -> Unit,
    private val onClose: () -> Unit,
) {
    var recipe: Recipe = initial
        private set

    val view: View = build()

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).roundToInt()

    private fun update(r: Recipe) {
        recipe = r
        onChange(r)
    }

    private fun build(): View {
        val scroll = ScrollView(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(32))
        }
        scroll.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Action buttons
        val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val saveLabel = if (recipe.builtIn) "Save copy" else "Save"
        actions.addView(button(saveLabel, "#FFC94A", Color.BLACK) { onSave(recipe, recipe.builtIn) })
        if (!recipe.builtIn) actions.addView(button("Save as new", "#333333", Color.WHITE) { onSave(recipe, true) })
        if (!recipe.builtIn) actions.addView(button("Delete", "#5A1E1E", Color.WHITE) { onDelete(recipe) })
        actions.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        actions.addView(button("Close", "#333333", Color.WHITE) { onClose() })
        col.addView(actions)

        if (recipe.credit.isNotBlank()) {
            col.addView(TextView(ctx).apply {
                text = "Recipe by ${recipe.credit}"
                setTextColor(Color.parseColor("#AAAAAA"))
                textSize = 12f
                setPadding(0, dp(8), 0, 0)
            })
        }

        col.addView(EditText(ctx).apply {
            setText(recipe.name)
            hint = "Recipe name"
            isSingleLine = true
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    recipe = recipe.copy(name = s?.toString().orEmpty().ifBlank { "Untitled" })
                }
            })
        })

        val sims = FilmSim.values()
        col.addView(spinner("Film simulation", sims.map { it.label }, recipe.film.ordinal) {
            update(recipe.copy(film = sims[it]))
        })

        val levels = listOf("Off", "Weak", "Strong")
        col.addView(spinner("Grain effect", levels, recipe.grain) { update(recipe.copy(grain = it)) })
        col.addView(spinner("Grain size", listOf("Small", "Large"), if (recipe.grainLarge) 1 else 0) {
            update(recipe.copy(grainLarge = it == 1))
        })
        col.addView(spinner("Color Chrome Effect", levels, recipe.chrome) { update(recipe.copy(chrome = it)) })
        col.addView(spinner("Color Chrome FX Blue", levels, recipe.chromeBlue) { update(recipe.copy(chromeBlue = it)) })

        val wbs = WbMode.values()
        col.addView(spinner("White balance", wbs.map { it.label }, recipe.wb.ordinal) { update(recipe.copy(wb = wbs[it])) })
        col.addView(slider("Kelvin (when WB = Kelvin)", 2500f, 10000f, 100f, recipe.kelvin.toFloat(),
            { "${it.roundToInt()}K" }) { update(recipe.copy(kelvin = it.roundToInt())) })
        col.addView(slider("WB shift Red", -9f, 9f, 1f, recipe.wbR.toFloat(), { Recipe.s(it.roundToInt()) }) {
            update(recipe.copy(wbR = it.roundToInt()))
        })
        col.addView(slider("WB shift Blue", -9f, 9f, 1f, recipe.wbB.toFloat(), { Recipe.s(it.roundToInt()) }) {
            update(recipe.copy(wbB = it.roundToInt()))
        })

        val drs = listOf(100, 200, 400)
        col.addView(spinner("Dynamic range", drs.map { "DR$it" }, drs.indexOf(recipe.dr).coerceAtLeast(0)) {
            update(recipe.copy(dr = drs[it]))
        })
        col.addView(slider("Highlight", -2f, 4f, 0.5f, recipe.highlight, { Recipe.s(it) }) { update(recipe.copy(highlight = it)) })
        col.addView(slider("Shadow", -2f, 4f, 0.5f, recipe.shadow, { Recipe.s(it) }) { update(recipe.copy(shadow = it)) })
        col.addView(slider("Color", -4f, 4f, 1f, recipe.color.toFloat(), { Recipe.s(it.roundToInt()) }) {
            update(recipe.copy(color = it.roundToInt()))
        })
        col.addView(slider("Sharpness", -4f, 4f, 1f, recipe.sharpness.toFloat(), { Recipe.s(it.roundToInt()) }) {
            update(recipe.copy(sharpness = it.roundToInt()))
        })
        col.addView(slider("Clarity", -5f, 5f, 1f, recipe.clarity.toFloat(), { Recipe.s(it.roundToInt()) }) {
            update(recipe.copy(clarity = it.roundToInt()))
        })
        col.addView(slider("Exposure compensation", -2f, 2f, 1f / 3f, recipe.ev, { Recipe.evText(it) }) {
            update(recipe.copy(ev = it))
        })

        col.addView(TextView(ctx).apply {
            text = "Noise reduction isn't simulated (phone processing handles it). Tip: long-press a recipe chip to edit it."
            setTextColor(Color.parseColor("#888888"))
            textSize = 11f
            setPadding(0, dp(12), 0, 0)
        })
        return scroll
    }

    private fun button(label: String, bg: String, fg: Int, onClick: () -> Unit): View =
        TextView(ctx).apply {
            text = label
            setTextColor(fg)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor(bg))
            }
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
        }

    private fun spinner(label: String, items: List<String>, selected: Int, onSel: (Int) -> Unit): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(TextView(ctx).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 14f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sp = Spinner(ctx)
        sp.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, items)
        sp.setSelection(selected.coerceIn(0, items.size - 1), false)
        var first = true
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (first) { first = false; if (pos == selected) return }
                onSel(pos)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        row.addView(sp)
        return row
    }

    private fun slider(
        label: String, lo: Float, hi: Float, step: Float, value: Float,
        fmt: (Float) -> String, onValue: (Float) -> Unit,
    ): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(2))
        }
        val tv = TextView(ctx).apply { setTextColor(Color.WHITE); textSize = 14f }
        val steps = ((hi - lo) / step).roundToInt()
        fun valueAt(progress: Int): Float {
            val v = lo + progress * step
            return (v * 100f).roundToInt() / 100f
        }
        val sb = SeekBar(ctx)
        sb.max = steps
        sb.progress = ((value - lo) / step).roundToInt().coerceIn(0, steps)
        tv.text = "$label: ${fmt(valueAt(sb.progress))}"
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = valueAt(progress)
                tv.text = "$label: ${fmt(v)}"
                if (fromUser) onValue(v)
            }
            override fun onStartTrackingTouch(seek: SeekBar?) {}
            override fun onStopTrackingTouch(seek: SeekBar?) {}
        })
        box.addView(tv)
        box.addView(sb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return box
    }
}
