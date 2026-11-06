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

/** NORMAL mode settings: base + tone sliders + 3-way colour grading. Ranges are -1..1 unless noted. */
data class NormalSettings(
    val naturalBase: Boolean = true,
    val exposure: Float = 0f,     // EV, -2..2
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val saturation: Float = 0f,
    val vibrance: Float = 0f,
    val sx: Float = 0f, val sy: Float = 0f, val sl: Float = 0f,
    val mx: Float = 0f, val my: Float = 0f, val ml: Float = 0f,
    val hx: Float = 0f, val hy: Float = 0f, val hl: Float = 0f,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("naturalBase", naturalBase)
        listOf("exposure" to exposure, "contrast" to contrast, "highlights" to highlights, "shadows" to shadows,
            "saturation" to saturation, "vibrance" to vibrance, "sx" to sx, "sy" to sy, "sl" to sl,
            "mx" to mx, "my" to my, "ml" to ml, "hx" to hx, "hy" to hy, "hl" to hl
        ).forEach { (k, v) -> put(k, v.toDouble()) }
    }

    fun summary(): String {
        fun s(v: Float) = (if (v > 0) "+" else "") + (v * 100).toInt()
        val parts = mutableListOf(if (naturalBase) "Natural base" else "Phone base")
        if (exposure != 0f) parts += String.format("EV %+.1f", exposure)
        if (contrast != 0f) parts += "Contrast ${s(contrast)}"
        if (highlights != 0f) parts += "Highlights ${s(highlights)}"
        if (shadows != 0f) parts += "Shadows ${s(shadows)}"
        if (saturation != 0f) parts += "Saturation ${s(saturation)}"
        if (vibrance != 0f) parts += "Vibrance ${s(vibrance)}"
        if (sx != 0f || sy != 0f || sl != 0f || mx != 0f || my != 0f || ml != 0f || hx != 0f || hy != 0f || hl != 0f) parts += "Colour graded"
        return parts.joinToString(" · ")
    }

    companion object {
        fun fromJson(o: JSONObject): NormalSettings {
            fun f(k: String) = o.optDouble(k, 0.0).toFloat()
            return NormalSettings(o.optBoolean("naturalBase", true), f("exposure"), f("contrast"), f("highlights"),
                f("shadows"), f("saturation"), f("vibrance"), f("sx"), f("sy"), f("sl"), f("mx"), f("my"), f("ml"),
                f("hx"), f("hy"), f("hl"))
        }
    }
}

data class NormalPreset(val name: String, val settings: NormalSettings)

/** White balance: camera Auto (default) or the recipe's fixed WB, plus a colour-wheel tint. */
data class WbSettings(val useRecipe: Boolean = false, val wheelX: Float = 0f, val wheelY: Float = 0f)

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

    var wb: WbSettings
        get() = WbSettings(
            useRecipe = prefs.getBoolean("wb_recipe", false),
            wheelX = prefs.getFloat("wb_x", 0f),
            wheelY = prefs.getFloat("wb_y", 0f),
        )
        set(v) {
            prefs.edit().putBoolean("wb_recipe", v.useRecipe).putFloat("wb_x", v.wheelX).putFloat("wb_y", v.wheelY).apply()
        }

    var normal: NormalSettings
        get() = prefs.getString("normal", null)?.let { runCatching { NormalSettings.fromJson(JSONObject(it)) }.getOrNull() }
            ?: NormalSettings()
        set(v) { prefs.edit().putString("normal", v.toJson().toString()).apply() }

    var normalPresets: List<NormalPreset>
        get() = runCatching {
            val arr = org.json.JSONArray(prefs.getString("normalPresets", "[]"))
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                NormalPreset(o.getString("name"), NormalSettings.fromJson(o.getJSONObject("s")))
            }
        }.getOrDefault(emptyList())
        set(v) {
            val arr = org.json.JSONArray()
            v.forEach { arr.put(JSONObject().put("name", it.name).put("s", it.settings.toJson())) }
            prefs.edit().putString("normalPresets", arr.toString()).apply()
        }

    /** Look family: 0 = Fuji, 1 = Styles, 2 = Normal. */
    var family: Int
        get() = prefs.getInt("family", if (prefs.getBoolean("stylesMode", false)) 1 else 0)
        set(v) { prefs.edit().putInt("family", v).apply() }

    /** Lens colour calibration: physical camera id -> measured average RGB of a white/grey target. */
    var lensCalib: Map<String, FloatArray>
        get() = runCatching {
            val o = JSONObject(prefs.getString("lensCalib", "{}")!!)
            o.keys().asSequence().associateWith { k ->
                val a = o.getJSONArray(k); floatArrayOf(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat())
            }
        }.getOrDefault(emptyMap())
        set(v) {
            val o = JSONObject()
            v.forEach { (k, rgb) -> o.put(k, org.json.JSONArray().put(rgb[0].toDouble()).put(rgb[1].toDouble()).put(rgb[2].toDouble())) }
            prefs.edit().putString("lensCalib", o.toString()).apply()
        }

    var lensCalibRef: String?
        get() = prefs.getString("lensCalibRef", null)
        set(v) { prefs.edit().putString("lensCalibRef", v).apply() }

    var gridMode: Int
        get() = prefs.getInt("gridMode", 0)
        set(v) { prefs.edit().putInt("gridMode", v).apply() }
}
