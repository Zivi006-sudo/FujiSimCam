package com.fujisim.cam

import kotlin.math.cbrt
import kotlin.math.pow

/** sRGB <-> linear <-> OKLab conversions (same maths as tools/lut/oklab.py). */
object ColorMath {
    fun toLin(c: Float): Float = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    fun toSrgb(c: Float): Float {
        val v = c.coerceAtLeast(0f)
        return if (v <= 0.0031308f) v * 12.92f else (1.055 * v.toDouble().pow(1 / 2.4) - 0.055).toFloat()
    }

    /** sRGB (0..1) -> OKLab into out[0..2]. */
    fun srgbToLab(r: Float, g: Float, b: Float, out: FloatArray) {
        val lr = toLin(r); val lg = toLin(g); val lb = toLin(b)
        val l = cbrt(0.4122214708f * lr + 0.5363325363f * lg + 0.0514459929f * lb)
        val m = cbrt(0.2119034982f * lr + 0.6806995451f * lg + 0.1073969566f * lb)
        val s = cbrt(0.0883024619f * lr + 0.2817188376f * lg + 0.6299787005f * lb)
        out[0] = 0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s
        out[1] = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s
        out[2] = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s
    }

    /** OKLab -> linear sRGB into out (may be out of gamut). */
    fun labToLin(L: Float, a: Float, b: Float, out: FloatArray) {
        val l = L + 0.3963377774f * a + 0.2158037573f * b
        val m = L - 0.1055613458f * a - 0.0638541728f * b
        val s = L - 0.0894841775f * a - 1.2914855480f * b
        val l3 = l * l * l; val m3 = m * m * m; val s3 = s * s * s
        out[0] = 4.0767416621f * l3 - 3.3077115913f * m3 + 0.2309699292f * s3
        out[1] = -1.2684380046f * l3 + 2.6097574011f * m3 - 0.3413193965f * s3
        out[2] = -0.0041960863f * l3 - 0.7034186147f * m3 + 1.7076147010f * s3
    }
}
