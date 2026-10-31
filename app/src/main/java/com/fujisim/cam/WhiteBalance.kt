package com.fujisim.cam

import android.hardware.camera2.CaptureRequest
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/**
 * Fuji-style white balance on a phone:
 * the camera is set to the nearest supported AWB preset, and the remaining difference
 * (exact Kelvin + R/B shift) is applied as RGB gains inside the shader.
 */
object WhiteBalance {
    class Plan(val awbMode: Int, val gains: FloatArray)

    private val presets = listOf(
        CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT to 2850,
        CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT to 3000,
        CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT to 4200,
        CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT to 5500,
        CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT to 6500,
        CaptureRequest.CONTROL_AWB_MODE_SHADE to 7500,
    )

    /** available = camera's supported AWB modes, or null if unknown (assume all). */
    fun plan(r: Recipe, available: IntArray?): Plan {
        var mode = CaptureRequest.CONTROL_AWB_MODE_AUTO
        var gains = floatArrayOf(1f, 1f, 1f)
        val target: Int? = when (r.wb) {
            WbMode.AUTO -> null
            WbMode.KELVIN -> r.kelvin
            else -> r.wb.kelvin
        }
        if (target != null) {
            val t = target.coerceIn(2500, 10000)
            val usable = presets.filter { available == null || available.contains(it.first) }
            val base = usable.minByOrNull { abs(1e6 / it.second - 1e6 / t) }
                ?: (CaptureRequest.CONTROL_AWB_MODE_AUTO to 5500)
            mode = base.first
            val a = kelvinLinear(base.second)
            val b = kelvinLinear(t)
            gains = floatArrayOf(a[0] / b[0], a[1] / b[1], a[2] / b[2])
        }
        gains[0] *= 1f + 0.025f * r.wbR
        gains[2] *= 1f + 0.025f * r.wbB
        val l = 0.2126f * gains[0] + 0.7152f * gains[1] + 0.0722f * gains[2]
        return Plan(mode, FloatArray(3) { (gains[it] / l).coerceIn(0.4f, 2.5f) })
    }

    /** Approximate linear RGB colour of a black-body light source (Tanner Helland fit). */
    private fun kelvinLinear(k: Int): FloatArray {
        val t = k / 100.0
        val r = if (t <= 66) 255.0 else 329.698727446 * (t - 60).pow(-0.1332047592)
        val g = if (t <= 66) 99.4708025861 * ln(t) - 161.1195681661
        else 288.1221695283 * (t - 60).pow(-0.0755148492)
        val b = when {
            t >= 66 -> 255.0
            t <= 19 -> 1.0
            else -> 138.5177312231 * ln(t - 10) - 305.0447927307
        }
        return doubleArrayOf(r, g, b).map { v ->
            (v.coerceIn(1.0, 255.0) / 255.0).pow(2.2).toFloat()
        }.toFloatArray()
    }
}
