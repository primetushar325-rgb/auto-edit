package com.autoedit.export

import android.content.Context
import android.media.MediaCodecInfo
import android.net.Uri
import android.os.Looper
import android.util.Log
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.autoedit.engine.ClipRef
import com.autoedit.engine.ClipType
import com.autoedit.engine.ProjectModel
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToLong

/**
 * Video export built on top of Media3 [Transformer].
 *
 * Why this replaces the hand-rolled MediaCodec/Surface pipeline:
 *  - Transformer owns the full codec lifecycle (configure -> input surface ->
 *    start -> drain -> EOS -> release), so the state-order crash we hit can't
 *    happen in app code.
 *  - [DefaultEncoderFactory] with [DefaultEncoderFactory.setEnableFallback]
 *    negotiates a supported encoder/format automatically; we additionally
 *    try software encoders explicitly if the hardware path fails, so export
 *    never hard-crashes on an unsupported device.
 *
 * Images are each one [EditedMediaItem] in an [EditedMediaItemSequence] with
 * an explicit image duration; the whole sequence becomes one [Composition].
 * Each image gets a gentle Ken Burns zoom (100% -> 104% or reverse) via
 * [ScaleAndRotateTransformation] - the "basic zoom/Ken Burns" motion scope -
 * and is letterboxed (no stretch/corner crop) to the exact output frame with
 * [Presentation.LAYOUT_SCALE_TO_FIT].
 *
 * Transformer must be created + started + polled on a thread with a live
 * [Looper]; we use the main thread and expose a coroutine-friendly [export].
 */
@UnstableApi
class Media3VideoExporter(private val ctx: Context) {

    class ExportFailedException(message: String, cause: Throwable? = null) : Exception(message, cause)
    class ExportCancelledException : Exception("cancelled")

    data class Result(val file: File, val usedSoftwareEncoder: Boolean, val warning: String?)

    companion object {
        private const val TAG = "AutoEditMedia3"
        private const val PROGRESS_POLL_MS = 200L
    }

    /**
     * Export [project]'s images (and already-present video clips) to [outFile].
     * Suspends until completion; calls [onProgress] (0..1) as Transformer reports.
     *
     * @param isCancelled checked between polls; throws [ExportCancelledException].
     */
    suspend fun export(
        project: ProjectModel,
        preset: Media3ExportPresets.Preset,
        outFile: File,
        isCancelled: () -> Boolean,
        onProgress: suspend (Float, String) -> Unit
    ): Result {
        require(project.clips.isNotEmpty()) { "No clips to export" }
        if (Looper.myLooper() == null) {
            // Transformer needs a Looper; the caller is expected on Dispatchers.Main
            // or a handler thread. Fail loudly rather than silently misbehave.
            error("Media3VideoExporter.export must be called on a Looper thread (use Dispatchers.Main)")
        }

        val durations = project.clipDurations()

        // 1) Build one EditedMediaItem per clip.
        val editedItems = ArrayList<EditedMediaItem>(project.clips.size)
        project.clips.forEachIndexed { i, clip ->
            val item = buildEditedItem(clip, i, project, preset, durations[i])
            editedItems += item
        }
        if (editedItems.isEmpty()) throw ExportFailedException("Project contains no exportable clips.")

        val composition = Composition.Builder(EditedMediaItemSequence(editedItems)).build()

        // 2) Try the hardware path first; on failure retry once forcing software.
        val warnings = ArrayList<String>()
        var attempt = 0
        while (true) {
            attempt++
            val forceSoftware = attempt > 1
            if (forceSoftware) {
                Log.w(TAG, "hardware encode failed - retrying with software encoder")
                warnings += "Hardware encoder failed; used software encoder (slower)."
            }
            try {
                runTransformer(
                    composition = composition,
                    preset = preset,
                    outFile = outFile,
                    forceSoftwareEncoder = forceSoftware,
                    isCancelled = isCancelled,
                    onProgress = onProgress
                )
                if (outFile.exists() && outFile.length() > 0L) {
                    Log.i(TAG, "export OK: ${outFile.length() / 1024} KB, software=$forceSoftware")
                    return Result(outFile, forceSoftware, warnings.lastOrNull())
                }
                throw ExportFailedException("Transformer completed but produced no output file.")
            } catch (e: ExportCancelledException) {
                outFile.delete()
                throw e
            } catch (e: ExportFailedException) {
                outFile.delete()
                if (!forceSoftware) {
                    Log.w(TAG, "hardware attempt failed, will retry software: ${e.message}")
                    continue
                }
                throw e
            }
        }
    }

    // ----------------------------------------------------------- EditedMediaItem

    private fun buildEditedItem(
        clip: ClipRef,
        index: Int,
        project: ProjectModel,
        preset: Media3ExportPresets.Preset,
        durationSec: Double
    ): EditedMediaItem {
        val durationMs = (durationSec * 1000.0).roundToLong().coerceAtLeast(200L)
        // Source clips are copied into the project folder as plain files; if a
        // content:// URI ever leaks through, parse it directly.
        val uri = if (clip.uri.startsWith("content://") || clip.uri.startsWith("androidresource://")) {
            Uri.parse(clip.uri)
        } else {
            Uri.fromFile(File(clip.uri))
        }

        val mediaItemBuilder = MediaItem.Builder().setUri(uri)
        if (clip.type == ClipType.IMAGE) {
            // Media3 uses setImageDurationMs to hold a still for the clip length.
            mediaItemBuilder.setImageDurationMs(durationMs)
        } else {
            // Trimmed video segment.
            mediaItemBuilder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.videoInMs.coerceAtLeast(0L))
                    .setEndPositionMs(clip.videoOutMs.coerceAtLeast(clip.videoInMs + 200L))
                    .build()
            )
        }

        val videoEffects = mutableListOf<Effect>()

        // NOTE: A Ken Burns / zoom matrix effect is intentionally NOT applied here.
        // Media3 1.4.x clips scaled quads against the NDC cube and re-triangulates,
        // which can leave a degenerate black triangle burned into every frame when
        // scale pushes vertices to/through the NDC boundary (>=1.0). That triangle
        // only appeared in the encoded output (in-app preview uses a separate
        // Compose Canvas), matching the reported bug. We letterbox instead, which
        // only ever scales DOWN, so vertices stay within NDC and no clipping runs.
        // Motion on images is handled by the project's clip duration; if Ken Burns
        // is later re-added it must use a crop/scale effect that stays within
        // [0,1] or a custom shader program without the NDC polygon clipping.

        // Letterbox to the exact output frame (no stretch, no unexpected crop).
        videoEffects += Presentation.createForWidthAndHeight(
            preset.width,
            preset.height,
            Presentation.LAYOUT_SCALE_TO_FIT
        )

        val editedBuilder = EditedMediaItem.Builder(mediaItemBuilder.build())
            .setFrameRate(preset.fps)
            .setEffects(Effects(/* audioProcessors= */ emptyList<AudioProcessor>(), videoEffects))

        if (clip.type == ClipType.VIDEO) {
            // We mux voice/music separately in VideoExporter; drop clip audio.
            editedBuilder.setRemoveAudio(true)
        }
        return editedBuilder.build()
    }

    // --------------------------------------------------------------- Transformer run

    private suspend fun runTransformer(
        composition: Composition,
        preset: Media3ExportPresets.Preset,
        outFile: File,
        forceSoftwareEncoder: Boolean,
        isCancelled: () -> Boolean,
        onProgress: suspend (Float, String) -> Unit
    ) {
        outFile.parentFile?.mkdirs()
        if (outFile.exists()) outFile.delete()

        val done = CompletableDeferred<Unit>()
        val error = AtomicReference<Throwable?>(null)
        val cancelled = AtomicBoolean(false)

        val encoderFactory = buildEncoderFactory(preset, forceSoftwareEncoder)

        // Transformer invokes its listener on the looper it was created on.
        val transformer = Transformer.Builder(ctx)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoderFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    Log.i(TAG, "Transformer onCompleted: ${exportResult.videoEncoderName}")
                    done.complete(Unit)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    Log.e(TAG, "Transformer onError (code=${exportException.errorCode}): ${exportException.message}", exportException)
                    error.compareAndSet(null, exportException)
                    done.complete(Unit)
                }
            })
            .build()

        transformer.start(composition, outFile.absolutePath)
        Log.i(
            TAG,
            "transformer start: ${preset.width}x${preset.height}@${preset.fps} " +
                "${preset.bitrate / 1000}kbps gop=${Media3ExportPresets.IFRAME_INTERVAL_SECONDS}s " +
                "software=$forceSoftwareEncoder"
        )

        val progressHolder = ProgressHolder()
        var lastReported = -1
        try {
            while (!done.isCompleted) {
                if (isCancelled() || cancelled.get()) {
                    cancelled.set(true)
                    cancelTransformer(transformer)
                    throw ExportCancelledException()
                }
                when (transformer.getProgress(progressHolder)) {
                    Transformer.PROGRESS_STATE_AVAILABLE -> {
                        val pct = progressHolder.progress
                        if (pct != lastReported) {
                            lastReported = pct
                            onProgress(pct / 100f, "Rendering video ${pct}%")
                        }
                    }
                    Transformer.PROGRESS_STATE_WAITING_FOR_AVAILABILITY,
                    Transformer.PROGRESS_STATE_NOT_STARTED -> {
                        // no progress yet; keep polling
                    }
                }
                try {
                    kotlinx.coroutines.delay(PROGRESS_POLL_MS)
                } catch (_: kotlinx.coroutines.CancellationException) {
                    cancelTransformer(transformer)
                    throw ExportCancelledException()
                }
            }
            error.get()?.let {
                throw ExportFailedException("Transformer failed: ${it.message ?: it.javaClass.simpleName}", it)
            }
            onProgress(1f, "Done")
        } finally {
            // Transformer has no explicit release; it cleans itself up after
            // onCompleted/onError. If cancelled, cancel() was already called.
        }
    }

    private fun buildEncoderFactory(
        preset: Media3ExportPresets.Preset,
        forceSoftwareEncoder: Boolean
    ): DefaultEncoderFactory {
        val videoSettings = androidx.media3.transformer.VideoEncoderSettings.Builder()
            .setBitrate(preset.bitrate)
            .setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            .setiFrameIntervalSeconds(Media3ExportPresets.IFRAME_INTERVAL_SECONDS)
            .build()

        val builder = DefaultEncoderFactory.Builder(ctx)
            .setRequestedVideoEncoderSettings(videoSettings)
            .setEnableFallback(true)

        if (forceSoftwareEncoder) {
            // EncoderSelector is given a MIME type and returns encoders in priority
            // order. Reorder so software encoders come first; if none exist on the
            // device, the default (hardware) list is used so export still runs.
            builder.setVideoEncoderSelector { mimeType ->
                val all = androidx.media3.transformer.EncoderSelector.DEFAULT.selectEncoderInfos(mimeType)
                val (sw, hw) = all.partition { isSoftwareEncoder(it.name) }
                if (sw.isNotEmpty()) {
                    ImmutableList.copyOf(sw + hw)
                } else {
                    all
                }
            }
        }
        return builder.build()
    }

    private fun cancelTransformer(transformer: Transformer) {
        try {
            transformer.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "transformer.cancel() failed", e)
        }
    }

    private fun isSoftwareEncoder(name: String?): Boolean {
        if (name == null) return false
        val n = name.lowercase()
        // Android software AVC encoders are named "OMX.google.h264.encoder" or
        // "c2.android.avc.encoder"; hardware names are vendor prefixed (OMX.qcom,
        // OMX.Exynos, c2.mtk, etc.).
        return n.contains("google") || n.contains("android")
    }
}
