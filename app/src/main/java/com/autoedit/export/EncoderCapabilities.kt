package com.autoedit.export

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaCodecList
import android.util.Log
import com.autoedit.engine.Quality

/**
 * Hardware H.264 encoder capability detection.
 *
 * [resolveRequest] is pure (unit-testable). The Android probes
 * ([maxAvcWidth], [probe]) query the real device.
 *
 * Policy (spec parts 19/20/28):
 *  - 4K only when some device H.264 encoder reports width >= 3840 AND the
 *    probe succeeds.
 *  - 60 fps only when the probe succeeds; otherwise fall back to 30.
 *  - Never silently switch to a CPU/YUV pipeline: if no hardware encoder
 *    works, the export fails with a clear, specific message.
 */
object EncoderCapabilities {

    private const val TAG = "AutoEditExport"
    const val MIME_AVC = "video/avc"

    data class Resolved(
        val width: Int,
        val height: Int,
        val fps: Int,
        val fallback: String?
    )

    /**
     * Pure resolution/FPS decision, no Android calls.
     *
     * @param reqW requested width
     * @param reqH requested height
     * @param reqFps requested fps
     * @param maxDevWidth max width any device H.264 encoder reports (<= 0 = unknown, assume 1080p ok)
     * @param fps60Supported true when a device probe confirmed 60 fps at the chosen resolution
     */
    fun resolveRequest(
        reqW: Int,
        reqH: Int,
        reqFps: Int,
        maxDevWidth: Int,
        fps60Supported: Boolean
    ): Resolved {
        var w = reqW
        var h = reqH
        var fps = reqFps
        var why: String? = null

        val longSide = maxOf(w, h)
        val maxLong = if (maxDevWidth > 0) maxDevWidth else 1920
        if (longSide > maxLong) {
            // scale the requested size down, keep aspect, floor to even numbers
            val scale = maxLong.toDouble() / longSide
            w = (w * scale).toInt().let { if (it % 2 != 0) it - 1 else it }
            h = (h * scale).toInt().let { if (it % 2 != 0) it - 1 else it }
            why = "Resolution not supported on this device - using ${w}x$h"
        }

        if (fps == 60 && !fps60Supported) {
            fps = 30
            why = (why?.plus(" • ") ?: "") + "60 fps not supported at this resolution - using 30 fps"
        }
        return Resolved(w, h, fps, why?.ifBlank { null })
    }

    /**
     * Longest side (px) supported by ANY hardware H.264 encoder on this device.
     * 0 when nothing could be determined (caller treats it as 1080p-ok).
     */
    fun maxAvcWidth(): Int {
        return try {
            val list = MediaCodecList(MediaCodecList.ALL_CODECS)
            var max = 0
            for (i in 0 until MediaCodecList.getCodecCount()) {
                val info = MediaCodecList.getCodecInfoAt(i)
                if (!info.isEncoder) continue
                val mime = info.supportedTypes?.firstOrNull { MIME_AVC.equals(it, ignoreCase = true) } ?: continue
                val caps = info.getCapabilitiesForType(mime) ?: continue
                val vc = caps.videoCapabilities ?: continue
                val widths = vc.supportedWidths ?: continue
                max = maxOf(max, widths.upper)
            }
            max
        } catch (e: Exception) {
            Log.w(TAG, "maxAvcWidth probe failed", e)
            0
        }
    }

    /**
     * Live probe: try to create + start + stop a real hardware encoder at
     * exactly (w,h,fps). Returns true only if the device actually accepts it.
     */
    fun probe(w: Int, h: Int, fps: Int, bitrate: Int = 12_000_000): Boolean {
        return try {
            val c = MediaCodec.createEncoderByType(MIME_AVC)
            try {
                val fmt = MediaFormat.createVideoFormat(MIME_AVC, w, h).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                }
                c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                c.start()
                c.stop()
                true
            } finally {
                try { c.release() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "encoder probe failed for ${w}x$h@${fps}", e)
            false
        }
    }

    /**
     * Decide the final export resolution/FPS for a requested [quality]+aspect+fps,
     * falling back safely (4K -> 1080p -> 720p; 60 -> 30).
     * Returns the resolved size + a human-readable warning (or null).
     *
     * Throws IllegalStateException when no hardware encoder works at any preset
     * (the app has no CPU/YUV fallback by design - that is the unstable path
     * being replaced, spec part 28).
     */
    fun resolve(quality: Quality, aspectW: Int, aspectH: Int, reqFps: Int): Resolved {
        val base = when (quality) {
            Quality.Q720 -> 720
            Quality.Q1080 -> 1080
            Quality.Q4K -> 2160
        }
        val landscape = aspectW >= aspectH
        val maxW = maxAvcWidth()

        fun sizeFor(b: Int): Pair<Int, Int> =
            if (landscape) (b * aspectW / aspectH) to b else b to (b * aspectH / aspectW)

        // 1) Requested preset at requested fps.
        val (rw, rh) = sizeFor(base)
        val r = resolveRequest(rw, rh, reqFps, maxW, fps60Supported = reqFps <= 30)
        if (probe(r.width, r.height, r.fps)) return r

        // 2) Same preset, 30 fps.
        if (reqFps > 30) {
            val r30 = resolveRequest(rw, rh, 30, maxW, fps60Supported = false)
            if (probe(r30.width, r30.height, 30)) {
                return r30.copy(
                    fallback = (r30.fallback?.plus(" • ") ?: "") +
                        "${reqFps} fps not supported on this device - using 30 fps"
                )
            }
        }

        // 3) One preset down.
        val lower = if (base > 1080) 1080 else 720
        val (lw, lh) = sizeFor(lower)
        val rl = resolveRequest(lw, lh, 30, maxW, fps60Supported = false)
        if (probe(rl.width, rl.height, rl.fps)) {
            val msg = "Selected resolution not supported on this device - using ${rl.width}x${rl.height}"
            return rl.copy(fallback = (rl.fallback?.plus(" • ") ?: "") + msg)
        }

        throw IllegalStateException("Video encoding is not supported on this device.")
    }
}
