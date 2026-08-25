package com.autoedit.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EncoderCapabilityLogicTest {

    @Test
    fun `1080p30 passes on a device that supports 1080p`() {
        val r = EncoderCapabilities.resolveRequest(1920, 1080, 30, maxDevWidth = 1920, fps60Supported = false)
        assertEquals(1920, r.width)
        assertEquals(1080, r.height)
        assertEquals(30, r.fps)
        assertNull(r.fallback)
    }

    @Test
    fun `4k is scaled down on a 1080p-only device`() {
        val r = EncoderCapabilities.resolveRequest(3840, 2160, 30, maxDevWidth = 1920, fps60Supported = false)
        assertTrue("width=${r.width}", r.width <= 1920)
        assertTrue("height=${r.height}", r.height <= 1080)
        assertTrue(r.fallback != null && r.fallback!!.contains("Resolution"))
    }

    @Test
    fun `scaled resolution stays even (encoders need even dimensions)`() {
        val r = EncoderCapabilities.resolveRequest(3840, 2160, 30, maxDevWidth = 1919, fps60Supported = false)
        assertEquals(0, r.width % 2)
        assertEquals(0, r.height % 2)
    }

    @Test
    fun `60 fps falls back to 30 when unsupported`() {
        val r = EncoderCapabilities.resolveRequest(1920, 1080, 60, maxDevWidth = 3840, fps60Supported = false)
        assertEquals(30, r.fps)
        assertTrue(r.fallback != null && r.fallback!!.contains("60 fps"))
    }

    @Test
    fun `60 fps is kept when supported`() {
        val r = EncoderCapabilities.resolveRequest(1920, 1080, 60, maxDevWidth = 3840, fps60Supported = true)
        assertEquals(60, r.fps)
        assertNull(r.fallback)
    }

    @Test
    fun `unknown device width is treated as 1080p-capable`() {
        val r = EncoderCapabilities.resolveRequest(1920, 1080, 30, maxDevWidth = 0, fps60Supported = false)
        assertEquals(1920, r.width)
        assertNull(r.fallback)
        // but 4K is still clamped when unknown
        val r2 = EncoderCapabilities.resolveRequest(3840, 2160, 30, maxDevWidth = 0, fps60Supported = false)
        assertTrue(r2.width <= 1920)
    }

    @Test
    fun `portrait keeps portrait after fallback`() {
        val r = EncoderCapabilities.resolveRequest(1080, 1920, 30, maxDevWidth = 1280, fps60Supported = false)
        assertTrue("w=${r.width} h=${r.height}", r.width < r.height)
        assertTrue(r.width <= 1280 && r.height <= 1280)
        assertEquals(0, r.width % 2)
        assertEquals(0, r.height % 2)
    }
}
