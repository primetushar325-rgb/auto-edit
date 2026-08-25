package com.autoedit.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportProgressTest {

    @Test
    fun `stages are in the spec order and never overlap`() {
        assertTrue(ExportProgress.RENDER_START < ExportProgress.AUDIO_START)
        assertTrue(ExportProgress.AUDIO_START < ExportProgress.MUX_START)
        assertTrue(ExportProgress.MUX_START < ExportProgress.SAVE_START)
        assertEquals(1.0f, ExportProgress.DONE, 0f)
    }

    @Test
    fun `render spans five to eighty five percent`() {
        assertEquals(0.05f, ExportProgress.render(0f), 1e-4f)
        assertEquals(0.85f, ExportProgress.render(1f), 1e-4f)
        assertEquals(0.45f, ExportProgress.render(0.5f), 1e-4f)
    }

    @Test
    fun `the 90 percent freeze is impossible - rendering ends at the audio boundary`() {
        // The old pipeline mapped overall = 0.12 + frac*0.8, so render 97-98%
        // pinned the UI at exactly ~90-91% (right where the device stalled).
        // The new mapping: the WHOLE render stage ends at 0.85, and 0.85+
        // (audio/mux/finalize) is only reached AFTER the encoder has
        // finished and produced its EOS.
        val r97 = ExportProgress.render(0.97f)
        assertTrue("render(0.97)=$r97 must still be in the render stage (< 0.85)", r97 < 0.85f)
        val r100 = ExportProgress.render(1f)
        assertEquals(0.85f, r100, 1e-4f)
        // the audio stage starts only after rendering is DONE
        assertTrue(ExportProgress.audio(0f) > r97)
        assertTrue(ExportProgress.audio(0f) >= r100 - 1e-4f)
    }

    @Test
    fun `progress is monotonic across all stages`() {
        val seq = ArrayList<Float>()
        for (i in 0..10) seq += ExportProgress.prep(i / 10f)
        for (i in 0..10) seq += ExportProgress.render(i / 10f)
        for (i in 0..10) seq += ExportProgress.audio(i / 10f)
        for (i in 0..10) seq += ExportProgress.mux(i / 10f)
        for (i in 0..10) seq += ExportProgress.save(i / 10f)
        seq += ExportProgress.DONE
        for (i in 1 until seq.size) {
            assertTrue(
                "progress must never go backwards: ${seq[i - 1]} -> ${seq[i]}",
                seq[i] >= seq[i - 1]
            )
        }
    }

    @Test
    fun `nothing before the final file may report one hundred percent`() {
        for (i in 0 until 10) {
            assertTrue(ExportProgress.prep(i / 10f) < 1f)
            assertTrue(ExportProgress.render(i / 10f) < 1f)
            assertTrue(ExportProgress.audio(i / 10f) < 1f)
            assertTrue(ExportProgress.mux(i / 10f) < 1f)
            assertTrue(ExportProgress.save(i / 10f) < 1f)
        }
        // even save(1.0) must not reach 1.0 - only DONE may
        assertTrue(ExportProgress.save(1f) < 1f)
        assertEquals(1f, ExportProgress.DONE, 0f)
    }

    @Test
    fun `clamping keeps values in range`() {
        assertEquals(0.0f, ExportProgress.prep(-1f), 1e-4f)
        assertEquals(0.05f, ExportProgress.prep(2f), 1e-4f)
        assertTrue(ExportProgress.render(-5f) >= 0.05f)
        assertTrue(ExportProgress.render(5f) <= 0.85f)
    }
}
