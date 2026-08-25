// WrongConstant: MediaMuxer.OUTPUT_FORMAT_MPEG_4 (= 2) - the platform android.jar stub
// omits the MediaMuxer OUTPUT_FORMAT_* constants, so the value is inlined.
@file:Suppress("WrongConstant")

package com.autoedit.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
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
import com.autoedit.export.AacEncoder
import com.autoedit.export.EncoderCapabilities
import com.autoedit.export.ExportProgress
import com.autoedit.export.GpuFrameRenderer
import com.autoedit.export.SurfaceEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * VIDEO EXPORT PIPELINE (surface-based, hardware encoded).
 *
 * Architecture:
 *
 *   IMAGE (one clip decoded at a time)
 *     -> GpuFrameRenderer (EGL + OpenGL ES 2.0, motion / transitions / look)
 *     -> MediaCodec INPUT SURFACE (COLOR_FormatSurface, eglPresentationTimeANDROID)
 *     -> hardware H.264 encoder
 *     -> MediaMuxer -> temp video MP4
 *
 *   VOICE + MUSIC (MediaExtractor/MediaCodec decode, DSP mix)
 *     -> AAC encoder -> temp audio MP4
 *
 *   temp video + temp audio -> MediaExtractor interleave -> MediaMuxer -> FINAL MP4
 *     -> verification (size / duration / resolution / first frame decodes)
 *     -> best-effort MediaStore copy
 *
 * The old CPU pipeline (Bitmap -> manual YUV conversion ->
 * queueInputBuffer) has been REMOVED: there is no video YUV path, no
 * per-frame getPixels, and no getInputImage anywhere in this file.
 */
class VideoExporter(private val ctx: Context) {

    class ExportCancelled : Exception("cancelled")

    /** Export failure with a stage + a human-readable, specific message. */
    class ExportException(val stage: String, message: String, cause: Throwable? = null) :
        Exception(message, cause)

    data class ExportResult(val file: File, val mediaStoreUri: Uri?, val warning: String?)

    private companion object {
        const val TAG = "AutoEditExport"
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
     * Full export pipeline (runs on Dispatchers.Default via the caller).
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
        var runDir: File? = null
        var warning: String? = null
        try {
            val p = project
            if (p.clips.isEmpty()) {
                throw ExportException("prepare", "Add images before exporting.")
            }
            val total = p.clipDurations().sumOf { it.coerceAtLeast(0.0) }
            if (total <= 0.0) {
                throw ExportException("prepare", "Project has no duration.")
            }

            // ----------------------------------------------------------------
            // STAGE 1 (0-5%): prepare - capability probe, dirs, temp run dir
            // ----------------------------------------------------------------
            onProgress(ExportProgress.prep(0.2f), "Preparing…")
            val a = p.aspect
            val resolved = EncoderCapabilities.resolve(p.export.quality, a.w, a.h, p.export.fps)
            if (resolved.fallback != null) warning = resolved.fallback
            val w = resolved.width
            val h = resolved.height
            val fps = resolved.fps
            val frames = (total * fps).toInt().coerceAtLeast(1)
            val expectedSec = frames / fps.toDouble()
            val easing = FormulaCatalog.byId(p.formulaId)?.easing ?: EasingType.EASE_IN_OUT
            Log.i(
                TAG,
                "export start: ${p.clips.size} clips, $frames frames, ${w}x$h@${fps}, " +
                    "total=%.2fs, bitrate=%d (requested %s)"
                    .format(total, p.export.videoBitrate, p.export.quality.label)
            )
            if (warning != null) onProgress(ExportProgress.prep(0.4f), warning)

            onProgress(ExportProgress.prep(0.6f), "Preparing…")
            if (!tempDir.exists() && !tempDir.mkdirs()) {
                throw ExportException("prepare", "Could not create temp folder: ${tempDir.absolutePath}")
            }
            if (!exportDir.exists() && !exportDir.mkdirs()) {
                throw ExportException("prepare", "Could not create export folder: ${exportDir.absolutePath}")
            }
            runDir = File(tempDir, "run-${System.currentTimeMillis()}")
            if (!runDir!!.mkdirs()) {
                throw ExportException("prepare", "Could not create run temp folder.")
            }
            val videoTemp = File(runDir!!, "video-temp.mp4")
            val audioTemp = File(runDir!!, "audio-temp.mp4")
            val stamp = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(Date())
            val safeName = p.name.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().ifBlank { "Project" }
            finalFile = File(exportDir, "Auto Edit - $safeName - $stamp.mp4")

            // ----------------------------------------------------------------
            // STAGE 2 (5-85%): GPU render -> hardware H.264 -> temp video MP4
            // ----------------------------------------------------------------
            onProgress(ExportProgress.render(0f), "Rendering frames 0/$frames")
            try {
                renderVideo(p, w, h, fps, frames, videoTemp, easing, isCancelled) { frac ->
                    val n = (frac * frames).toInt().coerceIn(0, frames)
                    onProgress(
                        ExportProgress.render(frac),
                        if (frames > 200) "Rendering frames $n/$frames" else "Rendering frames $n/$frames"
                    )
                }
            } catch (e: SurfaceEncoder.EncoderStuckException) {
                throw ExportException("video encode", e.message ?: "Video encoder stopped responding.", e)
            } catch (e: GpuFrameRenderer.GpuException) {
                throw ExportException(
                    "video encode",
                    "GPU rendering failed on this device: ${rootMsg(e)}. Please try again.", e
                )
            } catch (e: ExportCancelled) {
                throw e
            } catch (e: Exception) {
                throw ExportException("video encode", "Video rendering failed: ${rootMsg(e)}", e)
            }
            if (!videoTemp.exists() || videoTemp.length() == 0L) {
                throw ExportException("video encode", "Encoder produced an empty video file.")
            }
            Log.i(TAG, "video stage done: ${videoTemp.length() / 1024} KB temp MP4")

            // ----------------------------------------------------------------
            // STAGE 3 (85-92%): audio decode/mix/encode (skipped if none set)
            // ----------------------------------------------------------------
            var audioOk = false
            val hasAudio = p.voice != null || p.music != null
            if (hasAudio) {
                try {
                    encodeAudioStage(p, total, audioTemp, isCancelled) { sub, label ->
                        onProgress(ExportProgress.audio(sub), label)
                    }
                    audioOk = audioTemp.exists() && audioTemp.length() > 0
                } catch (e: ExportCancelled) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "audio stage failed - continuing video-only", e)
                    warning = (warning?.plus(" • ") ?: "") +
                        "Video saved WITHOUT audio (audio failed: ${rootMsg(e).take(120)})"
                }
            }

            // ----------------------------------------------------------------
            // STAGE 4 (92-98%): final mux + verification
            // ----------------------------------------------------------------
            onProgress(ExportProgress.mux(0.1f), "Finalizing…")
            try {
                if (audioOk) {
                    remuxToFinal(videoTemp, audioTemp, finalFile!!) { frac ->
                        onProgress(ExportProgress.mux(0.1f + frac * 0.6f), "Finalizing…")
                    }
                } else {
                    moveOrCopy(videoTemp, finalFile!!)
                }
            } catch (e: ExportCancelled) {
                throw e
            } catch (e: Exception) {
                throw ExportException("finalize", "Could not finalize the video: ${rootMsg(e)}", e)
            }
            val verifyErr = verifyMp4(finalFile!!, w, h, expectedSec)
            if (verifyErr != null) {
                throw ExportException("finalize", "Exported file failed verification: $verifyErr")
            }
            onProgress(ExportProgress.mux(0.95f), "Finalizing…")
            Log.i(TAG, "final MP4 verified: ${finalFile!!.length() / 1024} KB")

            // ----------------------------------------------------------------
            // STAGE 5 (98-100%): MediaStore copy (best effort)
            // ----------------------------------------------------------------
            onProgress(ExportProgress.save(0.2f), "Saving…")
            val store = copyToMediaStore(p, finalFile!!)
            success = true
            onProgress(ExportProgress.DONE, "Done")
            Log.i(TAG, "export complete: ${finalFile!!.absolutePath}${if (store != null) " (+ MediaStore copy)" else ""}")
            return Result.success(ExportResult(finalFile!!, store, warning))
        } catch (e: ExportCancelled) {
            onProgress(0f, "Cancelled")
            Log.i(TAG, "export cancelled")
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
            // temp/ is ALWAYS cleaned (success, failure, cancellation)
            runDir?.let { d ->
                val ok = runCatching { d.deleteRecursively() }.isSuccess
                if (!ok) Log.w(TAG, "could not clean run temp dir ${d.absolutePath}")
            }
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

    // ================================================================ video

    private suspend fun renderVideo(
        p: ProjectModel,
        w: Int,
        h: Int,
        fps: Int,
        frames: Int,
        videoTemp: File,
        easing: EasingType,
        isCancelled: () -> Boolean,
        onFrac: suspend (Float) -> Unit
    ) {
        val encoder = SurfaceEncoder(w, h, fps, p.export.videoBitrate, videoTemp)
        val renderer = GpuFrameRenderer(ctx, w, h, p.adjustments, easing)
        val videoDecoders = HashMap<Int, VideoFrameDecoder>()
        var rendererReady = false
        try {
            encoder.init()
            val surface = encoder.inputSurface
                ?: throw ExportException("video encode", "Encoder did not create an input surface.")
            renderer.init(surface)
            rendererReady = true
            onFrac(0f)

            val durations = p.clipDurations()

            for (i in 0 until frames) {
                if (isCancelled() || Thread.currentThread().isInterrupted) throw ExportCancelled()
                val t = i / fps.toDouble()
                val state = TimelineMath.frameAt(t, durations, p.transitionDurationSec)

                // fetch decoder frames for any VIDEO clips on screen (rare)
                val videoFrame: (Int, Long) -> YuvFrame? = { idx, neededMs ->
                    val c = p.clips.getOrNull(idx)
                    if (c == null || c.type != ClipType.VIDEO) {
                        null
                    } else {
                        val dec = videoDecoders.getOrPut(idx) {
                            VideoFrameDecoder(ctx, c.uri, c.videoInMs, c.videoOutMs, maxOf(w, h) * 3 / 2).also { it.init() }
                        }
                        dec.nextFrame(neededMs * 1000L)
                    }
                }

                renderer.renderFrame(p, state, durations, videoFrame)
                val ptsNs = i.toLong() * 1_000_000_000L / fps
                renderer.setPresentationTimeNs(ptsNs)
                renderer.swap() // blocks with natural backpressure when the encoder queue is full
                encoder.noteFrameRendered()
                encoder.drain(blocking = false)
                if (i % 30 == 0 || i == frames - 1) onFrac((i + 1).toFloat() / frames)
            }

            encoder.finishEos(isCancelled)
            Log.i(TAG, "render loop finished: $frames frames at ${fps} fps")
        } finally {
            for (d in videoDecoders.values) {
                runCatching { d.release() }
            }
            videoDecoders.clear()
            if (rendererReady) renderer.release()
            encoder.release()
        }
    }

    // ================================================================ audio

    private suspend fun encodeAudioStage(
        p: ProjectModel,
        total: Double,
        audioTemp: File,
        isCancelled: () -> Boolean,
        onAudio: suspend (Float, String) -> Unit
    ) {
        onAudio(0.05f, "Loading audio…")
        var voicePcm: AudioDsp.PcmAudio? = null
        var musicPcm: AudioDsp.PcmAudio? = null
        val voiceCfg = p.voice
        val musicCfg = p.music
        if (voiceCfg != null) {
            voicePcm = AudioDecoder.decode(ctx, voiceCfg.uri, total + voiceCfg.offsetSec + 2.0, isCancelled)
        }
        if (musicCfg != null) {
            onAudio(0.25f, "Loading audio…")
            musicPcm = AudioDecoder.decode(ctx, musicCfg.uri, total + 2.0, isCancelled)
        }
        onAudio(0.45f, "Mixing audio…")
        var mixed = AudioDsp.mix(total, voicePcm, voiceCfg, musicPcm, musicCfg, p.duckMusic)
        // trim/pad to EXACTLY the video sample count
        mixed = AudioDsp.toExactLength(mixed, AudioDsp.exactSampleCount(total))
        onAudio(0.55f, "Encoding audio…")
        AacEncoder.encode(mixed, audioTemp)
        onAudio(0.95f, "Encoding audio…")
        Log.i(TAG, "audio stage done: ${audioTemp.length() / 1024} KB AAC temp MP4")
    }

    // ================================================================ final

    /** Stream-copy remux: temp video + temp audio -> final MP4 (interleaved by PTS). */
    private suspend fun remuxToFinal(videoIn: File, audioIn: File, out: File, onFrac: suspend (Float) -> Unit) {
        val ve = MediaExtractor()
        val ae = MediaExtractor()
                val muxer = MediaMuxer(out.absolutePath, 2) // MediaMuxer.OUTPUT_FORMAT_MPEG_4
        var started = false
        try {
            ve.setDataSource(videoIn.absolutePath)
            ae.setDataSource(audioIn.absolutePath)
            var vTrack = -1
            var aTrack = -1
            for (i in 0 until ve.trackCount) {
                val m = ve.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("video/")) {
                    vTrack = i
                    break
                }
            }
            for (i in 0 until ae.trackCount) {
                val m = ae.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("audio/")) {
                    aTrack = i
                    break
                }
            }
            if (vTrack < 0) throw ExportException("finalize", "Video temp has no video track.")
            if (aTrack < 0) throw ExportException("finalize", "Audio temp has no audio track.")
            ve.selectTrack(vTrack)
            ae.selectTrack(aTrack)
            val outV = muxer.addTrack(ve.getTrackFormat(vTrack))
            val outA = muxer.addTrack(ae.getTrackFormat(aTrack))
            muxer.start()
            started = true
            Log.i(TAG, "final mux started: video+audio -> ${out.name}")

            val buf = ByteBuffer.allocateDirect(1 shl 20)
            val info = android.media.MediaCodec.BufferInfo()
            var vDone = false
            var aDone = false
            var samples = 0L
            var vSamples = 0L
            while (!vDone || !aDone) {
                val pickVideo = when {
                    vDone -> false
                    aDone -> true
                    else -> {
                        val vt = ve.sampleTime
                        val at = ae.sampleTime
                        if (vt < 0) false else if (at < 0) true else vt <= at
                    }
                }
                if (pickVideo) {
                    buf.clear()
                    val n = ve.readSampleData(buf, 0)
                    if (n < 0) {
                        vDone = true
                    } else {
                        info.size = n
                        info.offset = 0
                        info.presentationTimeUs = ve.sampleTime
                        info.flags = if (ve.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                            MediaCodec.BUFFER_FLAG_SYNC_FRAME else 0
                        muxer.writeSampleData(outV, buf, info)
                        samples++
                        vSamples++
                    }
                } else {
                    buf.clear()
                    val n = ae.readSampleData(buf, 0)
                    if (n < 0) {
                        aDone = true
                    } else {
                        info.size = n
                        info.offset = 0
                        info.presentationTimeUs = ae.sampleTime
                        info.flags = if (ae.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                            MediaCodec.BUFFER_FLAG_SYNC_FRAME else 0
                        muxer.writeSampleData(outA, buf, info)
                        samples++
                    }
                }
                if (samples % 64 == 0L) onFrac(0.5f)
            }
            onFrac(0.9f)
            Log.i(TAG, "final mux finished: $samples samples (video=$vSamples)")
        } finally {
            try {
                if (started) muxer.stop()
            } catch (e: Exception) {
                Log.w(TAG, "final muxer stop failed", e)
            }
            try {
                muxer.release()
            } catch (e: Exception) {
                Log.w(TAG, "final muxer release failed", e)
            }
            try {
                ve.release()
            } catch (e: Exception) {
                Log.w(TAG, "video extractor release failed", e)
            }
            try {
                ae.release()
            } catch (e: Exception) {
                Log.w(TAG, "audio extractor release failed", e)
            }
        }
    }

    private fun moveOrCopy(from: File, to: File) {
        if (!from.renameTo(to)) {
            to.outputStream().use { out -> from.inputStream().use { it.copyTo(out, 1024 * 1024) } }
            if (!from.delete()) Log.w(TAG, "could not delete ${from.absolutePath} after copy")
        }
    }

    /** Post-finalize verification. Returns an error string, or null when OK. */
    private fun verifyMp4(file: File, w: Int, h: Int, expectedSec: Double): String? {
        return try {
            if (!file.exists() || file.length() <= 0L) return "file missing or empty"
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(file.absolutePath)
                val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val ww = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                val hh = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                val codec = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                Log.i(TAG, "verify: ${file.length() / 1024} KB, ${durMs}ms, ${ww}x$hh, codec=$codec")
                if (ww != w || hh != h) return "resolution is ${ww}x$hh, expected ${w}x$h"
                if (abs(durMs / 1000.0 - expectedSec) > 0.75) {
                    return "duration is ${durMs}ms, expected ~${(expectedSec * 1000).toLong()}ms"
                }
                val frame = r.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: return "first frame could not be decoded"
                frame.recycle()
                null
            } finally {
                runCatching { r.release() }
            }
        } catch (e: Exception) {
            "verification failed: ${rootMsg(e)}"
        }
    }

    // ================================================================ store

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
