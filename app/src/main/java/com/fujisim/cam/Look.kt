package com.fujisim.cam

/** Everything the colour engine needs for one frame. Built from a Fuji recipe or an iPhone-style Style. */
data class Look(
    val wb: FloatArray = floatArrayOf(1f, 1f, 1f),
    val sat: Float = 1f,
    val contrast: Float = 0f,
    val lift: Float = 0f,
    val highlight: Float = 0f,
    val shadow: Float = 0f,
    val hue: FloatArray = FloatArray(6),
    val bandSat: FloatArray = FloatArray(6) { 1f },
    val bandLum: FloatArray = FloatArray(6),
    val shadowTint: FloatArray = FloatArray(3),
    val highTint: FloatArray = FloatArray(3),
    val mono: Float = 0f,
    val monoW: FloatArray = floatArrayOf(0.299f, 0.587f, 0.114f),
    val monoTone: FloatArray = floatArrayOf(1f, 1f, 1f),
    val chrome: Float = 0f,
    val chromeBlue: Float = 0f,
    val grain: Float = 0f,
    val grainSize: Float = 1.2f,
    val sharp: Float = 0f,
    val clarity: Float = 0f,
    val warmth: Float = 0f,
    val glow: Float = 0f,
    val lut: String? = null,        // film-simulation LUT reference
    val mist: Float = 0f,           // Pro-Mist soft glow 0..1
    val vignette: Float = 0f,       // 0..1
    val halation: Float = 0f,       // 0..1
    val cornerSoft: Float = 0f,     // 0..1
    val denoise: Float = 0.8f,      // light noise reduction before the look, 0..1
) {
    fun withLens(l: LensSettings) = copy(
        mist = l.mist, vignette = l.vignette, halation = l.halation, cornerSoft = l.cornerSoft,
    )

    /** Uniform name -> value (1–3 floats). Size/scale/time are set by the renderer. */
    fun uniforms(mix: Float): List<Pair<String, FloatArray>> = listOf(
        "uMix" to floatArrayOf(mix),
        "uWb" to wb,
        "uSat" to floatArrayOf(sat),
        "uContrast" to floatArrayOf(contrast),
        "uLift" to floatArrayOf(lift),
        "uHighlight" to floatArrayOf(highlight),
        "uShadow" to floatArrayOf(shadow),
        "uHueA" to floatArrayOf(hue[0] / 360f, hue[1] / 360f, hue[2] / 360f),
        "uHueB" to floatArrayOf(hue[3] / 360f, hue[4] / 360f, hue[5] / 360f),
        "uSatA" to floatArrayOf(bandSat[0], bandSat[1], bandSat[2]),
        "uSatB" to floatArrayOf(bandSat[3], bandSat[4], bandSat[5]),
        "uLumA" to floatArrayOf(bandLum[0], bandLum[1], bandLum[2]),
        "uLumB" to floatArrayOf(bandLum[3], bandLum[4], bandLum[5]),
        "uShadowTint" to shadowTint,
        "uHighTint" to highTint,
        "uMono" to floatArrayOf(mono),
        "uMonoW" to monoW,
        "uMonoTone" to monoTone,
        "uChrome" to floatArrayOf(chrome),
        "uChromeBlue" to floatArrayOf(chromeBlue),
        "uGrain" to floatArrayOf(grain),
        "uGrainSize" to floatArrayOf(grainSize),
        "uSharp" to floatArrayOf(sharp),
        "uClarity" to floatArrayOf(clarity),
        "uWarmth" to floatArrayOf(warmth),
        "uGlow" to floatArrayOf(glow),
        "uLutMix" to floatArrayOf(if (lut != null) 1f else 0f),
        "uMist" to floatArrayOf(mist),
        "uVignette" to floatArrayOf(vignette),
        "uHalation" to floatArrayOf(halation),
        "uCornerSoft" to floatArrayOf(cornerSoft),
        "uDenoise" to floatArrayOf(denoise),
    )

    companion object {
        fun fromRecipe(r: Recipe, wbGains: FloatArray): Look {
            val p = r.film.p
            val drComp = when (r.dr) { 200 -> 0.75f; 400 -> 1.5f; else -> 0f }
            return Look(
                wb = wbGains,
                sat = p.sat * (1f + 0.06f * r.color),
                contrast = p.contrast,
                lift = p.lift,
                highlight = r.highlight - drComp,
                shadow = r.shadow,
                hue = p.hue,
                bandSat = p.bandSat,
                bandLum = p.bandLum,
                shadowTint = p.shadowTint,
                highTint = p.highTint,
                mono = if (p.mono) 1f else 0f,
                monoW = p.monoW,
                monoTone = p.monoTone,
                chrome = r.chrome * 0.5f,
                chromeBlue = r.chromeBlue * 0.5f,
                grain = when (r.grain) { 1 -> 0.022f; 2 -> 0.038f; else -> 0f },
                grainSize = if (r.grainLarge) 1.6f else 1.0f,
                sharp = r.sharpness * 0.22f,
                clarity = r.clarity / 5f,
                warmth = r.warmth / 10f,
                glow = r.glow / 10f,
                lut = r.lut ?: r.film.lut?.let { "asset:$it" },
            )
        }

        fun fromStyle(style: PhotoStyle, s: StyleSettings): Look {
            val b = style.b
            val tone = s.tone.coerceIn(-1f, 1f)
            val color = s.color.coerceIn(-1f, 1f)
            // Palette = how much of the style's colour goes into the scene.
            // Colour axis pushes the scene from grey (left) towards the style's own colour (right).
            val k = (s.palette.coerceIn(0f, 1f) * (1f + 0.8f * color)).coerceIn(0f, 2f)
            fun lerp(a: Float, z: Float) = a + (z - a) * k
            // A gentle extra cast in the style's accent colour, so every style recolours the scene.
            val acc = android.graphics.Color.parseColor(style.accentHex)
            val ar = android.graphics.Color.red(acc) / 255f
            val ag = android.graphics.Color.green(acc) / 255f
            val ab = android.graphics.Color.blue(acc) / 255f
            val am = (ar + ag + ab) / 3f
            val cast = if (b.mono || style == PhotoStyle.STANDARD) 0f else 0.035f * k
            return Look(
                wb = FloatArray(3) { lerp(1f, b.wb[it]) },
                sat = lerp(1f, b.sat) * (1f + 0.45f * color),
                contrast = lerp(0f, b.contrast) - 0.12f * tone,
                lift = lerp(0f, b.lift) + 0.03f * tone.coerceAtLeast(0f),
                highlight = lerp(0f, b.highlight) + 0.8f * tone,
                shadow = lerp(0f, b.shadow) - 2.5f * tone,
                shadowTint = FloatArray(3) { b.shadowTint[it] * k },
                highTint = floatArrayOf(
                    b.highTint[0] * k + (ar - am) * cast,
                    b.highTint[1] * k + (ag - am) * cast,
                    b.highTint[2] * k + (ab - am) * cast,
                ),
                mono = if (b.mono) s.palette.coerceIn(0f, 1f) else 0f,
                warmth = b.warmth * k,
                grain = s.grain.coerceIn(0f, 1f) * 0.05f,
                grainSize = 1.2f,
                sharp = s.detail.coerceIn(-1f, 1f) * 0.9f,
                clarity = s.detail.coerceIn(-1f, 1f) * 0.35f,
            )
        }
    }
}
