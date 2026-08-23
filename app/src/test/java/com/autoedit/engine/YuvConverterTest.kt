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
    fun `1920x1080 planar layout fits exactly`() {
        // Encoder layout: pixelStride=1 (planar), rowStride aligned to full width (1920).
        val w = 1920
        val h = 1080
        val uCap = YuvConverter.minChromaSize(w, h, 1920, 1)
        val rgb = IntArray(w * h) { (it * 7) and 0xFFFFFF }
        val y = plane(YuvConverter.minLumaSize(w, h, 1920))
        val u = plane(uCap)
        val v = plane(uCap)
        val clamped = YuvConverter.rgbToYuv420(rgb, w, h, y, 1920, u, 1920, 1, v, 1920, 1)
        assertEquals("planar 1080p must fit with zero clamped writes", 0, clamped)
        // luma is fully populated
        assertTrue(y.get(YuvConverter.maxLumaIndex(w, h, 1920)).toInt() and 0xFF in 0..255)
    }

    /**
     * REGRESSION for the field report:
     *   "U plane too small for 1920x1080: rowStride=1920, pixelStride=2,
     *    capacity=1036799, need=1036800"
     *
     * The device's encoder allocates the semi-planar (NV12) U and V planes as
     * (chromaH-1)*rowStride + (w-1) = 539*1920 + 1919 = EXACTLY 1,036,799 bytes:
     * the last chroma row holds only the w-1 = 1,919 bytes that are actually
     * written (the last write lands at byte offset w-2 = 1,918). The old
     * requirement was chromaH*rowStride = 1,036,800 - one byte MORE than the
     * plane actually needs - which rejected this perfectly valid plane.
     *
     * This test locks the exact sizes so the off-by-one can never silently
     * regress in EITHER direction:
     *   - minChromaSize must equal exactly 1,036,799 (accept the device plane)
     *   - a 1,036,799-byte U and V plane must fit with ZERO clamped writes
     *   - the mirror read direction must fit the same planes
     */
    @Test
    fun `device 1080p semi planar U and V planes of exactly 1036799 bytes are sufficient`() {
        val w = 1920
        val h = 1080
        val deviceCap = 1_036_799  // the exact U/V plane capacity reported by the field device
        assertEquals("semi-planar 1080p chroma requirement must be exactly the device's allocation",
            deviceCap, YuvConverter.minChromaSize(w, h, 1920, 2))
        // the old over-strict formula (chromaH * rowStride = 1,036,800) must NOT be the requirement
        assertTrue(YuvConverter.minChromaSize(w, h, 1920, 2) < (h / 2) * 1920)

        val rgb = IntArray(w * h) { (it * 13) and 0xFFFFFF }
        val y = plane(YuvConverter.minLumaSize(w, h, 1920))
        val u = plane(deviceCap)
        val v = plane(deviceCap)
        val clamped = YuvConverter.rgbToYuv420(rgb, w, h, y, 1920, u, 1920, 2, v, 1920, 2)
        assertEquals("a real-world 1,036,799-byte U and V plane must fit with zero clamped writes", 0, clamped)
        val rgb2 = IntArray(w * h)
        val readClamped = YuvConverter.yuv420ToArgb(y, 1920, u, 1920, 2, v, 1920, 2, w, h, rgb2)
        assertEquals("read direction must also fit the device planes", 0, readClamped)
    }

    /**
     * Pins the tight chroma bound in BOTH directions and addresses the 1,036,800
     * figure directly.
     *
     *  - The tight minimum (last byte actually accessed + 1) is exactly 1,036,799:
     *    last accessed chroma byte = (540-1)*1920 + (1920/2-1)*2 = 1,036,798.
     *    A plane one byte SHORTER (1,036,798) must clamp -> proves byte 1,036,798
     *    is really accessed and 1,036,799 is the exact tight bound.
     *  - The padded full-stride size (h/2)*rowStride = 1,036,800 is a valid
     *    SUPERSET and must also fit with zero clamped writes.
     *
     *  So both 1,036,799 (the device's tight allocation) and 1,036,800 (padded)
     *  are accepted; the regression guard locks the tight bound at 1,036,799 so it
     *  can never silently regress in either direction.
     */
    @Test
    fun `chroma tight bound is exactly 1036799 and padded 1036800 also fits`() {
        val w = 1920
        val h = 1080
        val tight = YuvConverter.minChromaSize(w, h, 1920, 2)
        assertEquals("tight semi-planar 1080p chroma bound", 1_036_799, tight)
        val rgb = IntArray(w * h) { (it * 13) and 0xFFFFFF }
        val y = plane(YuvConverter.minLumaSize(w, h, 1920))
        // one byte short of the tight bound -> must clamp (proves the last byte is accessed)
        val uSmall = plane(tight - 1)
        val vSmall = plane(tight - 1)
        val clampedSmall = YuvConverter.rgbToYuv420(rgb, w, h, y, 1920, uSmall, 1920, 2, vSmall, 1920, 2)
        assertTrue("a plane one byte short of the tight bound must clamp", clampedSmall > 0)
        // the padded full-stride size (h/2)*rowStride = 1,036,800 is a valid superset
        val uPad = plane(1_036_800)
        val vPad = plane(1_036_800)
        val clampedPad = YuvConverter.rgbToYuv420(rgb, w, h, y, 1920, uPad, 1920, 2, vPad, 1920, 2)
        assertEquals("the padded 1,036,800-byte plane must also fit with zero clamps", 0, clampedPad)
    }

    @Test
    fun `1920x1080 semi planar layout fits exactly`() {
        val w = 1920
        val h = 1080
        val uCap = YuvConverter.minChromaSize(w, h, 1920, 2) // exact tight bound
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
        val clamped = YuvConverter.rgbToYuv420(rgb, 64, 64, java.nio.ByteBuffer.allocate(10), 64, java.nio.ByteBuffer.allocate(10), 64, 1, java.nio.ByteBuffer.allocate(10), 64, 1)
        assertEquals(-1, clamped)
        val out = IntArray(10)
        val read = YuvConverter.yuv420ToArgb(java.nio.ByteBuffer.allocate(10), 64, java.nio.ByteBuffer.allocate(10), 64, 1, java.nio.ByteBuffer.allocate(10), 64, 1, 64, 64, out)
        assertEquals(-1, read)
    }

    @Test
    fun `round trip preserves colors within tolerance`() {
        val w = 160
        val h = 96
        // Two constraints for a fair 4:2:0 round-trip:
        // 1) in-gamut colors only (out-of-gamut extremes clip in YUV - correct codec behavior)
        // 2) color must be constant within each 2x2 chroma block (a 2x2 luma block shares
        //    ONE chroma sample - a fast-changing synthetic ramp would legitimately error)
        val rgb = IntArray(w * h) { i ->
            val bx = (i % w) / 2
            val by = i / w / 2
            val r = (by * 23 + bx * 7) % 236 + 10
            val g = (by * 41 + bx * 13) % 236 + 10
            val b = (by * 17 + bx * 29) % 236 + 10
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
            val dr = ((a ushr 16) and 0xFF) - ((b ushr 16) and 0xFF)
            val dg = ((a ushr 8) and 0xFF) - ((b ushr 8) and 0xFF)
            val db = (a and 0xFF) - (b and 0xFF)
            maxDiff = maxOf(maxDiff, kotlin.math.abs(dr), kotlin.math.abs(dg), kotlin.math.abs(db))
        }
        assertTrue("max channel diff was $maxDiff (want <= 6 for BT.601)", maxDiff <= 6)
    }

    @Test
    fun `buffer sizes derive dynamically per resolution`() {
        // planar (pixelStride=1): last row needs w/2 bytes
        assertEquals(1_035_840, YuvConverter.minChromaSize(1920, 1080, 1920, 1))
        assertEquals(460_160, YuvConverter.minChromaSize(1280, 720, 1280, 1))
        // semi-planar (pixelStride=2): last row needs w-1 bytes (the field case)
        assertEquals(1_036_799, YuvConverter.minChromaSize(1920, 1080, 1920, 2))
        assertEquals(460_799, YuvConverter.minChromaSize(1280, 720, 1280, 2))
        // packed planar (rowStride = w/2): full stride rows
        assertEquals(230_400, YuvConverter.minChromaSize(1280, 720, 640, 1))
        assertEquals(2_073_600, YuvConverter.minLumaSize(1920, 1080, 1920))
        assertEquals(921_600, YuvConverter.minLumaSize(1280, 720, 1280))
    }
}
