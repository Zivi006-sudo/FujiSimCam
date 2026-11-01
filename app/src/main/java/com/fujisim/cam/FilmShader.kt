package com.fujisim.cam

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.hardware.HardwareBuffer
import android.media.ImageReader

/** AGSL backend of the colour engine: live viewfinder (RenderEffect) and still photos. */
class FilmShader {
    private val shader = RuntimeShader(Shaders.AGSL)

    fun set(look: Look, width: Float, height: Float, scale: Float, time: Float, mix: Float) {
        put("uSize", floatArrayOf(width, height))
        put("uScale", floatArrayOf(scale))
        put("uTime", floatArrayOf(time))
        for ((name, v) in look.uniforms(mix)) put(name, v)
    }

    private fun put(name: String, v: FloatArray) {
        when (v.size) {
            1 -> shader.setFloatUniform(name, v[0])
            2 -> shader.setFloatUniform(name, v[0], v[1])
            3 -> shader.setFloatUniform(name, v[0], v[1], v[2])
            else -> shader.setFloatUniform(name, v[0], v[1], v[2], v[3])
        }
    }

    fun renderEffect(): RenderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content")

    companion object {
        /**
         * Apply a look to a full-size bitmap on the GPU (background thread).
         * Rotation is done on the GPU too, so large (50MP) photos don't need an extra copy in memory.
         */
        fun process(src: Bitmap, look: Look, scale: Float, rotation: Int = 0): Bitmap {
            val rot = ((rotation % 360) + 360) % 360
            val w = if (rot == 90 || rot == 270) src.height else src.width
            val h = if (rot == 90 || rot == 270) src.width else src.height
            val fs = FilmShader()
            fs.set(look, w.toFloat(), h.toFloat(), scale, 0f, 1f)

            val reader = ImageReader.newInstance(
                w, h, PixelFormat.RGBA_8888, 1,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
            )
            val node = RenderNode("film")
            val renderer = HardwareRenderer()
            var result: Bitmap? = null
            try {
                renderer.setSurface(reader.surface)
                renderer.setContentRoot(node)
                node.setPosition(0, 0, w, h)
                node.setRenderEffect(fs.renderEffect())
                val canvas = node.beginRecording()
                when (rot) {
                    90 -> { canvas.translate(w.toFloat(), 0f); canvas.rotate(90f) }
                    180 -> { canvas.translate(w.toFloat(), h.toFloat()); canvas.rotate(180f) }
                    270 -> { canvas.translate(0f, h.toFloat()); canvas.rotate(270f) }
                }
                canvas.drawBitmap(src, 0f, 0f, null)
                node.endRecording()
                renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()

                val image = reader.acquireNextImage()
                if (image != null) {
                    val hb = image.hardwareBuffer
                    if (hb != null) {
                        val hw = Bitmap.wrapHardwareBuffer(hb, ColorSpace.get(ColorSpace.Named.SRGB))
                        result = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hb.close()
                    }
                    image.close()
                }
            } finally {
                node.discardDisplayList()
                renderer.destroy()
                reader.close()
            }
            return result ?: throw IllegalStateException("GPU processing failed")
        }
    }
}
