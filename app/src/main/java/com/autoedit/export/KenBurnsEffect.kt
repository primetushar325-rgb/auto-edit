package com.autoedit.export

import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import com.autoedit.engine.EasingType
import com.autoedit.engine.TimelineMath

/**
 * Basic Ken Burns motion: a gentle, smooth scale from [startScale] to [endScale]
 * over [durationMs], centered in frame. Implemented as a Media3
 * [MatrixTransformation] so it runs through Transformer's GL pipeline with zero
 * app-side surface management.
 *
 * Scale stays within the 1.00..1.04 safe cinematic range; no pan (basic motion
 * scope). The transform uses the project's ease-in-out so the move is never
 * abrupt, matching the preview's easing curve.
 */
@UnstableApi
class KenBurnsEffect(
    private val durationMs: Long,
    private val startScale: Float,
    private val endScale: Float,
    @Suppress("unused") private val fps: Int
) : MatrixTransformation {

    override fun getMatrix(presentationTimeUs: Long): android.graphics.Matrix {
        val t = if (durationMs <= 0) 1.0
        else (presentationTimeUs / 1000.0 / durationMs.toDouble()).coerceIn(0.0, 1.0)
        val eased = TimelineMath.easing(t, EasingType.EASE_IN_OUT)
        val scale = startScale + (endScale - startScale) * eased.toFloat()

        // A centered scale: translate origin to center, scale, translate back.
        // MatrixTransformation operates on a -1..1 coordinate system with the
        // center at (0,0), so a pure setScale around origin is already centered.
        return android.graphics.Matrix().apply { setScale(scale, scale) }
    }
}
