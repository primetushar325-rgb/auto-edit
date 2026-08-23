package com.autoedit.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.autoedit.engine.YuvConverter

private const val TAG = "AutoEdit"

/**
 * Pull-style video frame decoder for inserted video clips:
 * MediaCodec + ImageReader (YUV_420_888) + stride-correct YuvConverter.
 *
 * The returned Bitmap is REUSED by this decoder - it is only valid until the
 * next call on the same instance.
 */
class VideoFrameDecoder(
    private val ctx: Context,
    private val uriOrPath: String,
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
    private var clampWarned = false
    /** Keeps the fd (file channel / pfd) alive for the whole decode. */
    private var fdHolder: Any? = null

    fun size(): Pair<Int, Int> = width to height

    fun init() {
        val ext = MediaExtractor()
        val fd: java.io.FileDescriptor = if (ProjectStorage.isPath(uriOrPath)) {
            val f = java.io.File(uriOrPath)
            if (!f.exists()) throw Exception("Video file is missing: ${f.absolutePath}")
            val ch = FileChannel.open(f.toPath(), java.nio.file.StandardOpenOption.READ)
            fdHolder = ch
            ch.fd
        } else {
            val pfd = ctx.contentResolver.openFileDescriptor(android.net.Uri.parse(uriOrPath), "r")
                ?: throw Exception("Unable to open this video file.")
            fdHolder = pfd
            pfd.fd
        }
        try {
            ext.setDataSource(fd)
        } catch (e: Exception) {
            releaseFd()
            throw Exception("Unable to read this video file: ${e.message ?: "unknown error"}")
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
            runCatching { ext.release() }
            throw Exception("No video track found in this file.")
        }
        ext.selectTrack(track)
        val fmt = ext.getTrackFormat(track)
        val srcW = fmt.getInteger(MediaFormat.KEY_WIDTH)
        val srcH = fmt.getInteger(MediaFormat.KEY_HEIGHT)
        if (srcW <= 0 || srcH <= 0) {
            runCatching { ext.release() }
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
        runCatching { ext.seekTo(inMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC) }
    }

    private fun releaseFd() {
        val h = fdHolder
        fdHolder = null
        if (h != null) {
            runCatching {
                when (h) {
                    is FileChannel -> h.close()
                    is android.content.res.AssetFileDescriptor -> h.close()
                }
            }.onFailure { Log.w(TAG, "fd close failed", it) }
        }
    }

    /** Decode until we have a frame at or after [neededUs], return the reused frame. */
    fun nextFrame(neededUs: Long): Bitmap? {
        val dec = decoder ?: return null
        val imgReader = reader ?: return null
        val bmp = frame ?: return null
        var guard = 0
        while (currentPtsUs < neededUs && !outputEos && guard < 2000) {
            guard++
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
                            try {
                                yuvToArgb(img)
                                currentPtsUs = info.presentationTimeUs
                            } finally {
                                runCatching { img.close() }
                            }
                        }
                    }
                    dec.releaseOutputBuffer(outIdx, false)
                    if (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEos = true
                }
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
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
            if (img == null) runCatching { Thread.sleep(5) }
        }
        return img
    }

    /** Stride-correct, bounds-safe YUV420 -> ARGB (chroma is h/2 rows, not h). */
    private fun yuvToArgb(img: Image) {
        val w = width
        val h = height
        val out = pixels
        val yp = img.planes[0]
        val up = img.planes[1]
        val vp = img.planes[2]
        val yRow = yp.rowStride
        val uRow = up.rowStride
        val vRow = vp.rowStride
        val uPix = up.pixelStride
        val vPix = vp.pixelStride
        val yNeed = YuvConverter.minLumaSize(w, h, yRow)
        val uNeed = YuvConverter.minChromaSize(w, h, uRow, uPix)
        val vNeed = YuvConverter.minChromaSize(w, h, vRow, vPix)
        val yCap = yp.buffer.position() + yp.buffer.limit()
        val uCap = up.buffer.position() + up.buffer.limit()
        val vCap = vp.buffer.position() + vp.buffer.limit()
        if (yCap < yNeed || uCap < uNeed || vCap < vNeed) {
            Log.e(TAG, "video frame planes too small for ${w}x$h: y=$yCap/$yNeed u=$uCap/$uNeed v=$vCap/$vNeed")
            return
        }
        val clamped = YuvConverter.yuv420ToArgb(
            yp.buffer, yRow,
            up.buffer, uRow, uPix,
            vp.buffer, vRow, vPix,
            w, h, out
        )
        if (clamped > 0 && !clampWarned) {
            Log.w(TAG, "video YUV read: $clamped reads clamped to plane bounds - frame may be corrupted")
            clampWarned = true
        }
        frame?.setPixels(out, 0, w, 0, 0, w, h)
    }

    fun release() {
        runCatching { decoder?.stop() }.onFailure { Log.w(TAG, "decoder stop failed", it) }
        runCatching { decoder?.release() }.onFailure { Log.w(TAG, "decoder release failed", it) }
        runCatching { extractor?.release() }.onFailure { Log.w(TAG, "extractor release failed", it) }
        runCatching { reader?.close() }.onFailure { Log.w(TAG, "image reader close failed", it) }
        decoder = null
        extractor = null
        reader = null
        runCatching { frame?.recycle() }.onFailure { Log.w(TAG, "frame recycle failed", it) }
        frame = null
        releaseFd()
    }
}
