package com.autoedit.app

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import com.autoedit.engine.AudioConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Preview-only audio playback: two independent MediaPlayer tracks
 * (voice + music) kept in sync with the preview clock.
 * Loading/preparing happens on a background thread to avoid ANRs.
 */
class PreviewAudio(private val ctx: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var voice: MediaPlayer? = null
    private var music: MediaPlayer? = null
    private var playing = false

    private fun attrs(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    fun loadVoice(cfg: AudioConfig?) {
        scope.launch {
            val mp = cfg?.let { runCatching { safeMp(it.uri) }.getOrNull() }
            synchronized(lock) {
                releaseInner(voice)
                voice = mp
            }
        }
    }

    fun loadMusic(cfg: AudioConfig?) {
        scope.launch {
            val mp = cfg?.let { runCatching { safeMp(it.uri) }.getOrNull() }
            synchronized(lock) {
                releaseInner(music)
                music = mp
            }
        }
    }

    private fun safeMp(uri: String): MediaPlayer = MediaPlayer().apply {
        setDataSource(ctx, Uri.parse(uri))
        setAudioAttributes(attrs())
        prepare()
    }

    private fun releaseInner(mp: MediaPlayer?) {
        try {
            mp?.pause()
            mp?.release()
        } catch (_: Exception) {
        }
    }

    fun play() {
        playing = true
    }

    fun pause() {
        playing = false
        synchronized(lock) {
            pauseInner(voice)
            pauseInner(music)
        }
    }

    private fun pauseInner(mp: MediaPlayer?) {
        try {
            if (mp != null && mp.isPlaying) mp.pause()
        } catch (_: Exception) {
        }
    }

    /** Called every preview frame with the current playhead time. */
    fun tick(t: Double, voiceCfg: AudioConfig?, musicCfg: AudioConfig?) {
        if (!playing) return
        var v: MediaPlayer? = null
        var m: MediaPlayer? = null
        synchronized(lock) {
            v = voice
            m = music
        }
        tickOne(v, voiceCfg, t)
        tickOne(m, musicCfg, t)
    }

    private fun tickOne(mp: MediaPlayer?, cfg: AudioConfig?, t: Double) {
        if (mp == null || cfg == null || cfg.durationSec <= 0) return
        try {
            val v = cfg.volume.coerceIn(0f, 1f)
            mp.setVolume(v, v)
            val lt = t - cfg.offsetSec
            if (lt < 0 || (!cfg.loop && lt >= cfg.durationSec)) {
                if (mp.isPlaying) mp.pause()
                return
            }
            val pos = if (cfg.loop) lt % cfg.durationSec else lt
            val target = (pos * 1000).toLong()
            if (!mp.isPlaying) {
                mp.seekTo(target.toInt())
                mp.start()
            } else if (abs(mp.currentPosition - target) > 1500) {
                mp.seekTo(target.toInt())
            }
        } catch (_: Exception) {
        }
    }

    fun release() {
        playing = false
        synchronized(lock) {
            releaseInner(voice)
            releaseInner(music)
            voice = null
            music = null
        }
        scope.cancel()
    }
}
