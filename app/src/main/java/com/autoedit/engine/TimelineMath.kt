package com.autoedit.engine

import kotlin.math.pow

/**
 * One frame of the timeline.
 *
 * - [clipIndex]: the primary clip shown at this time.
 * - [localT]: seconds elapsed inside the primary clip.
 * - [prevIndex]: index of the previous clip while an incoming transition is active, else -1.
 * - [blend]: 0..1 progress of the incoming transition.
 *
 * Transitions play *inside* the first [transitionDurationSec] of a clip, so total
 * duration is always exactly `clips * clipDuration` (100 images x 3s = 300s, guaranteed).
 */
data class FrameState(
    val clipIndex: Int,
    val localT: Double,
    val prevIndex: Int,
    val blend: Double
)

object TimelineMath {

    fun totalDuration(clipCount: Int, clipDurationSec: Double): Double {
        if (clipCount <= 0 || clipDurationSec <= 0.0) return 0.0
        return clipCount * clipDurationSec
    }

    fun clipStart(index: Int, clipDurationSec: Double): Double = index * clipDurationSec

    fun frameAt(
        time: Double,
        clipCount: Int,
        clipDurationSec: Double,
        transitionDurationSec: Double
    ): FrameState {
        if (clipCount <= 0 || clipDurationSec <= 0.0) return FrameState(0, 0.0, -1, 0.0)
        val total = totalDuration(clipCount, clipDurationSec)
        val t = time.coerceIn(0.0, (total - 1e-6).coerceAtLeast(0.0))
        val idx = (t / clipDurationSec).toInt().coerceIn(0, clipCount - 1)
        val local = t - idx * clipDurationSec
        val td = transitionDurationSec.coerceIn(0.0, clipDurationSec * 0.9)
        val inTrans = idx > 0 && td > 0 && local < td
        return FrameState(
            clipIndex = idx,
            localT = local,
            prevIndex = if (inTrans) idx - 1 else -1,
            blend = if (inTrans) (local / td).coerceIn(0.0, 1.0) else 0.0
        )
    }

    /**
     * Variable per-clip duration version (mixed image/video timelines).
     * Same transition semantics as the fixed-duration [frameAt].
     */
    fun frameAt(time: Double, clipDurations: List<Double>, transitionDurationSec: Double): FrameState {
        val n = clipDurations.size
        if (n == 0) return FrameState(0, 0.0, -1, 0.0)
        val total = clipDurations.sumOf { it.coerceAtLeast(0.0) }
        if (total <= 0.0) return FrameState(0, 0.0, -1, 0.0)
        var t = time.coerceIn(0.0, (total - 1e-6).coerceAtLeast(0.0))
        var idx = 0
        while (idx < n - 1) {
            val d = clipDurations[idx].coerceAtLeast(0.0)
            if (t < d) break
            t -= d
            idx++
        }
        val local = t
        val td = transitionDurationSec.coerceIn(0.0, clipDurations[idx].coerceAtLeast(0.0) * 0.9)
        val inTrans = idx > 0 && td > 0 && local < td
        return FrameState(
            clipIndex = idx,
            localT = local,
            prevIndex = if (inTrans) idx - 1 else -1,
            blend = if (inTrans) (local / td).coerceIn(0.0, 1.0) else 0.0
        )
    }

    fun easing(t: Double, type: EasingType): Double {
        val x = t.coerceIn(0.0, 1.0)
        return when (type) {
            EasingType.LINEAR -> x
            EasingType.EASE_IN_OUT ->
                if (x < 0.5) 4.0 * x * x * x else 1.0 - (-2.0 * x + 2.0).pow(3) / 2.0
            EasingType.EASE_OUT -> 1.0 - (1.0 - x).pow(3)
        }
    }
}
