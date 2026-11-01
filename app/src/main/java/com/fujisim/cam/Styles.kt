package com.fujisim.cam

import android.content.Context
import org.json.JSONObject

/** Base character of an iPhone-style Photographic Style (at full Palette). */
class StyleBase(
    val wb: FloatArray = floatArrayOf(1f, 1f, 1f),
    val sat: Float = 1f,
    val contrast: Float = 0f,
    val lift: Float = 0f,
    val highlight: Float = 0f,
    val shadow: Float = 0f,
    val shadowTint: FloatArray = FloatArray(3),
    val highTint: FloatArray = FloatArray(3),
    val warmth: Float = 0f,
    val mono: Boolean = false,
)

enum class PhotoStyle(val label: String, val b: StyleBase) {
    STANDARD("Standard", StyleBase()),
    AMBER("Amber", StyleBase(wb = floatArrayOf(1.06f, 1f, 0.88f), sat = 1.05f,
        highTint = floatArrayOf(0.02f, 0.01f, -0.02f), warmth = 0.3f)),
    GOLD("Gold", StyleBase(wb = floatArrayOf(1.08f, 1.02f, 0.84f), sat = 1.08f, warmth = 0.5f)),
    ROSE_GOLD("Rose Gold", StyleBase(wb = floatArrayOf(1.08f, 0.97f, 0.93f), sat = 1.04f,
        highTint = floatArrayOf(0.015f, 0f, 0.006f))),
    NEUTRAL("Neutral", StyleBase(wb = floatArrayOf(0.97f, 1f, 1.04f), sat = 0.92f)),
    COOL_ROSE("Cool Rose", StyleBase(wb = floatArrayOf(1f, 0.96f, 1.06f), sat = 0.96f,
        highTint = floatArrayOf(0.01f, 0f, 0.012f))),
    VIBRANT("Vibrant", StyleBase(sat = 1.32f, contrast = 0.15f)),
    NATURAL("Natural", StyleBase(sat = 0.95f, contrast = -0.1f, lift = 0.01f)),
    LUMINOUS("Luminous", StyleBase(sat = 1.06f, contrast = -0.05f, lift = 0.03f, shadow = -1.5f, highlight = 0.5f)),
    DRAMATIC("Dramatic", StyleBase(sat = 0.9f, contrast = 0.35f, shadow = 2f, highlight = -0.5f)),
    QUIET("Quiet", StyleBase(sat = 0.75f, contrast = -0.15f, lift = 0.05f)),
    COZY("Cozy", StyleBase(wb = floatArrayOf(1.07f, 1f, 0.86f), contrast = 0.2f, shadow = 1.5f, warmth = 0.2f)),
    ETHEREAL("Ethereal", StyleBase(sat = 0.8f, contrast = -0.2f, lift = 0.06f,
        highTint = floatArrayOf(0.01f, 0f, 0.015f))),
    MUTED_BW("Muted B&W", StyleBase(contrast = -0.1f, lift = 0.03f, mono = true)),
    STARK_BW("Stark B&W", StyleBase(contrast = 0.5f, shadow = 1f, mono = true)),
}

/** User adjustments for a style: Tone/Colour pad, Palette, Grain, Detail. */
data class StyleSettings(
    val tone: Float = 0f,     // -1 darker .. +1 brighter
    val color: Float = 0f,    // -1 muted .. +1 rich
    val palette: Float = 1f,  // 0 .. 1 style intensity
    val grain: Float = 0f,    // 0 .. 1
    val detail: Float = 0f,   // -1 smooth .. +1 crisp
) {
    fun toJson() = JSONObject().apply {
        put("tone", tone.toDouble()); put("color", color.toDouble()); put("palette", palette.toDouble())
        put("grain", grain.toDouble()); put("detail", detail.toDouble())
    }

    companion object {
        fun fromJson(o: JSONObject) = StyleSettings(
            tone = o.optDouble("tone", 0.0).toFloat(),
            color = o.optDouble("color", 0.0).toFloat(),
            palette = o.optDouble("palette", 1.0).toFloat(),
            grain = o.optDouble("grain", 0.0).toFloat(),
            detail = o.optDouble("detail", 0.0).toFloat(),
        )
    }
}

class AppPrefs(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)

    fun styleSettings(style: PhotoStyle): StyleSettings =
        prefs.getString("style_${style.name}", null)?.let {
            runCatching { StyleSettings.fromJson(JSONObject(it)) }.getOrNull()
        } ?: StyleSettings()

    fun saveStyleSettings(style: PhotoStyle, s: StyleSettings) {
        prefs.edit().putString("style_${style.name}", s.toJson().toString()).apply()
    }

    var selectedStyle: PhotoStyle
        get() = runCatching { PhotoStyle.valueOf(prefs.getString("selectedStyle", "STANDARD")!!) }
            .getOrDefault(PhotoStyle.STANDARD)
        set(v) { prefs.edit().putString("selectedStyle", v.name).apply() }

    var stylesMode: Boolean
        get() = prefs.getBoolean("stylesMode", false)
        set(v) { prefs.edit().putBoolean("stylesMode", v).apply() }

    var gridMode: Int
        get() = prefs.getInt("gridMode", 0)
        set(v) { prefs.edit().putInt("gridMode", v).apply() }
}
