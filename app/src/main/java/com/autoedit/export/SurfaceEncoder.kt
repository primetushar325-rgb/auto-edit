// WrongConstant: MediaMuxer.OUTPUT_FORMAT_MPEG_4 (= 2) inlined - see AacEncoder.kt.
@file:Suppress("WrongConstant")

package com.autoedit.export

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import android.view.Surface
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Hardware H.264 encoder driven through a MediaCodec INPUT SURFACE.
 *
 * The GPU renderer ([GpuFrameRenderer]) draws frames straight into the
 * encoder's input surface via EGL; presentation timestamps are set with
 * EGLExt.eglPresentationTimeANDROID before each swap. This class owns:
 *
 *  - encoder configuration (COLOR_FormatSurface, CBR, profile left to the
 *    device, keyframe interval 1s)
 *  - the MediaMuxer for the temp video MP4 (format change, start, write,
 *    stop, release - all handled exactly once)
 *  - the output drain loop (never an unbounded spin: bounded timeouts + a
 *    hard EOS deadline)
 *  - a stall watchdog: if the encoder stops producing output for
 *    STALL_MS while frames are still being rendered, it stops the encoder
 *    (which unblocks a stuck EGL swap) and flags the run so the pipeline
 *    aborts with a controlled, user-visible error instead of freezing.
 */
class SurfaceEncoder(
    private val w: Int,
    private val h: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val videoFile: File
) {
    class EncoderStuckException :
        Exception("Video encoder stopped responding. Please try again.")

    class EncoderStartException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    companion object {
        private const val TAG = "AutoEditExport"
        // MediaMuxer.OUTPUT_FORMAT_MPEG_4 (= 2) - inlined because this platform's
        // android.jar stub omits the MediaMuxer OUTPUT_FORMAT_* constants.
        private const val OUTPUT_FORMAT_MPEG_4 = 2
        private const val STALL_MS = 20_000L
        private const val EOS_DEADLINE_MS = 30_000L
        private const val DRAIN_TIMEOUT_US = 50_000L
    }

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var muxerStarted = false
    private var muxerStopped = false
    private var codecStopped = false
    private var eosSent = false
    private var samplesWritten = 0
    private var framesAccepted = 0L

    private val active = AtomicBoolean(true)
    private val stalled = AtomicBoolean(false)
    private val lastActivity = AtomicLong(System.nanoTime())
    private var watchdog: Thread? = null

    /** The encoder input surface to render into (valid after [init]). */
    var inputSurface: Surface? = null
        private set

    /** True when the encoder died on its own (watchdog fired). */
    val isStalled: Boolean get() = stalled.get()

    /** Configure + start the encoder; returns nothing, sets [inputSurface]. */
    fun init() {
        val c = try {
            MediaCodec.createEncoderByType(EncoderCapabilities.MIME_AVC)
        } catch (e: Exception) {
            throw EncoderStartException("No H.264 hardware encoder available: ${e.message}", e)
        }
        codec = c
        val fmt = MediaFormat.createVideoFormat(EncoderCapabilities.MIME_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        }
        try {
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            throw EncoderStartException("H.264 encoder configuration failed: ${e.message}", e)
        }
        try {
            c.start()
        } catch (e: Exception) {
            throw EncoderStartException("H.264 encoder start failed: ${e.message}", e)
        }
        val s = try {
            c.createInputSurface()
        } catch (e: Exception) {
            throw EncoderStartException("Failed to create encoder input surface: ${e.message}", e)
        }
        inputSurface = s
        muxer = try {
            MediaMuxer(videoFile.absolutePath, OUTPUT_FORMAT_MPEG_4)
        } catch (e: Exception) {
            throw EncoderStartException("Could not create output file: ${e.message}", e)
        }
        lastActivity.set(System.nanoTime())
        Log.i(
            TAG,
            "encoder ready: avc ${w}x$h@${fps} ${bitrate / 1000}kbps CBR, COLOR_FormatSurface, input surface created"
        )
        startWatchdog()
    }

    private fun startWatchdog() {
        val t = Thread {
            while (active.get() && !stalled.get()) {
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (eosSent) break
                val idleMs = (System.nanoTime() - lastActivity.get()) / 1_000_000
                if (idleMs > STALL_MS) {
                    Log.e(
                        TAG,
                        "encoder stall detected: no output for ${idleMs}ms while rendering - stopping encoder"
                    )
                    stalled.set(true)
                    try {
                        codec?.stop()
                        codecStopped = true
                    } catch (e: Exception) {
                        Log.w(TAG, "watchdog codec.stop failed", e)
                    }
                    return@Thread
                }
            }
        }
        t.isDaemon = true
        t.name = "ae-encoder-watchdog"
        watchdog = t
        t.start()
    }

    /**
     * Drain available encoder output into the muxer.
     * @param blocking true to wait up to [DRAIN_TIMEOUT_US] for output.
     * @return true when an end-of-stream sample was written.
     * @throws EncoderStuckException when the watchdog fired.
     */
    fun drain(blocking: Boolean = false): Boolean {
        if (stalled.get()) throw EncoderStuckException()
        val c = codec ?: return false
        val m = muxer ?: return false
        var deadline = System.nanoTime() + if (blocking) DRAIN_TIMEOUT_US * 1000L else 0
        var eos = false
        while (true) {
            if (stalled.get()) throw EncoderStuckException()
            val now = System.nanoTime()
            val timeoutUs = if (blocking && now < deadline) ((deadline - now) / 1000L).toInt() else 0
            val o = try {
                c.dequeueOutputBuffer(info, timeoutUs.toLong())
            } catch (e: IllegalStateException) {
                // encoder already stopped (watchdog/cancel)
                Log.w(TAG, "drain after encoder stop", e)
                return eos
            }
            if (o == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!blocking) break
                if (System.nanoTime() >= deadline) break
                continue
            }
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (track < 0) {
                    track = m.addTrack(c.outputFormat)
                    Log.i(TAG, "encoder output format changed - muxer track ${track} added")
                }
                continue
            }
            if (o < 0) continue
            if (info.flags and MediaCodec.INFO_OUTPUT_FORMAT_CHANGED != 0 && track < 0) {
                track = m.addTrack(c.outputFormat)
            }
            if (info.size > 0 && track >= 0) {
                if (!muxerStarted) {
                    m.start()
                    muxerStarted = true
                    Log.i(TAG, "muxer started (video track ${track})")
                }
                val buf = c.getOutputBuffer(o)
                if (buf != null) {
                    m.writeSampleData(track, buf, info)
                }
                samplesWritten++
            }
            c.releaseOutputBuffer(o, false)
            lastActivity.set(System.nanoTime())
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                eos = true
                break
            }
        }
        return eos
    }

    /** Signal end of input and wait for the EOS sample. */
    fun finishEos(isCancelled: () -> Boolean) {
        val c = codec ?: return
        if (!eosSent) {
            c.signalEndOfInputStream()
            eosSent = true
            Log.i(TAG, "end-of-stream signaled to encoder (${framesAccepted} frames rendered)")
        }
        val deadline = System.currentTimeMillis() + EOS_DEADLINE_MS
        while (true) {
            if (isCancelled()) return
            if (stalled.get()) throw EncoderStuckException()
            if (System.currentTimeMillis() > deadline) {
                throw EncoderStuckException()
            }
            if (drain(blocking = true)) {
                Log.i(TAG, "EOS received - ${samplesWritten} video samples written")
                return
            }
        }
    }

    /** Stop the encoder + muxer and release everything. Safe to call twice. */
    fun release() {
        active.set(false)
        watchdog?.let { runCatching { it.interrupt() } }
        watchdog = null
        val c = codec
        if (c != null && !codecStopped) {
            runFinalize("encoder stop") {
                try { c.stop() } catch (e: Exception) { Log.w(TAG, "encoder stop failed", e) }
            }
            codecStopped = true
        }
        runFinalize("encoder release") {
            try { c?.release() } catch (e: Exception) { Log.w(TAG, "encoder release failed", e) }
        }
        codec = null
        val m = muxer
        if (m != null) {
            runFinalize("muxer stop") {
                try {
                    if (muxerStarted && !muxerStopped) {
                        m.stop()
                        muxerStopped = true
                        Log.i(TAG, "muxer stopped")
                    }
                } catch (e: Exception) { Log.w(TAG, "muxer stop failed", e) }
            }
            runFinalize("muxer release") {
                try { m.release() } catch (e: Exception) { Log.w(TAG, "muxer release failed", e) }
            }
        }
        muxer = null
        inputSurface = null
    }

    /** A frame was rendered + swapped: updates the watchdog timestamp. */
    fun noteFrameRendered() {
        framesAccepted++
        lastActivity.set(System.nanoTime())
    }

    /**
     * Run a native cleanup call on a daemon thread with a hard timeout so a
     * stuck native stop()/release() can never hang the export coroutine.
     */
    private fun runFinalize(name: String, block: () -> Unit) {
        val t = Thread {
            try {
                block()
            } catch (e: Throwable) {
                Log.w(TAG, "$name failed", e)
            }
        }
        t.isDaemon = true
        t.name = "ae-finalize-$name"
        t.start()
        try {
            t.join(8_000)
        } catch (_: InterruptedException) {
        }
        if (t.isAlive) {
            Log.e(TAG, "$name did not finish in 8000ms - abandoning cleanup thread")
        }
    }
}
