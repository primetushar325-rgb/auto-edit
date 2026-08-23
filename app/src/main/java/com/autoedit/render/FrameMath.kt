package com.autoedit.render

import com.autoedit.engine.ClipMotion
import com.autoedit.engine.EasingType
import com.autoedit.engine.TimelineMath

/**
 * Interpolated transform for a clip at a point in time.
 * Pure math shared by the Compose preview and the software export renderer.
 */
data class MotionTransform(
    val scale: Float,
    val xFrac: Float,
    val yFrac: Float
) {
    companion object {
        val IDENTITY = MotionTransform(1f, 0f, 0f)
    }
}

object FrameMath {

    fun transformAt(
        motion: ClipMotion?,
        localT: Double,
        clipDuration: Double,
        easing: EasingType
    ): MotionTransform {
        if (motion == null || clipDuration <= 0.0) return MotionTransform.IDENTITY
        val p = TimelineMath.easing(localT / clipDuration, easing).toFloat()
        return MotionTransform(
            lerp(motion.start.scale, motion.end.scale, p),
            lerp(motion.start.x, motion.end.x, p),
            lerp(motion.start.y, motion.end.y, p)
        )
    }

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
