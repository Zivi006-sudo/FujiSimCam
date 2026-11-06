package com.fujisim.cam

import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** GLSL backend of the colour engine, run on every video frame by Media3. */
@OptIn(UnstableApi::class)
class FilmGlProgram(
    private val look: Look,
    private val lutImage: LutImage?,
    useHdr: Boolean,
    private val temporal: Float = 0f,
) : BaseGlShaderProgram(useHdr, 1) {
    private val program: GlProgram = try {
        GlProgram(Shaders.VERTEX_GL, Shaders.FRAGMENT_GL)
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e)
    }
    private var width = 1f
    private var height = 1f
    private val lut: LutImage = lutImage ?: LutLibrary.identity
    private val lutTex: Int = uploadTexture(lut.bitmap)

    private fun uploadTexture(bmp: Bitmap): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        return ids[0]
    }

    init {
        program.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
        )
    }

    private var prevTex = 0
    private var havePrev = false
    private var temporalOk = true

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        width = inputWidth.toFloat()
        height = inputHeight.toFloat()
        if (temporal > 0f && prevTex == 0) {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            prevTex = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prevTex)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, inputWidth, inputHeight, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        }
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            try { program.setSamplerTexIdUniform("uLut", lutTex, 1) } catch (e: RuntimeException) { }
            set("uLutN", floatArrayOf(lut.size.toFloat()))
            set("uLutSize", floatArrayOf(lut.bitmap.width.toFloat(), lut.bitmap.height.toFloat()))
            set("uSize", floatArrayOf(width, height))
            set("uScale", floatArrayOf(max(1f, min(width, height) / 1080f)))
            set("uTime", floatArrayOf(presentationTimeUs / 1_000_000f))
            for ((name, v) in look.uniforms(1f)) set(name, v)
            if (lutImage == null) set("uLutMix", floatArrayOf(0f))
            val useTemporal = temporal > 0f && havePrev && temporalOk
            if (prevTex != 0) {
                try { program.setSamplerTexIdUniform("uPrev", prevTex, 2) } catch (e: RuntimeException) { }
            }
            set("uTemporal", floatArrayOf(if (useTemporal) temporal else 0f))
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            if (temporal > 0f && prevTex != 0 && temporalOk) {
                // Keep this output for the next frame's temporal noise reduction.
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prevTex)
                GLES20.glCopyTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width.toInt(), height.toInt())
                if (GLES20.glGetError() != GLES20.GL_NO_ERROR) temporalOk = false else havePrev = true
            }
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    private fun set(name: String, v: FloatArray) {
        try {
            if (v.size == 1) program.setFloatUniform(name, v[0]) else program.setFloatsUniform(name, v)
        } catch (e: RuntimeException) {
            // Uniform optimised out by the GPU compiler — safe to ignore.
        }
    }

    override fun release() {
        super.release()
        GLES20.glDeleteTextures(1, intArrayOf(lutTex), 0)
        if (prevTex != 0) GLES20.glDeleteTextures(1, intArrayOf(prevTex), 0)
        try {
            program.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }
}

/** Re-encodes a recorded clip with the look applied (also tone-maps 10-bit HLG "log" clips). */
@OptIn(UnstableApi::class)
object VideoStyler {
    fun style(
        ctx: Context,
        input: File,
        output: File,
        look: Look,
        lutImage: LutImage?,
        temporal: Float,
        onProgress: (Int) -> Unit,
        onDone: (Throwable?) -> Unit,
    ) {
        val effect = GlEffect { _, useHdr -> FilmGlProgram(look, lutImage, useHdr, temporal) }
        val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input)))
            .setEffects(Effects(emptyList(), listOf(effect)))
            .build()
        val composition = Composition.Builder(EditedMediaItemSequence(item))
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()

        val handler = Handler(Looper.getMainLooper())
        var transformer: Transformer? = null
        val poll = object : Runnable {
            override fun run() {
                val t = transformer ?: return
                val holder = ProgressHolder()
                if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                handler.postDelayed(this, 300)
            }
        }
        val tr = Transformer.Builder(ctx)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    handler.removeCallbacks(poll)
                    onDone(null)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    handler.removeCallbacks(poll)
                    onDone(exportException)
                }
            })
            .build()
        transformer = tr
        tr.start(composition, output.absolutePath)
        handler.post(poll)
    }
}
