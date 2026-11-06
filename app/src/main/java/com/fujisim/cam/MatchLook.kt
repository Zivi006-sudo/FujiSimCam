package com.fujisim.cam

import android.graphics.Bitmap
import android.graphics.Color
import java.io.File
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Match Look: learns the colour grade of sample photos and bakes it into a 3D LUT.
 * Per tone band (shadows → highlights) it matches brightness distribution, colour tint and saturation,
 * working in OKLab so colours stay natural. Source statistics come from the live camera frame
 * blended with typical phone values.
 */
object MatchLook {
    private const val BANDS = 8
    private const val HBINS = 64

    class Stats(
        val meanA: FloatArray, val meanB: FloatArray, val meanC: FloatArray, val count: IntArray,
        val cdf: FloatArray,
    )

    /** Analyse images. cropUi = ignore the edges where app UI / captions usually sit in screenshots. */
    fun analyse(images: List<Bitmap>, cropUi: Boolean): Stats {
        val sa = DoubleArray(BANDS); val sb = DoubleArray(BANDS); val sc = DoubleArray(BANDS); val cnt = IntArray(BANDS)
        val hist = DoubleArray(HBINS)
        val lab = FloatArray(3)
        for (bmp in images) {
            val x0 = if (cropUi) (bmp.width * 0.03f).toInt() else 0
            val x1 = if (cropUi) (bmp.width * 0.86f).toInt() else bmp.width
            val y0 = if (cropUi) (bmp.height * 0.10f).toInt() else 0
            val y1 = if (cropUi) (bmp.height * 0.74f).toInt() else bmp.height
            val step = maxOf(1, sqrt(((x1 - x0).toDouble() * (y1 - y0)) / 60000.0).toInt())
            var y = y0
            while (y < y1) {
                var x = x0
                while (x < x1) {
                    val p = bmp.getPixel(x, y)
                    ColorMath.srgbToLab(Color.red(p) / 255f, Color.green(p) / 255f, Color.blue(p) / 255f, lab)
                    val L = lab[0].coerceIn(0f, 0.9999f)
                    val bi = (L * BANDS).toInt()
                    sa[bi] += lab[1].toDouble(); sb[bi] += lab[2].toDouble(); sc[bi] += hypot(lab[1], lab[2]).toDouble()
                    cnt[bi]++
                    hist[(L * HBINS).toInt()] += 1.0
                    x += step
                }
                y += step
            }
        }
        val total = hist.sum().coerceAtLeast(1.0)
        val cdf = FloatArray(HBINS)
        var acc = 0.0
        for (i in 0 until HBINS) { acc += hist[i]; cdf[i] = (acc / total).toFloat() }
        val ma = FloatArray(BANDS); val mb = FloatArray(BANDS); val mc = FloatArray(BANDS)
        for (i in 0 until BANDS) if (cnt[i] > 0) {
            ma[i] = (sa[i] / cnt[i]).toFloat(); mb[i] = (sb[i] / cnt[i]).toFloat(); mc[i] = (sc[i] / cnt[i]).toFloat()
        }
        fillGaps(ma, cnt); fillGaps(mb, cnt); fillGaps(mc, cnt)
        return Stats(ma, mb, mc, cnt, cdf)
    }

    /** Bands with too few samples borrow from their neighbours. */
    private fun fillGaps(v: FloatArray, cnt: IntArray) {
        for (i in v.indices) if (cnt[i] < 40) {
            var sum = 0f; var w = 0f
            for (j in v.indices) if (cnt[j] >= 40) {
                val ww = 1f / (1 + (i - j) * (i - j)); sum += v[j] * ww; w += ww
            }
            v[i] = if (w > 0f) sum / w else 0f
        }
    }

    /** Typical phone photo statistics, blended with the live frame for robustness. */
    fun blendWithTypical(live: Stats?): Stats {
        val typC = FloatArray(BANDS) { i -> 0.03f + 0.05f * exp(-((i - 4.0) * (i - 4.0) / 8.0)).toFloat() }
        val typCdf = FloatArray(HBINS) { i ->
            val x = (i + 1f) / HBINS
            (0.5 * (1 + erf((x - 0.5) / (0.22 * sqrt(2.0))))).toFloat()
        }
        if (live == null) return Stats(FloatArray(BANDS), FloatArray(BANDS), typC, IntArray(BANDS) { 100 }, typCdf)
        return Stats(
            FloatArray(BANDS) { live.meanA[it] * 0.5f },
            FloatArray(BANDS) { live.meanB[it] * 0.5f },
            FloatArray(BANDS) { 0.5f * live.meanC[it] + 0.5f * typC[it] },
            IntArray(BANDS) { 100 },
            FloatArray(HBINS) { 0.5f * live.cdf[it] + 0.5f * typCdf[it] },
        )
    }

    private fun erf(x: Double): Double {   // Abramowitz-Stegun
        val t = 1.0 / (1.0 + 0.3275911 * kotlin.math.abs(x))
        val y = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-x * x)
        return if (x >= 0) y else -y
    }

    private fun bandValue(v: FloatArray, L: Float): Float {
        val pos = (L * BANDS - 0.5f).coerceIn(0f, BANDS - 1f)
        val i = pos.toInt().coerceAtMost(BANDS - 2)
        val t = pos - i
        return v[i] * (1 - t) + v[i + 1] * t
    }

    /** Tone curve: histogram matching from source CDF to reference CDF, softened. */
    private fun toneCurve(src: Stats, ref: Stats, amount: Float): FloatArray {
        val curve = FloatArray(HBINS + 1)
        for (i in 0..HBINS) {
            val L = i.toFloat() / HBINS
            val q = if (i == 0) 0f else src.cdf[(i - 1).coerceAtMost(HBINS - 1)]
            var j = 0
            while (j < HBINS - 1 && ref.cdf[j] < q) j++
            val prev = if (j == 0) 0f else ref.cdf[j - 1]
            val frac = if (ref.cdf[j] - prev > 1e-6f) ((q - prev) / (ref.cdf[j] - prev)).coerceIn(0f, 1f) else 0f
            val matched = (j + frac) / HBINS
            curve[i] = L + (matched - L) * amount
        }
        // smooth + keep monotonic + fixed ends
        val sm = curve.copyOf()
        for (i in 1 until HBINS) sm[i] = (curve[i - 1] + 2 * curve[i] + curve[i + 1]) / 4f
        sm[0] = 0f; sm[HBINS] = 1f
        for (i in 1..HBINS) if (sm[i] < sm[i - 1]) sm[i] = sm[i - 1]
        return sm
    }

    /** Build an n³ LUT (R fastest) as sRGB triples. */
    fun buildLut(ref: Stats, src: Stats, strength: Float, n: Int = 33): FloatArray {
        val curve = toneCurve(src, ref, 0.75f)
        val out = FloatArray(n * n * n * 3)
        val lab = FloatArray(3); val lin = FloatArray(3)
        var idx = 0
        for (bi in 0 until n) for (gi in 0 until n) for (ri in 0 until n) {
            val r = ri / (n - 1f); val g = gi / (n - 1f); val b = bi / (n - 1f)
            ColorMath.srgbToLab(r, g, b, lab)
            val L0 = lab[0]; val a0 = lab[1]; val b0 = lab[2]
            val pos = (L0.coerceIn(0f, 1f) * HBINS)
            val pi = pos.toInt().coerceAtMost(HBINS - 1)
            val L1 = curve[pi] + (curve[pi + 1] - curve[pi]) * (pos - pi)
            val k = (bandValue(ref.meanC, L1) / bandValue(src.meanC, L0).coerceAtLeast(0.01f)).coerceIn(0.35f, 1.8f)
            val a1 = (a0 - bandValue(src.meanA, L0)) * k + bandValue(ref.meanA, L1)
            val b1 = (b0 - bandValue(src.meanB, L0)) * k + bandValue(ref.meanB, L1)
            var L = L0 + (L1 - L0) * strength
            var aa = a0 + (a1 - a0) * strength
            var bb = b0 + (b1 - b0) * strength
            L = L.coerceIn(0f, 1f)
            // gamut: pull chroma in until it fits
            for (t in 0 until 10) {
                ColorMath.labToLin(L, aa, bb, lin)
                if (lin.all { it in -0.0005f..1.0005f }) break
                aa *= 0.88f; bb *= 0.88f
            }
            ColorMath.labToLin(L, aa, bb, lin)
            out[idx++] = ColorMath.toSrgb(lin[0].coerceIn(0f, 1f))
            out[idx++] = ColorMath.toSrgb(lin[1].coerceIn(0f, 1f))
            out[idx++] = ColorMath.toSrgb(lin[2].coerceIn(0f, 1f))
        }
        return out
    }

    fun writeCube(file: File, data: FloatArray, n: Int, title: String) {
        file.bufferedWriter().use { w ->
            w.write("TITLE \"${title.replace("\"", "")}\"\n# Made with FujiSim Cam Match Look\nLUT_3D_SIZE $n\n")
            var i = 0
            while (i < data.size) {
                w.write(String.format(java.util.Locale.US, "%.6f %.6f %.6f\n", data[i], data[i + 1], data[i + 2]))
                i += 3
            }
        }
    }
}
