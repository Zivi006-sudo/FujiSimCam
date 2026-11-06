package com.fujisim.cam

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * Clean mode: merges a handheld burst into one low-noise frame.
 * 1. Align each frame to the first (global shift, coarse-to-fine search on luminance).
 * 2. Average pixel-by-pixel, skipping pixels that differ a lot from the reference (moving things → no ghosts).
 */
class CleanMerge(ref: Bitmap) {
    val width = ref.width
    val height = ref.height
    private val refPx = IntArray(width * height).also { ref.getPixels(it, 0, width, 0, 0, width, height) }
    private val sum = ShortArray(width * height * 3)
    private val count = ByteArray(width * height)
    private val refSmall = luma(refPx, width, height, 8)
    private val sw = width / 8
    private val sh = height / 8
    private val refHalf by lazy { luma(refPx, width, height, 2) }
    var merged = 0
        private set

    /** Downscaled luminance (box average) for fast alignment. */
    private fun luma(px: IntArray, w: Int, h: Int, f: Int): IntArray {
        val ow = w / f; val oh = h / f
        val out = IntArray(ow * oh)
        for (y in 0 until oh) for (x in 0 until ow) {
            var s = 0
            var yy = y * f
            while (yy < y * f + f) {
                var xx = x * f
                while (xx < x * f + f) {
                    val c = px[yy * w + xx]
                    s += ((c shr 16 and 255) * 77 + (c shr 8 and 255) * 150 + (c and 255) * 29) shr 8
                    xx += 2
                }
                yy += 2
            }
            out[y * ow + x] = s
        }
        return out
    }

    private fun bestShift(a: IntArray, b: IntArray, w: Int, h: Int, range: Int, cx: Int = 0, cy: Int = 0): Pair<Int, Int> {
        var best = Long.MAX_VALUE; var bx = cx; var by = cy
        val m = range + 2
        for (dy in cy - range..cy + range) for (dx in cx - range..cx + range) {
            var sad = 0L
            var y = m
            while (y < h - m) {
                val yb = y + dy
                if (yb in 0 until h) {
                    var x = m
                    while (x < w - m) {
                        val xb = x + dx
                        if (xb in 0 until w) sad += abs(a[y * w + x] - b[yb * w + xb])
                        x += 2
                    }
                }
                y += 2
            }
            if (sad < best) { best = sad; bx = dx; by = dy }
        }
        return bx to by
    }

    /** Add one frame. Returns false if it was too different to use (big movement). */
    fun add(frame: Bitmap): Boolean {
        if (frame.width != width || frame.height != height) return false
        val px = IntArray(width * height)
        frame.getPixels(px, 0, width, 0, 0, width, height)
        val small = luma(px, width, height, 8)
        val (cx, cy) = bestShift(refSmall, small, sw, sh, 8)
        // refine at 1/2 scale around the coarse estimate, on a central crop
        val half = 2
        val frHalf = luma(px, width, height, half)
        val hw = width / half; val hh = height / half
        val (fx, fy) = bestShift(refHalf, frHalf, hw, hh, 3, cx * 4, cy * 4)
        val dx = fx * half; val dy = fy * half
        if (abs(dx) > width / 10 || abs(dy) > height / 10) return false

        val threshold = 42
        for (y in 0 until height) {
            val ys = y + dy
            if (ys < 0 || ys >= height) continue
            val row = y * width
            val rowS = ys * width
            for (x in 0 until width) {
                val xs = x + dx
                if (xs < 0 || xs >= width) continue
                val r = refPx[row + x]
                val f = px[rowS + xs]
                val dr = abs((r shr 16 and 255) - (f shr 16 and 255))
                val dg = abs((r shr 8 and 255) - (f shr 8 and 255))
                val db = abs((r and 255) - (f and 255))
                if (dr + dg + db < threshold) {
                    val i = row + x
                    val o = i * 3
                    sum[o] = (sum[o] + (f shr 16 and 255)).toShort()
                    sum[o + 1] = (sum[o + 1] + (f shr 8 and 255)).toShort()
                    sum[o + 2] = (sum[o + 2] + (f and 255)).toShort()
                    count[i] = (count[i] + 1).toByte()
                }
            }
        }
        merged++
        return true
    }

    fun result(): Bitmap {
        val out = IntArray(width * height)
        for (i in out.indices) {
            val r = refPx[i]
            val n = 1 + count[i]
            val o = i * 3
            val rr = ((r shr 16 and 255) + sum[o]) / n
            val gg = ((r shr 8 and 255) + sum[o + 1]) / n
            val bb = ((r and 255) + sum[o + 2]) / n
            out[i] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
        return Bitmap.createBitmap(out, width, height, Bitmap.Config.ARGB_8888)
    }
}
