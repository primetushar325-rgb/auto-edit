package com.autoedit.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri

/**
 * Memory-safe image decoding.
 * Always two-pass (bounds first, inSampleSize second) so even a 500-image
 * project with huge sources never loads full-res bitmaps.
 */
object ImageLoader {

    fun readSize(ctx: Context, uriOrPath: String): Pair<Int, Int>? = runCatching {
        ProjectStorage.openInput(ctx, uriOrPath)?.use {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(it, null, o)
            if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
        }
    }.getOrNull()

    /** Decode [uri] scaled so the longest side is <= [maxDim]. Null on failure. */
    fun decodeScaled(ctx: Context, uriOrPath: String, maxDim: Int): Bitmap? = runCatching {
        val size = readSize(ctx, uriOrPath) ?: return@runCatching null
        var sample = 1
        val longest = maxOf(size.first, size.second)
        while (longest / (sample * 2) >= maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        ProjectStorage.openInput(ctx, uriOrPath)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }.getOrNull()

    /** Quick display name from a content URI, falls back to a default. */
    fun displayName(ctx: Context, uri: String, fallback: String): String {
        val name = runCatching {
            val u = Uri.parse(uri)
            var out = fallback
            ctx.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) out = c.getString(0) ?: fallback }
            out
        }.getOrDefault(fallback)
        return name.ifBlank { fallback }
    }

    /** Quick bounds-only decode check: can this URI/path be decoded as an image? */
    fun isValidImage(ctx: Context, uriOrPath: String): Boolean = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ProjectStorage.openInput(ctx, uriOrPath)?.use {
            BitmapFactory.decodeStream(it, null, o)
        }
        o.outWidth > 0 && o.outHeight > 0
    }.getOrDefault(false)

    /** Duration of a video file in ms (0 on failure). */
    fun videoDurationMs(ctx: Context, uri: String): Long {
        val r = android.media.MediaMetadataRetriever()
        return try {
            val holder = openFdHolder(ctx, uri) ?: return 0L
            try {
                r.setDataSource(holder.fd)
            } finally {
                closeFdHolder(holder)
            }
            val ms = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            if (ms in 200..(60 * 60 * 1000)) ms else 0L
        } catch (e: Exception) {
            0L
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    /** A thumbnail frame from a video at [timeMs] (for timeline/preview cards). */
    fun videoThumb(ctx: Context, uri: String, timeMs: Long, maxDim: Int = 320): Bitmap? = runCatching {
        val r = android.media.MediaMetadataRetriever()
        try {
            val holder = openFdHolder(ctx, uri) ?: return@runCatching null
            try {
                r.setDataSource(holder.fd)
            } finally {
                closeFdHolder(holder)
            }
            val frame = r.getFrameAtTime(timeMs * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST)
                ?: return@runCatching null
            // scale down
            if (maxOf(frame.width, frame.height) <= maxDim) frame
            else {
                val sc = maxDim.toFloat() / maxOf(frame.width, frame.height)
                val w = (frame.width * sc).toInt().coerceAtLeast(16)
                val h = (frame.height * sc).toInt().coerceAtLeast(16)
                val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(out)
                c.drawBitmap(frame, null, android.graphics.RectF(0f, 0f, w.toFloat(), h.toFloat()), null)
                frame.recycle()
                out
            }
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }.getOrNull()

    private class FdHolder(val fd: java.io.FileDescriptor, val closable: (() -> Unit)?)

    private fun openFdHolder(ctx: Context, uriOrPath: String): FdHolder? = runCatching {
        if (ProjectStorage.isPath(uriOrPath)) {
            val f = java.io.File(uriOrPath)
            if (!f.exists()) return@runCatching null
            val ch = java.nio.channels.FileChannel.open(f.toPath(), java.nio.file.StandardOpenOption.READ)
            FdHolder(ch.fd) { runCatching { ch.close() } }
        } else {
            val pfd = ctx.contentResolver.openFileDescriptor(android.net.Uri.parse(uriOrPath), "r")
                ?: return@runCatching null
            FdHolder(pfd.fd) { runCatching { pfd.close() } }
        }
    }.getOrNull()

    private fun closeFdHolder(h: FdHolder?) {
        h?.closable?.invoke()
    }

    /** Fast duration estimate (ms) from media metadata, 0 on failure. */
    fun estimateDurationMs(ctx: Context, uri: String): Long {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(ctx, Uri.parse(uri))
            val ms = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            if (ms in 100..(12 * 3600 * 1000)) ms else 0L
        } catch (e: Exception) {
            0L
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }
}
