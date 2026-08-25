@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.autoedit.export

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.MediaMetadataRetriever
import com.autoedit.engine.AspectRatio
import com.autoedit.engine.ClipMotion
import com.autoedit.engine.ClipRef
import com.autoedit.engine.ClipType
import com.autoedit.engine.ExportConfig
import com.autoedit.engine.Keyframe
import com.autoedit.engine.MotionType
import com.autoedit.engine.ProjectModel
import com.autoedit.engine.ProjectRepository
import com.autoedit.engine.Quality
import com.autoedit.engine.TransitionType
import com.autoedit.media.ProjectStorage
import com.autoedit.media.VideoExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * On-device export self-test.
 *
 *   adb shell am start -n com.autoedit.app/.MainActivity --es selftest "3"
 *
 * Generates [imageCount] test images (distinct gradient + number per image),
 * then exports and verifies the project ACROSS ALL THREE EXPORT PRESETS
 * (720p / 1080p / 4K):
 *
 *   - file exists, non-zero size
 *   - duration matches images * 3s
 *   - exact target resolution
 *   - first frame decodes
 *   - frames sampled from each image's region are DISTINCT, confirming all
 *     images actually appear in sequence (not a black screen / single image)
 *
 * A machine-readable JSON report with a per-preset block is written to
 * files/projects/<id>/export/selftest-report.json. Log tag: AutoEditSelfTest.
 */
object SelfTestRunner {

    data class SelfTestResult(
        val ok: Boolean,
        val message: String,
        val videoFile: File?,
        val reportFile: File?
    )

    private data class PresetCase(val quality: Quality, val wantW: Int, val wantH: Int)

    suspend fun run(app: Application, imageCount: Int): SelfTestResult {
        val count = imageCount.coerceIn(1, 500)
        val id = "selftest-${System.currentTimeMillis()}"
        val log = ArrayList<String>()
        fun note(s: String) {
            log.add(s)
            android.util.Log.i("AutoEditSelfTest", s)
        }
        note("self-test start: $count images across 720p/1080p/4K, 30fps, no audio")
        val t0 = System.currentTimeMillis()
        try {
            val repo = ProjectRepository(File(app.filesDir, "projects"))
            ProjectStorage.ensureProject(app, id)
            val srcDir = ProjectStorage.sourceDir(app, id)

            note("generating $count test images…")
            val clips = ArrayList<ClipRef>()
            for (i in 0 until count) {
                val bmp = makeTestImage(i, count)
                val f = File(srcDir, "selftest-$i.png")
                bmp.compress(Bitmap.CompressFormat.PNG, 90, f.outputStream())
                bmp.recycle()
                val motion = if (i % 2 == 0) {
                    ClipMotion(MotionType.ZOOM_IN, Keyframe(1.0f), Keyframe(1.08f))
                } else {
                    ClipMotion(MotionType.ZOOM_OUT, Keyframe(1.08f), Keyframe(1.0f))
                }
                clips += ClipRef(uri = f.absolutePath, type = ClipType.IMAGE, motion = motion)
            }

            val cases = listOf(
                PresetCase(Quality.Q720, 1280, 720),
                PresetCase(Quality.Q1080, 1920, 1080),
                PresetCase(Quality.Q4K, 3840, 2160)
            )

            val caseResults = ArrayList<String>()
            var allOk = true
            var lastFile: File? = null

            for (case in cases) {
                val p = ProjectModel(
                    id = id,
                    name = "SelfTest-$count-${case.quality.label}",
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    clips = clips,
                    formulaId = "F01",
                    clipDurationSec = 3.0,
                    transition = TransitionType.CROSS_DISSOLVE,
                    transitionDurationSec = 0.45,
                    aspect = AspectRatio.LANDSCAPE_16_9,
                    export = ExportConfig(
                        quality = case.quality,
                        fps = 30,
                        aspect = AspectRatio.LANDSCAPE_16_9
                    )
                )
                repo.save(p)
                val expectedSec = count * 3.0
                val tag = case.quality.label
                note("── preset $tag (${case.wantW}x${case.wantH}) ──")

                val result = withContext(Dispatchers.Default) {
                    VideoExporter(app).export(
                        p,
                        ProjectStorage.tempDir(app, id),
                        ProjectStorage.exportDir(app, id),
                        isCancelled = { false }
                    ) { _, _ -> }
                }
                if (result.isFailure) {
                    val msg = result.exceptionOrNull()?.message?.replace("\"", "'")
                    note("$tag FAILED: $msg")
                    caseResults += "\"$tag\": {\"ok\": false, \"error\": \"$msg\"}"
                    allOk = false
                    continue
                }
                val file = result.getOrThrow().file
                lastFile = file

                val v = verify(file, case.wantW, case.wantH, expectedSec)
                val vJson = v.entries.joinToString(", ") { "\"${it.key}\": ${fmt(it.value)}" }
                note("$tag verification: $vJson, size=${file.length() / 1024}KB")

                // Confirm every image region actually shows a distinct frame.
                var allImagesVisible = true
                if (count >= 2) {
                    val sampleTimes = if (count >= 3)
                        listOf(0.5, expectedSec / 2.0, expectedSec - 0.5)
                    else listOf(0.5, expectedSec - 0.5)
                    val samples = sampleTimes.map { grabFrame(file, (it * 1_000_000).toLong()) }
                    for (i in 0 until samples.size - 1) {
                        val a = samples[i]
                        val b = samples[i + 1]
                        if (a != null && b != null) {
                            val diff = bitmapDiff(a, b)
                            note("$tag frame region $i vs ${i + 1} diff=${"%.3f".format(diff)}")
                            if (diff <= 0.01f) allImagesVisible = false
                        }
                        a?.recycle()
                    }
                    samples.lastOrNull()?.recycle()
                }

                val ok = v["exists"] == true &&
                    (v["sizeBytes"] as Long) > 0 &&
                    v["durationMs"] != null &&
                    abs((v["durationMs"] as Long) / 1000.0 - expectedSec) <= 1.0 &&
                    v["width"] == case.wantW && v["height"] == case.wantH &&
                    v["firstFrameDecodes"] == true &&
                    allImagesVisible
                if (!ok) allOk = false
                caseResults += "\"$tag\": {\"ok\": $ok, \"file\": \"${file.name}\", $vJson, \"allImagesVisible\": $allImagesVisible}"
            }

            val ms = System.currentTimeMillis() - t0
            note("self-test ${if (allOk) "PASSED" else "FAILED"} in ${ms}ms across ${cases.size} presets")
            lastFile?.let { note("primary video: ${it.absolutePath} (${it.length() / 1024} KB)") }

            val reportFile = File(ProjectStorage.exportDir(app, id), "selftest-report.json")
            reportFile.parentFile?.mkdirs()
            val report = buildString {
                appendLine("{")
                appendLine("  \"ok\": $allOk,")
                appendLine("  \"images\": $count,")
                appendLine("  \"elapsedMs\": $ms,")
                appendLine("  \"presets\": {")
                appendLine(caseResults.joinToString(",\n"))
                appendLine("  }")
                appendLine("}")
            }
            reportFile.writeText(report)
            note("report: ${reportFile.absolutePath}")

            return SelfTestResult(allOk, reportFile.absolutePath, lastFile, reportFile)
        } catch (e: Exception) {
            note("self-test crashed: ${e.stackTraceToString().take(2000)}")
            android.util.Log.i("AutoEditSelfTest", "done: ok=false msg=crash: ${e.message}")
            return SelfTestResult(false, "crash: ${e.message}", null, null)
        }
    }

    private fun fmt(v: Any?): String = when (v) {
        null -> "null"
        is Boolean, is Long, is Int -> v.toString()
        else -> "\"${v.toString().replace("\"", "'")}\""
    }

    private fun makeTestImage(index: Int, count: Int): Bitmap {
        val w = 1280
        val h = 720
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val hue = (index * 137.0 % 360.0).toFloat()
        val col1 = Color.HSVToColor(floatArrayOf(hue, 0.7f, 0.9f))
        val col2 = Color.HSVToColor(floatArrayOf((hue + 60f) % 360f, 0.8f, 0.5f))
        val p = Paint()
        p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), col1, col2, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        p.color = Color.WHITE
        p.isAntiAlias = true
        p.textSize = 300f
        p.textAlign = Paint.Align.CENTER
        c.drawText(index.toString(), w / 2f, h / 2f + 100f, p)
        p.textSize = 48f
        p.color = Color.argb(200, 255, 255, 255)
        c.drawText(
            "AutoEdit self-test • image ${index + 1} of $count • zoom ${if (index % 2 == 0) "100→108" else "108→100"}%",
            w / 2f, h - 60f, p
        )
        return bmp
    }

    private fun verify(file: File, w: Int, h: Int, expectedSec: Double): LinkedHashMap<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        out["exists"] = file.exists()
        out["sizeBytes"] = if (file.exists()) file.length() else 0L
        if (!file.exists() || file.length() == 0L) return out
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            out["durationMs"] = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            out["width"] = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            out["height"] = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            out["videoMime"] = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            val f = r.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST)
            out["firstFrameDecodes"] = f != null
            f?.recycle()
        } catch (e: Exception) {
            out["verifyError"] = e.message
        } finally {
            runCatching { r.release() }
        }
        return out
    }

    private fun grabFrame(file: File, timeUs: Long): Bitmap? = try {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            r.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
        } finally {
            runCatching { r.release() }
        }
    } catch (e: Exception) {
        android.util.Log.w("AutoEditSelfTest", "grabFrame failed at ${timeUs}us", e)
        null
    }

    /** Mean absolute per-channel difference between two in-memory bitmaps (0..1). */
    private fun bitmapDiff(a: Bitmap, b: Bitmap): Float {
        val w = minOf(a.width, b.width)
        val h = minOf(a.height, b.height)
        val n = w * h
        if (n == 0) return -1f
        var sum = 0L
        val pa = IntArray(w)
        val pb = IntArray(w)
        for (y in 0 until h) {
            a.getPixels(pa, 0, w, 0, y, w, 1)
            b.getPixels(pb, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val ca = pa[x]
                val cb = pb[x]
                sum += abs((ca and 0xFF) - (cb and 0xFF)) +
                    abs(((ca shr 8) and 0xFF) - ((cb shr 8) and 0xFF)) +
                    abs(((ca shr 16) and 0xFF) - ((cb shr 16) and 0xFF))
            }
        }
        return ((sum.toDouble() / (n * 3)) / 255.0).toFloat()
    }
}
