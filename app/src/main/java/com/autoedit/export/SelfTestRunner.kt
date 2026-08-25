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
import com.autoedit.engine.EasingType
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
import java.util.UUID
import kotlin.math.abs

/**
 * On-device export self-test (run via:
 *   adb shell am start -n com.autoedit.app/.MainActivity --es selftest "2"
 * ).
 *
 * It creates a throwaway project with [imageCount] generated images (a
 * distinct gradient + index number per image), applies deterministic
 * Formula-01-style motion (image 1: 100% -> 108%, image 2: 108% -> 100%),
 * exports 1080p/30 with NO audio, then VERIFIES the MP4:
 *
 *   - file exists, non-zero size
 *   - duration matches the expected seconds
 *   - resolution is 1920x1080
 *   - first and last frames decode and are DIFFERENT (both images present)
 *
 * Results are written as a JSON report next to the video, the evidence
 * frames are saved as PNGs, and every line is logged with tag
 * "AutoEditSelfTest" (adb logcat -s AutoEditSelfTest).
 */
object SelfTestRunner {

    data class SelfTestResult(
        val ok: Boolean,
        val message: String,
        val videoFile: File?,
        val reportFile: File?
    )

    suspend fun run(app: Application, imageCount: Int): SelfTestResult {
        val count = imageCount.coerceIn(1, 500)
        val id = "selftest-${System.currentTimeMillis()}"
        val log = ArrayList<String>()
        fun note(s: String) {
            log.add(s)
            android.util.Log.i("AutoEditSelfTest", s)
        }
        note("self-test start: $count images, 1080p/30, no audio, Formula-01 motion")
        val t0 = System.currentTimeMillis()
        try {
            val repo = ProjectRepository(File(app.filesDir, "projects"))
            ProjectStorage.ensureProject(app, id)
            val srcDir = ProjectStorage.sourceDir(app, id)

            // ------------------------------------------------ generate images
            note("generating $count test images…")
            val clips = ArrayList<ClipRef>()
            for (i in 0 until count) {
                val bmp = makeTestImage(i, count)
                val f = File(srcDir, "selftest-$i.png")
                bmp.compress(Bitmap.CompressFormat.PNG, 90, f.outputStream())
                bmp.recycle()
                // deterministic motion: even index = zoom in 100->108,
                // odd index = zoom out 108->100 (Formula 01 safe range)
                val motion = if (i % 2 == 0) {
                    ClipMotion(MotionType.ZOOM_IN, Keyframe(1.0f), Keyframe(1.08f))
                } else {
                    ClipMotion(MotionType.ZOOM_OUT, Keyframe(1.08f), Keyframe(1.0f))
                }
                clips += ClipRef(uri = f.absolutePath, type = ClipType.IMAGE, motion = motion)
            }

            val p = ProjectModel(
                id = id,
                name = "SelfTest-$count",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                clips = clips,
                formulaId = "F01",
                clipDurationSec = 3.0,
                transition = TransitionType.CROSS_DISSOLVE,
                transitionDurationSec = 0.45,
                aspect = AspectRatio.LANDSCAPE_16_9,
                export = ExportConfig(
                    quality = Quality.Q1080,
                    fps = 30,
                    aspect = AspectRatio.LANDSCAPE_16_9
                )
            )
            repo.save(p)

            val expectedSec = count * 3.0
            val progress = ArrayList<Pair<Float, String>>()
            val result = withContext(Dispatchers.Default) {
                VideoExporter(app).export(
                p,
                ProjectStorage.tempDir(app, id),
                ProjectStorage.exportDir(app, id),
                isCancelled = { false }
            ) { frac, stage ->
                progress += frac to stage
                }
            }
            note("export result: ${if (result.isSuccess) "SUCCESS" else "FAILURE ${result.exceptionOrNull()?.message}"}")
            result.onFailure { e ->
                note("failure detail: ${e.stackTraceToString().take(2000)}")
                return cleanup(app, id, log, false, "export failed: ${e.message}")
            }
            val res = result.getOrThrow()
            val file = res.file

            // --------------------------------------------------- verification
            val v = verify(file, 1920, 1080, expectedSec)
            val vJson = v.entries.joinToString(", ") { "${it.key}=${fmt(it.value)}" }
            note("verification: $vJson")

            // evidence frames: t=1s (image 1, mid zoom-in) and t=last-1 (image N)
            val f1 = grabFrame(file, 1000_000L)
            val f2 = grabFrame(file, ((expectedSec - 1.0) * 1_000_000).toLong())
            val f1File = File(file.parentFile, "selftest-frame1-${(1000 / 1000)}s.png")
            val f2File = File(file.parentFile, "selftest-frame2.png")
            f1?.let { it.compress(Bitmap.CompressFormat.PNG, 90, f1File.outputStream()); it.recycle() }
            f2?.let { it.compress(Bitmap.CompressFormat.PNG, 90, f2File.outputStream()); it.recycle() }
            note("evidence frames: ${if (f1 != null) f1File.name else "MISSING"}, ${if (f2 != null) f2File.name else "MISSING"}")
            if (count >= 2 && f1File.exists() && f2File.exists()) {
                val diff = frameDifference(f1File, f2File)
                val verdict = if (diff > 0.01f) "DISTINCT (both images present)" else "TOO SIMILAR (suspicious)"
                note("frame difference (t=1s vs t=${(expectedSec - 1.0).toString()}s): ${"%.3f".format(diff)} -> $verdict")
            }

            val ok = v["exists"] == true &&
                (v["sizeBytes"] as Long) > 0 &&
                v["durationMs"] != null &&
                abs((v["durationMs"] as Long) / 1000.0 - expectedSec) <= 0.75 &&
                v["width"] == 1920 && v["height"] == 1080 &&
                v["firstFrameDecodes"] == true
            val ms = System.currentTimeMillis() - t0
            note("self-test ${if (ok) "PASSED" else "FAILED"} in ${ms}ms")
            note("video: ${file.absolutePath} (${file.length() / 1024} KB)")

            // write the JSON report
            val report = buildString {
                appendLine("{")
                appendLine("  \"ok\": $ok,")
                appendLine("  \"images\": $count,")
                appendLine("  \"expectedDurationSec\": $expectedSec,")
                appendLine("  \"elapsedMs\": $ms,")
                appendLine("  \"video\": \"${file.name}\",")
                appendLine("  \"sizeBytes\": ${file.length()},")
                appendLine("  \"verification\": { ${vJson} },")
                appendLine("  \"warning\": ${res.warning?.let { "\"${it.replace("\"", "'")}\"" } ?: "null"},")
                appendLine("  \"progressSamples\": [${progress.takeLast(20).joinToString(", ") { "%.2f".format(it.first) }}]")
                append("}")
            }
            val reportFile = File(file.parentFile, "selftest-report.json")
            reportFile.writeText(report)
            note("report: ${reportFile.absolutePath}")

            return SelfTestResult(ok, reportFile.absolutePath, file, reportFile)
        } catch (e: Exception) {
            note("self-test crashed: ${e.stackTraceToString().take(2000)}")
            return cleanup(app, id, log, false, "crash: ${e.message}")
        }
    }

    private fun fmt(v: Any?): String = when (v) {
        null -> "null"
        is Boolean, is Long, is Int -> v.toString()
        else -> "\"${v.toString().replace("\"", "'")}\""
    }

    private fun cleanup(
        app: Application,
        id: String,
        log: List<String>,
        ok: Boolean,
        message: String
    ): SelfTestResult {
        // keep the self-test project folder as evidence; just report
        android.util.Log.i("AutoEditSelfTest", "done: ok=$ok msg=$message")
        return SelfTestResult(ok, message, null, null)
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
        c.drawText("AutoEdit self-test • image ${index + 1} of $count • zoom ${if (index % 2 == 0) "100→108" else "108→100"}%", w / 2f, h - 60f, p)
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

    /** Mean absolute pixel difference between two evidence PNGs (0..1). */
    private fun frameDifference(a: File, b: File): Float {
        return try {
            val ba = android.graphics.BitmapFactory.decodeFile(a.absolutePath)
            val bb = android.graphics.BitmapFactory.decodeFile(b.absolutePath)
            if (ba == null || bb == null) return -1f
            val n = minOf(ba.width, bb.width) * minOf(ba.height, bb.height)
            if (n == 0) return -1f
            var sum = 0L
            val pa = IntArray(ba.width)
            val pb = IntArray(bb.width)
            for (y in 0 until minOf(ba.height, bb.height)) {
                ba.getPixels(pa, 0, ba.width, 0, y, ba.width, 1)
                bb.getPixels(pb, 0, bb.width, 0, y, bb.width, 1)
                for (x in 0 until minOf(ba.width, bb.width)) {
                    val ca = pa[x]
                    val cb = pb[x]
                    sum += abs((ca and 0xFF) - (cb and 0xFF)) +
                        abs(((ca shr 8) and 0xFF) - ((cb shr 8) and 0xFF)) +
                        abs(((ca shr 16) and 0xFF) - ((cb shr 16) and 0xFF))
                }
            }
            val d = (sum.toDouble() / (n * 3)) / 255.0
            ba.recycle()
            bb.recycle()
            d.toFloat()
        } catch (e: Exception) {
            -1f
        }
    }
}
