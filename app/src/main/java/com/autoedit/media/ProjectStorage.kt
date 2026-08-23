package com.autoedit.media

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.InputStream

/**
 * Per-project scoped storage layout (app private storage, no shared storage):
 *
 *   filesDir/projects/<project_id>/
 *     source_images/   (copied images + video clips)
 *     audio/           (copied voice/music files)
 *     export/          (final .mp4 outputs)
 *     temp/            (intermediate render files - ALWAYS cleaned after export)
 *     project.json     (project document)
 *
 * Everything is copied into the project folder, so picker URI grants
 * expiring never breaks a saved project.
 */
object ProjectStorage {

    private const val TAG = "AutoEdit"
    private const val ORPHAN_MAX_AGE_MS = 24L * 60 * 60 * 1000

    fun baseDir(ctx: Context): File = File(ctx.filesDir, "projects")

    fun folder(ctx: Context, projectId: String): File = File(baseDir(ctx), projectId)

    fun sourceDir(ctx: Context, projectId: String): File = File(folder(ctx, projectId), "source_images")

    fun audioDir(ctx: Context, projectId: String): File = File(folder(ctx, projectId), "audio")

    fun exportDir(ctx: Context, projectId: String): File = File(folder(ctx, projectId), "export")

    fun tempDir(ctx: Context, projectId: String): File = File(folder(ctx, projectId), "temp")

    fun projectJsonFile(ctx: Context, projectId: String): File = File(folder(ctx, projectId), "project.json")

    /** Create the project folder tree (idempotent). */
    fun ensureProject(ctx: Context, projectId: String): File {
        val root = folder(ctx, projectId)
        sourceDir(ctx, projectId).mkdirs()
        audioDir(ctx, projectId).mkdirs()
        exportDir(ctx, projectId).mkdirs()
        tempDir(ctx, projectId).mkdirs()
        root.mkdirs()
        return root
    }

    /** True when [s] is a plain file path rather than a content URI. */
    fun isPath(s: String): Boolean = !s.startsWith("content://")

    fun openInput(ctx: Context, uriOrPath: String): InputStream? = runCatching {
        if (isPath(uriOrPath)) {
            File(uriOrPath).takeIf { it.exists() }?.inputStream()
        } else {
            ctx.contentResolver.openInputStream(Uri.parse(uriOrPath))
        }
    }.getOrNull()

    /** Copy a content URI (or path) into [dest]; returns true on success. */
    fun copyUriTo(ctx: Context, src: String, dest: File): Boolean = runCatching {
        val ins = openInput(ctx, src) ?: return false
        ins.use { input ->
            dest.parentFile?.mkdirs()
            dest.outputStream().use { input.copyTo(it, 1024 * 1024) }
        }
        dest.exists() && dest.length() > 0
    }.getOrDefault(false)

    /** Delete a whole project folder. */
    fun deleteProjectFolder(ctx: Context, projectId: String) {
        val ok = runCatching { folder(ctx, projectId).deleteRecursively() }.isSuccess
        if (!ok) Log.w(TAG, "could not delete project folder ${projectId}")
    }

    /** Total bytes used by one project folder. */
    fun folderSize(ctx: Context, projectId: String): Long {
        val root = folder(ctx, projectId)
        if (!root.exists()) return 0L
        var total = 0L
        root.walkTopDown().forEach { if (it.isFile) total += it.length() }
        return total
    }

    /**
     * Startup cleanup: remove files inside any project's temp/ older than 24h
     * (leftovers from crashed exports) and old global ae-* cache temp files.
     * Returns the number of files removed.
     */
    fun cleanOrphans(ctx: Context): Int {
        var removed = 0
        val now = System.currentTimeMillis()
        val base = baseDir(ctx)
        if (base.exists()) {
            base.listFiles()?.forEach { proj ->
                val temp = File(proj, "temp")
                if (temp.exists()) {
                    temp.walkTopDown().filter { it.isFile && now - it.lastModified() > ORPHAN_MAX_AGE_MS }
                        .forEach { if (it.delete()) removed++ }
                }
            }
        }
        ctx.cacheDir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.startsWith("ae-") && now - f.lastModified() > ORPHAN_MAX_AGE_MS) {
                if (f.delete()) removed++
            }
        }
        if (removed > 0) Log.i(TAG, "startup cleanup removed $removed orphan temp file(s)")
        return removed
    }
}
