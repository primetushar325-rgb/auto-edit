// WrongConstant: MediaMuxer.OUTPUT_FORMAT_MPEG_4 (= 2) - this platform's android.jar
// stub omits the MediaMuxer OUTPUT_FORMAT_* constants, so the value is inlined.
@file:Suppress("WrongConstant")

package com.autoedit.export

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.autoedit.engine.AudioDsp
import java.io.File

/**
 * AAC-LC encoder for the audio track: PCM (mono 48 kHz) -> small temp .mp4
 * with a single audio track, which is later remuxed with the GPU-rendered
 * video track.
 *
 * Byte-buffer encoding (queueInputBuffer) is the CORRECT approach for audio -
 * the surface-based pipeline requirement applies to the video path only.
 */
object AacEncoder {

    private const val TAG = "AutoEditExport"
    // MediaMuxer.OUTPUT_FORMAT_MPEG_4 (= 2). The value is inlined because this
    // platform's android.jar stub omits the MediaMuxer OUTPUT_FORMAT_* constants.
    private const val OUTPUT_FORMAT_MPEG_4 = 2

    class AudioEncodeException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    fun encode(pcm: ShortArray, out: File) {
        val encoder = MediaCodec.createEncoderByType("audio/mp4a.4ac.001a010000")
        val muxer = MediaMuxer(out.absolutePath, OUTPUT_FORMAT_MPEG_4)
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
            Log.i(TAG, "aac encoder started (48kHz mono 128k)")
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
                            encoder.queueInputBuffer(
                                idx, 0, n * 2,
                                i.toLong() * 1_000_000_000L / AudioDsp.TARGET_RATE, 0
                            )
                            break
                        }
                    }
                    guard++
                    if (guard > 300) throw AudioEncodeException("AAC encoder stopped accepting frames.")
                }
                i += n
            }
            var guard = 0
            while (true) {
                val idx = encoder.dequeueInputBuffer(20_000)
                if (idx >= 0) {
                    encoder.queueInputBuffer(
                        idx, 0, 0,
                        i.toLong() * 1_000_000_000L / AudioDsp.TARGET_RATE,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
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
                            if (!started) {
                                muxer.start()
                                started = true
                            }
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
            Log.i(TAG, "aac encoder finished, ${if (started) "track written" else "NO DATA (empty audio)"}")
        } finally {
            try { encoder.stop() } catch (e: Exception) { Log.w(TAG, "aac encoder stop failed", e) }
            try { encoder.release() } catch (e: Exception) { Log.w(TAG, "aac encoder release failed", e) }
            try { if (started) muxer.stop() } catch (e: Exception) { Log.w(TAG, "aac muxer stop failed", e) }
            try { muxer.release() } catch (e: Exception) { Log.w(TAG, "aac muxer release failed", e) }
        }
    }
}
