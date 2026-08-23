package com.autoedit.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class ExportConfigTest {

    @Test
    fun `720p landscape is 1280x720`() {
        val c = ExportConfig(Quality.Q720, 30, AspectRatio.LANDSCAPE_16_9)
        assertEquals(1280, c.widthFor())
        assertEquals(720, c.heightFor())
    }

    @Test
    fun `1080p landscape is 1920x1080`() {
        val c = ExportConfig(Quality.Q1080, 30, AspectRatio.LANDSCAPE_16_9)
        assertEquals(1920, c.widthFor())
        assertEquals(1080, c.heightFor())
    }

    @Test
    fun `4k landscape is 3840x2160`() {
        val c = ExportConfig(Quality.Q4K, 30, AspectRatio.LANDSCAPE_16_9)
        assertEquals(3840, c.widthFor())
        assertEquals(2160, c.heightFor())
    }

    @Test
    fun `portrait 9x16 flips dimensions`() {
        val c = ExportConfig(Quality.Q1080, 30, AspectRatio.PORTRAIT_9_16)
        assertEquals(1080, c.widthFor())
        assertEquals(1920, c.heightFor())
    }

    @Test
    fun `square is 1080x1080 at 1080p`() {
        val c = ExportConfig(Quality.Q1080, 30, AspectRatio.SQUARE_1_1)
        assertEquals(1080, c.widthFor())
        assertEquals(1080, c.heightFor())
    }

    @Test
    fun `default export is 1080p 30fps`() {
        val c = ExportConfig()
        assertEquals(Quality.Q1080, c.quality)
        assertEquals(30, c.fps)
        assertEquals(AspectRatio.LANDSCAPE_16_9, c.aspect)
    }

    @Test
    fun `fps options are 24 30 60`() {
        assertEquals(listOf(24, 30, 60), ExportConfig.FPS_OPTIONS)
    }

    @Test
    fun `bitrate grows with quality`() {
        assertTrue(ExportConfig(Quality.Q720, 30).videoBitrate < ExportConfig(Quality.Q1080, 30).videoBitrate)
        assertTrue(ExportConfig(Quality.Q1080, 30).videoBitrate < ExportConfig(Quality.Q4K, 30).videoBitrate)
    }

    private fun assertTrue(cond: Boolean) {
        org.junit.Assert.assertTrue(cond)
    }

    @Test
    fun `color matrix is neutral by default`() {
        val m = Adjustments().toColorMatrix()
        // row-major 4x5: diagonal at 0, 6, 12, 18; translation column at 4, 9, 14
        assertEquals(1f, m[0], 1e-4f)
        assertEquals(1f, m[6], 1e-4f)
        assertEquals(1f, m[12], 1e-4f)
        assertEquals(1f, m[18], 1e-4f)
        assertEquals(0f, m[4], 1e-4f)
        assertTrue(!Adjustments().isNeutral().not())
        assertTrue(Adjustments(brightness = 10f).isNeutral().not())
    }

    @Test
    fun `brightness shifts the translation channel`() {
        val m = Adjustments(brightness = 50f).toColorMatrix()
        assertTrue(m[4] > 0f && m[9] > 0f && m[14] > 0f)
        val m2 = Adjustments(brightness = -50f).toColorMatrix()
        assertTrue(m2[4] < 0f)
    }
}
