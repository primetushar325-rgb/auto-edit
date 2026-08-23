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

    fun readSize(ctx: Context, uri: Uri): Pair<Int, Int>? = runCatching {
        ctx.contentResolver.openInputStream(uri)?.use {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(it, null, o)
            if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
        }
    }.getOrNull()

    /** Decode [uri] scaled so the longest side is <= [maxDim]. Null on failure. */
    fun decodeScaled(ctx: Context, uri: String, maxDim: Int): Bitmap? = runCatching {
        val u = Uri.parse(uri)
        val size = readSize(ctx, u) ?: return@runCatching null
        var sample = 1
        val longest = maxOf(size.first, size.second)
        while (longest / (sample * 2) >= maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        ctx.contentResolver.openInputStream(u)?.use {
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

    /** Quick bounds-only decode check: can this URI be decoded as an image? */
    fun isValidImage(ctx: Context, uri: String): Boolean = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(Uri.parse(uri))?.use {
            BitmapFactory.decodeStream(it, null, o)
        }
        o.outWidth > 0 && o.outHeight > 0
    }.getOrDefault(false)

    /** Duration of a video file in ms (0 on failure). */
    fun videoDurationMs(ctx: Context, uri: String): Long {
        val r = android.media.MediaMetadataRetriever()
        return try {
            val pfd = ctx.contentResolver.openFileDescriptor(Uri.parse(uri), "r") ?: return 0L
            try {
                r.setDataSource(pfd.fileDescriptor)
            } finally {
                try { pfd.close() } catch (_: Exception) {}
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
            val pfd = ctx.contentResolver.openFileDescriptor(Uri.parse(uri), "r") ?: return@runCatching null
            try {
                r.setDataSource(pfd.fileDescriptor)
            } finally {
                try { pfd.close() } catch (_: Exception) {}
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
