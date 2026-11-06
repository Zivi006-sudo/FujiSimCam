package com.fujisim.cam

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import kotlin.math.hypot
import kotlin.math.roundToInt

/** What the phone exposes to apps: lenses, RAW support, resolutions. Used for the RAW test and lens naming. */
object CameraReport {

    data class LensInfo(
        val id: String,
        val logicalParent: String?,
        val facingBack: Boolean,
        val focalMm35: Int,         // 35mm-equivalent focal length (0 if unknown)
        val raw: Boolean,
        val rawSize: String,
        val maxJpegMp: Int,
    ) {
        val label: String
            get() = if (focalMm35 > 0) "${focalMm35}mm" else "camera $id"
    }

    fun lenses(ctx: Context): List<LensInfo> {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val out = mutableListOf<LensInfo>()
        val seen = HashSet<String>()
        for (id in runCatching { cm.cameraIdList.toList() }.getOrDefault(emptyList())) {
            val ch = runCatching { cm.getCameraCharacteristics(id) }.getOrNull() ?: continue
            out += describe(id, null, ch); seen += id
            val physical = runCatching { ch.physicalCameraIds }.getOrDefault(emptySet())
            for (pid in physical) {
                if (pid in seen) continue
                val pch = runCatching { cm.getCameraCharacteristics(pid) }.getOrNull() ?: continue
                out += describe(pid, id, pch); seen += pid
            }
        }
        return out
    }

    private fun describe(id: String, parent: String?, ch: CameraCharacteristics): LensInfo {
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val raw = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val rawSizes = runCatching { map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.toList() }.getOrNull() ?: emptyList()
        val biggestRaw = rawSizes.maxByOrNull { it.width.toLong() * it.height }
        val jpeg = runCatching {
            (map?.getOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList()) +
                (map?.getHighResolutionOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList())
        }.getOrDefault(emptyList())
        val maxMp = ((jpeg.maxOfOrNull { it.width.toLong() * it.height } ?: 0L) / 1_000_000L).toInt()
        val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
        val sensor = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val eq = if (focal > 0f && sensor != null) {
            (focal * 43.27f / hypot(sensor.width, sensor.height)).roundToInt()
        } else 0
        val facing = ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        return LensInfo(id, parent, facing, eq, raw && rawSizes.isNotEmpty(),
            biggestRaw?.let { "${it.width}×${it.height} (${(it.width.toLong() * it.height / 1_000_000L)}MP)" } ?: "—", maxMp)
    }

    fun text(ctx: Context, extras: List<String>): String {
        val sb = StringBuilder()
        sb.append("FujiSim Cam — camera report\n")
        sb.append("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}\n\n")
        for (l in lenses(ctx)) {
            val where = if (l.logicalParent != null) "physical (in ${l.logicalParent})" else "logical/standalone"
            sb.append("• ${if (l.facingBack) "Back" else "Front"} ${l.label} — id ${l.id}, $where\n")
            sb.append("   RAW: ${if (l.raw) "YES ${l.rawSize}" else "no"} · max JPEG ${l.maxJpegMp}MP\n")
        }
        if (extras.isNotEmpty()) {
            sb.append("\n")
            extras.forEach { sb.append(it).append('\n') }
        }
        return sb.toString()
    }
}
