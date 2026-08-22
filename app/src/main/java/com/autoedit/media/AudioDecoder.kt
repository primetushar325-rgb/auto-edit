package com.autoedit.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.autoedit.engine.AudioDsp
import java.io.ByteArrayOutputStream

class AudioDecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Decodes any audio file the Android codec stack supports (mp3, m4a, aac,
 * ogg, wav, flac, opus...) to mono 48 kHz PCM for mixing.
 * Decoding stops once [maxDurationSec] of output has been captured.
 */
object AudioDecoder {

    // Values mirror android.media.MediaCodec.BUFFER_FLAG_FORMAT_CHANGED / BUFFER_FLAG_CODEC_EOS
    private val FLAG_FORMAT_CHANGED = 0x02000000
    private val FLAG_CODEC_EOS = 0x80000000.toInt()

    fun decode(
        ctx: Context,
        uri: String,
        maxDurationSec: Double,
        isCancelled: () -> Boolean = { false }
    ): AudioDsp.PcmAudio {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        val pfd = ctx.contentResolver.openFileDescriptor(Uri.parse(uri), "r")
            ?: throw AudioDecodeException("Unable to open this audio file.")
        try {
            extractor.setDataSource(pfd.fileDescriptor)
            var trackIndex = -1
            var mime = ""
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("audio/")) {
                    trackIndex = i
                    mime = m
                    break
                }
            }
            if (trackIndex < 0) throw AudioDecodeException("No audio track found in this file.")
            extractor.selectTrack(trackIndex)
            val inFormat = extractor.getTrackFormat(trackIndex)
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inFormat, null, null, 0)
            decoder.start()

            val maxOutBytes = (maxDurationSec * 48000 * 2 * 2).toInt().coerceIn(256 * 1024, 120 * 1024 * 1024)
            val pcm = ByteArrayOutputStream()
            var inputDone = false
            var outputEos = false
            var channels = 2
            var sampleRate = 44100
            val info = MediaCodec.BufferInfo()

            var guard = 0
            while (!outputEos) {
                guard++
                if (guard > 1_000_000) break
                if (isCancelled()) throw VideoExporter.ExportCancelled()

                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)
                        if (buf != null) {
                            buf.clear()
                            val n = extractor.readSampleData(buf, 0)
                            if (n < 0) {
                                buf.clear()
                                decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            }
                        }
                    }
                }

                val outIdx = decoder.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx >= 0 -> {
                        val flags = info.flags
                        if (flags and FLAG_FORMAT_CHANGED != 0) {
                            val f = decoder.outputFormat
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (info.size > 0) {
                            val buf = decoder.getOutputBuffer(outIdx)
                            if (buf != null && buf.hasArray()) {
                                pcm.write(buf.array(), buf.arrayOffset() + info.offset, info.size)
                            }
                        }
                        decoder.releaseOutputBuffer(outIdx, false)
                        if (flags and FLAG_CODEC_EOS != 0) outputEos = true
                        if (inputDone && outputEos) break
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = decoder.outputFormat
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    // timeout / buffers changed: keep looping
                    else -> Unit
                }
                if (pcm.size() >= maxOutBytes) break
            }

            if (pcm.size() < 64) throw AudioDecodeException("Unable to decode this audio file.")
            val bytes = pcm.toByteArray()
            val nSamples = bytes.size / 2
            val samples = ShortArray(nSamples)
            var j = 0
            for (i in 0 until nSamples * 2 step 2) {
                samples[j++] = (bytes[i].toInt() or (bytes[i + 1].toInt() shl 8)).toShort()
            }
            return AudioDsp.normalize(samples, channels, sampleRate)
        } catch (e: AudioDecodeException) {
            throw e
        } catch (e: Exception) {
            throw AudioDecodeException("Unable to load this audio file.", e)
        } finally {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
            try { pfd.close() } catch (_: Exception) {}
        }
    }
}
