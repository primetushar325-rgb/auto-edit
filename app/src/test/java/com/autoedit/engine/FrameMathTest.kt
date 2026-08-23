package com.autoedit.engine

import com.autoedit.render.FrameMath
import com.autoedit.render.MotionTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameMathTest {

    @Test
    fun `zoom in 100 to 108 interpolates with easing`() {
        val motion = ClipMotion(
            MotionType.ZOOM_IN,
            Keyframe(1.0f, 0f, 0f),
            Keyframe(1.08f, 0f, 0f)
        )
        val start = FrameMath.transformAt(motion, 0.0, 3.0, EasingType.EASE_IN_OUT)
        assertEquals(1.0f, start.scale, 1e-4f)
        val mid = FrameMath.transformAt(motion, 1.5, 3.0, EasingType.EASE_IN_OUT)
        assertEquals(1.04f, mid.scale, 1e-3f)
        val end = FrameMath.transformAt(motion, 3.0, 3.0, EasingType.EASE_IN_OUT)
        assertEquals(1.08f, end.scale, 1e-4f)
    }

    @Test
    fun `zoom out 108 to 100`() {
        val motion = ClipMotion(
            MotionType.ZOOM_OUT,
            Keyframe(1.08f, 0f, 0f),
            Keyframe(1.0f, 0f, 0f)
        )
        val start = FrameMath.transformAt(motion, 0.0, 3.0, EasingType.EASE_IN_OUT)
        assertEquals(1.08f, start.scale, 1e-4f)
        val end = FrameMath.transformAt(motion, 3.0, 3.0, EasingType.EASE_IN_OUT)
        assertEquals(1.0f, end.scale, 1e-4f)
        // monotonic decrease
        val a = FrameMath.transformAt(motion, 0.75, 3.0, EasingType.LINEAR).scale
        val b = FrameMath.transformAt(motion, 1.5, 3.0, EasingType.LINEAR).scale
        val c = FrameMath.transformAt(motion, 2.25, 3.0, EasingType.LINEAR).scale
        assertTrue(a > b && b > c)
    }

    @Test
    fun `pan left moves x from positive to less positive`() {
        val motion = ClipMotion(
            MotionType.PAN_LEFT,
            Keyframe(1.0f, 0.03f, 0f),
            Keyframe(1.0f, -0.0105f, 0f)
        )
        val t0 = FrameMath.transformAt(motion, 0.0, 3.0, EasingType.LINEAR)
        val t1 = FrameMath.transformAt(motion, 3.0, 3.0, EasingType.LINEAR)
        assertEquals(0.03f, t0.xFrac, 1e-4f)
        assertEquals(-0.0105f, t1.xFrac, 1e-4f)
    }

    @Test
    fun `null motion gives identity transform`() {
        val t = FrameMath.transformAt(null, 1.5, 3.0, EasingType.EASE_IN_OUT)
        assertEquals(MotionTransform.IDENTITY, t)
    }

    @Test
    fun `clamped time never extrapolates`() {
        val motion = ClipMotion(
            MotionType.ZOOM_IN,
            Keyframe(1.0f, 0f, 0f),
            Keyframe(1.08f, 0f, 0f)
        )
        val over = FrameMath.transformAt(motion, 10.0, 3.0, EasingType.LINEAR)
        assertEquals(1.08f, over.scale, 1e-4f)
    }

    @Test
    fun `transition blend math`() {
        val state = TimelineMath.frameAt(3.225, 5, 3.0, 0.45)
        assertEquals(1, state.clipIndex)
        assertEquals(0, state.prevIndex)
        assertEquals(0.5, state.blend, 1e-9)
    }
}
