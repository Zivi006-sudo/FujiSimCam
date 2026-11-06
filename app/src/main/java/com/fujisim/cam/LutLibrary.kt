package com.fujisim.cam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.UUID
import kotlin.math.roundToInt

/** A 3D LUT stored as a 2D strip: width = n*n (blue slices side by side), height = n. */
class LutImage(val size: Int, val bitmap: Bitmap)

/**
 * Loads film-simulation LUTs.
 * References: "asset:<name>" for built-in sims (assets/luts/<name>.cube), "file:<name>" for imported .cube files.
 */
object LutLibrary {
    private val cache = HashMap<String, LutImage>()

    /** 2×2×2 identity LUT, used when no LUT is active (the shader still needs an input). */
    val identity: LutImage by lazy {
        val n = 2
        val data = FloatArray(n * n * n * 3)
        var i = 0
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            data[i++] = r.toFloat(); data[i++] = g.toFloat(); data[i++] = b.toFloat()
        }
        toImage(n, data)
    }

    @Synchronized
    fun get(ctx: Context, ref: String?): LutImage? {
        if (ref.isNullOrBlank()) return null
        cache[ref]?.let { return it }
        val img = runCatching {
            val reader = when {
                ref.startsWith("asset:") ->
                    BufferedReader(InputStreamReader(ctx.assets.open("luts/${ref.removePrefix("asset:")}.cube")))
                ref.startsWith("file:") ->
                    File(lutDir(ctx), ref.removePrefix("file:")).bufferedReader()
                else -> return null
            }
            reader.use { parse(it) }
        }.getOrNull() ?: return null
        cache[ref] = img
        return img
    }

    fun lutDir(ctx: Context): File = File(ctx.filesDir, "luts").apply { mkdirs() }

    /** Copy a user-picked .cube into app storage. Returns the "file:" reference, or throws with a readable message. */
    fun import(ctx: Context, uri: Uri): String {
        val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalArgumentException("Couldn't read the file")
        text.reader().buffered().use { parse(it) }   // validate before saving
        val name = UUID.randomUUID().toString() + ".cube"
        File(lutDir(ctx), name).writeText(text)
        return "file:$name"
    }

    /** Parse an Adobe/Resolve .cube 3D LUT (R changes fastest). */
    fun parse(reader: BufferedReader): LutImage {
        var size = 0
        var minV = floatArrayOf(0f, 0f, 0f)
        var maxV = floatArrayOf(1f, 1f, 1f)
        var data: FloatArray? = null
        var count = 0
        reader.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val parts = line.split(Regex("\\s+"))
            when (parts[0].uppercase()) {
                "TITLE" -> {}
                "LUT_1D_SIZE" -> throw IllegalArgumentException("1D LUTs aren't supported — use a 3D .cube LUT")
                "LUT_3D_SIZE" -> {
                    size = parts[1].toInt()
                    require(size in 2..65) { "LUT size $size not supported (2–65)" }
                    data = FloatArray(size * size * size * 3)
                }
                "DOMAIN_MIN" -> minV = floatArrayOf(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat())
                "DOMAIN_MAX" -> maxV = floatArrayOf(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat())
                else -> {
                    val d = data ?: return@forEach
                    if (parts.size >= 3 && count < d.size / 3) {
                        val v0 = parts[0].toFloatOrNull()
                        if (v0 != null) {
                            for (k in 0..2) {
                                val v = parts[k].toFloat()
                                d[count * 3 + k] = (v - minV[k]) / (maxV[k] - minV[k])
                            }
                            count++
                        }
                    }
                }
            }
        }
        val d = data ?: throw IllegalArgumentException("Not a 3D .cube LUT (no LUT_3D_SIZE)")
        require(count == size * size * size) { "LUT is incomplete ($count of ${size * size * size} entries)" }
        return toImage(size, d)
    }

    private fun toImage(n: Int, d: FloatArray): LutImage {
        val bmp = Bitmap.createBitmap(n * n, n, Bitmap.Config.ARGB_8888)
        val px = IntArray(n * n * n)
        var i = 0
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            fun c(v: Float) = (v.coerceIn(0f, 1f) * 255f).roundToInt()
            val x = r + b * n
            val y = g
            px[y * (n * n) + x] = Color.argb(255, c(d[i * 3]), c(d[i * 3 + 1]), c(d[i * 3 + 2]))
            i++
        }
        bmp.setPixels(px, 0, n * n, 0, 0, n * n, n)
        return LutImage(n, bmp)
    }
}
