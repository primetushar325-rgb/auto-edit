package com.autoedit.export

/**
 * Pure progress mapping for the export pipeline.
 *
 * Stages (spec-mandated, real state only):
 *   0.00 - 0.05  Preparing (capability probe, dirs, temp)
 *   0.05 - 0.85  Rendering / encoding frames
 *   0.85 - 0.92  Audio processing / muxing
 *   0.92 - 0.98  Finalizing (remux + verification)
 *   0.98 - 1.00  Saving
 *   1.00         ONLY after the final MP4 is closed, verified and saved.
 *
 * No stage may ever report 1.0 before the final file exists and passed
 * verification. [DONE] is the only value equal to 1.0.
 */
object ExportProgress {

    const val PREP_START = 0.00f
    const val RENDER_START = 0.05f
    const val AUDIO_START = 0.85f
    const val MUX_START = 0.92f
    const val SAVE_START = 0.98f
    const val DONE = 1.0f

    /** Preparing stage, 0..1 sub-progress -> 0.00 .. 0.05. */
    fun prep(sub: Float): Float = (PREP_START + sub * (RENDER_START - PREP_START)).coerceIn(PREP_START, RENDER_START)

    /** Rendering stage, fraction of frames rendered -> 0.05 .. 0.85. */
    fun render(frac: Float): Float = (RENDER_START + frac * (AUDIO_START - RENDER_START)).coerceIn(RENDER_START, AUDIO_START)

    /** Audio stage, 0..1 sub-progress -> 0.85 .. 0.92. */
    fun audio(sub: Float): Float = (AUDIO_START + sub * (MUX_START - AUDIO_START)).coerceIn(AUDIO_START, MUX_START)

    /** Final mux / verification stage, 0..1 -> 0.92 .. 0.98. */
    fun mux(sub: Float): Float = (MUX_START + sub * (SAVE_START - MUX_START)).coerceIn(MUX_START, SAVE_START)

    /** Save stage, 0..1 -> 0.98 .. 1.00 (1.0 itself is only emitted as [DONE]). */
    fun save(sub: Float): Float = (SAVE_START + sub * (DONE - SAVE_START)).coerceIn(SAVE_START, DONE - 0.001f)
}
