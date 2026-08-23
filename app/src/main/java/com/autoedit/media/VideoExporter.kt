package com.autoedit.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.util.Log
import com.autoedit.engine.AudioDsp
import com.autoedit.engine.ClipType
import com.autoedit.engine.EasingType
import com.autoedit.engine.FormulaCatalog
import com.autoedit.engine.ProjectModel
import com.autoedit.engine.TimelineMath
import com.autoedit.engine.YuvConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VideoExporter(private val ctx: Context) {

    class ExportCancelled : Exception("cancelled")

    /** Export failure with a stage + a human-readable, specific message. */
    class ExportException(val stage: String, message: String, cause: Throwable? = null) :
        Exception(message, cause)

    data class ExportResult(val file: File, val mediaStoreUri: Uri?)

    private companion object {
        const val TAG = "AutoEdit"
        const val OUTPUT_FORMAT_MP4 = 2 // MediaCodec.OUTPUT_FORMAT_MP4
    }

    private fun rootMsg(e: Throwable): String {
        val parts = ArrayList<String>()
        var cur: Throwable? = e
        var depth = 0
        while (cur != null && depth < 3) {
            val m = cur.message
            if (!m.isNullOrBlank()) parts.add(m)
            cur = cur.cause
            depth++
        }
        return if (parts.isEmpty()) e.javaClass.simpleName else parts.joinToString(" -> ")
    }

    /**
     * Full export pipeline (runs on Dispatchers.Default via the caller):
     *  1. decode + mix audio, trimmed to the EXACT video sample count
     *  2. AAC-encode the mix into a small temp file inside the project's temp/
     *  3. ONE MediaMuxer session on the final .mp4 (inside the project's
     *     export/ folder): audio track added first (real format from the
     *     encoded temp), video track added when the encoder reports its
     *     format, then video frames are streamed in live while audio
     *     samples are interleaved by PTS. No separate video-remux step.
     *  4. best-effort copy of the final file to MediaStore (Movies/Auto Edit)
     *  5. temp/ is always cleaned (success or failure); final file removed
     *     on failure
     */
    suspend fun export(
        project: ProjectModel,
        tempDir: File,
        exportDir: File,
        isCancelled: () -> Boolean = { false },
        onProgress: suspend (Float, String) -> Unit
    ): Result<ExportResult> {
        var success = false
        var finalFile: File? = null
        var audioExtractor: MediaExtractor? = null
        try {
            if (!tempDir.exists() && !tempDir.mkdirs()) {
                throw ExportException("prepare", "Could not create temp folder: ${tempDir.absolutePath}")
            }
            if (!exportDir.exists() && !exportDir.mkdirs()) {
                throw ExportException("prepare", "Could not create export folder: ${exportDir.absolutePath}")
            }
            val p = project
            val durations = p.clipDurations()
            val total = durations.sumOf { it.coerceAtLeast(0.0) }
            if (total <= 0.0 || p.clips.isEmpty()) {
                throw ExportException("prepare", "Add images before exporting.")
            }
            val w = p.export.widthFor(p.aspect)
            val h = p.export.heightFor(p.aspect)
            val fps = p.export.fps
            val frames = (total * fps).toInt().coerceAtLeast(1)
            val easing = FormulaCatalog.byId(p.formulaId)?.easing ?: EasingType.EASE_IN_OUT
            Log.i(TAG, "export start: ${frames} frames, ${w}x$h@${fps}, total=${"%.2f".format(total)}s")

            // ------------------------------------------------ 1) audio
            var voicePcm: AudioDsp.PcmAudio? = null
            var musicPcm: AudioDsp.PcmAudio? = null
            val voiceCfg = p.voice
            val musicCfg = p.music
            if (voiceCfg != null) {
                onProgress(0.02f, "Loading voice…")
                try {
                    voicePcm = AudioDecoder.decode(ctx, voiceCfg.uri, total + voiceCfg.offsetSec + 2.0, isCancelled)
                } catch (e: ExportCancelled) { throw e }
                catch (e: Exception) {
                    Log.e(TAG, "voice decode failed", e)
                    throw ExportException("audio", "Voice audio could not be decoded: ${rootMsg(e)}", e)
                }
            }
            if (musicCfg != null) {
                onProgress(0.05f, "Loading music…")
                try {
                    musicPcm = AudioDecoder.decode(ctx, musicCfg.uri, total + 2.0, isCancelled)
                } catch (e: ExportCancelled) { throw e }
                catch (e: Exception) {
                    Log.e(TAG, "music decode failed", e)
                    throw ExportException("audio", "Music audio could not be decoded: ${rootMsg(e)}", e)
                }
            }
            var mixed: ShortArray? = null
            val aacTemp: File?
            if (voicePcm != null || musicPcm != null) {
                onProgress(0.08f, "Mixing audio…")
                var m = AudioDsp.mix(total, voicePcm, voiceCfg, musicPcm, musicCfg, p.duckMusic)
                // trim/pad to EXACTLY the video sample count (Long math)
                m = AudioDsp.toExactLength(m, AudioDsp.exactSampleCount(total))
                mixed = m
                aacTemp = File(tempDir, "audio.aac")
                onProgress(0.10f, "Encoding audio…")
                try {
                    encodeAac(mixed, aacTemp)
                } catch (e: ExportException) { throw e }
                catch (e: Exception) {
                    Log.e(TAG, "aac encode failed", e)
                    throw ExportException("audio", "Audio encoding failed: ${rootMsg(e)}", e)
                }
                val ext = MediaExtractor().apply {
                    try { setDataSource(aacTemp.absolutePath) }
                    catch (e: Exception) {
                        Log.e(TAG, "aac temp unreadable", e)
                        throw ExportException("audio", "Encoded audio temp file is unreadable: ${rootMsg(e)}", e)
                    }
                }
                audioExtractor = ext
            } else {
                aacTemp = null
            }

            // ------------------------------------------------ 2) video + mux (single session)
            val stamp = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(Date())
            val safeName = p.name.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().ifBlank { "Project" }
            finalFile = File(exportDir, "Auto Edit - $safeName - $stamp.mp4")
            onProgress(0.12f, "Rendering frames…")
            try {
                encodeVideo(
                    p, frames, w, h, fps, finalFile,
                    aacTemp, audioExtractor,
                    easing, isCancelled
                ) { frac ->
                    onProgress(0.12f + frac * 0.8f, "Rendering… ${(frac * 100).toInt()}%")
                }
            } catch (e: ExportCancelled) { throw e }
            catch (e: ExportException) { throw e }
            catch (e: Exception) {
                Log.e(TAG, "video encode failed", e)
                throw ExportException("video", "Video rendering failed: ${rootMsg(e)}", e)
            }

            // ------------------------------------------------ 3) media store copy
            onProgress(0.97f, "Saving…")
            val store = copyToMediaStore(p, finalFile)
            onProgress(1f, "Done")
            success = true
            return Result.success(ExportResult(finalFile, store))
        } catch (e: ExportCancelled) {
            onProgress(0f, "Cancelled")
            return Result.failure(e)
        } catch (e: InterruptedException) {
            onProgress(0f, "Cancelled")
            return Result.failure(ExportCancelled())
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "export OOM", e)
            onProgress(0f, "Out of memory")
            return Result.failure(e)
        } catch (e: ExportException) {
            Log.e(TAG, "export failed at stage=${e.stage}", e)
            onProgress(0f, "Failed")
            return Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "export failed", e)
            onProgress(0f, "Failed")
            return Result.failure(ExportException("export", "Export failed: ${rootMsg(e)}", e))
        } finally {
            try { audioExtractor?.release() } catch (e: Exception) { Log.w(TAG, "audio extractor release failed", e) }
            // temp/ is always cleaned, success or failure
            val cleanOk = runCatching { tempDir.deleteRecursively() }.isSuccess
            if (!cleanOk) Log.w(TAG, "could not clean temp dir ${tempDir.absolutePath}")
            if (!success) {
                val f = finalFile
                if (f != null && f.exists() && !f.delete()) {
                    Log.w(TAG, "could not delete partial export ${f.absolutePath}")
                }
            }
        }
    }

    // ------------------------------------------------------------- video

    private fun encodeVideo(
        p: ProjectModel,
        frames: Int,
        w: Int,
        h: Int,
        fps: Int,
        finalFile: File,
        aacTemp: File?,
        audioExtractor: MediaExtractor?,
        easing: EasingType,
        isCancelled: () -> Boolean,
        onFrame: (Float) -> Unit
    ) {
        // Fresh buffers for THIS export only - never reused across projects.
        val frameBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        val canvas = Canvas(frameBmp)
        val renderer = ExportRenderer(w, h, p.adjustments, easing)
        val cache = ClipImageCache(ctx, w, h)
        val videoDecoders = HashMap<Int, VideoFrameDecoder>()
        val encoder = MediaCodec.createEncoderByType("video/avc")
        val muxer = MediaMuxer(finalFile.absolutePath, OUTPUT_FORMAT_MP4)
        val info = MediaCodec.BufferInfo()
        val aInfo = MediaCodec.BufferInfo()
        val audioBuf = ByteArray(256 * 1024)
        var videoTrack = -1
        var audioTrack = -1
        var muxerStarted = false
        var audioDone = audioExtractor == null
        var yuvWarned = false
        try {
            val fmt = MediaFormat.createVideoFormat("video/avc", w, h).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, p.export.videoBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            }
            encoder.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            if (audioExtractor != null && audioExtractor.trackCount > 0) {
                audioExtractor.selectTrack(0)
                audioTrack = muxer.addTrack(audioExtractor.getTrackFormat(0))
            }

            val durations = p.clipDurations()

            fun ensureStarted() {
                if (!muxerStarted) {
                    muxer.start()
                    muxerStarted = true
                }
            }

            fun noteVideoFormat() {
                if (videoTrack < 0) {
                    videoTrack = muxer.addTrack(encoder.outputFormat)
                }
                ensureStarted()
            }

            fun drainAudioUpTo(frameEndUs: Long) {
                if (audioDone || audioTrack < 0 || audioExtractor == null) return
                while (true) {
                    val t = audioExtractor.sampleTime
                    if (t >= frameEndUs) break
                    val size = audioExtractor.readSampleData(audioBuf, 0)
                    if (size < 0) { audioDone = true; break }
                    aInfo.size = size
                    aInfo.offset = 0
                    aInfo.presentationTimeUs = audioExtractor.sampleTime
                    aInfo.flags = audioExtractor.sampleFlags
                    ensureStarted()
                    muxer.writeSampleData(audioTrack, audioBuf, aInfo)
                }
            }

            for (i in 0 until frames) {
                if (isCancelled() || Thread.currentThread().isInterrupted) throw ExportCancelled()
                val t = i / fps.toDouble()
                val state = TimelineMath.frameAt(t, durations, p.transitionDurationSec)
                cache.prepare(state, p)
                canvas.drawColor(Color.BLACK)
                val rendered = runCatching {
                    renderer.render(canvas, p, state, durations) { idx ->
                        if (p.clips.getOrNull(idx)?.type == ClipType.VIDEO) {
                            videoFrameFor(videoDecoders, p, idx, state, maxOf(w, h) * 3 / 2)
                        } else {
                            cache.get(idx)
                        }
                    }
                }
                if (rendered.isFailure) {
                    // safety net: one bad frame never kills a long export
                    val ex = rendered.exceptionOrNull()
                    if (!yuvWarned) {
                        Log.w(TAG, "frame $i (t=${"%.2f".format(t)}s) failed: ${rootMsg(ex!!)} - substituting black frame", ex)
                        yuvWarned = true
                    }
                    canvas.drawColor(Color.BLACK)
                }
                val ptsNs = i.toLong() * 1_000_000_000L / fps
                feedVideoInput(encoder, frameBmp, pixels, w, h, ptsNs) {
                    while (true) {
                        val o = encoder.dequeueOutputBuffer(info, 2_000)
                        if (o == MediaCodec.INFO_OUTPUT_TIMEOUT) break
                        if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) noteVideoFormat()
                        if (o == 0) {
                            if (info.flags and MediaCodec.INFO_OUTPUT_FORMAT_CHANGED != 0) noteVideoFormat()
                            if (info.size > 0 && videoTrack >= 0) {
                                ensureStarted()
                                muxer.writeSampleData(videoTrack, encoder.getOutputBuffer(0)!!, info)
                            }
                            encoder.releaseOutputBuffer(0, false)
                        }
                    }
                }
                // write this frame's output, then interleave audio up to the frame end
                while (true) {
                    val o = encoder.dequeueOutputBuffer(info, 5_000)
                    when {
                        o == 0 -> {
                            if (info.flags and MediaCodec.INFO_OUTPUT_FORMAT_CHANGED != 0) noteVideoFormat()
                            if (info.size > 0 && videoTrack >= 0) {
                                ensureStarted()
                                muxer.writeSampleData(videoTrack, encoder.getOutputBuffer(0)!!, info)
                            }
                            encoder.releaseOutputBuffer(0, false)
                            drainAudioUpTo((i + 1).toLong() * (1_000_000L / fps))
                            break
                        }
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> noteVideoFormat()
                        o == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                    }
                }
                if (i % 5 == 0) onFrame(i.toFloat() / frames)
            }

            // end of stream
            feedVideoInput(encoder, frameBmp, pixels, w, h, frames.toLong() * 1_000_000_000L / fps, isEos = true) {
                while (true) {
                    val o = encoder.dequeueOutputBuffer(info, 5_000)
                    if (o == MediaCodec.INFO_OUTPUT_TIMEOUT) break
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) noteVideoFormat()
                    if (o == 0) {
                        if (info.flags and MediaCodec.INFO_OUTPUT_FORMAT_CHANGED != 0) noteVideoFormat()
                        if (info.size > 0 && videoTrack >= 0) {
                            ensureStarted()
                            muxer.writeSampleData(videoTrack, encoder.getOutputBuffer(0)!!, info)
                        }
                        encoder.releaseOutputBuffer(0, false)
                    }
                }
            }
            var eos = false
            while (!eos) {
                val o = encoder.dequeueOutputBuffer(info, 100_000)
                if (o == 0) {
                    if (info.flags and MediaCodec.INFO_OUTPUT_FORMAT_CHANGED != 0) noteVideoFormat()
                    if (info.size > 0 && videoTrack >= 0) {
                        ensureStarted()
                        muxer.writeSampleData(videoTrack, encoder.getOutputBuffer(0)!!, info)
                    }
                    encoder.releaseOutputBuffer(0, false)
                    eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    noteVideoFormat()
                }
            }
            drainAudioUpTo(Long.MAX_VALUE / 2)
            if (muxerStarted) muxer.stop()
        } finally {
            try { encoder.stop() } catch (e: Exception) { Log.w(TAG, "encoder stop failed", e) }
            try { encoder.release() } catch (e: Exception) { Log.w(TAG, "encoder release failed", e) }
            for (d in videoDecoders.values) {
                try { d.release() } catch (e: Exception) { Log.w(TAG, "video decoder release failed", e) }
            }
            videoDecoders.clear()
            try {
                if (muxerStarted) muxer.stop()
            } catch (e: Exception) { Log.w(TAG, "muxer stop failed", e) }
            try { muxer.release() } catch (e: Exception) { Log.w(TAG, "muxer release failed", e) }
            try { if (aacTemp != null && aacTemp.exists() && !aacTemp.delete()) Log.w(TAG, "could not delete ${aacTemp.absolutePath}") } catch (e: Exception) { Log.w(TAG, "aac temp delete failed", e) }
            try { frameBmp.recycle() } catch (e: Exception) { Log.w(TAG, "frame bmp recycle failed", e) }
            renderer.release()
            cache.clear()
        }
    }

    private fun videoFrameFor(
        decoders: HashMap<Int, VideoFrameDecoder>,
        p: ProjectModel,
        idx: Int,
        state: com.autoedit.engine.FrameState,
        maxSide: Int
    ): Bitmap? {
        val c = p.clips[idx]
        if (c.type != ClipType.VIDEO) return null
        val dec = decoders.getOrPut(idx) {
            VideoFrameDecoder(ctx, c.uri, c.videoInMs, c.videoOutMs, maxSide).also { it.init() }
        }
        val localT = if (idx == state.clipIndex) state.localT else p.clipDurationAt(idx)
        val neededMs = c.videoInMs + (localT * 1000.0).toLong()
        return dec.nextFrame(neededMs * 1000L)
    }

    private inline fun feedVideoInput(
        encoder: MediaCodec,
        bmp: Bitmap,
        pixels: IntArray,
        w: Int,
        h: Int,
        pts: Long,
        isEos: Boolean = false,
        drain: () -> Unit
    ) {
        var guard = 0
        while (true) {
            val idx = encoder.dequeueInputBuffer(20_000)
            if (idx >= 0) {
                if (isEos) {
                    encoder.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    return
                }
                val img = encoder.getInputImage(idx)
                if (img != null) {
                    writeYuv(img, bmp, pixels, w, h)
                    encoder.queueInputBuffer(idx, 0, w * h * 3 / 2, pts, 0)
                    return
                }
            }
            guard++
            if (guard > 300) {
                throw ExportException("video", "H.264 encoder stopped accepting frames (input queue stuck).")
            }
            drain()
        }
    }

    /**
     * ARGB frame -> encoder YUV420_888 input image.
     * Plane capacities are asserted against the ACTUAL resolution first
     * (clear error, never a silent mid-export crash), then the write uses
     * the stride-correct, bounds-safe YuvConverter.
     */
    private fun writeYuv(img: Image, bmp: Bitmap, pixels: IntArray, w: Int, h: Int) {
        if (pixels.size < w * h) {
            throw ExportException(
                "video",
                "Frame buffer is ${pixels.size} ints but ${w}x$h needs ${w * h} - buffer sizing bug, please report"
            )
        }
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val yp = img.planes[0]
        val up = img.planes[1]
        val vp = img.planes[2]
        val yRow = yp.rowStride
        val uRow = up.rowStride
        val vRow = vp.rowStride
        val uPix = up.pixelStride
        val vPix = vp.pixelStride
        val yB = yp.buffer
        val uB = up.buffer
        val vB = vp.buffer
        val yNeed = YuvConverter.minLumaSize(w, h, yRow)
        val uNeed = YuvConverter.minChromaSize(w, h, uRow, uPix)
        val vNeed = YuvConverter.minChromaSize(w, h, vRow, vPix)
        if (yB.position() + yB.limit() < yNeed) {
            throw ExportException("video", "Y plane too small for ${w}x$h: rowStride=$yRow, capacity=${yB.position() + yB.limit()}, need=$yNeed")
        }
        if (uB.position() + uB.limit() < uNeed) {
            throw ExportException("video", "U plane too small for ${w}x$h: rowStride=$uRow, pixelStride=$uPix, capacity=${uB.position() + uB.limit()}, need=$uNeed")
        }
        if (vB.position() + vB.limit() < vNeed) {
            throw ExportException("video", "V plane too small for ${w}x$h: rowStride=$vRow, pixelStride=$vPix, capacity=${vB.position() + vB.limit()}, need=$vNeed")
        }
        val clamped = YuvConverter.rgbToYuv420(pixels, w, h, yB, yRow, uB, uRow, uPix, vB, vRow, vPix)
        if (clamped > 0) {
            Log.w(TAG, "YUV write: $clamped writes clamped to plane bounds (layout mismatch) - frame may be partially corrupted")
        }
    }

    // ------------------------------------------------------------- audio

    private fun encodeAac(pcm: ShortArray, out: File) {
        val encoder = MediaCodec.createEncoderByType("audio/mp4a.4ac.001a010000")
        val muxer = MediaMuxer(out.absolutePath, OUTPUT_FORMAT_MP4)
        val info = MediaCodec.BufferInfo()
        var track = -1
        var started = false
        try {
            val fmt = MediaFormat.createAudioFormat("audio/mp4a.4ac.001a010000", AudioDsp.TARGET_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32_000)
            }
            encoder.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            val chunk = 1024
            var i = 0
            while (i < pcm.size) {
                val n = minOf(chunk, pcm.size - i)
                var guard = 0
                while (true) {
                    val idx = encoder.dequeueInputBuffer(20_000)
                    if (idx >= 0) {
                        val buf = encoder.getInputBuffer(idx)
                        if (buf != null) {
                            buf.clear()
                            for (k in 0 until n) {
                                val j = (i + k).coerceIn(0, pcm.size - 1)
                                val s = pcm[j].toInt()
                                buf.put((s and 0xFF).toByte())
                                buf.put(((s shr 8) and 0xFF).toByte())
                            }
                            encoder.queueInputBuffer(idx, 0, n * 2, i.toLong() * 1_000_000_000L / AudioDsp.TARGET_RATE, 0)
                            break
                        }
                    }
                    guard++
                    if (guard > 300) throw ExportException("audio", "AAC encoder stopped accepting frames.")
                }
                i += n
            }
            var guard = 0
            while (true) {
                val idx = encoder.dequeueInputBuffer(20_000)
                if (idx >= 0) {
                    encoder.queueInputBuffer(idx, 0, 0, i.toLong() * 1_000_000_000L / AudioDsp.TARGET_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    break
                }
                guard++
                if (guard > 300) break
            }
            var eos = false
            while (!eos) {
                val o = encoder.dequeueOutputBuffer(info, 100_000)
                if (o == 0) {
                    if (info.flags and MediaCodec.INFO_OUTPUT_FORMAT_CHANGED != 0 && track < 0) {
                        track = muxer.addTrack(encoder.outputFormat)
                    }
                    if (info.size > 0 && track >= 0) {
                        val buf = encoder.getOutputBuffer(0)
                        if (buf != null) {
                            if (!started) { muxer.start(); started = true }
                            info.offset = 0
                            muxer.writeSampleData(track, buf, info)
                        }
                    }
                    encoder.releaseOutputBuffer(0, false)
                    eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && track < 0) {
                    track = muxer.addTrack(encoder.outputFormat)
                }
            }
        } finally {
            try { encoder.stop() } catch (e: Exception) { Log.w(TAG, "aac encoder stop failed", e) }
            try { encoder.release() } catch (e: Exception) { Log.w(TAG, "aac encoder release failed", e) }
            try { if (started) muxer.stop() } catch (e: Exception) { Log.w(TAG, "aac muxer stop failed", e) }
            try { muxer.release() } catch (e: Exception) { Log.w(TAG, "aac muxer release failed", e) }
        }
    }

    // ------------------------------------------------------------- store

    /** Best-effort copy into MediaStore (Movies/Auto Edit). Never throws. */
    private fun copyToMediaStore(p: ProjectModel, file: File): Uri? {
        return try {
            val stamp = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(Date())
            val safeName = p.name.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().ifBlank { "Project" }
            val values = ContentValues().apply {
                put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, "Auto Edit - $safeName - $stamp.mp4")
                put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/Auto Edit")
                    put(android.provider.MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val uri = ctx.contentResolver.insert(
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return null
            ctx.contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out, 1024 * 1024) }
            } ?: run {
                runCatching { ctx.contentResolver.delete(uri, null, null) }
                return null
            }
            if (Build.VERSION.SDK_INT >= 29) {
                val done = ContentValues().apply {
                    put(android.provider.MediaStore.Video.Media.IS_PENDING, 0)
                }
                ctx.contentResolver.update(uri, done, null, null)
            }
            uri
        } catch (e: Exception) {
            Log.w(TAG, "media store copy failed (export file is kept in the project folder)", e)
            null
        }
    }
}
