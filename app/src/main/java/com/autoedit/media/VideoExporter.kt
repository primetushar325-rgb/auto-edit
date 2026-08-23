package com.autoedit.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
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
import com.autoedit.engine.ProjectModel
import java.io.IOException
import com.autoedit.engine.EasingType
import com.autoedit.engine.FormulaCatalog
import com.autoedit.engine.TimelineMath
import com.autoedit.render.ExportRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VideoExporter(private val ctx: Context) {

    class ExportCancelled : Exception("cancelled")

    /** Export failure with a stage + a human-readable root-cause message. */
    class ExportException(val stage: String, message: String, cause: Throwable? = null) :
        Exception(message, cause)

    private companion object {
        const val TAG = "AutoEdit"
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

    // MediaCodec.OUTPUT_FORMAT_MP4 (value 2) - not present in the SDK stub, so kept as a constant
    private val OUTPUT_FORMAT_MP4 = 2

    private var pixels = IntArray(0)

    /**
     * Full export pipeline (runs on [Dispatchers.Default] via the caller):
     * 1. decode voice + music, mix to mono 48k PCM
     * 2. render every frame to a bitmap, H.264 encode, mux to temp mp4
     * 3. AAC encode the mixed audio, mux to temp m4a
     * 4. remux video + audio into the final mp4
     * 5. copy to MediaStore: Movies/Auto Edit/
     */
    suspend fun export(
        project: ProjectModel,
        isCancelled: () -> Boolean = { false },
        onProgress: suspend (Float, String) -> Unit
    ): Result<Uri> {
        val stamp = System.currentTimeMillis()
        val tmpVideo = File(ctx.cacheDir, "ae-video-$stamp.mp4")
        val tmpAudio = File(ctx.cacheDir, "ae-audio-$stamp.m4a")
        val tmpFinal = File(ctx.cacheDir, "ae-final-$stamp.mp4")
        try {
            val p = project
            val durations = p.clipDurations()
            val total = durations.sumOf { it.coerceAtLeast(0.0) }
            if (total <= 0.0 || p.clips.isEmpty()) {
                throw IllegalStateException("Add images before exporting.")
            }
            val w = p.export.widthFor(p.aspect)
            val h = p.export.heightFor(p.aspect)
            val fps = p.export.fps
            val frames = (total * fps).toInt().coerceAtLeast(1)
            val videoDurationUs = frames * (1_000_000L / fps)
            val easing = FormulaCatalog.byId(p.formulaId)?.easing ?: EasingType.EASE_IN_OUT

            var voicePcm: AudioDsp.PcmAudio? = null
            var musicPcm: AudioDsp.PcmAudio? = null
            val voiceCfg = p.voice
            val musicCfg = p.music
            if (voiceCfg != null) {
                onProgress(0.02f, "Loading voice…")
                voicePcm = AudioDecoder.decode(ctx, voiceCfg.uri, total + voiceCfg.offsetSec + 2.0, isCancelled)
            }
            if (musicCfg != null) {
                onProgress(0.05f, "Loading music…")
                musicPcm = AudioDecoder.decode(ctx, musicCfg.uri, total + 2.0, isCancelled)
            }
            var mixed: ShortArray? = null
            if (voicePcm != null || musicPcm != null) {
                onProgress(0.08f, "Mixing audio…")
                var m = AudioDsp.mix(total, voicePcm, voiceCfg, musicPcm, musicCfg, p.duckMusic)
                // Trim/pad to EXACTLY the video sample count so the AAC track can
                // never overshoot the video duration (remux mismatch guard).
                val exact = frames * AudioDsp.TARGET_RATE / fps
                m = AudioDsp.toExactLength(m, exact)
                mixed = m
            }

            onProgress(0.1f, "Rendering frames…")
            val frameBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(frameBmp)
            val renderer = ExportRenderer(w, h, p.adjustments, easing)
            val cache = ClipImageCache(ctx, w, h)
            try {
                encodeVideo(
                    p, frames, w, h, fps, tmpVideo, frameBmp, canvas, renderer, cache, isCancelled
                ) { frac ->
                    onProgress(0.1f + frac * 0.75f, "Rendering… ${(frac * 100).toInt()}%")
                }
            } catch (e: ExportException) {
                throw e
            } catch (e: ExportCancelled) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "video encoding failed", e)
                throw ExportException("video encoding", "Video rendering failed: ${rootMsg(e)}", e)
            } finally {
                renderer.release()
                cache.clear()
                try { frameBmp.recycle() } catch (_: Exception) {}
            }

            if (mixed != null) {
                onProgress(0.87f, "Encoding audio…")
                try {
                    encodeAac(mixed, tmpAudio)
                } catch (e: ExportException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "audio encoding failed", e)
                    throw ExportException("audio encoding", "Audio encoding failed: ${rootMsg(e)}", e)
                }
            }

            onProgress(0.93f, "Merging…")
            try {
                remux(tmpVideo, if (mixed != null) tmpAudio else null, tmpFinal, videoDurationUs)
            } catch (e: ExportException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "mux merge failed", e)
                throw ExportException("merge", "Could not merge audio and video: ${rootMsg(e)}", e)
            }

            onProgress(0.97f, "Saving to Movies/Auto Edit…")
            val uri = saveToMediaStore(tmpFinal, p)
            onProgress(1f, "Done")
            return Result.success(uri)
        } catch (e: ExportCancelled) {
            onProgress(0f, "Cancelled")
            return Result.failure(e)
        } catch (e: InterruptedException) {
            onProgress(0f, "Cancelled")
            return Result.failure(ExportCancelled())
        } catch (e: OutOfMemoryError) {
            onProgress(0f, "Out of memory")
            return Result.failure(e)
        } catch (e: ExportException) {
            onProgress(0f, "Failed")
            return Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "export failed", e)
            onProgress(0f, "Failed")
            return Result.failure(ExportException("export", "Export failed: ${rootMsg(e)}", e))
        } finally {
            tmpVideo.delete()
            tmpAudio.delete()
            tmpFinal.delete()
        }
    }

    // ---------------------------------------------------------------- video

    private suspend fun encodeVideo(
        p: ProjectModel,
        frames: Int,
        w: Int,
        h: Int,
        fps: Int,
        out: File,
        frameBmp: Bitmap,
        canvas: Canvas,
        renderer: ExportRenderer,
        cache: ClipImageCache,
        isCancelled: () -> Boolean,
        onFrame: suspend (Float) -> Unit
    ) {
        val encoder = MediaCodec.createEncoderByType("video/avc")
        val muxer = MediaMuxer(out.absolutePath, OUTPUT_FORMAT_MP4)
        val info = MediaCodec.BufferInfo()
        var videoTrack = -1
        var muxerStarted = false
        var formatSeen = false
        val videoDecoders = HashMap<Int, VideoFrameDecoder>()
        try {
            val fmt = MediaFormat.createVideoFormat("video/avc", w, h).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, p.export.videoBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            }
            encoder.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            fun handleOutput(o: Int): Boolean {
                return when {
                    o == 0 -> {
                        if (info.flags and MediaCodec.INFO_OUTPUT_FORMAT_CHANGED != 0) {
                            if (!formatSeen) {
                                formatSeen = true
                                videoTrack = muxer.addTrack(encoder.outputFormat)
                            }
                        }
                        if (info.size > 0 && videoTrack >= 0) {
                            val buf = encoder.getOutputBuffer(0)
                            if (buf != null) {
                                if (!muxerStarted) { muxer.start(); muxerStarted = true }
                                muxer.writeSampleData(videoTrack, buf, info)
                            }
                        }
                        encoder.releaseOutputBuffer(0, false)
                        (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0)
                    }
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!formatSeen) {
                            formatSeen = true
                            videoTrack = muxer.addTrack(encoder.outputFormat)
                        }
                        false
                    }
                    else -> false
                }
            }

            val durations = p.clipDurations()
            val maxSide = (maxOf(w, h) * 1.5f).toInt()
            for (i in 0 until frames) {
                if (isCancelled() || Thread.currentThread().isInterrupted) throw ExportCancelled()
                val t = i / fps.toDouble()
                val state = TimelineMath.frameAt(t, durations, p.transitionDurationSec)
                cache.prepare(state, p)
                canvas.drawColor(Color.BLACK)
                renderer.render(canvas, p, state, durations) { idx ->
                    if (p.clips[idx].type == ClipType.VIDEO) videoFrameFor(videoDecoders, p, idx, state, maxSide)
                    else cache.get(idx)
                }
                feedVideoInput(encoder, frameBmp, i.toLong() * 1_000_000_000L / fps) {
                    while (true) {
                        val o = encoder.dequeueOutputBuffer(info, 2_000)
                        if (o == MediaCodec.INFO_TRY_AGAIN_LATER) break
                        if (handleOutput(o)) break
                        if (o == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) continue
                    }
                }
                val eos = handleOutput(encoder.dequeueOutputBuffer(info, 0))
                if (eos) {
                    // input queue full; keep draining until space
                    while (true) {
                        val o = encoder.dequeueOutputBuffer(info, 10_000)
                        if (o != MediaCodec.INFO_TRY_AGAIN_LATER && handleOutput(o)) break
                        if (o == MediaCodec.INFO_TRY_AGAIN_LATER && eos) break
                    }
                }
                if (i % 5 == 0) onFrame(i.toFloat() / frames)
            }

            // end of stream
            feedVideoInput(encoder, frameBmp, frames.toLong() * 1_000_000_000L / fps, isEos = true) {
                while (true) {
                    val o = encoder.dequeueOutputBuffer(info, 5_000)
                    if (o == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (handleOutput(o)) break
                }
            }
            var eos = false
            while (!eos) {
                val o = encoder.dequeueOutputBuffer(info, 100_000)
                if (o == MediaCodec.INFO_TRY_AGAIN_LATER) continue
                eos = handleOutput(o)
            }
        } finally {
            try { encoder.stop() } catch (_: Exception) {}
            try { encoder.release() } catch (_: Exception) {}
            try {
                if (muxerStarted) muxer.stop()
            } catch (_: Exception) {}
            try { muxer.release() } catch (_: Exception) {}
            for (d in videoDecoders.values) {
                try { d.release() } catch (_: Exception) {}
            }
            videoDecoders.clear()
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
                    writeYuv(img, bmp)
                    encoder.queueInputBuffer(idx, 0, bmp.width * bmp.height * 3 / 2, pts, 0)
                    return
                }
            }
            guard++
            if (guard > 300) throw IllegalStateException("Video encoder is not accepting frames.")
            drain()
        }
    }

    private fun writeYuv(img: Image, bmp: Bitmap) {
        val w = bmp.width
        val h = bmp.height
        if (pixels.size < w * h) pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
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
        for (yy in 0 until h) {
            var ui = yy * uRow
            var vi = yy * vRow
            for (xx in 0 until w) {
                val p = pixels[yy * w + xx]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val y = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)
                val u = ((b - y) * 0.564 + 128).toInt().coerceIn(0, 255)
                val v = ((r - y) * 0.713 + 128).toInt().coerceIn(0, 255)
                yB.put(yy * yRow + xx, y.toByte())
                if (xx and 1 == 0) {
                    uB.put(ui, u.toByte())
                    vB.put(vi, v.toByte())
                    ui += uPix
                    vi += vPix
                }
            }
        }
    }

    // ---------------------------------------------------------------- audio

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
                                val s = pcm[i + k].toInt()
                                buf.put((s and 0xFF).toByte())
                                buf.put(((s shr 8) and 0xFF).toByte())
                            }
                            encoder.queueInputBuffer(
                                idx, 0, n * 2,
                                i.toLong() * 1_000_000_000L / AudioDsp.TARGET_RATE, 0
                            )
                            break
                        }
                    }
                    guard++
                    if (guard > 300) throw IllegalStateException("Audio encoder is not accepting frames.")
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
                    if (info.flags and MediaCodec.INFO_OUTPUT_FORMAT_CHANGED != 0) {
                        if (track < 0) track = muxer.addTrack(encoder.outputFormat)
                    }
                    if (info.size > 0 && track >= 0) {
                        val buf = encoder.getOutputBuffer(0)
                        if (buf != null) {
                            if (!started) { muxer.start(); started = true }
                            muxer.writeSampleData(track, buf, info)
                        }
                    }
                    encoder.releaseOutputBuffer(0, false)
                    eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0)
                } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (track < 0) track = muxer.addTrack(encoder.outputFormat)
                }
            }
        } finally {
            try { encoder.stop() } catch (_: Exception) {}
            try { encoder.release() } catch (_: Exception) {}
            try { if (started) muxer.stop() } catch (_: Exception) {}
            try { muxer.release() } catch (_: Exception) {}
        }
    }

    // ---------------------------------------------------------------- remux

    private fun remux(video: File, audio: File?, out: File, videoDurationUs: Long) {
        val ev = MediaExtractor().apply { setDataSource(video.absolutePath) }
        val ea = audio?.let { MediaExtractor().apply { setDataSource(it.absolutePath) } }
        try {
            val vTrack = (0 until ev.trackCount).first {
                ev.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            ev.selectTrack(vTrack)
            val fmtV = ev.getTrackFormat(vTrack)
            val mux = MediaMuxer(out.absolutePath, OUTPUT_FORMAT_MP4)
            try {
                var aTrack = -1
                var fmtA: MediaFormat? = null
                if (ea != null) {
                    aTrack = (0 until ea.trackCount).first {
                        ea.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                    }
                    ea.selectTrack(aTrack)
                    fmtA = ea.getTrackFormat(aTrack)
                }
                val outV = mux.addTrack(fmtV)
                val outA = if (fmtA != null) mux.addTrack(fmtA) else -1
                mux.start()
                val buf = java.nio.ByteBuffer.allocate(512 * 1024)
                val info = MediaCodec.BufferInfo()
                var nextV = ev.sampleTime
                var nextA = if (ea != null) ea.sampleTime else Long.MAX_VALUE
                var doneV = false
                var doneA = ea == null
                var videoMaxPts = 0L
                var videoSamples = 0
                var audioMaxPts = 0L
                var audioSamples = 0
                var audioSkipped = 0
                val frameUs = 1_000_000L / 60
                while (!doneV || !doneA) {
                    val takeV = if (doneA) true else if (doneV) false else nextV <= nextA
                    if (takeV) {
                        buf.position(0)
                        val size = ev.readSampleData(buf, 0)
                        if (size < 0) {
                            doneV = true
                        } else {
                            info.size = size
                            info.offset = 0
                            info.presentationTimeUs = ev.sampleTime
                            info.flags = ev.sampleFlags
                            mux.writeSampleData(outV, buf, info)
                            videoMaxPts = ev.sampleTime
                            videoSamples++
                            nextV = ev.sampleTime
                        }
                    } else {
                        val a = ea!!
                        // PTS guard: never write audio beyond the video duration
                        // (AAC encoder delay can overshoot by a couple of frames).
                        if (a.sampleTime >= videoDurationUs - frameUs) {
                            doneA = true
                            audioSkipped++
                        } else {
                            buf.position(0)
                            val size = a.readSampleData(buf, 0)
                            if (size < 0) {
                                doneA = true
                            } else {
                                info.size = size
                                info.offset = 0
                                info.presentationTimeUs = a.sampleTime
                                info.flags = a.sampleFlags
                                mux.writeSampleData(outA, buf, info)
                                audioMaxPts = a.sampleTime
                                audioSamples++
                                nextA = a.sampleTime
                            }
                        }
                    }
                }
                // Pre-stop validation: timestamps must be sane (audio may be
                // shorter, never longer than video + one frame).
                val vDurSec = videoMaxPts / 1_000_000.0
                val aDurSec = audioMaxPts / 1_000_000.0
                Log.i(TAG, "remux: video ${videoSamples} samples / ${vDurSec}s, audio ${audioSamples} samples / ${aDurSec}s, skipped $audioSkipped")
                if (audioMaxPts > videoDurationUs + 2 * frameUs) {
                    Log.w(TAG, "remux: audio PTS ${audioMaxPts}us exceeded video ${videoDurationUs}us - check trim")
                }
                mux.stop()
            } finally {
                try { mux.release() } catch (_: Exception) {}
            }
        } catch (e: IOException) {
            Log.e(TAG, "remux I/O failure", e)
            throw ExportException("merge", "Merge failed (storage or file error): ${rootMsg(e)}", e)
        } catch (e: RuntimeException) {
            Log.e(TAG, "remux failed", e)
            throw ExportException("merge", "Merge failed: ${rootMsg(e)}", e)
        } finally {
            try { ev.release() } catch (_: Exception) {}
            try { ea?.release() } catch (_: Exception) {}
        }
    }

    private fun saveToMediaStore(src: File, p: ProjectModel): Uri {
        val values = ContentValues()
        val stamp = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(Date())
        val safeName = p.name.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().ifBlank { "Project" }
        values.put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, "Auto Edit - $safeName - $stamp.mp4")
        values.put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/Auto Edit")
            values.put(android.provider.MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(
            android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
        ) ?: throw IllegalStateException("Not enough storage or could not create the video file.")
        try {
            val out = ctx.contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("Not enough storage to export this video.")
            out.use { o ->
                src.inputStream().use { it.copyTo(o) }
            }
            if (Build.VERSION.SDK_INT >= 29) {
                val done = ContentValues().apply {
                    put(android.provider.MediaStore.Video.Media.IS_PENDING, 0)
                }
                ctx.contentResolver.update(uri, done, null, null)
            }
            return uri
        } catch (e: Exception) {
            runCatching { ctx.contentResolver.delete(uri, null, null) }
            throw e
        }
    }
}
