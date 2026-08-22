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
    val enabled: Boolean = true
) {
    fun zoomRangeLabel(): String =
        "100\u2013${"%.0f".format(100 + zoomMax * 100)}% zoom"
}

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

    val all: List<Formula> = listOf(F01, F02, F03, F04)

    fun byId(id: String?): Formula? = all.firstOrNull { it.id == id }

    fun default(): Formula = F01
}
