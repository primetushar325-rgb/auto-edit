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
        val showZoomEditor: Int? = null,
        val showJunction: Int? = null,
        val pendingVideo: Pair<String, Double>? = null,
        val exporting: Boolean = false,
        val exportProgress: Float = 0f,
        val exportStage: String = "",
        val needStoragePermission: Boolean = false
    )

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
        refreshProjects()
        toast("Project deleted")
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
        val newClips = p.clips.toMutableList()
        val addedUris = mutableListOf<String>()
        var added = 0
        var skipped = 0
        for (u in uris) {
            val s = u.toString()
            if (newClips.any { it.uri == s }) continue
            val mime = runCatching { app.contentResolver.getType(u) }.getOrNull()
            if (mime != null && !mime.startsWith("image/")) {
                skipped++
                continue
            }
            // Newly added clips are STATIC by default - no auto formula/motion.
            // Motion is applied only when the user taps "Apply Formula" or sets zoom.
            newClips += ClipRef(uri = s, type = ClipType.IMAGE, motion = null)
            addedUris += s
            added++
        }
        if (added == 0 && skipped > 0) {
            toast("None of the selected files are images")
            return
        }
        if (added == 0) {
            toast("These images are already in the project")
            return
        }
        mutate { pr -> pr.copy(clips = newClips) }
        val total = (p.clips.size + added) * p.imageClipDuration()
        val totalStr = fmtTime(total)
        toast(
            if (added >= 100) "$added images imported • Total duration: $totalStr"
            else "+$added image${if (added > 1) "s" else ""} added ($totalStr total) — apply a Formula or set zoom"
        )
        // Background validation: corrupted/unsupported images must never crash
        // the import - report them with a friendly message instead.
        mainScope.launch {
            val bad = withContext(Dispatchers.IO) {
                addedUris.count { uri -> !com.autoedit.media.ImageLoader.isValidImage(app, uri) }
            }
            if (bad > 0) toast("$bad image(s) could not be imported")
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
            pr.copy(
                formulaId = f.id,
                clipDurationSec = f.clipDurationSec,
                transition = f.transition,
                transitionDurationSec = f.transitionDurationSec,
                clips = pr.clips.mapIndexed { i, c -> c.copy(motion = planned[i]) }
            )
        }
        _ui.update { it.copy(showFormula = false) }
        toast("${f.name} • ${f.tagline} applied")
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
        _ui.update { it.copy(pendingVideo = null) }
        mutate { pr ->
            val clips = pr.clips.toMutableList()
            val at = insertAt.coerceIn(0, clips.size)
            clips.add(at, ClipRef(uri = s, type = ClipType.VIDEO, videoInMs = inC, videoOutMs = outC))
            // junctions at/after the insertion shift by one
            val junctions = pr.junctionTransitions.mapKeys {
                if (it.key >= at) it.key + 1 else it.key
            }
            pr.copy(clips = clips, junctionTransitions = junctions)
        }
        val seg = (outC - inC) / 1000.0
        toast("Video added (${fmtTime(seg)} segment)")
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
        mainScope.launch {
            toast("Loading voice…")
            val s = uri.toString()
            val dur = withContext(Dispatchers.Default) {
                val est = ImageLoader.estimateDurationMs(ctx = app, uri = s) / 1000.0
                if (est > 0.5) est
                else {
                    runCatching { AudioDecoder.decode(app, s, 1200.0) }.getOrNull()?.durationSec ?: 0.0
                }
            }
            if (dur <= 0.2) {
                toast("Unable to load this audio file")
                return@launch
            }
            val name = withContext(Dispatchers.IO) { ImageLoader.displayName(app, s, "voice") }
            mutate { pr -> pr.copy(voice = AudioConfig(s, name, dur, loop = false)) }
            audio.loadVoice(_ui.value.voice)
            toast("Voice added (${fmtTime(dur)})")
        }
    }

    fun pickMusic(uri: Uri) {
        mainScope.launch {
            toast("Loading music…")
            val s = uri.toString()
            val dur = withContext(Dispatchers.Default) {
                val est = ImageLoader.estimateDurationMs(ctx = app, uri = s) / 1000.0
                if (est > 0.5) est
                else {
                    runCatching { AudioDecoder.decode(app, s, 1200.0) }.getOrNull()?.durationSec ?: 0.0
                }
            }
            if (dur <= 0.2) {
                toast("Unable to load this audio file")
                return@launch
            }
            val name = withContext(Dispatchers.IO) { ImageLoader.displayName(app, s, "music") }
            mutate { pr -> pr.copy(music = AudioConfig(s, name, dur, volume = 0.5f, loop = true)) }
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

    private fun doExport(p: ProjectModel) {
        stopPreview()
        _ui.update {
            it.copy(
                showExport = false,
                exporting = true,
                exportProgress = 0f,
                exportStage = "Starting…"
            )
        }
        cancelFlag.set(false)
        exportJob = mainScope.launch {
            val snapshot = p
            val result = withContext(Dispatchers.Default) {
                exporter.export(snapshot, { cancelFlag.get() }) { prog, stage ->
                    _ui.update { it.copy(exportProgress = prog, exportStage = stage) }
                }
            }
            _ui.update { it.copy(exporting = false) }
            result.onSuccess {
                toast("Video saved to Movies/Auto Edit")
            }.onFailure { e ->
                val msg = when {
                    e is VideoExporter.ExportCancelled -> "Export cancelled"
                    e.message != null && e.message!!.contains("storage", ignoreCase = true) ->
                        "Not enough storage to export this video."
                    else -> (e.message ?: "Export failed. Please try again.").take(300)
                }
                toast(msg)
            }
        }
    }

    fun cancelExport() {
        cancelFlag.set(true)
        exportJob?.cancel()
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
                 pendingVideo: Pair<String, Double>? = _ui.value.pendingVideo) {
        _ui.update {
            it.copy(
                showFormula = showFormula,
                showAdjust = showAdjust,
                showExport = showExport,
                showClipMenu = showClipMenu,
                showRename = showRename,
                showZoomEditor = showZoomEditor,
                showJunction = showJunction,
                pendingVideo = pendingVideo
            )
        }
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
