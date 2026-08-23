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
        var pfd: android.os.ParcelFileDescriptor? = null
        return try {
            pfd = setupRetriever(ctx, uri, r)
            val ms = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            if (ms in 200..(60 * 60 * 1000)) ms else 0L
        } catch (e: Exception) {
            0L
        } finally {
            runCatching { r.release() }
            runCatching { pfd?.close() }
        }
    }

    /** A thumbnail frame from a video at [timeMs] (for timeline/preview cards). */
    fun videoThumb(ctx: Context, uri: String, timeMs: Long, maxDim: Int = 320): Bitmap? = runCatching {
        val r = android.media.MediaMetadataRetriever()
        var pfd: android.os.ParcelFileDescriptor? = null
        try {
            pfd = setupRetriever(ctx, uri, r)
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
            runCatching { r.release() }
            runCatching { pfd?.close() }
        }
    }.getOrNull()

    private fun setupRetriever(ctx: Context, uriOrPath: String, r: android.media.MediaMetadataRetriever): android.os.ParcelFileDescriptor? {
        return if (ProjectStorage.isPath(uriOrPath)) {
            if (!java.io.File(uriOrPath).exists()) throw Exception("File missing: $uriOrPath")
            r.setDataSource(uriOrPath)
            null
        } else {
            val pfd = ctx.contentResolver.openFileDescriptor(android.net.Uri.parse(uriOrPath), "r")
                ?: throw Exception("Unable to open: $uriOrPath")
            r.setDataSource((pfd as android.content.res.AssetFileDescriptor).fd)
            pfd // caller keeps it open until done with the retriever
        }
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
