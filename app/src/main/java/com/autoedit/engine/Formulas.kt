package com.autoedit.engine

/**
 * A Formula is an automatic editing recipe.
 * Adding a new formula = adding a new entry to [FormulaCatalog]. No UI changes needed.
 */
data class Formula(
    val id: String,
    val name: String,
    val tagline: String,
    val description: String,
    val clipDurationSec: Double,
    val motionMode: MotionMode,
    val motionPool: List<MotionType>,
    /** Random zoom amount range (added on top of 100%). e.g. 0.04..0.08 = 104%..108% */
    val zoomMin: Float,
    val zoomMax: Float,
    /** Max pan drift as fraction of frame. */
    val panMax: Float,
    val transition: TransitionType,
    val transitionDurationSec: Double,
    val easing: EasingType,
    val category: PresetCategory = PresetCategory.FORMULA,
    val enabled: Boolean = true
) {
    fun zoomRangeLabel(): String =
        "100\u2013${"%.0f".format(100 + zoomMax * 100)}% zoom"
}

enum class PresetCategory { FORMULA, TREND }

object FormulaCatalog {

    /**
     * FORMULA 01 — RANDOM CINEMATIC (required, default).
     * Every image gets a slightly different, safe, cinematic move.
     */
    val F01 = Formula(
        id = "F01",
        name = "FORMULA 01",
        tagline = "RANDOM CINEMATIC",
        description = "Every image gets a unique, subtly different cinematic move. " +
            "Zoom stays in the safe 100\u2013108% range, motion is smooth ease-in-out.",
        clipDurationSec = 3.0,
        motionMode = MotionMode.RANDOM,
        motionPool = MotionType.values().toList(),
        zoomMin = 0.04f,
        zoomMax = 0.08f,
        panMax = 0.035f,
        transition = TransitionType.CROSS_DISSOLVE,
        transitionDurationSec = 0.45,
        easing = EasingType.EASE_IN_OUT
    )

    val F02 = Formula(
        id = "F02",
        name = "FORMULA 02",
        tagline = "SLOW DOCUMENTARY",
        description = "Long 4-second holds with very gentle zoom and Ken Burns drift. " +
            "Soft fades. Made for storytelling and photo essays.",
        clipDurationSec = 4.0,
        motionMode = MotionMode.RANDOM,
        motionPool = listOf(MotionType.ZOOM_IN, MotionType.ZOOM_OUT, MotionType.KEN_BURNS),
        zoomMin = 0.02f,
        zoomMax = 0.04f,
        panMax = 0.02f,
        transition = TransitionType.FADE,
        transitionDurationSec = 0.8,
        easing = EasingType.EASE_IN_OUT
    )

    val F03 = Formula(
        id = "F03",
        name = "FORMULA 03",
        tagline = "SMOOTH ZOOM",
        description = "One clean, predictable move: a slow 100% \u2192 110% zoom on every " +
            "image with soft fades. Calm and consistent.",
        clipDurationSec = 3.0,
        motionMode = MotionMode.FIXED,
        motionPool = listOf(MotionType.ZOOM_IN),
        zoomMin = 0.10f,
        zoomMax = 0.10f,
        panMax = 0f,
        transition = TransitionType.FADE,
        transitionDurationSec = 0.4,
        easing = EasingType.EASE_IN_OUT
    )

    val F04 = Formula(
        id = "F04",
        name = "FORMULA 04",
        tagline = "DYNAMIC MOTION",
        description = "Fast 2.5-second cuts, stronger zoom (up to 112%) and wider pans with " +
            "flash transitions. High energy.",
        clipDurationSec = 2.5,
        motionMode = MotionMode.RANDOM,
        motionPool = MotionType.values().toList(),
        zoomMin = 0.06f,
        zoomMax = 0.12f,
        panMax = 0.05f,
        transition = TransitionType.FLASH,
        transitionDurationSec = 0.35,
        easing = EasingType.EASE_OUT
    )

    // ------------------------------------------------------------------
    // 2026 VIRAL TRENDING EFFECTS - separate category, same engine.
    // One-tap apply, exactly like formulas.
    // ------------------------------------------------------------------

    val T01 = Formula(
        id = "T01",
        name = "TREND 01",
        tagline = "BEAT PUNCH",
        description = "Every clip starts zoomed in and snaps back to 100% on the " +
            "beat, with hard flash cuts. High energy, viral style.",
        clipDurationSec = 2.5,
        motionMode = MotionMode.FIXED,
        motionPool = listOf(MotionType.PUNCH_ZOOM),
        zoomMin = 0.05f,
        zoomMax = 0.15f,
        panMax = 0f,
        transition = TransitionType.FLASH,
        transitionDurationSec = 0.3,
        easing = EasingType.EASE_OUT,
        category = PresetCategory.TREND
    )

    val T02 = Formula(
        id = "T02",
        name = "TREND 02",
        tagline = "GLITCH FLASH CUTS",
        description = "Fast 2.2s cuts, punchy zoom-pan jumps and glitchy white " +
            "flash between every clip.",
        clipDurationSec = 2.2,
        motionMode = MotionMode.RANDOM,
        motionPool = listOf(MotionType.ZOOM_PAN, MotionType.PUNCH_ZOOM),
        zoomMin = 0.08f,
        zoomMax = 0.18f,
        panMax = 0.06f,
        transition = TransitionType.FLASH,
        transitionDurationSec = 0.22,
        easing = EasingType.EASE_OUT,
        category = PresetCategory.TREND
    )

    val T03 = Formula(
        id = "T03",
        name = "TREND 03",
        tagline = "SPEED-RAMP ZOOM",
        description = "Accelerating 100% to 125% speed-ramp zoom on every clip " +
            "with soft dissolves. Smooth and hypnotic.",
        clipDurationSec = 3.0,
        motionMode = MotionMode.FIXED,
        motionPool = listOf(MotionType.RAMP_ZOOM),
        zoomMin = 0.15f,
        zoomMax = 0.25f,
        panMax = 0f,
        transition = TransitionType.CROSS_DISSOLVE,
        transitionDurationSec = 0.4,
        easing = EasingType.EASE_IN_OUT,
        category = PresetCategory.TREND
    )

    val T04 = Formula(
        id = "T04",
        name = "TREND 04",
        tagline = "ON-BEAT SHAKE",
        description = "Hard cuts with an alternating directional jolt on every " +
            "clip, like the edit punched in on the beat.",
        clipDurationSec = 2.0,
        motionMode = MotionMode.FIXED,
        motionPool = listOf(MotionType.SHAKE),
        zoomMin = 0.0f,
        zoomMax = 0.0f,
        panMax = 0.045f,
        transition = TransitionType.NONE,
        transitionDurationSec = 0.0,
        easing = EasingType.EASE_OUT,
        category = PresetCategory.TREND
    )

    val T05 = Formula(
        id = "T05",
        name = "TREND 05",
        tagline = "WHIP-PAN",
        description = "Fast whip-pan swings that land on every new clip, glued " +
            "together with quick slide transitions.",
        clipDurationSec = 2.5,
        motionMode = MotionMode.FIXED,
        motionPool = listOf(MotionType.WHIP_PAN),
        zoomMin = 0.02f,
        zoomMax = 0.05f,
        panMax = 0.07f,
        transition = TransitionType.SLIDE_LEFT,
        transitionDurationSec = 0.25,
        easing = EasingType.EASE_OUT,
        category = PresetCategory.TREND
    )

    val formulas: List<Formula> = listOf(F01, F02, F03, F04)
    val trends: List<Formula> = listOf(T01, T02, T03, T04, T05)
    val all: List<Formula> = formulas + trends

    fun byId(id: String?): Formula? = all.firstOrNull { it.id == id }

    fun default(): Formula = F01
}
