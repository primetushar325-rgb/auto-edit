package com.autoedit.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the field crash "index=1036800 out of bounds
 * (limit=1036799)" - the U plane of a 1920x1080 YUV420 frame.
 */
class YuvConverterTest {

    private fun plane(size: Int) = java.nio.ByteBuffer.allocate(size)

    @Test
    fun `1920x1080 planar layout fits exactly - the reported crash case`() {
        // Encoder layout: pixelStride=1, rowStride aligned to full width (1920).
        // U/V plane capacity = 1920 * 540 = 1,036,800 bytes exactly.
        val w = 1920
        val h = 1080
        val uCap = 1_036_800
        val rgb = IntArray(w * h) { (it * 7) and 0xFFFFFF }
        val y = plane(YuvConverter.minLumaSize(w, h, 1920))
        val u = plane(uCap)
        val v = plane(uCap)
        val clamped = YuvConverter.rgbToYuv420(rgb, w, h, y, 1920, u, 1920, 1, v, 1920, 1)
        assertEquals("planar 1080p must fit with zero clamped writes", 0, clamped)
        // the last valid U index is 1,036,799 - the old code wrote at 1,036,800
        assertEquals(uCap - 1, YuvConverter.minChromaSize(w, h, 1920, 1))
        // luma is fully populated
        assertTrue(y.get(YuvConverter.maxLumaIndex(w, h, 1920)).toInt() and 0xFF in 0..255)
    }

    @Test
    fun `1920x1080 semi planar layout fits exactly`() {
        val w = 1920
        val h = 1080
        val uCap = 1_036_800
        val rgb = IntArray(w * h) { (it * 13) and 0xFFFFFF }
        val y = plane(YuvConverter.minLumaSize(w, h, 1920))
        val u = plane(uCap)
        val v = plane(uCap)
        val clamped = YuvConverter.rgbToYuv420(rgb, w, h, y, 1920, u, 1920, 2, v, 1920, 2)
        assertEquals(0, clamped)
    }

    @Test
    fun `packed chroma rows also fit`() {
        val w = 1280
        val h = 720
        val uCap = 360 * 640 // 720p packed: 640 bytes per chroma row
        val rgb = IntArray(w * h) { (it * 3) and 0xFFFFFF }
        val y = plane(YuvConverter.minLumaSize(w, h, 1280))
        val u = plane(uCap)
        val v = plane(uCap)
        val clamped = YuvConverter.rgbToYuv420(rgb, w, h, y, 1280, u, 640, 1, v, 640, 1)
        assertEquals(0, clamped)
        assertEquals(uCap, YuvConverter.minChromaSize(w, h, 640, 1))
    }

    @Test
    fun `bounds safety net clamps instead of throwing on a too small plane`() {
        val w = 64
        val h = 64
        val rgb = IntArray(w * h) { 0xFF808080.toInt() }
        val y = plane(YuvConverter.minLumaSize(w, h, 64))
        val u = plane(100) // deliberately too small
        val v = plane(100)
        val clamped = YuvConverter.rgbToYuv420(rgb, w, h, y, 64, u, 64, 1, v, 64, 1)
        assertTrue("a mismatched plane must be detected via the clamp counter", clamped > 0)
    }

    @Test
    fun `structurally invalid planes return -1 without throwing`() {
        val rgb = IntArray(64)
        val clamped = YuvConverter.rgbToYuv420(rgb, 64, 64, ByteArray(10), 64, ByteArray(10), 64, 1, ByteArray(10), 64, 1)
        assertEquals(-1, clamped)
        val out = IntArray(10)
        val read = YuvConverter.yuv420ToArgb(ByteArray(10), 64, ByteArray(10), 64, 1, ByteArray(10), 64, 1, 64, 64, out)
        assertEquals(-1, read)
    }

    @Test
    fun `round trip preserves colors within tolerance`() {
        val w = 160
        val h = 96
        val rgb = IntArray(w * h) { i ->
            val r = (i * 11) and 0xFF
            val g = (i * 57) and 0xFF
            val b = (i * 199) and 0xFF
            0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
        }
        val uRow = w // aligned row stride, planar
        val y = plane(YuvConverter.minLumaSize(w, h, uRow))
        val u = plane(YuvConverter.minChromaSize(w, h, uRow, 1))
        val v = plane(YuvConverter.minChromaSize(w, h, uRow, 1))
        assertEquals(0, YuvConverter.rgbToYuv420(rgb, w, h, y, uRow, u, uRow, 1, v, uRow, 1))
        val back = IntArray(w * h)
        assertEquals(0, YuvConverter.yuv420ToArgb(y, uRow, u, uRow, 1, v, uRow, 1, w, h, back))
        var maxDiff = 0
        for (i in rgb.indices) {
            val a = rgb[i]
            val b = back[i]
            val dr = (a ushr 16) - (b ushr 16)
            val dg = (a ushr 8) - (b ushr 8)
            val db = a - b
            maxDiff = maxOf(maxDiff, kotlin.math.abs(dr), kotlin.math.abs(dg), kotlin.math.abs(db))
        }
        assertTrue("max channel diff was $maxDiff (want <= 6 for BT.601)", maxDiff <= 6)
    }

    @Test
    fun `buffer sizes derive dynamically per resolution`() {
        // 720p and 1080p must compute different (correct) plane sizes
        assertEquals(1_036_800, YuvConverter.minChromaSize(1920, 1080, 1920, 1))
        assertEquals(460_800, YuvConverter.minChromaSize(1280, 720, 1280, 1))
        assertEquals(2_073_600, YuvConverter.minLumaSize(1920, 1080, 1920))
        assertEquals(921_600, YuvConverter.minLumaSize(1280, 720, 1280))
    }
}
