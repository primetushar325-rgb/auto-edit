@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.autoedit.app

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autoedit.engine.Adjustments
import com.autoedit.engine.AspectRatio
import com.autoedit.engine.AudioConfig
import com.autoedit.engine.ClipRef
import com.autoedit.engine.ClipType
import com.autoedit.engine.FormulaCatalog
import com.autoedit.engine.MotionPlanner
import com.autoedit.engine.ProjectModel
import com.autoedit.engine.ProjectRepository
import com.autoedit.engine.Quality
import com.autoedit.engine.TransitionType
import com.autoedit.media.AudioDecoder
import com.autoedit.media.ImageLoader
import com.autoedit.media.ProjectStorage
import com.autoedit.media.VideoExporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class AppViewModel(app: Application) : ViewModel() {

    sealed interface Screen {
        object Home : Screen
        object Editor : Screen
    }

    data class ClipInfo(
        val index: Int,
        val uri: String,
        val isVideo: Boolean,
        val motionLabel: String,
        val junctionLabel: String?,
        val zoomLabel: String?,
        val transitionBefore: Boolean
    )

    data class Ui(
        val screen: Screen = Screen.Home,
        val projects: List<ProjectModel> = emptyList(),
        val projectId: String? = null,
        val projectName: String = "",
        val clipInfos: List<ClipInfo> = emptyList(),
        val clipCount: Int = 0,
        val totalDurationSec: Double = 0.0,
        val clipDurationSec: Double = 3.0,
        val formulaId: String? = null,
        val transition: TransitionType = TransitionType.CROSS_DISSOLVE,
        val transitionDurationSec: Double = 0.45,
        val aspect: AspectRatio = AspectRatio.LANDSCAPE_16_9,
        val quality: Quality = Quality.Q1080,
        val fps: Int = 30,
        val voice: AudioConfig? = null,
        val music: AudioConfig? = null,
        val duckMusic: Boolean = true,
        val fitToVoice: Boolean = false,
        val adjustments: Adjustments = Adjustments(),
        val version: Long = 0,
        val toast: String? = null,
        val toastAt: Long = 0L,
        val playing: Boolean = false,
        val showFormula: Boolean = false,
        val showAdjust: Boolean = false,
        val showExport: Boolean = false,
        val showClipMenu: Int? = null,
        val showRename: Boolean = false,
        val showStorage: Boolean = false,
        val storageTotal: Long = 0L,
        val storageRows: List<StorageRow> = emptyList(),
        val showZoomEditor: Int? = null,
        val showJunction: Int? = null,
        val pendingVideo: Pair<String, Double>? = null,
        val exporting: Boolean = false,
        val exportProgress: Float = 0f,
        val exportStage: String = "",
        val exportElapsedMs: Long = 0L,
        val lastExport: ExportOutcome? = null,
        val needStoragePermission: Boolean = false
    )

    data class ExportOutcome(
        val filePath: String,
        val displayName: String,
        val sizeBytes: Long,
        val durationSec: Double,
        val mediaStoreSaved: Boolean,
        val thumbnailPath: String?
    )

    data class StorageRow(val id: String, val name: String, val size: Long)

    private val app: Application = app
    private val repo = ProjectRepository(File(app.filesDir, "projects"))
    private val exporter = VideoExporter(app)
    private val audio = PreviewAudio(app)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val cancelFlag = AtomicBoolean(false)
    private var exportJob: Job? = null
    private val thumbLru = LinkedHashMap<String, ImageBitmap>()

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    init {
        refreshProjects()
        // startup cleanup: orphaned temp files from crashed exports (>24h old)
        mainScope.launch {
            withContext(Dispatchers.IO) { ProjectStorage.cleanOrphans(app) }
        }
    }

    // ------------------------------------------------------------------ home

    fun refreshProjects() {
        _ui.update { it.copy(projects = repo.list()) }
    }

    fun newProject() {
        val n = repo.nextProjectNumber()
        val p = ProjectModel(
            id = UUID.randomUUID().toString(),
            name = "Project $n",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            formulaId = null,
            motionSeed = (System.nanoTime() and 0x7FFFFFFFFFFFFFFFL)
        )
        repo.save(p)
        openProject(p.id)
    }

    fun newProjectWithFormula(formulaId: String) {
        val f = FormulaCatalog.byId(formulaId) ?: return
        val n = repo.nextProjectNumber()
        val p = ProjectModel(
            id = UUID.randomUUID().toString(),
            name = "Project $n",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            formulaId = f.id,
            motionSeed = (System.nanoTime() and 0x7FFFFFFFFFFFFFFFL)
        )
        ProjectStorage.ensureProject(app, p.id)
        repo.save(p)
        openProject(p.id)
        toast("${f.name} ${f.tagline} ready - tap APPLY FORMULA when images are added")
    }

    fun openProject(id: String) {
        val p = repo.load(id)
        if (p == null) {
            refreshProjects()
            return
        }
        audio.loadVoice(p.voice)
        audio.loadMusic(p.music)
        _ui.update {
            it.copy(screen = Screen.Editor, projectId = id, playing = false)
        }
        syncProjectUi(p)
    }

    fun deleteProject(id: String) {
        stopPreview()
        repo.delete(id)
        mainScope.launch {
            withContext(Dispatchers.IO) { ProjectStorage.deleteProjectFolder(app, id) }
            refreshProjects()
            if (_ui.value.showStorage) loadStorageInfo()
            toast("Project deleted")
        }
    }

    fun goHome() {
        stopPreview()
        _ui.update { it.copy(screen = Screen.Home, projectId = null, clipInfos = emptyList()) }
        refreshProjects()
    }

    // ---------------------------------------------------------------- project

    private fun current(): ProjectModel? = _ui.value.projectId?.let { repo.load(it) }

    private fun syncProjectUi(p: ProjectModel) {
        _ui.update {
            it.copy(
                projectName = p.name,
                clipInfos = p.clips.mapIndexed { i, c ->
                    val zoomLabel = if (c.hasZoomOverride()) {
                        "%.0f\u2192%.0f%%".format(c.startZoom?.times(100f) ?: 100f, c.endZoom?.times(100f) ?: 92f)
                    } else null
                    ClipInfo(
                        index = i,
                        uri = c.uri,
                        isVideo = c.type == ClipType.VIDEO,
                        motionLabel = when {
                            zoomLabel != null -> "Z $zoomLabel"
                            c.motion != null -> c.motion.type.short()
                            else -> "—"
                        },
                        junctionLabel = p.junctionTransitions[i]?.let { t ->
                            if (t == p.transition) null else t.label()
                        },
                        zoomLabel = zoomLabel,
                        transitionBefore = i > 0 && (p.junctionTransitions[i] ?: p.transition) != TransitionType.NONE
                    )
                },
                clipCount = p.clips.size,
                totalDurationSec = p.totalDuration(),
                clipDurationSec = p.effectiveClipDuration(),
                formulaId = p.formulaId,
                transition = p.transition,
                transitionDurationSec = p.transitionDurationSec,
                aspect = p.aspect,
                quality = p.export.quality,
                fps = p.export.fps,
                voice = p.voice,
                music = p.music,
                duckMusic = p.duckMusic,
                fitToVoice = p.fitToVoice,
                adjustments = p.adjustments,
                version = it.version + 1
            )
        }
    }

    private fun mutate(block: (ProjectModel) -> ProjectModel) {
        val p = current() ?: return
        val updated = block(p)
        repo.save(updated)
        syncProjectUi(updated)
    }

    // --------------------------------------------------------------- clips

    fun addImages(uris: List<Uri>) {
        val p = current() ?: return
        mainScope.launch {
            toast("Importing…")
            val copied = withContext(Dispatchers.IO) {
                val dir = ProjectStorage.sourceDir(app, p.id)
                val base = p.clips.size
                val out = ArrayList<Pair<String, String>>() // srcUri to localPath
                var skippedMime = 0
                for (u in uris) {
                    val src = u.toString()
                    if (p.clips.any { it.uri == src } || out.any { it.first == src }) continue
                    val mime = runCatching { app.contentResolver.getType(u) }.getOrNull()
                    if (mime != null && !mime.startsWith("image/")) {
                        skippedMime++
                        continue
                    }
                    val ext = when {
                        mime == "image/png" -> ".png"
                        mime == "image/webp" -> ".webp"
                        else -> ".img"
                    }
                    val dest = File(dir, "${System.currentTimeMillis()}_${base + out.size}$ext")
                    if (ProjectStorage.copyUriTo(app, src, dest)) out += src to dest.absolutePath
                }
                out to skippedMime
            }
            val (pairs, skipped) = copied
            if (pairs.isEmpty()) {
                if (skipped > 0) toast("None of the selected files are images")
                else toast("These images are already in the project")
                return@launch
            }
            // Newly added clips are STATIC by default - no auto formula/motion.
            // Motion is applied only when the user taps "Apply Formula" or sets zoom.
            val addedPaths = pairs.map { it.second }
            mutate { pr ->
                val clips = pr.clips.toMutableList()
                pairs.forEach { (_, path) -> clips += ClipRef(uri = path, type = ClipType.IMAGE, motion = null) }
                pr.copy(clips = clips)
            }
            val added = pairs.size
            val total = p.totalDuration() + added * p.imageClipDuration()
            val totalStr = fmtTime(total)
            toast(
                if (added >= 100) "$added images imported • Total duration: $totalStr"
                else "+$added image${if (added > 1) "s" else ""} added ($totalStr total) — apply a Formula or set zoom"
            )
            // Background validation: corrupted/unsupported images must never crash
            // the import - report them with a friendly message instead.
            launch {
                val bad = withContext(Dispatchers.IO) {
                    addedPaths.count { path -> !com.autoedit.media.ImageLoader.isValidImage(app, path) }
                }
                if (bad > 0) toast("$bad image(s) could not be imported")
            }
        }
    }



    fun removeClip(i: Int) {
        _ui.update { it.copy(showClipMenu = null) }
        mutate { pr ->
            val clips = pr.clips.filterIndexed { idx, _ -> idx != i }
            val junctions = pr.junctionTransitions
                .filterKeys { it > i }
                .mapKeys { it.key - 1 }
            pr.copy(clips = clips, junctionTransitions = junctions)
        }
    }

    fun moveClip(i: Int, delta: Int) {
        _ui.update { it.copy(showClipMenu = null) }
        mutate { pr ->
            val list = pr.clips.toMutableList()
            val j = (i + delta).coerceIn(0, list.size - 1)
            if (j != i) {
                val tmp = list[i]
                list[i] = list[j]
                list[j] = tmp
            }
            pr.copy(clips = list)
        }
    }

    fun cycleDuration() {
        val options = listOf(2.0, 3.0, 4.0, 5.0)
        val cur = _ui.value.clipDurationSec
        val next = options[(options.indexOfFirst { kotlin.math.abs(it - cur) < 0.01 } + 1) % options.size]
        mutate { pr -> pr.copy(clipDurationSec = next) }
        toast("Every image is now ${"%.0f".format(next)} seconds")
    }

    // ------------------------------------------------------------- formula

    fun applyFormula(formulaId: String) {
        val f = FormulaCatalog.byId(formulaId) ?: return
        val planner = MotionPlanner()
        mutate { pr ->
            val planned = planner.plan(pr.clips.size, f, pr.motionSeed)
            // NOTE: the formula sets motion + clip duration only. It deliberately
            // does NOT overwrite the project transition - that stays a user choice
            // (per-junction picker / project default). This is what stopped the
            // "unexpected white flash" (FLASH from applied formulas bleeding into
            // every cut without the user selecting a transition).
            pr.copy(
                formulaId = f.id,
                clipDurationSec = f.clipDurationSec,
                clips = pr.clips.mapIndexed { i, c -> c.copy(motion = planned[i]) }
            )
        }
        _ui.update { it.copy(showFormula = false) }
        toast("${f.name} • ${f.tagline} applied (transition unchanged)")
    }

    fun randomizeAgain() {
        val f = current()?.let { FormulaCatalog.byId(it.formulaId) } ?: FormulaCatalog.F01
        val planner = MotionPlanner()
        mutate { pr ->
            val seed = (System.nanoTime() and 0x7FFFFFFFFFFFFFFFL)
            val planned = planner.plan(pr.clips.size, f, seed)
            pr.copy(
                motionSeed = seed,
                clips = pr.clips.mapIndexed { i, c -> c.copy(motion = planned[i]) }
            )
        }
        toast("New motion sequence generated")
    }

    // -------------------------------------------------- per-clip zoom override

    fun setClipZoom(index: Int, startZoom: Float, endZoom: Float, applyToAll: Boolean) {
        val s = startZoom.coerceIn(0.5f, 1.5f)
        val e = endZoom.coerceIn(0.5f, 1.5f)
        _ui.update { it.copy(showZoomEditor = null) }
        mutate { pr ->
            val clips = pr.clips.mapIndexed { i, c ->
                if (i == index || applyToAll) c.copy(startZoom = s, endZoom = e) else c
            }
            pr.copy(clips = clips)
        }
        toast(if (applyToAll) "Zoom ${"%.0f".format(s * 100)}% ${"\u2192"} ${"%.0f".format(e * 100)}% applied to ALL clips"
              else "Zoom set for image ${index + 1}")
    }

    fun clearClipZoom(index: Int) {
        _ui.update { it.copy(showZoomEditor = null) }
        mutate { pr ->
            pr.copy(clips = pr.clips.mapIndexed { i, c ->
                if (i == index) c.copy(startZoom = null, endZoom = null) else c
            })
        }
        toast("Custom zoom cleared - back to formula")
    }

    // -------------------------------------------------- junction transitions

    fun setJunctionTransition(junction: Int, t: TransitionType?) {
        _ui.update { it.copy(showJunction = null) }
        mutate { pr ->
            val j = pr.junctionTransitions.toMutableMap()
            if (t == null) j.remove(junction) else j[junction] = t
            pr.copy(junctionTransitions = j)
        }
        toast(if (t == null) "Junction back to project default"
              else "Junction: ${t.label()}")
    }

    // -------------------------------------------------- video clips

    fun openVideoPicker(uri: Uri) {
        mainScope.launch {
            toast("Reading video…")
            val s = uri.toString()
            val durMs = withContext(Dispatchers.IO) {
                com.autoedit.media.ImageLoader.videoDurationMs(app, s)
            }
            if (durMs <= 200) {
                toast("Unable to read this video file")
                return@launch
            }
            _ui.update { it.copy(pendingVideo = s to (durMs / 1000.0)) }
        }
    }

    fun confirmAddVideo(inMs: Long, outMs: Long, insertAt: Int) {
        val pend = _ui.value.pendingVideo ?: return
        val (s, durSec) = pend
        if (outMs <= inMs + 200) {
            toast("Video segment is too short")
            return
        }
        val inC = inMs.coerceIn(0L, (durSec * 1000).toLong() - 200)
        val outC = outMs.coerceIn(inC + 200, (durSec * 1000).toLong())
        mainScope.launch {
            toast("Saving video into project…")
            val localPath = withContext(Dispatchers.IO) {
                val p = current() ?: return@withContext null
                val name = runCatching { com.autoedit.media.ImageLoader.displayName(app, s, "video") }.getOrDefault("video.mp4")
                val ext = name.substringAfterLast('.', "mp4").lowercase().takeIf { it.length in 1..5 } ?: "mp4"
                val dest = File(ProjectStorage.sourceDir(app, p.id), "video_${System.currentTimeMillis()}.$ext")
                if (ProjectStorage.copyUriTo(app, s, dest)) dest.absolutePath else null
            }
            if (localPath == null) {
                toast("Video could not be copied into the project")
                return@launch
            }
            _ui.update { it.copy(pendingVideo = null) }
            mutate { pr ->
                val clips = pr.clips.toMutableList()
                val at = insertAt.coerceIn(0, clips.size)
                clips.add(at, ClipRef(uri = localPath, type = ClipType.VIDEO, videoInMs = inC, videoOutMs = outC))
                // junctions at/after the insertion shift by one
                val junctions = pr.junctionTransitions.mapKeys {
                    if (it.key >= at) it.key + 1 else it.key
                }
                pr.copy(clips = clips, junctionTransitions = junctions)
            }
            val seg = (outC - inC) / 1000.0
            toast("Video added (${fmtTime(seg)} segment)")
        }
    }

    fun cancelVideo() {
        _ui.update { it.copy(pendingVideo = null) }
    }

    fun setTransition(t: TransitionType) {
        mutate { pr -> pr.copy(transition = t) }
    }

    fun setTransitionDuration(sec: Double) {
        mutate { pr -> pr.copy(transitionDurationSec = sec.coerceIn(0.0, 2.0)) }
    }

    fun setAspect(a: AspectRatio) {
        mutate { pr -> pr.copy(aspect = a, export = pr.export.copy(aspect = a)) }
    }

    fun setQuality(q: Quality) {
        mutate { pr -> pr.copy(export = pr.export.copy(quality = q)) }
    }

    fun setFps(fps: Int) {
        mutate { pr -> pr.copy(export = pr.export.copy(fps = fps)) }
    }

    // ---------------------------------------------------------------- audio

    fun pickVoice(uri: Uri) {
        val p = current() ?: return
        mainScope.launch {
            toast("Loading voice…")
            val src = uri.toString()
            val dur = withContext(Dispatchers.Default) {
                val est = ImageLoader.estimateDurationMs(ctx = app, uri = src) / 1000.0
                if (est > 0.5) est
                else {
                    runCatching { AudioDecoder.decode(app, src, 1200.0) }.getOrNull()?.durationSec ?: 0.0
                }
            }
            if (dur <= 0.2) {
                toast("Unable to load this audio file")
                return@launch
            }
            val name = withContext(Dispatchers.IO) { ImageLoader.displayName(app, src, "voice") }
            val localPath = withContext(Dispatchers.IO) {
                val ext = name.substringAfterLast('.', "audio").lowercase().takeIf { it.length in 1..5 } ?: "audio"
                val dest = File(ProjectStorage.audioDir(app, p.id), "voice_${System.currentTimeMillis()}.$ext")
                if (ProjectStorage.copyUriTo(app, src, dest)) dest.absolutePath else null
            }
            if (localPath == null) {
                toast("Voice could not be copied into the project")
                return@launch
            }
            mutate { pr -> pr.copy(voice = AudioConfig(localPath, name, dur, loop = false)) }
            audio.loadVoice(_ui.value.voice)
            toast("Voice added (${fmtTime(dur)})")
        }
    }

    fun pickMusic(uri: Uri) {
        val p = current() ?: return
        mainScope.launch {
            toast("Loading music…")
            val src = uri.toString()
            val dur = withContext(Dispatchers.Default) {
                val est = ImageLoader.estimateDurationMs(ctx = app, uri = src) / 1000.0
                if (est > 0.5) est
                else {
                    runCatching { AudioDecoder.decode(app, src, 1200.0) }.getOrNull()?.durationSec ?: 0.0
                }
            }
            if (dur <= 0.2) {
                toast("Unable to load this audio file")
                return@launch
            }
            val name = withContext(Dispatchers.IO) { ImageLoader.displayName(app, src, "music") }
            val localPath = withContext(Dispatchers.IO) {
                val ext = name.substringAfterLast('.', "audio").lowercase().takeIf { it.length in 1..5 } ?: "audio"
                val dest = File(ProjectStorage.audioDir(app, p.id), "music_${System.currentTimeMillis()}.$ext")
                if (ProjectStorage.copyUriTo(app, src, dest)) dest.absolutePath else null
            }
            if (localPath == null) {
                toast("Music could not be copied into the project")
                return@launch
            }
            mutate { pr -> pr.copy(music = AudioConfig(localPath, name, dur, volume = 0.5f, loop = true)) }
            audio.loadMusic(_ui.value.music)
            toast("Music added (${fmtTime(dur)})")
        }
    }

    fun updateVoice(block: (AudioConfig) -> AudioConfig) {
        mutate { pr -> pr.copy(voice = pr.voice?.let(block)) }
        audio.loadVoice(_ui.value.voice)
    }

    fun updateMusic(block: (AudioConfig) -> AudioConfig) {
        mutate { pr -> pr.copy(music = pr.music?.let(block)) }
        audio.loadMusic(_ui.value.music)
    }

    fun removeVoice() {
        mutate { pr -> pr.copy(voice = null, fitToVoice = false) }
        audio.loadVoice(null)
        toast("Voice removed")
    }

    fun removeMusic() {
        mutate { pr -> pr.copy(music = null) }
        audio.loadMusic(null)
        toast("Music removed")
    }

    fun setDuck(b: Boolean) {
        mutate { pr -> pr.copy(duckMusic = b) }
    }

    fun setFitToVoice(b: Boolean) {
        mutate { pr -> pr.copy(fitToVoice = b) }
        toast(if (b) "Images fitted to voice length" else "Back to fixed duration per image")
    }

    fun setAdjustments(a: Adjustments) {
        mutate { pr -> pr.copy(adjustments = a) }
    }

    fun renameProject(name: String) {
        val clean = name.trim().ifBlank { "Project" }
        mutate { pr -> pr.copy(name = clean) }
        _ui.update { it.copy(showRename = false) }
    }

    // -------------------------------------------------------------- preview

    fun setPlaying(b: Boolean) {
        if (b) audio.play() else audio.pause()
        _ui.update { it.copy(playing = b) }
    }

    fun audioTick(t: Double) {
        val v = _ui.value
        audio.tick(t, v.voice, v.music)
    }

    suspend fun loadPreviewBitmaps(indices: List<Int>): Map<Int, ImageBitmap> {
        val p = current() ?: return emptyMap()
        return withContext(Dispatchers.IO) {
            val out = HashMap<Int, ImageBitmap>()
            for (i in indices) {
                val clip = p.clips.getOrNull(i) ?: continue
                val bmp = if (clip.type == ClipType.VIDEO) {
                    val mid = clip.videoInMs + (clip.videoOutMs - clip.videoInMs) / 2
                    ImageLoader.videoThumb(app, clip.uri, mid, maxDim = 1280)
                } else {
                    ImageLoader.decodeScaled(app, clip.uri, 1280)
                }
                if (bmp != null) out[i] = bmp.asImageBitmap()
            }
            out
        }
    }

    suspend fun loadThumb(uri: String, isVideo: Boolean = false): ImageBitmap? = withContext(Dispatchers.IO) {
        val cached = synchronized(thumbLru) { thumbLru[uri] }
        if (cached != null) return@withContext cached
        val bmp: Bitmap = if (isVideo) {
            ImageLoader.videoThumb(app, uri, 500, maxDim = 256) ?: return@withContext null
        } else {
            ImageLoader.decodeScaled(app, uri, 256) ?: return@withContext null
        }
        val ib = bmp.asImageBitmap()
        synchronized(thumbLru) {
            thumbLru.remove(uri)
            thumbLru[uri] = ib
            while (thumbLru.size > 120) {
                val oldest = thumbLru.keys.first()
                thumbLru.remove(oldest)
            }
        }
        ib
    }

    private fun stopPreview() {
        audio.pause()
        if (_ui.value.playing) _ui.update { it.copy(playing = false) }
    }

    // --------------------------------------------------------------- export

    fun startExport() {
        val p = current() ?: return
        if (p.clips.isEmpty()) {
            toast("Add images before exporting")
            return
        }
        if (_ui.value.exporting) return
        if (Build.VERSION.SDK_INT < 29 &&
            app.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            _ui.update { it.copy(needStoragePermission = true) }
            return
        }
        doExport(p)
    }

    fun storagePermissionResult(granted: Boolean) {
        _ui.update { it.copy(needStoragePermission = false) }
        if (granted) {
            current()?.let { doExport(it) }
        } else {
            toast("Storage permission is required on this Android version")
        }
    }

    // Stall watchdog thresholds:
    //  - UI shows a "Still working - Force cancel" option after STALL_UI_MS with no progress.
    //  - The watchdog auto-force-cancels after STALL_KILL_MS so the export can never
    //    hang the UI indefinitely, even if a native call is stuck.
    private val STALL_UI_MS = 60_000L
    private val STALL_KILL_MS = 90_000L
    private var stallJob: Job? = null
    private val lastProgressMs = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())

    private fun doExport(p: ProjectModel) {
        stopPreview()
        lastProgressMs.set(System.currentTimeMillis())
        _ui.update {
            it.copy(
                showExport = false,
                exporting = true,
                exportProgress = 0f,
                exportStage = "Starting…"
            )
        }
        cancelFlag.set(false)
        // Stall watchdog: if progress stops moving for STALL_KILL_MS, force-cancel
        // so the export can never hang the UI indefinitely.
        stallJob = mainScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(5_000)
                val stalledFor = System.currentTimeMillis() - lastProgressMs.get()
                if (stalledFor > STALL_KILL_MS) {
                    android.util.Log.w("AutoEdit", "export stalled ${stalledFor}ms with no progress - force cancelling")
                    forceCancelExport("Export stalled - no progress for ${stalledFor / 1000}s")
                    break
                }
            }
        }
        val startMs = System.currentTimeMillis()
        exportJob = mainScope.launch {
            val snapshot = p
            val tempDir = ProjectStorage.tempDir(app, p.id)
            val exportDir = ProjectStorage.exportDir(app, p.id)
            val result = withContext(Dispatchers.Default) {
                exporter.export(
                    snapshot, tempDir, exportDir,
                    { cancelFlag.get() }
                ) { prog, stage ->
                    lastProgressMs.set(System.currentTimeMillis())
                    _ui.update {
                        it.copy(
                            exportProgress = prog,
                            exportStage = stage,
                            exportElapsedMs = System.currentTimeMillis() - startMs
                        )
                    }
                }
            }
            stallJob?.cancel()
            stallJob = null
            _ui.update { it.copy(exporting = false) }
            result.onSuccess { res ->
                // Build a thumbnail for the success screen (first frame).
                val thumb = withContext(Dispatchers.IO) {
                    runCatching {
                        val out = File(res.file.parentFile, "thumb_${res.file.nameWithoutExtension}.jpg")
                        val bmp = android.media.MediaMetadataRetriever().use { r ->
                            r.setDataSource(res.file.absolutePath)
                            r.getFrameAtTime(500_000)
                        }
                        if (bmp != null) {
                            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                            bmp.recycle()
                            out.absolutePath
                        } else null
                    }.getOrNull()
                }
                _ui.update {
                    it.copy(
                        lastExport = ExportOutcome(
                            filePath = res.file.absolutePath,
                            displayName = res.file.name,
                            sizeBytes = res.file.length(),
                            durationSec = snapshot.totalDuration(),
                            mediaStoreSaved = res.mediaStoreUri != null,
                            thumbnailPath = thumb
                        )
                    )
                }
            }.onFailure { e ->
                // always a specific, actionable message - never a generic one
                val msg = when {
                    e is VideoExporter.ExportCancelled -> "Export cancelled"
                    e.message != null && e.message!!.contains("storage", ignoreCase = true) ->
                        "Not enough storage to export this video."
                    else -> (e.message ?: "Export failed: ${e.javaClass.simpleName}").take(300)
                }
                toast(msg)
            }
        }
    }

    /**
     * Force-cancel: always responsive. Sets the cancel flag, cancels the export
     * coroutine + watchdog, and IMMEDIATELY closes the dialog so the user is
     * never left on a frozen, unresponsive export - even if a native call is stuck.
     */
    fun forceCancelExport(reason: String? = null) {
        cancelFlag.set(true)
        stallJob?.cancel()
        stallJob = null
        exportJob?.cancel()
        _ui.update { it.copy(exporting = false) }
        reason?.let { toast(it) }
    }

    fun cancelExport() {
        forceCancelExport(null)
    }

    fun dismissExportResult() {
        _ui.update { it.copy(lastExport = null) }
    }

    fun shareLastExport() {
        val out = _ui.value.lastExport ?: return
        mainScope.launch {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                app, "${app.packageName}.fileprovider", File(out.filePath)
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            app.startActivity(android.content.Intent.createChooser(intent, "Share video").apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    fun openLastExport() {
        val out = _ui.value.lastExport ?: return
        mainScope.launch {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                app, "${app.packageName}.fileprovider", File(out.filePath)
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { app.startActivity(intent) }.onFailure { toast("No app found to play video") }
        }
    }

    // ---------------------------------------------------------------- misc

    fun toast(msg: String) {
        _ui.update { it.copy(toast = msg, toastAt = System.currentTimeMillis()) }
    }

    fun dismissToast() {
        _ui.update { it.copy(toast = null) }
    }

    fun setSheet(showFormula: Boolean = _ui.value.showFormula,
                 showAdjust: Boolean = _ui.value.showAdjust,
                 showExport: Boolean = _ui.value.showExport,
                 showClipMenu: Int? = _ui.value.showClipMenu,
                 showRename: Boolean = _ui.value.showRename,
                 showZoomEditor: Int? = _ui.value.showZoomEditor,
                 showJunction: Int? = _ui.value.showJunction,
                 pendingVideo: Pair<String, Double>? = _ui.value.pendingVideo,
                 showStorage: Boolean = _ui.value.showStorage) {
        _ui.update {
            it.copy(
                showFormula = showFormula,
                showAdjust = showAdjust,
                showExport = showExport,
                showClipMenu = showClipMenu,
                showRename = showRename,
                showZoomEditor = showZoomEditor,
                showJunction = showJunction,
                pendingVideo = pendingVideo,
                showStorage = showStorage
            )
        }
    }

    // ---------------------------------------------------------------- storage

    fun openStorage() {
        _ui.update { it.copy(showStorage = true) }
        loadStorageInfo()
    }

    fun closeStorage() {
        _ui.update { it.copy(showStorage = false) }
    }

    private fun loadStorageInfo() {
        mainScope.launch {
            val (rows, total) = withContext(Dispatchers.IO) {
                val base = ProjectStorage.baseDir(app)
                val rows = base.listFiles()?.filter { it.isDirectory }?.map { d ->
                    val id = d.name
                    val pm = runCatching { repo.load(id) }.getOrNull()
                    StorageRow(id, pm?.name ?: id, ProjectStorage.folderSize(app, id))
                }?.sortedByDescending { it.size } ?: emptyList()
                val total = base.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                rows to total
            }
            _ui.update { it.copy(storageRows = rows, storageTotal = total) }
        }
    }

    fun fmtBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "${"%.0f".format(kb)} KB"
        val mb = kb / 1024.0
        if (mb < 1024) return "${"%.1f".format(mb)} MB"
        return "${"%.2f".format(mb / 1024.0)} GB"
    }

    /** Read-only snapshot for the preview renderer. */
    fun projectSnapshot(): ProjectModel? = current()

    fun fmtTime(sec: Double): String {
        val s = sec.toLong().coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    override fun onCleared() {
        super.onCleared()
        audio.release()
        mainScope.cancel()
    }
}
