package com.autoedit.media

import android.content.Context
import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log

private const val TAG = "AutoEdit"

/**
 * One decoded video frame as RAW YUV_420_888 planes.
 *
 * The [image] is owned by the decoder: it stays valid until the NEXT
 * [VideoFrameDecoder.nextFrame] call on the same decoder (or [VideoFrameDecoder.release]).
 * The GPU renderer uploads the planes to textures immediately, in the same
 * frame - no CPU YUV->RGB conversion anywhere in the export path.
 */
data class YuvFrame(val image: Image, val width: Int, val height: Int)

/**
 * Pull-style video frame decoder for inserted video clips:
 * MediaExtractor -> MediaCodec -> ImageReader (YUV_420_888).
 *
 * The decoder is configured to output at a reduced size (max [maxSide] on the
 * long side) so a 4K source clip never floods the GPU with full-res planes.
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
    private var heldImage: Image? = null
    private var currentPtsUs = -1L
    private var inputDone = false
    private var outputEos = false
    private val info = MediaCodec.BufferInfo()
    /** Keeps the fd (file channel / pfd) alive for the whole decode. */
    private var fdHolder: Any? = null

    fun size(): Pair<Int, Int> = width to height

    fun init() {
        val ext = MediaExtractor()
        try {
            if (ProjectStorage.isPath(uriOrPath)) {
                if (!java.io.File(uriOrPath).exists()) throw Exception("Video file is missing: $uriOrPath")
                ext.setDataSource(uriOrPath)
            } else {
                val pfd = ctx.contentResolver.openFileDescriptor(android.net.Uri.parse(uriOrPath), "r")
                    ?: throw Exception("Unable to open this video file.")
                fdHolder = pfd
                ext.setDataSource(pfd as android.content.res.AssetFileDescriptor)
            }
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
        width = (srcW * scale).toInt().coerceAtLeast(64).let { if (it % 2 != 0) it - 1 else it }
        height = (srcH * scale).toInt().coerceAtLeast(64).let { if (it % 2 != 0) it - 1 else it }

        val imgReader = android.media.ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 4)
        val dec = MediaCodec.createDecoderByType(mime)
        dec.configure(fmt, imgReader.surface, null, 0)
        dec.start()

        extractor = ext
        reader = imgReader
        decoder = dec
        runCatching { ext.seekTo(inMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC) }
        Log.v(TAG, "video clip decoder ready: ${mime} ${srcW}x$srcH -> ${width}x$height (GPU YUV planes)")
    }

    private fun releaseFd() {
        val h = fdHolder
        fdHolder = null
        if (h != null) {
            runCatching {
                (h as? android.os.ParcelFileDescriptor)?.close()
            }.onFailure { Log.w(TAG, "fd close failed", it) }
        }
    }

    /**
     * Decode until we have a frame at or after [neededUs]. Returns the frame
     * whose planes the GPU renderer must upload NOW (the Image is released
     * when the next frame is acquired or on release()).
     */
    fun nextFrame(neededUs: Long): YuvFrame? {
        val dec = decoder ?: return null
        val imgReader = reader ?: return null
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
                            // release the previous frame - the GPU already uploaded it
                            runCatching { heldImage?.close() }
                            heldImage = img
                            currentPtsUs = info.presentationTimeUs
                        }
                    }
                    dec.releaseOutputBuffer(outIdx, false)
                    if (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEos = true
                }
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
            }
        }
        val held = heldImage ?: return null
        return if (currentPtsUs >= 0) YuvFrame(held, width, height) else null
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

    fun release() {
        runCatching { decoder?.stop() }.onFailure { Log.w(TAG, "decoder stop failed", it) }
        runCatching { decoder?.release() }.onFailure { Log.w(TAG, "decoder release failed", it) }
        runCatching { extractor?.release() }.onFailure { Log.w(TAG, "extractor release failed", it) }
        runCatching { reader?.close() }.onFailure { Log.w(TAG, "image reader close failed", it) }
        runCatching { heldImage?.close() }.onFailure { Log.w(TAG, "held image close failed", it) }
        decoder = null
        extractor = null
        reader = null
        heldImage = null
        releaseFd()
    }
}
