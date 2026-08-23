package com.autoedit.engine

import kotlin.random.Random

/**
 * Generates a per-image motion sequence for a [Formula].
 *
 * - Seeded: same seed + formula + count => same sequence (reproducible).
 * - "Randomize again" = new seed => new sequence, images unchanged.
 * - Anti-repetition: avoids the same motion type in 2 consecutive clips when possible.
 */
class MotionPlanner {

    fun plan(clipCount: Int, formula: Formula, seed: Long): List<ClipMotion> {
        if (clipCount <= 0) return emptyList()
        val rng = Random(seed)
        val pool = formula.motionPool.ifEmpty { listOf(MotionType.ZOOM_IN) }
        val out = ArrayList<ClipMotion>(clipCount)
        var last: MotionType? = null
        var lastLast: MotionType? = null
        for (i in 0 until clipCount) {
            val type = pick(pool, formula.motionMode, last, lastLast, rng)
            out += makeMotion(type, formula, rng)
            lastLast = last
            last = type
        }
        return out
    }

    private fun pick(
        pool: List<MotionType>,
        mode: MotionMode,
        last: MotionType?,
        lastLast: MotionType?,
        rng: Random
    ): MotionType {
        if (mode == MotionMode.FIXED || pool.size == 1) return pool[0]
        val fresh = pool.filter { it != last && it != lastLast }
        val candidates = if (fresh.isNotEmpty()) fresh else pool
        return candidates[rng.nextInt(candidates.size)]
    }

    private fun makeMotion(type: MotionType, f: Formula, rng: Random): ClipMotion {
        val zoom = f.zoomMin + (f.zoomMax - f.zoomMin) * rng.nextFloat()
        val pan = f.panMax * (0.6f + 0.4f * rng.nextFloat())
        val smallZoom = zoom * 0.4f
        return when (type) {
            MotionType.ZOOM_IN ->
                ClipMotion(type, Keyframe(1f, 0f, 0f), Keyframe(1f + zoom, 0f, 0f))

            MotionType.ZOOM_OUT ->
                ClipMotion(type, Keyframe(1f + zoom, 0f, 0f), Keyframe(1f, 0f, 0f))

            MotionType.PAN_LEFT ->
                // image drifts gently to the left
                ClipMotion(
                    type,
                    Keyframe(1f + smallZoom, pan, 0f),
                    Keyframe(1f + smallZoom, -pan * 0.35f, 0f)
                )

            MotionType.PAN_RIGHT ->
                ClipMotion(
                    type,
                    Keyframe(1f + smallZoom, -pan, 0f),
                    Keyframe(1f + smallZoom, pan * 0.35f, 0f)
                )

            MotionType.PAN_UP ->
                // positive y = lower on screen; pan up = y decreases over time
                ClipMotion(
                    type,
                    Keyframe(1f + smallZoom, 0f, pan),
                    Keyframe(1f + smallZoom, 0f, -pan * 0.35f)
                )

            MotionType.PAN_DOWN ->
                ClipMotion(
                    type,
                    Keyframe(1f + smallZoom, 0f, -pan),
                    Keyframe(1f + smallZoom, 0f, pan * 0.35f)
                )

            MotionType.ZOOM_PAN -> {
                val dirX = if (rng.nextBoolean()) 1f else -1f
                val dirY = if (rng.nextBoolean()) 1f else -1f
                val p = pan * 0.7f
                ClipMotion(
                    type,
                    Keyframe(1f, dirX * p, dirY * p * 0.5f),
                    Keyframe(1f + zoom * 0.8f, -dirX * p * 0.3f, -dirY * p * 0.2f)
                )
            }

            MotionType.KEN_BURNS ->
                ClipMotion(
                    type,
                    Keyframe(1f, 0f, pan * 0.5f),
                    Keyframe(1f + zoom * 0.75f, 0f, -pan * 0.5f)
                )
        }
    }
}
