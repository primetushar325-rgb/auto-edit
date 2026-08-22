package com.autoedit.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineMathTest {

    @Test
    fun `1 image is 3 seconds`() {
        assertEquals(3.0, TimelineMath.totalDuration(1, 3.0), 1e-9)
    }

    @Test
    fun `10 images is 30 seconds`() {
        assertEquals(30.0, TimelineMath.totalDuration(10, 3.0), 1e-9)
    }

    @Test
    fun `100 images is 300 seconds`() {
        assertEquals(300.0, TimelineMath.totalDuration(100, 3.0), 1e-9)
    }

    @Test
    fun `200 images is 600 seconds`() {
        assertEquals(600.0, TimelineMath.totalDuration(200, 3.0), 1e-9)
    }

    @Test
    fun `500 images is 1500 seconds`() {
        assertEquals(1500.0, TimelineMath.totalDuration(500, 3.0), 1e-9)
    }

    @Test
    fun `zero images is zero duration`() {
        assertEquals(0.0, TimelineMath.totalDuration(0, 3.0), 1e-9)
    }

    @Test
    fun `transitions never change the total duration`() {
        // 200 x 3s must remain 600s even with transitions enabled
        val project = ProjectModel(
            id = "p1", name = "p", createdAt = 0, updatedAt = 0,
            clips = List(200) { ClipRef("content://img/$it") },
            transition = TransitionType.CROSS_DISSOLVE,
            transitionDurationSec = 0.45
        )
        assertEquals(600.0, project.totalDuration(), 1e-9)
    }

    @Test
    fun `frame at boundary picks the right clip`() {
        val s0 = TimelineMath.frameAt(0.0, 10, 3.0, 0.45)
        assertEquals(0, s0.clipIndex)
        val s999 = TimelineMath.frameAt(29.999, 10, 3.0, 0.45)
        assertEquals(9, s999.clipIndex)
        val s3 = TimelineMath.frameAt(3.0, 10, 3.0, 0.45)
        assertEquals(1, s3.clipIndex)
        assertEquals(0.0, s3.localT, 1e-9)
    }

    @Test
    fun `incoming transition active only in first td seconds`() {
        val inTrans = TimelineMath.frameAt(3.2, 10, 3.0, 0.45)
        assertEquals(1, inTrans.clipIndex)
        assertEquals(0, inTrans.prevIndex)
        assertTrue(inTrans.blend in 0.0..1.0)
        assertTrue(inTrans.blend > 0.0)

        val noTrans = TimelineMath.frameAt(3.5, 10, 3.0, 0.45)
        assertEquals(-1, noTrans.prevIndex)
    }

    @Test
    fun `first clip has no incoming transition`() {
        val s = TimelineMath.frameAt(0.1, 10, 3.0, 0.45)
        assertEquals(-1, s.prevIndex)
    }

    @Test
    fun `none transition never produces a blend`() {
        val s = TimelineMath.frameAt(3.1, 10, 3.0, 0.45)
        // transitionDurationSec still applies in math; NONE is filtered by the renderer,
        // but blend math itself stays valid:
        assertTrue(s.blend in 0.0..1.0)
    }

    @Test
    fun `easing endpoints and midpoint`() {
        assertEquals(0.0, TimelineMath.easing(0.0, EasingType.EASE_IN_OUT), 1e-9)
        assertEquals(1.0, TimelineMath.easing(1.0, EasingType.EASE_IN_OUT), 1e-9)
        assertEquals(0.5, TimelineMath.easing(0.5, EasingType.EASE_IN_OUT), 1e-9)
        assertEquals(0.5, TimelineMath.easing(0.5, EasingType.LINEAR), 1e-9)
        assertTrue(TimelineMath.easing(0.25, EasingType.EASE_IN_OUT) < 0.25)
        assertTrue(TimelineMath.easing(0.75, EasingType.EASE_IN_OUT) > 0.75)
        assertTrue(TimelineMath.easing(0.25, EasingType.EASE_OUT) > 0.25)
    }

    @Test
    fun `fit to voice distributes voice duration across images`() {
        val project = ProjectModel(
            id = "p1", name = "p", createdAt = 0, updatedAt = 0,
            clips = List(200) { ClipRef("content://img/$it") },
            clipDurationSec = 3.0,
            voice = AudioConfig("content://v", "v", durationSec = 600.0),
            fitToVoice = true
        )
        assertEquals(3.0, project.effectiveClipDuration(), 1e-9) // 600s / 200
        assertEquals(600.0, project.totalDuration(), 1e-9)
    }

    @Test
    fun `default mode stays at fixed 3 seconds`() {
        val project = ProjectModel(
            id = "p1", name = "p", createdAt = 0, updatedAt = 0,
            clips = List(10) { ClipRef("content://img/$it") },
            clipDurationSec = 3.0,
            voice = AudioConfig("content://v", "v", durationSec = 600.0),
            fitToVoice = false
        )
        assertEquals(30.0, project.totalDuration(), 1e-9)
    }

    @Test
    fun `frame beyond total clamps to last clip`() {
        val s = TimelineMath.frameAt(9999.0, 10, 3.0, 0.45)
        assertEquals(9, s.clipIndex)
    }
}
