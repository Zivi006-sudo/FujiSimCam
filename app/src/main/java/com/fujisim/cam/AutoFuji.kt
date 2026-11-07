package com.fujisim.cam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Auto Fuji: reads the live scene a few times a second and builds its own Fuji-style grade.
 *
 * The taste comes from the user's reference photos (Venice, Budapest, Tokyo, NYC iPhone-vs-Fuji pairs):
 *  - protect the light: expose for the highlights and let shadows sink to a warm near-black
 *  - highlights ease into warm ivory instead of clipping to blue-white
 *  - colour lives in the mid-tones and in one accent hue; everything else stays calm
 *  - skies lean teal rather than baby blue, greens go olive
 *  - a soft cream glow on sunlit surfaces, golden only when the light itself is golden
 * Film sims are blended (e.g. 60% Classic Chrome + 30% Nostalgic Neg) rather than switched.
 */
object AutoFuji {
    const val ID = "auto_fuji"
    val recipe = Recipe(id = ID, name = "Auto Fuji", film = FilmSim.CLASSIC_CHROME, builtIn = true)

    private val SIMS = linkedMapOf(
        "classic_chrome" to "Classic Chrome", "classic_neg" to "Classic Neg", "nostalgic_neg" to "Nostalgic Neg",
        "provia" to "Provia", "velvia" to "Velvia", "astia" to "Astia", "pro_neg_hi" to "Pro Neg Hi",
        "eterna" to "Eterna", "reala_ace" to "Reala Ace",
    )

    /** What the camera sees right now. */
    data class Scene(
        val p05: Float, val p50: Float, val p98: Float,
        val clip: Float, val dark: Float, val bright: Float,
        val warm: Float, val sky: Float, val green: Float, val skin: Float,
        val accentHue: Float, val accent: Float, val motion: Float,
    )

    /** Smoothed "how much is the scene like X" scores, 0..1. */
    private class Mood {
        var night = 0f; var sun = 0f; var golden = 0f; var soft = 0f; var indoor = 0f
        var landscape = 0f; var people = 0f; var lightShadow = 0f
        var accentHue = 0.07f; var accent = 0f
        var init = false
    }

    private val mood = Mood()
    private var prevLuma: IntArray? = null
    var motion = 0f; private set
    var locked = false
    var ev = 0f; private set
    private var lastEvStep = 0L
    private var lutRef: String? = null
    private var lutKey = ""
    private val recentRefs = ArrayDeque<String>()
    var look = Look(); private set
    var label = "Auto Fuji"; private set
    var detail = ""; private set

    fun reset() {
        mood.init = false; prevLuma = null; ev = 0f; locked = false; lutKey = ""; lutRef = null
    }

    // ------------------------------------------------------------------ Scene analysis

    fun analyse(bmp: Bitmap): Scene {
        val w = bmp.width; val h = bmp.height; val n = w * h
        val px = IntArray(n)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val hist = IntArray(256)
        val luma = IntArray(n)
        val hueW = FloatArray(24)
        val hsv = FloatArray(3)
        var clip = 0; var dark = 0; var bright = 0
        var warmSum = 0f; var warmN = 0
        var sky = 0; var skyN = 0; var green = 0; var skin = 0
        for (i in 0 until n) {
            val c = px[i]
            val r = (c shr 16 and 255); val g = (c shr 8 and 255); val b = (c and 255)
            val l = ((54 * r + 183 * g + 19 * b) shr 8).coerceIn(0, 255)
            luma[i] = l
            hist[l]++
            if (max(r, max(g, b)) >= 250) clip++
            if (l < 31) dark++
            if (l > 178) bright++
            if (l in 64..204) { warmSum += (r - b).toFloat() / (r + b + 1); warmN++ }
            Color.RGBToHSV(r, g, b, hsv)
            val hu = hsv[0]; val s = hsv[1]; val v = hsv[2]
            if (i / w < h * 0.45f) {
                skyN++
                if (hu in 185f..240f && s in 0.1f..0.8f && v > 0.45f) sky++
            }
            if (hu in 65f..165f && s > 0.2f && v > 0.15f) green++
            if (hu in 8f..40f && s in 0.18f..0.6f && v > 0.35f) skin++
            if (s > 0.3f && v > 0.2f) hueW[((hu / 15f).toInt()).coerceIn(0, 23)] += s * s * v
        }
        fun pct(p: Float): Float {
            val target = (n * p).toInt(); var acc = 0
            for (k in 0..255) { acc += hist[k]; if (acc > target) return k / 255f }
            return 1f
        }
        // Accent: the strongest saturated hue (smoothed over neighbouring bins)
        var best = 0; var bestW = 0f; var total = 0f
        for (k in 0 until 24) {
            val sw = hueW[k] + 0.5f * (hueW[(k + 23) % 24] + hueW[(k + 1) % 24])
            total += hueW[k]
            if (sw > bestW) { bestW = sw; best = k }
        }
        // Motion: mean luminance change since the last look at the scene
        val prev = prevLuma
        var mo = 0f
        if (prev != null && prev.size == n) {
            var d = 0L
            for (i in 0 until n) d += abs(luma[i] - prev[i])
            mo = d.toFloat() / n / 255f
        }
        prevLuma = luma
        motion = if (motion == 0f) mo else motion * 0.6f + mo * 0.4f
        return Scene(
            p05 = pct(0.05f), p50 = pct(0.5f), p98 = pct(0.98f),
            clip = clip.toFloat() / n, dark = dark.toFloat() / n, bright = bright.toFloat() / n,
            warm = if (warmN > 0) warmSum / warmN else 0f,
            sky = if (skyN > 0) sky.toFloat() / skyN else 0f,
            green = green.toFloat() / n, skin = skin.toFloat() / n,
            accentHue = (best + 0.5f) / 24f, accent = if (total > 0f) bestW / n else 0f,
            motion = mo,
        )
    }

    private fun sm(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    // ------------------------------------------------------------------ Decide the grade

    /**
     * Feed one scene reading. Returns true when the look, LUT or exposure changed enough to redraw.
     * [iso] is the live sensor ISO, [allowEv] lets Auto Fuji set exposure for the highlights.
     */
    fun update(ctx: Context, sc: Scene, iso: Int, allowEv: Boolean, evFrozen: Boolean, now: Long): Boolean {
        if (locked) return false
        val range = sc.p98 - sc.p05
        val night = max(sm(900f, 3200f, iso.toFloat()), sm(0.16f, 0.06f, sc.p50) * sm(300f, 1200f, iso.toFloat()))
        val indoor = sm(350f, 1100f, iso.toFloat()) * (1f - night) * (1f - sm(0.05f, 0.2f, sc.sky))
        val sun = sm(0.5f, 0.82f, range) * sm(0.6f, 0.9f, sc.p98) * (1f - night) * (1f - indoor)
        val golden = sm(0.04f, 0.14f, sc.warm) * sm(0.45f, 0.8f, sc.p98) * (1f - night) * (1f - 0.7f * indoor)
        val soft = (1f - sm(0.42f, 0.7f, range)) * (1f - night) * (1f - indoor)
        val landscape = sm(0.12f, 0.4f, sc.sky + sc.green)
        val people = sm(0.06f, 0.22f, sc.skin) * (1f - 0.6f * golden)
        val lightShadow = sm(0.28f, 0.5f, sc.dark) * sm(0.03f, 0.1f, sc.bright) * (1f - night)

        val a = if (!mood.init) 1f else 0.3f
        fun mix(o: Float, v: Float) = o + (v - o) * a
        mood.night = mix(mood.night, night); mood.indoor = mix(mood.indoor, indoor)
        mood.sun = mix(mood.sun, sun); mood.golden = mix(mood.golden, golden); mood.soft = mix(mood.soft, soft)
        mood.landscape = mix(mood.landscape, landscape); mood.people = mix(mood.people, people)
        mood.lightShadow = mix(mood.lightShadow, lightShadow)
        if (sc.accent > 0.02f) {
            val d = ((sc.accentHue - mood.accentHue + 1.5f) % 1f) - 0.5f      // shortest way round the hue circle
            mood.accentHue = (mood.accentHue + d * (if (mood.init) 0.25f else 1f) + 1f) % 1f
        }
        mood.accent = mix(mood.accent, sm(0.02f, 0.12f, sc.accent))
        mood.init = true

        val m = mood
        // --- Film sim blend
        val w = linkedMapOf(
            "classic_chrome" to 0.5f + 0.6f * m.sun + 0.4f * m.lightShadow + 0.2f * m.soft,
            "classic_neg" to 0.15f + 0.5f * m.soft + 0.3f * m.indoor + 0.2f * m.night,
            "nostalgic_neg" to 0.1f + 0.8f * m.golden + 0.15f * m.soft,
            "provia" to 0.25f * m.landscape,
            "velvia" to 0.25f * m.landscape * m.sun,
            "astia" to 0.5f * m.people,
            "pro_neg_hi" to 0.35f * m.people,
            "eterna" to 0.7f * m.night + 0.2f * m.indoor,
            "reala_ace" to 0.15f * m.landscape + 0.1f * m.people,
        )
        val top = w.entries.sortedByDescending { it.value }.take(3)
        val sum = top.sumOf { it.value.toDouble() }.toFloat()
        // Quantise to 5% so the LUT is only rebuilt when the blend really changes
        val parts = top.map { it.key to (it.value / sum * 20f).roundToInt() / 20f }.filter { it.second > 0f }
        val key = parts.joinToString(",") { "${it.first}:${it.second}" }
        var changed = false
        if (key != lutKey) {
            val luts = parts.mapNotNull { (name, wt) -> LutLibrary.get(ctx, "asset:$name")?.let { it to wt } }
            val blended = LutLibrary.blend(luts)
            if (blended != null) {
                val ref = "auto:$key"
                LutLibrary.put(ref, blended)
                recentRefs.addLast(ref)
                while (recentRefs.size > 4) LutLibrary.remove(recentRefs.removeFirst())
                lutRef = ref
                lutKey = key
                changed = true
            }
        }

        // --- Tone and colour, following the reference photos
        val hue = FloatArray(6)
        hue[2] = -6f * (1f - 0.5f * m.people)          // greens towards olive
        hue[4] = -8f                                    // blues towards teal
        val bandSat = floatArrayOf(1.04f, 1f, 0.86f, 1f, 0.9f - 0.1f * m.sun, 0.92f)
        val newLook = Look(
            sat = 1.05f + 0.1f * m.sun - 0.1f * m.people,
            highlight = (-0.5f - 1.0f * m.sun - 0.5f * m.golden + 0.5f * m.soft).coerceIn(-2f, 1f),
            shadow = (1.0f + 0.8f * m.sun + 1.0f * m.lightShadow + 0.4f * m.soft - 0.8f * m.night - 0.6f * m.people).coerceIn(-0.5f, 3f),
            hue = hue,
            bandSat = bandSat,
            shadowTint = floatArrayOf(0.006f, 0.001f, -0.012f).map { it * (1f + m.lightShadow) }.toFloatArray(),
            chrome = 0.5f + 0.5f * m.sun,
            chromeBlue = 0.25f + 0.5f * m.landscape,
            grain = 0.018f - 0.006f * m.night,
            grainSize = 1.0f,
            sharp = 0.15f,
            clarity = 0.15f * m.sun,
            warmth = (0.12f + 0.35f * m.golden - 0.12f * m.indoor).coerceAtLeast(0f),
            lut = lutRef,
            sunGlow = 0.35f * m.golden,
            rolloff = (0.6f + 0.3f * m.sun + 0.2f * m.golden).coerceAtMost(1f),
            sep = (0.45f + 0.25f * m.sun + 0.2f * m.lightShadow - 0.2f * m.people).coerceIn(0f, 0.9f),
            accentHue = m.accentHue,
            lightGlow = 0.15f + 0.3f * m.sun * (1f - m.golden) + 0.15f * m.lightShadow,
        )
        if (lookDiff(look, newLook) > 0.02f) { look = newLook; changed = true }

        // --- Exposure for the highlights: step a third of a stop at a time, never faster than every 1.2 s
        if (allowEv && !evFrozen && now - lastEvStep > 1200) {
            val floor = when {
                m.night > 0.5f -> 0f
                m.lightShadow > 0.5f -> -1.33f
                m.sun > 0.4f || m.golden > 0.4f -> -0.67f
                else -> -0.33f
            }
            val target = when {
                sc.clip > 0.012f && ev > floor -> ev - 0.33f
                (sc.p98 < 0.82f && ev < 0f) || ev < floor -> ev + 0.33f
                else -> ev
            }.coerceIn(floor, 0f)
            if (abs(target - ev) > 0.1f) { ev = (target * 3f).roundToInt() / 3f; lastEvStep = now; changed = true }
        } else if (!allowEv && !evFrozen && ev != 0f) { ev = 0f; changed = true }

        // --- Label
        val scenes = listOf(
            "night" to m.night, "indoors" to m.indoor, "golden light" to m.golden, "hard sun" to m.sun,
            "light & shadow" to m.lightShadow * 1.2f, "soft light" to m.soft, "portrait" to m.people * 1.1f,
            "landscape" to m.landscape * 0.9f,
        )
        val sceneName = scenes.maxByOrNull { it.second }?.first ?: ""
        label = "AUTO · " + parts.take(2).joinToString(" + ") { "${SIMS[it.first]} ${(it.second * 100).roundToInt()}%" } + " · $sceneName"
        detail = parts.joinToString(", ") { "${SIMS[it.first]} ${(it.second * 100).roundToInt()}%" } +
            " · $sceneName" + (if (ev != 0f) " · EV ${"%.1f".format(ev)}" else "")
        return changed
    }

    private fun lookDiff(a: Look, b: Look): Float =
        abs(a.highlight - b.highlight) / 4f + abs(a.shadow - b.shadow) / 4f + abs(a.sat - b.sat) +
            abs(a.warmth - b.warmth) + abs(a.sunGlow - b.sunGlow) + abs(a.rolloff - b.rolloff) +
            abs(a.sep - b.sep) + abs(a.lightGlow - b.lightGlow) + abs(a.chrome - b.chrome) / 2f +
            min(abs(a.accentHue - b.accentHue), 1f - abs(a.accentHue - b.accentHue)) * 2f +
            (if (a.lut != b.lut) 1f else 0f)
}
