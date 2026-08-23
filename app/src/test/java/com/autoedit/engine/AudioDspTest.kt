package com.autoedit.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class AudioDspTest {

    private fun tone(rate: Int, seconds: Double, freq: Double = 220.0, amp: Short = 16000): AudioDsp.PcmAudio {
        val n = (seconds * rate).toInt()
        val pcm = ShortArray(n)
        for (i in 0 until n) {
            pcm[i] = (amp * sin(2.0 * Math.PI * freq * i / rate)).toInt().toShort()
        }
        return AudioDsp.PcmAudio(pcm, rate)
    }

    @Test
    fun `voice alone passes through with its volume`() {
        val v = tone(AudioDsp.TARGET_RATE, 1.0)
        val cfg = AudioConfig("u", "v", 1.0, volume = 1f)
        val out = AudioDsp.mix(1.0, v, cfg, null, null, false)
        assertEquals(AudioDsp.TARGET_RATE, out.size)
        // quarter period of 220 Hz = 1/880 s => peak of the sine
        val idx = (AudioDsp.TARGET_RATE / 880.0).toInt() + 1
        assertTrue("got ${out[idx]}", abs(out[idx].toInt()) > 10000)
    }

    @Test
    fun `volume scales the signal`() {
        val v = tone(AudioDsp.TARGET_RATE, 1.0)
        val full = AudioDsp.mix(1.0, v, AudioConfig("u", "v", 1.0, volume = 1f), null, null, false)
        val half = AudioDsp.mix(1.0, v, AudioConfig("u", "v", 1.0, volume = 0.5f), null, null, false)
        for (i in 0 until full.size step 97) {
            assertEquals(full[i].toInt().toDouble() / 2.0, half[i].toInt().toDouble(), 2.0)
        }
    }

    @Test
    fun `voice offset delays the signal`() {
        val v = tone(AudioDsp.TARGET_RATE, 1.0)
        val cfg = AudioConfig("u", "v", 1.0, offsetSec = 0.5)
        val out = AudioDsp.mix(1.0, v, cfg, null, null, false)
        // before offset: silence
        for (i in 0 until (0.45 * AudioDsp.TARGET_RATE).toInt()) {
            assertEquals(0, out[i].toInt())
        }
        // after offset: signal (phase chosen away from a zero crossing)
        assertTrue(abs(out[(0.72 * AudioDsp.TARGET_RATE).toInt()].toInt()) > 500)
    }

    @Test
    fun `ducking reduces music while voice is active`() {
        // voice = tiny constant-ish tone so the music contribution is measurable
        val v = tone(AudioDsp.TARGET_RATE, 1.0, freq = 10.0, amp = 1)
        val m = tone(AudioDsp.TARGET_RATE, 1.0, freq = 440.0)
        val vc = AudioConfig("u", "v", 1.0)
        val mc = AudioConfig("u", "m", 1.0, volume = 1f)
        val ducked = AudioDsp.mix(1.0, v, vc, m, mc, duckMusic = true)
        val noDuck = AudioDsp.mix(1.0, null, null, m, mc, duckMusic = false)
        var best = 0
        for (i in 0 until noDuck.size) {
            if (abs(noDuck[i].toInt()) > abs(noDuck[best].toInt())) best = i
        }
        assertTrue("ducked must be smaller", abs(ducked[best].toInt()) < abs(noDuck[best].toInt()))
        // duck factor 0.3 => music peak must drop to well below half of the original
        assertTrue(abs(ducked[best].toInt()) < abs(noDuck[best].toInt()) / 2)
    }

    @Test
    fun `music loop repeats`() {
        val m = tone(AudioDsp.TARGET_RATE, 1.0, amp = 8000)
        val mc = AudioConfig("u", "m", 1.0, volume = 1f, loop = true)
        val out = AudioDsp.mix(3.0, null, null, m, mc, false)
        assertEquals((3.0 * AudioDsp.TARGET_RATE).toInt(), out.size)
        // sample at t=2.25s should equal sample at t=0.25s (loop period 1s)
        assertEquals(out[(0.25 * AudioDsp.TARGET_RATE).toInt()], out[(2.25 * AudioDsp.TARGET_RATE).toInt()])
    }

    @Test
    fun `fade in starts silent`() {
        val v = tone(AudioDsp.TARGET_RATE, 2.0)
        val cfg = AudioConfig("u", "v", 2.0, fadeInSec = 1.0, fadeOutSec = 1.0)
        val out = AudioDsp.mix(2.0, v, cfg, null, null, false)
        assertEquals(0, out[0].toInt())
        assertTrue(abs(out[(0.01 * AudioDsp.TARGET_RATE).toInt()].toInt()) < 1000)
        // around the midpoint the signal must be at full strength
        var maxMid = 0
        for (i in ((0.9 * AudioDsp.TARGET_RATE).toInt())..((1.1 * AudioDsp.TARGET_RATE).toInt())) {
            if (abs(out[i].toInt()) > maxMid) maxMid = abs(out[i].toInt())
        }
        assertTrue("midpoint too quiet: $maxMid", maxMid > 10000)
        // tail fades to silence
        assertEquals(0, out[out.size - 1].toInt())
    }

    @Test
    fun `stereo is averaged to mono`() {
        val stereo = shortArrayOf(100, 200, 300, 400)
        val mono = AudioDsp.toMono(stereo, 2)
        assertEquals(2, mono.size)
        assertEquals(150, mono[0].toInt())
        assertEquals(350, mono[1].toInt())
    }

    @Test
    fun `resampling keeps length ratio and signal bounds`() {
        val src = tone(44100, 1.0)
        val out = AudioDsp.resampleLinear(src.pcm, 44100, AudioDsp.TARGET_RATE)
        val expected = (src.pcm.size.toDouble() * AudioDsp.TARGET_RATE / 44100).toInt()
        assertEquals(expected.toDouble(), out.size.toDouble(), 2.0)
        out.forEach { assertTrue(abs(it.toInt()) <= 16000) }
    }

    @Test
    fun `normalize to 48k mono`() {
        val stereo44 = tone(44100, 1.0)
        val stereo = ShortArray(stereo44.pcm.size * 2)
        for (i in stereo44.pcm.indices) {
            stereo[i * 2] = stereo44.pcm[i]
            stereo[i * 2 + 1] = stereo44.pcm[i]
        }
        val norm = AudioDsp.normalize(stereo, 2, 44100)
        assertEquals(AudioDsp.TARGET_RATE, norm.sampleRate)
        val expected = (stereo44.pcm.size.toDouble() * AudioDsp.TARGET_RATE / 44100).toInt()
        assertEquals(expected.toDouble(), norm.pcm.size.toDouble(), 2.0)
        assertEquals(1.0, norm.durationSec, 0.02)
    }

    @Test
    fun `clipping stays in bounds`() {
        val maxed = AudioDsp.PcmAudio(ShortArray(48000) { 30000 }, AudioDsp.TARGET_RATE)
        val out = AudioDsp.mix(
            1.0, maxed, AudioConfig("u", "v", 1.0),
            maxed, AudioConfig("u", "m", 1.0), false
        )
        out.forEach {
            assertTrue(it.toInt() in -32768..32767)
        }
        // both at full amplitude must saturate, not overflow
        assertTrue(out.maxOf { abs(it.toInt()) } == 32767)
    }
}
