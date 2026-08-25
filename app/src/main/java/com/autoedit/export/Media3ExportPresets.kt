package com.autoedit.export

import android.util.Size
import com.autoedit.engine.AspectRatio
import com.autoedit.engine.Quality

/**
 * Export presets for the Media3 [androidx.media3.transformer.Transformer] pipeline.
 *
 * Each preset fixes:
 *  - target frame size (short side, aspect-aware), always even
 *  - video bitrate (CBR-ish), tuned for the resolution
 *  - I-frame (GOP) interval of 2 seconds for clean seeking + no visible keyframe pops
 *  - frame rate honouring the project setting
 *
 * Bitrates are deliberately on the generous side ("zero quality drop") while
 * staying within typical hardware H.264 encoder limits:
 *   720p  -> 5 Mbps
 *   1080p -> 12 Mbps
 *   4K    -> 40 Mbps
 */
object Media3ExportPresets {

    /** 2-second GOP at every preset -> consistent keyframes, no glitchy seeking. */
    const val IFRAME_INTERVAL_SECONDS = 2f

    data class Preset(
        val quality: Quality,
        val width: Int,
        val height: Int,
        val bitrate: Int,
        val fps: Int,
        val label: String
    ) {
        val shortSide: Int get() = minOf(width, height)
        val longSide: Int get() = maxOf(width, height)
    }

    /** Even the dimensions (H.264 requires even width/height). */
    private fun even(n: Int): Int = n - (n and 1)

    fun resolve(quality: Quality, aspect: AspectRatio, requestedFps: Int): Preset {
        val base = when (quality) {
            Quality.Q720 -> 720
            Quality.Q1080 -> 1080
            Quality.Q4K -> 2160
        }
        val (w, h) = when (aspect) {
            AspectRatio.LANDSCAPE_16_9 -> even(base * 16 / 9) to even(base)
            AspectRatio.PORTRAIT_9_16 -> even(base) to even(base * 16 / 9)
            AspectRatio.SQUARE_1_1 -> even(base) to even(base)
        }
        val bitrate = when (quality) {
            Quality.Q720 -> 5_000_000
            Quality.Q1080 -> 12_000_000
            Quality.Q4K -> 40_000_000
        }
        return Preset(
            quality = quality,
            width = w,
            height = h,
            bitrate = bitrate,
            fps = requestedFps,
            label = "${quality.label} @ ${requestedFps}fps"
        )
    }

    /**
     * Ordered list of fallbacks to try (lower resolution, lower fps, then lower
     * bitrate) when the Transformer reports the primary preset is unsupported by
     * any available encoder.
     */
    fun fallbackChain(preset: Preset): List<Preset> {
        val out = ArrayList<Preset>()
        // Same res, halve the bitrate once (some devices reject high bitrates).
        out += preset.copy(bitrate = preset.bitrate / 2)
        // Step down one quality tier, same aspect/fps.
        val lowerQuality = when (preset.quality) {
            Quality.Q4K -> Quality.Q1080
            Quality.Q1080 -> Quality.Q720
            Quality.Q720 -> Quality.Q720
        }
        if (lowerQuality != preset.quality) {
            out += resolve(lowerQuality, aspectFor(preset), preset.fps)
        }
        // Force 30 fps if 60 was requested.
        if (preset.fps > 30) {
            out += resolve(preset.quality, aspectFor(preset), 30)
            if (lowerQuality != preset.quality) {
                out += resolve(lowerQuality, aspectFor(preset), 30)
            }
        }
        return out.distinctBy { Triple(it.width, it.height, it.bitrate) }
    }

    private fun aspectFor(preset: Preset): AspectRatio {
        val ratio = preset.width.toFloat() / preset.height
        return when {
            kotlin.math.abs(ratio - 16f / 9f) < 0.01f -> AspectRatio.LANDSCAPE_16_9
            kotlin.math.abs(ratio - 9f / 16f) < 0.01f -> AspectRatio.PORTRAIT_9_16
            else -> AspectRatio.SQUARE_1_1
        }
    }

    @Suppress("unused")
    fun sizeOf(preset: Preset): Size = Size(preset.width, preset.height)
}
