package com.fujisim.cam

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** ⋯ Settings: rarely-changed options. */
class SettingsPanel(
    private val ctx: Context,
    private val highRes: Boolean,
    private val maxPhotoMp: Int,
    private val onHighRes: (Boolean) -> Unit,
    private val extraRows: List<View>,
    private val onClose: () -> Unit,
) {
    val view: View = build()

    private fun build(): View {
        val scroll = ScrollView(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = UiKit.dp(ctx, 16)
            setPadding(p, UiKit.dp(ctx, 4), p, UiKit.dp(ctx, 28))
        }
        scroll.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(UiKit.header(ctx, "Settings", UiKit.pill(ctx, "Done", "#FFC94A", Color.BLACK) { onClose() }))

        col.addView(UiKit.section(ctx, "PHOTO RESOLUTION"))
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        lateinit var b12: TextView
        lateinit var b50: TextView
        b12 = UiKit.pill(ctx, "12MP", "#333333") { onHighRes(false); UiKit.setPillOn(b12, true); UiKit.setPillOn(b50, false) }
        b50 = UiKit.pill(ctx, if (maxPhotoMp in 1..19) "Max (${maxPhotoMp}MP)" else "50MP", "#333333") {
            onHighRes(true); UiKit.setPillOn(b12, false); UiKit.setPillOn(b50, true)
        }
        UiKit.setPillOn(b12, !highRes); UiKit.setPillOn(b50, highRes)
        row.addView(b12); row.addView(b50)
        col.addView(row)
        col.addView(UiKit.note(ctx,
            if (maxPhotoMp in 1..19) "Xiaomi only gives apps up to ${maxPhotoMp}MP on this camera."
            else "50MP: more detail in good light; 12MP: cleaner in low light and faster."))

        extraRows.forEach { col.addView(it) }
        return scroll
    }
}
