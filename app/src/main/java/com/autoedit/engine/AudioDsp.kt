package com.autoedit.engine

/**
 * Pure audio DSP: mono-ization, linear resampling, and voice+music mixing.
 * No Android imports so this is unit-testable on the JVM.
 */
object AudioDsp {

    const val TARGET_RATE = 48000

    data class PcmAudio(val pcm: ShortArray, val sampleRate: Int) {
        val durationSec: Double get() = pcm.size.toDouble() / sampleRate
    }

    fun toMono(pcm: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return pcm
        val n = pcm.size / channels
        val out = ShortArray(n)
        for (i in 0 until n) {
            var sum = 0
            for (c in 0 until channels) sum += pcm[i * channels + c].toInt()
            out[i] = (sum / channels).toShort()
        }
        return out
    }

    fun resampleLinear(src: ShortArray, from: Int, to: Int): ShortArray {
        if (from == to) return src
        val nOut = ((src.size.toDouble() * to) / from).toInt().coerceAtLeast(0)
        val out = ShortArray(nOut)
        if (src.isEmpty()) return out
        for (i in 0 until nOut) {
            val pos = i * from.toDouble() / to
            val i0 = pos.toInt().coerceIn(0, src.size - 1)
            val i1 = (i0 + 1).coerceAtMost(src.size - 1)
            val frac = (pos - i0).toFloat()
            out[i] = (src[i0] * (1f - frac) + src[i1] * frac).toInt().toShort()
        }
        return out
    }

    /** Convert decoded PCM (any rate, 1-2ch) to mono 48 kHz. */
    fun normalize(pcm: ShortArray, channels: Int, fromRate: Int): PcmAudio {
        val mono = toMono(pcm, channels)
        val out = resampleLinear(mono, fromRate, TARGET_RATE)
        return PcmAudio(out, TARGET_RATE)
    }

    private fun sampleAt(a: PcmAudio, tSec: Double): Float {
        if (a.pcm.isEmpty()) return 0f
        val idx = (tSec * a.sampleRate).toInt().coerceIn(0, a.pcm.size - 1)
        return a.pcm[idx] / 32767f
    }

    private fun fadeGain(t: Double, dur: Double, fadeIn: Double, fadeOut: Double): Float {
        var g = 1f
        if (fadeIn > 0 && t < fadeIn) g = (t / fadeIn).toFloat().coerceIn(0f, 1f)
        if (fadeOut > 0 && t > dur - fadeOut) {
            g = minOf(g, ((dur - t) / fadeOut).toFloat().coerceIn(0f, 1f))
        }
        return g
    }

    /**
     * Mix voice + music into mono 48 kHz PCM of [totalSec] length.
     * - voice: offset, volume, fade in/out
     * - music: volume, loop, fade in/out, optional ducking while voice is active
     */
    fun mix(
        totalSec: Double,
        voice: PcmAudio?,
        voiceCfg: AudioConfig?,
        music: PcmAudio?,
        musicCfg: AudioConfig?,
        duckMusic: Boolean
    ): ShortArray {
        val n = (totalSec * TARGET_RATE).toInt().coerceAtLeast(0)
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i / TARGET_RATE.toDouble()
            var v = 0f
            var voiceActive = false
            if (voice != null && voiceCfg != null) {
                val tv = t - voiceCfg.offsetSec
                if (tv >= 0.0 && tv < voice.durationSec) {
                    voiceActive = true
                    v = sampleAt(voice, tv) * voiceCfg.volume *
                        fadeGain(tv, voice.durationSec, voiceCfg.fadeInSec, voiceCfg.fadeOutSec)
                }
            }
            var m = 0f
            if (music != null && musicCfg != null && music.durationSec > 0) {
                val tm = if (musicCfg.loop) t % music.durationSec else t
                if (tm < music.durationSec) {
                    m = sampleAt(music, tm) * musicCfg.volume *
                        fadeGain(tm, music.durationSec, musicCfg.fadeInSec, musicCfg.fadeOutSec)
                }
            }
            if (duckMusic && voiceActive && m != 0f) m *= 0.3f
            val sum = (v + m) * 32767f
            out[i] = sum.toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }
}
