package com.autoedit.engine

/**
 * Start/end transform keyframes for one image clip.
 * scale = zoom factor (1.0 = 100%)
 * x, y  = pan offsets as a fraction of frame width / frame height
 */
data class Keyframe(
    val scale: Float,
    val x: Float = 0f,
    val y: Float = 0f
) {
    companion object {
        fun origin(scale: Float = 1f) = Keyframe(scale, 0f, 0f)
    }
}

enum class MotionType {
    ZOOM_IN, ZOOM_OUT, PAN_LEFT, PAN_RIGHT, PAN_UP, PAN_DOWN, ZOOM_PAN, KEN_BURNS;

    fun label(): String = when (this) {
        ZOOM_IN -> "Zoom In"
        ZOOM_OUT -> "Zoom Out"
        PAN_LEFT -> "Pan Left"
        PAN_RIGHT -> "Pan Right"
        PAN_UP -> "Pan Up"
        PAN_DOWN -> "Pan Down"
        ZOOM_PAN -> "Zoom + Pan"
        KEN_BURNS -> "Ken Burns"
    }

    fun short(): String = when (this) {
        ZOOM_IN -> "ZI"
        ZOOM_OUT -> "ZO"
        PAN_LEFT -> "PL"
        PAN_RIGHT -> "PR"
        PAN_UP -> "PU"
        PAN_DOWN -> "PD"
        ZOOM_PAN -> "ZP"
        KEN_BURNS -> "KB"
    }
}

data class ClipMotion(
    val type: MotionType,
    val start: Keyframe,
    val end: Keyframe
)

enum class TransitionType {
    NONE, FADE, CROSS_DISSOLVE, SLIDE_LEFT, SLIDE_RIGHT, SLIDE_UP, SLIDE_DOWN, ZOOM, BLUR, FLASH;

    fun label(): String = when (this) {
        NONE -> "None"
        FADE -> "Fade"
        CROSS_DISSOLVE -> "Cross Dissolve"
        SLIDE_LEFT -> "Slide Left"
        SLIDE_RIGHT -> "Slide Right"
        SLIDE_UP -> "Slide Up"
        SLIDE_DOWN -> "Slide Down"
        ZOOM -> "Zoom"
        BLUR -> "Blur"
        FLASH -> "Flash"
    }
}

enum class EasingType { LINEAR, EASE_IN_OUT, EASE_OUT }

enum class MotionMode { RANDOM, FIXED }

enum class AspectRatio(val label: String, val w: Int, val h: Int) {
    LANDSCAPE_16_9("Landscape 16:9", 16, 9),
    PORTRAIT_9_16("Portrait 9:16", 9, 16),
    SQUARE_1_1("Square 1:1", 1, 1)
}

/** Basic image adjustments. Simple and bounded on purpose. */
data class Adjustments(
    val brightness: Float = 0f,   // -50 .. 50
    val contrast: Float = 0f,     // -50 .. 50
    val saturation: Float = 0f,   // -100 .. 100
    val vignette: Float = 0f,     // 0 .. 100
    val blur: Int = 0             // 0 .. 10
) {
    fun isNeutral(): Boolean =
        brightness == 0f && contrast == 0f && saturation == 0f && vignette == 0f && blur == 0

    fun needsColorFilter(): Boolean =
        brightness != 0f || contrast != 0f || saturation != 0f

    /** 20-float 4x5 color matrix (row major) for brightness / contrast / saturation. */
    fun toColorMatrix(): FloatArray {
        val m = floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
        if (brightness != 0f) {
            val b = brightness / 50f * 40f
            m[4] = b; m[9] = b; m[14] = b
        }
        if (contrast != 0f) {
            val k = 1f + contrast / 50f
            m[0] = k; m[5] = k; m[10] = k
            m[4] += 128f * (1f - k)
            m[9] += 128f * (1f - k)
            m[14] += 128f * (1f - k)
        }
        if (saturation != 0f) {
            val s = 1f + saturation / 100f
            m[0] = 0.213f + 0.787f * s; m[1] = 0.715f - 0.715f * s; m[2] = 0.072f - 0.072f * s
            m[3] = 0f; m[4] = 0f
            m[5] = 0.213f - 0.213f * s; m[6] = 0.715f + 0.285f * s; m[7] = 0.072f - 0.072f * s
            m[8] = 0.213f - 0.213f * s; m[9] = 0.715f - 0.715f * s; m[10] = 0.072f + 0.928f * s
            m[11] = 0f; m[12] = 0f
        }
        return m
    }
}

data class AudioConfig(
    val uri: String,
    val displayName: String,
    val durationSec: Double,
    val volume: Float = 1f,
    val offsetSec: Double = 0.0,
    val fadeInSec: Double = 0.0,
    val fadeOutSec: Double = 0.0,
    val loop: Boolean = false
)

enum class Quality(val label: String) {
    Q720("720p"),
    Q1080("1080p"),
    Q4K("4K");

    /** Base = short side in px. */
    fun baseSize(): Int = when (this) {
        Q720 -> 720
        Q1080 -> 1080
        Q4K -> 2160
    }

    fun resolutionFor(a: AspectRatio): Pair<Int, Int> {
        val base = baseSize()
        return when (a) {
            AspectRatio.LANDSCAPE_16_9 -> (base * 16 / 9) to base
            AspectRatio.PORTRAIT_9_16 -> base to (base * 16 / 9)
            AspectRatio.SQUARE_1_1 -> base to base
        }
    }
}

data class ExportConfig(
    val quality: Quality = Quality.Q1080,
    val fps: Int = 30,
    val aspect: AspectRatio = AspectRatio.LANDSCAPE_16_9
) {
    fun widthFor(a: AspectRatio = aspect): Int = quality.resolutionFor(a).first
    fun heightFor(a: AspectRatio = aspect): Int = quality.resolutionFor(a).second

    val videoBitrate: Int
        get() = when (quality) {
            Quality.Q720 -> 8_000_000
            Quality.Q1080 -> 12_000_000
            Quality.Q4K -> 35_000_000
        }

    companion object {
        val FPS_OPTIONS = listOf(24, 30, 60)
    }
}

data class ClipRef(
    val uri: String,
    val motion: ClipMotion? = null
)

data class ProjectModel(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val clips: List<ClipRef> = emptyList(),
    val formulaId: String? = null,
    val motionSeed: Long = 1L,
    val clipDurationSec: Double = 3.0,
    val transition: TransitionType = TransitionType.CROSS_DISSOLVE,
    val transitionDurationSec: Double = 0.45,
    val aspect: AspectRatio = AspectRatio.LANDSCAPE_16_9,
    val voice: AudioConfig? = null,
    val music: AudioConfig? = null,
    val duckMusic: Boolean = true,
    val fitToVoice: Boolean = false,
    val adjustments: Adjustments = Adjustments(),
    val export: ExportConfig = ExportConfig()
) {
    /**
     * Clip duration actually used for preview/export.
     * Default mode: fixed [clipDurationSec] (3 seconds).
     * Fit-to-voice mode: voice duration / number of images.
     */
    fun effectiveClipDuration(): Double {
        if (fitToVoice) {
            val v = voice?.durationSec ?: return clipDurationSec
            val n = clips.size.coerceAtLeast(1)
            return v / n
        }
        return clipDurationSec
    }

    fun totalDuration(): Double = clips.size * effectiveClipDuration()
}
