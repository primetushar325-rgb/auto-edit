package com.autoedit.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MixedTimelineTest {

    private fun image(uri: String = "content://img/default"): ClipRef = ClipRef(uri = uri)

    private fun video(inMs: Long = 0, outMs: Long = 4000, uri: String = "content://vid"): ClipRef =
        ClipRef(uri = uri, type = ClipType.VIDEO, videoInMs = inMs, videoOutMs = outMs)

    // --------------------------------------------------------- durations

    @Test
    fun `image clips use project duration`() {
        val p = ProjectModel("p", "p", 0, 0, clips = List(10) { image(it.toString()) })
        assertEquals(30.0, p.totalDuration(), 1e-9)
    }

    @Test
    fun `video clip uses its trim length`() {
        val p = ProjectModel(
            "p", "p", 0, 0,
            clips = listOf(image(), image(), video(1000, 5000), image())
        )
        // 3 + 3 + 4 + 3 = 13
        assertEquals(13.0, p.totalDuration(), 1e-9)
        val durations = p.clipDurations()
        assertEquals(4.0, durations[2], 1e-9) // video clip plays its 4s segment
    }

    @Test
    fun `mixed 25 clip 75 second style project`() {
        // 22 images x 3s + 3 video clips x (5+5+6)s = 66 + 16 = 82? keep it exact:
        val p = ProjectModel(
            "p", "p", 0, 0,
            clips = (0 until 22).map { image(it.toString()) } +
                listOf(video(0, 5000), video(0, 5000), video(0, 6000))
        )
        assertEquals(22 * 3.0 + 5.0 + 5.0 + 6.0, p.totalDuration(), 1e-9)
        assertEquals(25, p.clips.size)
    }

    @Test
    fun `fit to voice leaves room for video clips`() {
        val p = ProjectModel(
            "p", "p", 0, 0,
            clips = List(10) { image(it.toString()) } + listOf(video(0, 4000)),
            voice = AudioConfig("v", "v", durationSec = 34.0),
            fitToVoice = true
        )
        // (34 - 4) / 10 = 3.0 per image
        assertEquals(3.0, p.imageClipDuration(), 1e-9)
        assertEquals(34.0, p.totalDuration(), 1e-9)
    }

    // ------------------------------------------------- variable frameAt

    @Test
    fun `frameAt with variable durations picks the right clip`() {
        val d = listOf(3.0, 5.0, 3.0)
        val s0 = TimelineMath.frameAt(0.5, d, 0.45)
        assertEquals(0, s0.clipIndex)
        val s1 = TimelineMath.frameAt(3.5, d, 0.45)
        assertEquals(1, s1.clipIndex)
        assertEquals(0.5, s1.localT, 1e-9)
        val s2 = TimelineMath.frameAt(8.5, d, 0.45)
        assertEquals(2, s2.clipIndex)
        assertEquals(0.5, s2.localT, 1e-9)
        val sEnd = TimelineMath.frameAt(100.0, d, 0.45)
        assertEquals(2, sEnd.clipIndex)
    }

    @Test
    fun `frameAt transition window at junction of different lengths`() {
        val d = listOf(3.0, 5.0)
        // junction at t=3.0, first 0.45s of clip 1 is the transition
        val inTrans = TimelineMath.frameAt(3.2, d, 0.45)
        assertEquals(1, inTrans.clipIndex)
        assertEquals(0, inTrans.prevIndex)
        assertEquals(0.2 / 0.45, inTrans.blend, 1e-9)
        val noTrans = TimelineMath.frameAt(3.6, d, 0.45)
        assertEquals(-1, noTrans.prevIndex)
    }

    // ------------------------------------------------ junction overrides

    @Test
    fun `junction override wins over project default`() {
        val p = ProjectModel(
            "p", "p", 0, 0,
            clips = List(4) { image(it.toString()) },
            transition = TransitionType.CROSS_DISSOLVE,
            junctionTransitions = mapOf(2 to TransitionType.FLASH)
        )
        assertNull(p.junctionTransition(0))
        assertEquals(TransitionType.CROSS_DISSOLVE, p.junctionTransition(1))
        assertEquals(TransitionType.FLASH, p.junctionTransition(2))
        assertEquals(TransitionType.CROSS_DISSOLVE, p.junctionTransition(3))
    }

    // --------------------------------------------------- zoom overrides

    @Test
    fun `new clips are static by default`() {
        val c = ClipRef(uri = "x")
        assertNull(c.motion)
        assertNull(c.resolvedMotion())
    }

    @Test
    fun `manual zoom defaults to 100 to 92 push`() {
        val c = ClipRef(uri = "x", startZoom = 1f, endZoom = ClipRef.DEFAULT_END_ZOOM)
        val m = c.resolvedMotion()
        assertNotNull(m)
        assertEquals(1f, m!!.start.scale, 1e-4f)
        assertEquals(0.92f, m.end.scale, 1e-4f)
        assertEquals(MotionType.ZOOM_OUT, m.type)
    }

    @Test
    fun `zoom override replaces formula scale but keeps pan`() {
        val formulaMotion = ClipMotion(
            MotionType.ZOOM_PAN,
            Keyframe(1f, 0.02f, 0f),
            Keyframe(1.06f, -0.01f, 0f)
        )
        val c = ClipRef(uri = "x", motion = formulaMotion, startZoom = 1f, endZoom = 0.85f)
        val m = c.resolvedMotion()
        assertEquals(1f, m!!.start.scale, 1e-4f)
        assertEquals(0.85f, m.end.scale, 1e-4f)
        // pan preserved from the formula
        assertEquals(0.02f, m.start.x, 1e-4f)
        assertEquals(-0.01f, m.end.x, 1e-4f)
    }

    @Test
    fun `zoom override clamps to safe range`() {
        val c = ClipRef(uri = "x", startZoom = 5f, endZoom = 0.1f)
        val m = c.resolvedMotion()
        assertTrue(m!!.start.scale <= 1.5f)
        assertTrue(m.end.scale >= 0.5f)
    }

    // ------------------------------------------------- trend presets

    @Test
    fun `five viral trend presets exist as separate category`() {
        assertEquals(5, FormulaCatalog.trends.size)
        FormulaCatalog.trends.forEach { t ->
            assertEquals(PresetCategory.TREND, t.category)
            assertNotNull(FormulaCatalog.byId(t.id))
        }
        assertEquals(PresetCategory.FORMULA, FormulaCatalog.byId("F01")!!.category)
        // formulas untouched
        assertEquals(4, FormulaCatalog.formulas.size)
        assertEquals(9, FormulaCatalog.all.size)
    }

    @Test
    fun `beat punch starts zoomed and settles to 100`() {
        val t = FormulaCatalog.byId("T01")!!
        val motions = MotionPlanner().plan(6, t, 42L)
        motions.forEach { m ->
            assertEquals(MotionType.PUNCH_ZOOM, m.type)
            assertTrue("start ${m.start.scale}", m.start.scale in 1.05f..1.15f)
            assertEquals(1f, m.end.scale, 1e-4f)
        }
    }

    @Test
    fun `speed ramp zoom goes 100 to 115-125`() {
        val t = FormulaCatalog.byId("T03")!!
        val motions = MotionPlanner().plan(4, t, 7L)
        motions.forEach { m ->
            assertEquals(MotionType.RAMP_ZOOM, m.type)
            assertEquals(1f, m.start.scale, 1e-4f)
            assertTrue("end ${m.end.scale}", m.end.scale in 1.15f..1.251f)
        }
    }

    @Test
    fun `on-beat shake alternates direction per clip`() {
        val t = FormulaCatalog.byId("T04")!!
        val motions = MotionPlanner().plan(4, t, 3L)
        val dir0 = kotlin.math.sign(motions[0].start.x.toDouble())
        val dir1 = kotlin.math.sign(motions[1].start.x.toDouble())
        val dir2 = kotlin.math.sign(motions[2].start.x.toDouble())
        assertTrue(dir0 != 0.0)
        assertNotEquals(dir0, dir1)
        assertNotEquals(dir1, dir2)
        motions.forEach { m ->
            assertEquals(0f, m.end.x, 1e-4f) // settles back to center
            assertEquals(1f, m.end.scale, 1e-4f)
        }
    }

    @Test
    fun `whip pan starts wide and lands in center`() {
        val t = FormulaCatalog.byId("T05")!!
        val motions = MotionPlanner().plan(3, t, 9L)
        motions.forEach { m ->
            assertEquals(MotionType.WHIP_PAN, m.type)
            assertTrue("start x ${m.start.x}", kotlin.math.abs(m.start.x) > 0.05f)
            assertEquals(0f, m.end.x, 1e-4f)
        }
    }

    @Test
    fun `trend planner still avoids consecutive repeats on random pool`() {
        val t = FormulaCatalog.byId("T02")!!
        val motions = MotionPlanner().plan(40, t, 5L)
        for (i in 1 until motions.size) {
            assertNotEquals(motions[i - 1].type, motions[i].type)
        }
    }

    // ------------------------------------------------- audio trim

    @Test
    fun `toExactLength pads and trims`() {
        val src = ShortArray(10) { it.toShort() }
        val padded = AudioDsp.toExactLength(src, 14)
        assertEquals(14, padded.size)
        assertEquals(0, padded[10].toInt())
        assertEquals(9, padded[9].toInt())
        val trimmed = AudioDsp.toExactLength(src, 4)
        assertEquals(4, trimmed.size)
        assertEquals(3, trimmed[3].toInt())
    }
    // ------------------------------------------------ off-by-one / overflow audit

    @Test
    fun `exact sample count for the reported 21 point 6 second project`() {
        // 21.6s * 48000 = 1,036,800 samples - the buffer the field crash hit.
        // The sample loop must be `0 until n` over an array of exactly n.
        assertEquals(1_036_800, AudioDsp.exactSampleCount(21.6))
        val pcm = AudioDsp.toExactLength(ShortArray(1_036_801) { it.toShort() }, 1_036_800)
        assertEquals(1_036_800, pcm.size)
        // last valid index is size - 1; sampleAt clamps anything else
        val audio = AudioDsp.PcmAudio(pcm, 48000)
        val ok = AudioDsp.mix(21.6, audio, AudioConfig("u", "v", 21.6), null, null, false)
        assertEquals(1_036_800, ok.size)
    }

    @Test
    fun `exact sample count survives long projects (int overflow guard)`() {
        // 25 minutes: 1500s * 48000 = 72,000,000 (fine in Int)
        assertEquals(72_000_000, AudioDsp.exactSampleCount(1500.0))
        // 12.5 hours: 45000s * 48000 = 2,160,000,000 > Int.MAX - must clamp, not overflow
        val n = AudioDsp.exactSampleCount(45000.0)
        assertTrue("got $n", n in 0 until 2_147_483_647)
        assertEquals(0, AudioDsp.exactSampleCount(0.0))
    }

    @Test
    fun `default project transition is never flash (no surprise white flash)`() {
        val p = ProjectModel("p", "p", 0, 0, clips = List(3) { ClipRef(uri = "u$it") })
        assertEquals(TransitionType.CROSS_DISSOLVE, p.transition)
        // junction 0 does not exist (no cut before the first clip)
        assertNull(p.junctionTransition(0))
        // junctions empty -> every real junction resolves to the non-flash default
        for (i in 1..3) assertEquals(TransitionType.CROSS_DISSOLVE, p.junctionTransition(i))
    }

    @Test
    fun `mix output is exactly the requested length for boundary durations`() {
        val voice = AudioDsp.PcmAudio(ShortArray(48000) { 1000 }, 48000)
        for (secs in listOf(0.5, 1.0, 3.0, 21.6)) {
            val out = AudioDsp.mix(secs, voice, AudioConfig("u", "v", 1.0), null, null, false)
            assertEquals("secs=$secs", (secs * 48000).toLong().toInt(), out.size.toLong().toInt())
        }
    }

    // --------------------------------------------------- export test matrix

    @Test
    fun `test matrix - 2 point 5s clips at 10 25 50 images`() {
        for (n in listOf(10, 25, 50)) {
            val p = ProjectModel(
                "p", "p", 0, 0,
                clips = List(n) { ClipRef(uri = "u$it") },
                clipDurationSec = 2.5
            )
            assertEquals("n=$n", n * 2.5, p.totalDuration(), 1e-9)
            // exact audio sample count for the whole matrix case
            val samples = AudioDsp.exactSampleCount(p.totalDuration())
            assertEquals("n=$n", (n * 2.5 * 48000).toLong().toInt(), samples)
        }
    }

    @Test
    fun `test matrix - frameAt across non integer durations`() {
        // 3 clips x 2.5s = 7.5s
        val p = ProjectModel(
            "p", "p", 0, 0,
            clips = List(3) { ClipRef(uri = "u$it") },
            clipDurationSec = 2.5
        )
        val d = p.clipDurations()
        assertEquals(2.5, d[0], 1e-9)
        assertEquals(0, TimelineMath.frameAt(2.49, d, 0.45).clipIndex)
        assertEquals(1, TimelineMath.frameAt(2.5, d, 0.45).clipIndex)
        assertEquals(2, TimelineMath.frameAt(7.49, d, 0.45).clipIndex)
        assertEquals(2, TimelineMath.frameAt(100.0, d, 0.45).clipIndex) // clamped
    }

    @Test
    fun `test matrix - 720p and 1080p buffer sizes differ and are exact`() {
        // the crash index was 1,036,800 = 1920x540 (U plane of 1080p)
        assertEquals(1_036_800, YuvConverter.minChromaSize(1920, 1080, 1920, 1))
        assertEquals(460_800, YuvConverter.minChromaSize(1280, 720, 1280, 1))
        val cfg720 = ExportConfig(Quality.Q720, 30, AspectRatio.LANDSCAPE_16_9)
        val cfg1080 = ExportConfig(Quality.Q1080, 30, AspectRatio.LANDSCAPE_16_9)
        assertEquals(921_600, cfg720.widthFor() * cfg720.heightFor())
        assertEquals(2_073_600, cfg1080.widthFor() * cfg1080.heightFor())
    }
}
