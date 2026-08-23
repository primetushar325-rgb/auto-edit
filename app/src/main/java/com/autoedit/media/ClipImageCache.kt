package com.autoedit.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.autoedit.engine.AspectRatio
import com.autoedit.engine.FrameState
import com.autoedit.engine.ProjectModel

/**
 * Tiny LRU of full-quality (for the export frame size) bitmaps.
 * Only 3 entries are kept in RAM at any time, so a 500-image project
 * never puts real memory pressure on the device.
 */
class ClipImageCache(
    private val ctx: Context,
    private val frameW: Int,
    private val frameH: Int
) {
    private val maxDim = (maxOf(frameW, frameH) * 1.5f).toInt()
    private val cache = LinkedHashMap<Int, Bitmap>()
    private val lock = Any()
    private val capacity = 3

    fun get(idx: Int): Bitmap? = synchronized(lock) { cache[idx] }

    fun prepare(state: FrameState, project: ProjectModel) {
        val needed = if (state.prevIndex >= 0) listOf(state.clipIndex, state.prevIndex) else listOf(state.clipIndex)
        for (i in needed) {
            if (synchronized(lock) { cache.containsKey(i) }) continue
            val clip = project.clips.getOrNull(i) ?: continue
            val bmp = decodeFull(clip.uri) ?: continue
            synchronized(lock) {
                cache.remove(i)
                cache[i] = bmp
                while (cache.size > capacity) {
                    val oldest = cache.keys.first()
                    val evicted = cache.remove(oldest)
                    try { evicted?.recycle() } catch (_: Exception) {}
                }
            }
        }
    }

    private fun decodeFull(uriStr: String): Bitmap? = runCatching {
        val u = Uri.parse(uriStr)
        val size = ImageLoader.readSize(ctx, u) ?: return@runCatching null
        var sample = 1
        val longest = maxOf(size.first, size.second)
        while (longest / (sample * 2) >= maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        ctx.contentResolver.openInputStream(u)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }.getOrNull()

    fun clear() {
        synchronized(lock) {
            cache.values.forEach { b ->
                try { if (!b.isRecycled) b.recycle() } catch (_: Exception) {}
            }
            cache.clear()
        }
    }
}
