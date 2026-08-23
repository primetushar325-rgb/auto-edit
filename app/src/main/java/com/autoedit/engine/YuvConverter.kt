package com.autoedit.engine

/**
 * Pure YUV420_888 <-> ARGB conversion (BT.601), plane-stride aware.
 *
 * CORRECTNESS NOTE (root cause of the "index=1036800 out of bounds" crash):
 * chroma is subsampled 2x vertically. The U/V planes hold h/2 rows, not h
 * rows. Any writer that indexes chroma with the full-luma row offset
 * (yy * rowStride instead of (yy/2) * rowStride) overflows the U plane by
 * exactly its capacity: for 1920x1080 the U plane is 1920*540 = 1,036,800
 * bytes, so the first overflowing write lands at index 1,036,800 - the
 * exact field-reported index, identical across every project/duration.
 *
 * This converter handles both common plane layouts:
 *  - pixelStride == 1 (planar): chroma row = w/2 contiguous bytes
 *  - pixelStride == 2 (semi-planar): chroma samples interleaved, index = xx
 *
 * Bounds safety net: any index that would exceed a plane is clamped to the
 * last valid byte and counted; the caller logs the count and can abort with
 * a clear error instead of a mid-export crash.
 */
object YuvConverter {

    private fun cap(b: java.nio.ByteBuffer) = b.position() + b.limit()

    /**
     * RGB (ARGB int array) -> YUV420 planes (absolute ByteBuffer access, so
     * plane array offsets from MediaCodec input images are handled).
     * @return number of clamped writes (0 = layout matched perfectly).
     *         Returns -1 when the input/plane sizes are structurally invalid.
     */
    fun rgbToYuv420(
        rgb: IntArray,
        w: Int,
        h: Int,
        y: java.nio.ByteBuffer,
        yRow: Int,
        u: java.nio.ByteBuffer,
        uRow: Int,
        uPix: Int,
        v: java.nio.ByteBuffer,
        vRow: Int,
        vPix: Int
    ): Int {
        val yCap = cap(y); val uCap = cap(u); val vCap = cap(v)
        if (w <= 0 || h <= 0 || rgb.size < w * h) return -1
        if (yCap < (h - 1) * yRow + w) return -1
        var clamped = 0
        for (yy in 0 until h) {
            val cy = yy / 2
            val yBase = yy * yRow
            for (xx in 0 until w) {
                val p = rgb[yy * w + xx]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val yv = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)
                val uv = ((b - yv) * 0.564 + 128).toInt().coerceIn(0, 255)
                val vv = ((r - yv) * 0.713 + 128).toInt().coerceIn(0, 255)
                y.put(yBase + xx, yv.toByte())
                if (xx and 1 == 0) {
                    val uIdx = if (uPix == 1) cy * uRow + xx / 2 else cy * uRow + xx
                    val vIdx = if (vPix == 1) cy * vRow + xx / 2 else cy * vRow + xx
                    if (uIdx in 0 until uCap) u.put(uIdx, uv.toByte())
                    else { u.put(uCap - 1, uv.toByte()); clamped++ }
                    if (vIdx in 0 until vCap) v.put(vIdx, vv.toByte())
                    else { v.put(vCap - 1, vv.toByte()); clamped++ }
                }
            }
        }
        return clamped
    }

    /**
     * YUV420 planes -> ARGB int array.
     * @return number of clamped reads (0 = layout matched perfectly).
     *         Returns -1 when the input/plane sizes are structurally invalid.
     */
    fun yuv420ToArgb(
        y: java.nio.ByteBuffer,
        yRow: Int,
        u: java.nio.ByteBuffer,
        uRow: Int,
        uPix: Int,
        v: java.nio.ByteBuffer,
        vRow: Int,
        vPix: Int,
        w: Int,
        h: Int,
        out: IntArray
    ): Int {
        val yCap = cap(y); val uCap = cap(u); val vCap = cap(v)
        if (w <= 0 || h <= 0 || out.size < w * h) return -1
        if (yCap < (h - 1) * yRow + w) return -1
        var clamped = 0
        for (yy in 0 until h) {
            val cy = yy / 2
            val yBase = yy * yRow
            for (xx in 0 until w) {
                val yv = (y.get(yBase + xx).toInt()) and 0xFF
                // 4:2:0 - every pixel takes the chroma sample of its 2x2 block
                val uIdx = if (uPix == 1) cy * uRow + xx / 2 else cy * uRow + xx
                val vIdx = if (vPix == 1) cy * vRow + xx / 2 else cy * vRow + xx
                val uVal = (if (uIdx in 0 until uCap) u.get(uIdx).toInt() else { u.get(uCap - 1).toInt(); clamped++ }) and 0xFF
                val vVal = (if (vIdx in 0 until vCap) v.get(vIdx).toInt() else { v.get(vCap - 1).toInt(); clamped++ }) and 0xFF
                val r = (yv + 1.402 * (vVal - 128)).toInt().coerceIn(0, 255)
                val g = (yv - 0.344 * (uVal - 128) - 0.714 * (vVal - 128)).toInt().coerceIn(0, 255)
                val b = (yv + 1.772 * (uVal - 128)).toInt().coerceIn(0, 255)
                out[yy * w + xx] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
            }
        }
        return clamped
    }

    /** Absolute index of the last luma byte for a plane with these strides. */
    fun maxLumaIndex(w: Int, h: Int, rowStride: Int): Int = (h - 1) * rowStride + w - 1

    /** Minimum byte count a plane of these strides must hold (luma). */
    fun minLumaSize(w: Int, h: Int, rowStride: Int): Int = (h - 1) * rowStride + w

    /** Minimum byte count a chroma plane of these strides must hold
     *  (h/2 stride-aligned rows - what encoders actually allocate). */
    fun minChromaSize(w: Int, h: Int, rowStride: Int, pixelStride: Int): Int {
        val chromaH = h / 2
        if (chromaH == 0) return 0
        return chromaH * rowStride
    }
}
