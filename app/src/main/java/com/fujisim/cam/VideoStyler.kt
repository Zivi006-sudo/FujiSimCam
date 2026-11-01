package com.fujisim.cam

import android.content.Context
import android.net.Uri
import android.opengl.GLES20
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
class FilmGlProgram(private val look: Look, useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {
    private val program: GlProgram = try {
        GlProgram(Shaders.VERTEX_GL, Shaders.FRAGMENT_GL)
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e)
    }
    private var width = 1f
    private var height = 1f

    init {
        program.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
        )
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        width = inputWidth.toFloat()
        height = inputHeight.toFloat()
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            set("uSize", floatArrayOf(width, height))
            set("uScale", floatArrayOf(max(1f, min(width, height) / 1080f)))
            set("uTime", floatArrayOf(presentationTimeUs / 1_000_000f))
            for ((name, v) in look.uniforms(1f)) set(name, v)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
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
        onProgress: (Int) -> Unit,
        onDone: (Throwable?) -> Unit,
    ) {
        val effect = GlEffect { _, useHdr -> FilmGlProgram(look, useHdr) }
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
