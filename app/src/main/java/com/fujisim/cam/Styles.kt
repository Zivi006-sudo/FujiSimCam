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

enum class StyleCategory { UNDERTONE, MOOD }

enum class PhotoStyle(val label: String, val category: StyleCategory, val accentHex: String, val b: StyleBase) {
    STANDARD("Standard", StyleCategory.UNDERTONE, "#E6E6E6", StyleBase()),
    AMBER("Amber", StyleCategory.UNDERTONE, "#E8A24E", StyleBase(wb = floatArrayOf(1.07f, 1f, 0.86f), sat = 1.05f,
        highTint = floatArrayOf(0.022f, 0.011f, -0.022f), warmth = 0.3f)),
    GOLD("Gold", StyleCategory.UNDERTONE, "#E9C25A", StyleBase(wb = floatArrayOf(1.09f, 1.03f, 0.82f), sat = 1.08f, warmth = 0.5f)),
    ROSE_GOLD("Rose Gold", StyleCategory.UNDERTONE, "#E8A08C", StyleBase(wb = floatArrayOf(1.09f, 0.96f, 0.93f), sat = 1.04f,
        highTint = floatArrayOf(0.018f, 0f, 0.008f))),
    NEUTRAL("Neutral", StyleCategory.UNDERTONE, "#C9C7BE", StyleBase(wb = floatArrayOf(0.97f, 1f, 1.04f), sat = 0.92f)),
    COOL_ROSE("Cool Rose", StyleCategory.UNDERTONE, "#C8A2DA", StyleBase(wb = floatArrayOf(1f, 0.95f, 1.07f), sat = 0.96f,
        highTint = floatArrayOf(0.012f, 0f, 0.014f))),
    VIBRANT("Vibrant", StyleCategory.MOOD, "#F05A7E", StyleBase(sat = 1.32f, contrast = 0.15f)),
    NATURAL("Natural", StyleCategory.MOOD, "#8DBF7A", StyleBase(sat = 0.95f, contrast = -0.1f, lift = 0.01f)),
    LUMINOUS("Luminous", StyleCategory.MOOD, "#F2DE8C", StyleBase(sat = 1.06f, contrast = -0.05f, lift = 0.03f, shadow = -1.5f, highlight = 0.5f)),
    DRAMATIC("Dramatic", StyleCategory.MOOD, "#4FA8E8", StyleBase(wb = floatArrayOf(0.98f, 1f, 1.03f), sat = 0.9f, contrast = 0.35f, shadow = 2f, highlight = -0.5f,
        shadowTint = floatArrayOf(-0.01f, 0.002f, 0.012f))),
    QUIET("Quiet", StyleCategory.MOOD, "#A9B4BF", StyleBase(sat = 0.75f, contrast = -0.15f, lift = 0.05f)),
    COZY("Cozy", StyleCategory.MOOD, "#D2804E", StyleBase(wb = floatArrayOf(1.07f, 1f, 0.86f), contrast = 0.2f, shadow = 1.5f, warmth = 0.2f)),
    ETHEREAL("Ethereal", StyleCategory.MOOD, "#D2B2EC", StyleBase(sat = 0.8f, contrast = -0.2f, lift = 0.06f,
        highTint = floatArrayOf(0.012f, 0f, 0.016f))),
    MUTED_BW("Muted B&W", StyleCategory.MOOD, "#9C9C9C", StyleBase(contrast = -0.1f, lift = 0.03f, mono = true)),
    STARK_BW("Stark B&W", StyleCategory.MOOD, "#FFFFFF", StyleBase(contrast = 0.5f, shadow = 1f, mono = true)),
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

/** Global lens character, applied on top of any recipe or style. */
data class LensSettings(
    val mist: Float = 0f,
    val vignette: Float = 0f,
    val halation: Float = 0f,
    val cornerSoft: Float = 0f,
    val naturalDetail: Boolean = true,
)

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

    var highRes: Boolean
        get() = prefs.getBoolean("highRes", false)
        set(v) { prefs.edit().putBoolean("highRes", v).apply() }

    var lens: LensSettings
        get() = LensSettings(
            mist = prefs.getFloat("lens_mist", 0f),
            vignette = prefs.getFloat("lens_vignette", 0f),
            halation = prefs.getFloat("lens_halation", 0f),
            cornerSoft = prefs.getFloat("lens_corner", 0f),
            naturalDetail = prefs.getBoolean("lens_natural", true),
        )
        set(v) {
            prefs.edit().putFloat("lens_mist", v.mist).putFloat("lens_vignette", v.vignette)
                .putFloat("lens_halation", v.halation).putFloat("lens_corner", v.cornerSoft)
                .putBoolean("lens_natural", v.naturalDetail).apply()
        }

    var gridMode: Int
        get() = prefs.getInt("gridMode", 0)
        set(v) { prefs.edit().putInt("gridMode", v).apply() }
}
