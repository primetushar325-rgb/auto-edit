package com.autoedit.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri

/**
 * Pull-style video frame decoder: decodes a trimmed segment
 * [inMs]..[outMs] of a video URI into Bitmaps sized for the export frame.
 *
 * - Uses MediaCodec + ImageReader (YUV_420_888) + a manual BT.601 YUV->RGB
 *   conversion, so it works on all minSdk-26 devices.
 * - [nextFrame] returns a frame whose presentation time is >= the requested
 *   time (holds the last frame after the segment ends - caller stops first).
 * - The returned Bitmap is REUSED: it is only valid until the next call.
 */
private const val TAG = "AutoEdit"

class VideoFrameDecoder(
    private val ctx: Context,
    private val uri: String,
    private val inMs: Long,
    private val outMs: Long,
    private val maxSide: Int
) {
    private var extractor: MediaExtractor? = null
    private var decoder: MediaCodec? = null
    private var reader: android.media.ImageReader? = null
    private var width = 0
    private var height = 0
    private var frame: Bitmap? = null
    private var pixels = IntArray(0)
    private var currentPtsUs = -1L
    private var inputDone = false
    private var outputEos = false
    private val info = MediaCodec.BufferInfo()

    /** Decoded frames arrive at the source aspect ratio, capped at [maxSide]. */
    fun size(): Pair<Int, Int> = width to height

    fun init() {
        val ext = MediaExtractor()
        val pfd = ctx.contentResolver.openFileDescriptor(Uri.parse(uri), "r")
            ?: throw Exception("Unable to open this video file.")
        try {
            ext.setDataSource(pfd.fileDescriptor)
        } finally {
            try { pfd.close() } catch (_: Exception) {}
        }
        var track = -1
        var mime = ""
        for (i in 0 until ext.trackCount) {
            val f = ext.getTrackFormat(i)
            val m = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (m.startsWith("video/")) {
                track = i
                mime = m
                break
            }
        }
        if (track < 0) {
            ext.release()
            throw Exception("No video track found in this file.")
        }
        ext.selectTrack(track)
        val fmt = ext.getTrackFormat(track)
        val srcW = fmt.getInteger(MediaFormat.KEY_WIDTH)
        val srcH = fmt.getInteger(MediaFormat.KEY_HEIGHT)
        if (srcW <= 0 || srcH <= 0) {
            ext.release()
            throw Exception("Unable to read video dimensions.")
        }
        val scale = (maxSide.toDouble() / maxOf(srcW, srcH)).coerceAtMost(2.0)
        width = (srcW * scale).toInt().coerceAtLeast(64)
        height = (srcH * scale).toInt().coerceAtLeast(64)

        val imgReader = android.media.ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 4)
        val dec = MediaCodec.createDecoderByType(mime)
        dec.configure(fmt, imgReader.surface, null, 0)
        dec.start()

        extractor = ext
        reader = imgReader
        decoder = dec
        frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        pixels = IntArray(width * height)

        try {
            ext.seekTo(inMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        } catch (_: Exception) {
        }
    }

    /**
     * Decode until we have a frame at or after [neededUs] (absolute time in the
     * source file), then return the reused frame bitmap.
     */
    fun nextFrame(neededUs: Long): Bitmap? {
        val dec = decoder ?: return null
        val imgReader = reader ?: return null
        val bmp = frame ?: return null
        var guard = 0
        while (currentPtsUs < neededUs && !outputEos && guard < 2000) {
            guard++
            // feed input up to the segment end
            if (!inputDone) {
                val inIdx = dec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf = dec.getInputBuffer(inIdx)
                    if (buf != null) {
                        buf.clear()
                        val n = extractor!!.readSampleData(buf, 0)
                        val t = extractor!!.sampleTime
                        if (n < 0 || t >= outMs * 1000L) {
                            buf.clear()
                            dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(inIdx, 0, n, t, 0)
                        }
                    }
                }
            }
            val outIdx = dec.dequeueOutputBuffer(info, 100_000)
            when {
                outIdx >= 0 -> {
                    val flags = info.flags
                    if (info.size > 0) {
                        val img = awaitImage(imgReader)
                        if (img != null) {
                            yuvToArgb(img, pixels)
                            bmp.setPixels(pixels, 0, width, 0, 0, width, height)
                            currentPtsUs = info.presentationTimeUs
                        }
                        try { img?.close() } catch (_: Exception) {}
                    }
                    dec.releaseOutputBuffer(outIdx, false)
                    if (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEos = true
                }
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // keep looping
                }
            }
        }
        return if (currentPtsUs >= 0) bmp else null
    }

    private fun awaitImage(reader: android.media.ImageReader): Image? {
        var img: Image? = null
        var guard = 0
        while (img == null && guard < 200) {
            guard++
            img = reader.acquireLatestImage()
            if (img == null) Thread.sleep(5)
        }
        return img
    }

    /** BT.601 YUV_420_888 -> ARGB into [out] (width*height ints). */
    private fun yuvToArgb(img: Image, out: IntArray) {
        val yPlane = img.planes[0]
        val uPlane = img.planes[1]
        val vPlane = img.planes[2]
        val yRow = yPlane.rowStride
        val uRow = uPlane.rowStride
        val vRow = vPlane.rowStride
        val uPix = uPlane.pixelStride
        val vPix = vPlane.pixelStride
        val yB = yPlane.buffer
        val uB = uPlane.buffer
        val vB = vPlane.buffer
        var p = 0
        for (y in 0 until height) {
            val yBase = y * yRow
            val uBase = y * uRow
            val vBase = y * vRow
            for (x in 0 until width) {
                val yv = yB.get(yBase + x).toInt() and 0xFF
                val uv = (x shr 1)
                val u = (uB.get(uBase + uv * uPix).toInt() and 0xFF) - 128
                val v = (vB.get(vBase + uv * vPix).toInt() and 0xFF) - 128
                val r = (yv + 1.402 * v).toInt().coerceIn(0, 255)
                val g = (yv - 0.344 * u - 0.714 * v).toInt().coerceIn(0, 255)
                val b = (yv + 1.772 * u).toInt().coerceIn(0, 255)
                if (p >= out.size) {
                    // safety net: plane/stride mismatch - stop filling, log once
                    android.util.Log.w(TAG, "yuvToArgb: pixel $p out of bounds (size=${out.size}) - clamping")
                    return
                }
                out[p++] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
            }
        }
    }

    fun release() {
        try { decoder?.stop() } catch (_: Exception) {}
        try { decoder?.release() } catch (_: Exception) {}
        try { extractor?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        decoder = null
        extractor = null
        reader = null
        frame?.recycle()
        frame = null
    }
}
