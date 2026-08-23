package com.autoedit.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class ProjectJsonTest {

    private fun sampleProject(): ProjectModel = ProjectModel(
        id = "abc-123",
        name = "My \"Documentary\"\nwith émoji 😀",
        createdAt = 111L,
        updatedAt = 222L,
        clips = listOf(
            ClipRef("content://media/1"),
            ClipRef(
                uri = "content://media/2",
                motion = ClipMotion(MotionType.ZOOM_IN, Keyframe(1.0f, 0f, 0f), Keyframe(1.08f, 0.01f, 0f))
            )
        ),
        formulaId = "F01",
        motionSeed = 42L,
        clipDurationSec = 3.0,
        transition = TransitionType.FLASH,
        transitionDurationSec = 0.45,
        aspect = AspectRatio.PORTRAIT_9_16,
        voice = AudioConfig("content://v", "voice.m4a", 12.5, volume = 0.8f, offsetSec = 1.0, fadeInSec = 0.5),
        music = AudioConfig("content://m", "song.mp3", 200.0, volume = 0.4f, loop = true),
        duckMusic = true,
        fitToVoice = false,
        adjustments = Adjustments(brightness = 5f, contrast = -10f, saturation = 20f, vignette = 30f, blur = 2),
        export = ExportConfig(Quality.Q4K, 60, AspectRatio.PORTRAIT_9_16)
    )

    @Test
    fun `round trip preserves every field`() {
        val p = sampleProject()
        val decoded = ProjectJson.decode(ProjectJson.encode(p))
        assertEquals(p.id, decoded.id)
        assertEquals(p.name, decoded.name)
        assertEquals(p.clips.size, decoded.clips.size)
        assertEquals(p.clips[0].uri, decoded.clips[0].uri)
        assertNull(decoded.clips[0].motion)
        assertEquals(p.clips[1].motion, decoded.clips[1].motion)
        assertEquals(p.formulaId, decoded.formulaId)
        assertEquals(p.motionSeed, decoded.motionSeed)
        assertEquals(p.clipDurationSec, decoded.clipDurationSec, 1e-9)
        assertEquals(p.transition, decoded.transition)
        assertEquals(p.transitionDurationSec, decoded.transitionDurationSec, 1e-9)
        assertEquals(p.aspect, decoded.aspect)
        assertEquals(p.voice, decoded.voice)
        assertEquals(p.music, decoded.music)
        assertEquals(p.duckMusic, decoded.duckMusic)
        assertEquals(p.fitToVoice, decoded.fitToVoice)
        assertEquals(p.adjustments, decoded.adjustments)
        assertEquals(p.export, decoded.export)
    }

    @Test
    fun `special characters survive escaping`() {
        val p = sampleProject()
        val decoded = ProjectJson.decode(ProjectJson.encode(p))
        assertEquals(p.name, decoded.name)
    }

    @Test
    fun `missing optional fields fall back to defaults`() {
        val json = """{"id":"x","name":"n","createdAt":1,"updatedAt":2,"clips":[]}"""
        val decoded = ProjectJson.decode(json)
        assertEquals("x", decoded.id)
        assertEquals(3.0, decoded.clipDurationSec, 1e-9)
        assertEquals(TransitionType.CROSS_DISSOLVE, decoded.transition)
        assertEquals(AspectRatio.LANDSCAPE_16_9, decoded.aspect)
        assertNull(decoded.voice)
        assertNull(decoded.music)
        assertEquals(Quality.Q1080, decoded.export.quality)
        assertEquals(30, decoded.export.fps)
    }

    @Test
    fun `malformed json throws instead of crashing silently`() {
        try {
            ProjectJson.decode("{\"id\":")
            fail("expected failure")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `round trip keeps video clips zoom overrides and junctions`() {
        val p = ProjectModel(
            id = "mix",
            name = "mix",
            createdAt = 1L,
            updatedAt = 2L,
            clips = listOf(
                ClipRef(uri = "img1"),
                ClipRef(uri = "vid1", type = ClipType.VIDEO, videoInMs = 1500L, videoOutMs = 6500L),
                ClipRef(uri = "img2", startZoom = 1f, endZoom = 0.85f)
            ),
            junctionTransitions = mapOf(1 to TransitionType.FLASH, 2 to TransitionType.SLIDE_LEFT)
        )
        val d = ProjectJson.decode(ProjectJson.encode(p))
        assertEquals(3, d.clips.size)
        assertEquals(ClipType.VIDEO, d.clips[1].type)
        assertEquals(1500L, d.clips[1].videoInMs)
        assertEquals(6500L, d.clips[1].videoOutMs)
        assertNull(d.clips[0].startZoom)
        assertNull(d.clips[0].endZoom)
        assertEquals(1f, d.clips[2].startZoom!!, 1e-6f)
        assertEquals(0.85f, d.clips[2].endZoom!!, 1e-6f)
        assertEquals(TransitionType.FLASH, d.junctionTransitions[1])
        assertEquals(TransitionType.SLIDE_LEFT, d.junctionTransitions[2])
        // duration math follows the stored video trim
        assertEquals(3.0 + 5.0 + 3.0, d.totalDuration(), 1e-9)
    }

    @Test
    fun `duplicate images kept as separate clips`() {
        val p = ProjectModel(
            id = "d", name = "d", createdAt = 0, updatedAt = 0,
            clips = listOf(ClipRef("u"), ClipRef("u"), ClipRef("u"))
        )
        val decoded = ProjectJson.decode(ProjectJson.encode(p))
        assertEquals(3, decoded.clips.size)
    }

    @Test
    fun `repository save load list delete`() {
        val dir = createTempDir()
        try {
            val repo = ProjectRepository(dir)
            val p1 = ProjectModel("id1", "Alpha", 1L, 2L, clips = List(10) { ClipRef("u$it") })
            val p2 = ProjectModel("id2", "Beta", 3L, 4L)
            repo.save(p1)
            repo.save(p2)
            assertEquals(2, repo.list().size)
            assertEquals("Beta", repo.list().first().name) // sorted by updatedAt desc
            assertEquals(p1.clips.size, repo.load("id1")!!.clips.size)
            assertTrue(repo.rename("id1", "Alpha 2"))
            assertEquals("Alpha 2", repo.load("id1")!!.name)
            assertTrue(repo.delete("id1"))
            assertNull(repo.load("id1"))
            assertEquals(1, repo.list().size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `next project number increments`() {
        val dir = createTempDir()
        try {
            val repo = ProjectRepository(dir)
            assertEquals(1, repo.nextProjectNumber())
            repo.save(ProjectModel("a", "Project 1", 0, 0))
            repo.save(ProjectModel("b", "Project 3", 0, 0))
            assertEquals(4, repo.nextProjectNumber())
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun createTempDir(): File {
        val d = File(System.getProperty("java.io.tmpdir"), "autotest-${System.nanoTime()}")
        d.mkdirs()
        return d
    }
}
