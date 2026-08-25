package com.autoedit.app

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.autoedit.engine.ProjectModel
import com.autoedit.engine.TimelineMath
import com.autoedit.render.PreviewRenderer
import com.autoedit.render.PreviewRenderer.drawFrame

@Composable
fun EditorScreen(vm: AppViewModel) {
    val ui by vm.ui.collectAsState()
    val ctx = LocalContext.current
    var playTime by remember { mutableFloatStateOf(0f) }
    var fullscreen by remember { mutableStateOf(false) }
    val previewBmps = remember { mutableStateOf<Map<Int, ImageBitmap>>(emptyMap()) }

    val n = ui.clipCount
    val clipDur = ui.clipDurationSec
    val clipIdx = if (n > 0 && clipDur > 0) {
        (playTime / clipDur.toFloat()).toInt().coerceIn(0, n - 1)
    } else 0

    LaunchedEffect(ui.playing, ui.totalDurationSec) {
        if (!ui.playing) return@LaunchedEffect
        var last = System.nanoTime()
        while (true) {
            withFrameNanos { now ->
                val total = ui.totalDurationSec
                val dt = (now - last) / 1e9
                last = now
                if (total <= 0.0) {
                    vm.setPlaying(false)
                    return@withFrameNanos
                }
                playTime += dt.toFloat()
                if (playTime >= total) {
                    playTime = total.toFloat()
                    vm.setPlaying(false)
                }
                vm.audioTick(playTime.toDouble())
            }
        }
    }

    LaunchedEffect(ui.version, clipIdx, n) {
        if (n == 0) {
            previewBmps.value = emptyMap()
            return@LaunchedEffect
        }
        val need = listOf(clipIdx, clipIdx + 1).filter { it < n }.toSet()
        val loaded = previewBmps.value
        val missing = need.filter { it !in loaded }
        if (missing.isEmpty()) {
            if (loaded.keys.any { it !in need }) previewBmps.value = loaded.filterKeys { it in need }
            return@LaunchedEffect
        }
        val got = vm.loadPreviewBitmaps(missing)
        if (got.isNotEmpty()) previewBmps.value = (loaded + got).filterKeys { it in need }
    }

    // Robust image picker (see ImagePicker.kt). The Jetpack
    // PickMultipleVisualMedia(maxItems) contract throws IllegalArgumentException
    // at launch on Android 13+ whenever maxItems > the device's
    // MediaStore.getPickImagesMaxLimit() (default 100) - that was the
    // ADD IMAGES crash. We build our own intents with a clamped limit.
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            val uris = ImagePicker.parseUris(result.resultCode, result.data)
            if (uris.isNotEmpty()) {
                ImagePicker.persistReadPermissions(ctx, uris)
                vm.addImages(uris)
            }
        } catch (e: Exception) {
            vm.toast("Some images could not be imported")
        }
    }
    val pickVoice = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
        it?.let { u -> vm.pickVoice(u) }
    }
    val pickMusic = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
        it?.let { u -> vm.pickMusic(u) }
    }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
        it?.let { u -> vm.openVideoPicker(u) }
    }
    val storagePerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.storagePermissionResult(it)
    }
    LaunchedEffect(ui.needStoragePermission) {
        if (ui.needStoragePermission) {
            storagePerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    fun launchImages() {
        try {
            val intent = if (ImagePicker.photoPickerAvailable(ctx)) {
                ImagePicker.createIntent(ctx)
            } else {
                ImagePicker.fallbackIntent(ctx)
            }
            pickImages.launch(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            // ROM without the preferred picker - fall back to SAF
            try {
                pickImages.launch(ImagePicker.fallbackIntent(ctx))
            } catch (e2: Exception) {
                vm.toast("Could not open the image picker on this device")
            }
        } catch (e: Exception) {
            vm.toast("Could not open the image picker on this device")
        }
    }

    val proj = remember(ui.version) { vm.projectSnapshot() }

    if (fullscreen) {
        FullscreenPreview(
            proj = proj,
            playTime = playTime,
            bmps = previewBmps.value,
            ui = ui,
            onExit = { fullscreen = false },
            onTogglePlay = { vm.setPlaying(!ui.playing) }
        )
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(AeBlack)
                .padding(horizontal = 16.dp)
        ) {
            // ------------------------------------------------ top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AeIconButton(AeIcon.Kind.BACK, size = 40, background = AeSurface2, tint = AeText) {
                    vm.goHome()
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = ui.projectName,
                        style = MaterialTheme.typography.titleMedium,
                        color = AeText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { vm.setSheet(showRename = true) }
                    )
                    Text(
                        text = "$n images \u2022 ${vm.fmtTime(ui.totalDurationSec)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = AeTextDim
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(AeSurface2)
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = vm.fmtTime(ui.totalDurationSec),
                        style = MaterialTheme.typography.labelLarge,
                        color = AeGold
                    )
                }
            }

            // ------------------------------------------------ preview
            if (n == 0) {
                EmptyPreview(onAdd = { launchImages() })
            } else {
                // The order matters: opaque background is drawn FIRST and the
                // Canvas clears itself to opaque black on every frame, so no
                // previous screen's pixels can show through (the black-arrow /
                // bleed-through glitch).
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(previewAspectRatio(ui))
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF000000))
                        .clickable { vm.setPlaying(!ui.playing) }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        // Always clear the full surface with opaque black first.
                        drawRect(Color(0xFF000000))
                        val p = proj
                        if (p != null && p.clips.isNotEmpty()) {
                            val state = TimelineMath.frameAt(
                                playTime.toDouble(),
                                p.clipDurations(),
                                p.transitionDurationSec
                            )
                            drawFrame(p, state, p.clipDurations(), previewBmps.value)
                        }
                    }
                }
            }

            // ------------------------------------------------ transport
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AeIconButton(AeIcon.Kind.RESTART, size = 44, background = AeSurface2, tint = AeTextDim) {
                    playTime = 0f
                    vm.audioTick(0.0)
                }
                Spacer(Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(if (ui.playing) AeSurface3 else AeGold)
                        .clickable { vm.setPlaying(!ui.playing) },
                    contentAlignment = Alignment.Center
                ) {
                    AeIcon(
                        if (ui.playing) AeIcon.Kind.PAUSE else AeIcon.Kind.PLAY,
                        size = 24.dp,
                        tint = if (ui.playing) AeText else Color(0xFF1A1405)
                    )
                }
                Spacer(Modifier.width(12.dp))
                SelectionContainer {
                    Text(
                        text = "${vm.fmtTime(playTime.toDouble())} / ${vm.fmtTime(ui.totalDurationSec)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = AeText
                    )
                }
                Spacer(Modifier.weight(1f))
                AeIconButton(AeIcon.Kind.EXPAND, size = 44, background = AeSurface2, tint = AeTextDim) {
                    fullscreen = true
                }
            }

            // ------------------------------------------------ scrolling body
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionChip("IMAGES", AeIcon.Kind.IMAGE, ui.clipCount == 0) { launchImages() }
                    ActionChip("FORMULA", AeIcon.Kind.SETTINGS) { vm.setSheet(showFormula = true) }
                    ActionChip("VIDEO", AeIcon.Kind.VIDEO) { pickVideo.launch("video/*") }
                    ActionChip("VOICE", if (ui.voice != null) AeIcon.Kind.CHECK else AeIcon.Kind.VOICE) {
                        pickVoice.launch("audio/*")
                    }
                    ActionChip("MUSIC", if (ui.music != null) AeIcon.Kind.CHECK else AeIcon.Kind.MUSIC) {
                        pickMusic.launch("audio/*")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionChip("LOOK", AeIcon.Kind.ADJUST, modifier = Modifier.weight(1f)) {
                        vm.setSheet(showAdjust = true)
                    }
                    ActionChip("EXPORT", AeIcon.Kind.DOWNLOAD, gold = true, modifier = Modifier.weight(2f)) {
                        if (!ui.exporting) vm.setSheet(showExport = true)
                    }
                }
                Spacer(Modifier.height(16.dp))

                // ------------------------------------------------ timeline
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("TIMELINE", style = MaterialTheme.typography.labelLarge, color = AeGold)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "$n \u00d7 ${"%.1f".format(clipDur)}s",
                        style = MaterialTheme.typography.labelSmall,
                        color = AeTextDim
                    )
                    Spacer(Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(AeSurface)
                            .clickable { vm.cycleDuration() }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = "${"%.0f".format(clipDur)}s / clip",
                            style = MaterialTheme.typography.labelSmall,
                            color = AeGold
                        )
                    }
                }
                if (ui.voice != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "FIT IMAGES TO VOICE",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (ui.fitToVoice) AeGold else AeTextDim
                        )
                        Spacer(Modifier.width(8.dp))
                        SwitchCompat(
                            checked = ui.fitToVoice,
                            onCheckedChange = { vm.setFitToVoice(it) }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))

                if (n == 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AeSurface, RoundedCornerShape(14.dp))
                            .padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Pick images above. Every image automatically becomes a ${"%.0f".format(clipDur)}-second clip.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AeTextDim,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    TimelineRow(
                        vm = vm,
                        ui = ui,
                        onClipClick = { i -> vm.setSheet(showClipMenu = i) },
                        onJunctionClick = { i -> vm.setSheet(showJunction = i) }
                    )
                }
                Spacer(Modifier.height(14.dp))

                ui.voice?.let { AudioCard(vm, isVoice = true) }
                if (ui.voice != null && ui.music != null) Spacer(Modifier.height(10.dp))
                ui.music?.let { AudioCard(vm, isVoice = false) }

                Spacer(Modifier.height(24.dp))
                GoldButton(
                    text = "EXPORT VIDEO",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { vm.startExport() }
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (ui.showFormula) FormulaSheet(vm)
    if (ui.showAdjust) AdjustSheet(vm)
    if (ui.showExport || ui.exporting) ExportPanel(vm)
    if (ui.showRename) RenameDialog(vm)
    ui.showClipMenu?.let { i -> ClipMenuDialog(vm, i) }
    ui.showZoomEditor?.let { i -> ZoomEditorDialog(vm, i) }
    ui.showJunction?.let { i -> JunctionPickerDialog(vm, i) }
    ui.pendingVideo?.let { VideoTrimDialog(vm) }
}

@Composable
fun previewAspectRatio(ui: AppViewModel.Ui): Float = when (ui.aspect) {
    com.autoedit.engine.AspectRatio.LANDSCAPE_16_9 -> 16f / 9f
    com.autoedit.engine.AspectRatio.PORTRAIT_9_16 -> 9f / 16f
    com.autoedit.engine.AspectRatio.SQUARE_1_1 -> 1f
}

@Composable
private fun EmptyPreview(onAdd: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(18.dp))
            .background(AeSurface),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        EmptyIllustration()
        Spacer(Modifier.height(16.dp))
        Text(
            text = "YOUR CLIPS PREVIEW HERE",
            style = MaterialTheme.typography.labelLarge,
            color = AeText
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Add images and they become a cinematic sequence",
            style = MaterialTheme.typography.bodySmall,
            color = AeTextDim
        )
        Spacer(Modifier.height(18.dp))
        GoldButton(text = "ADD IMAGES", icon = AeIcon.Kind.PLUS, onClick = onAdd)
    }
}

@Composable
private fun FullscreenPreview(
    proj: ProjectModel?,
    playTime: Float,
    bmps: Map<Int, ImageBitmap>,
    ui: AppViewModel.Ui,
    onExit: () -> Unit,
    onTogglePlay: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000))
    ) {
        Canvas(modifier = Modifier.fillMaxSize().clickable { onTogglePlay() }) {
            // Clear to opaque black first - no bleed-through in fullscreen.
            drawRect(Color(0xFF000000))
            val p = proj
            if (p != null && p.clips.isNotEmpty()) {
                val state = TimelineMath.frameAt(
                    playTime.toDouble(),
                    p.clipDurations(),
                    p.transitionDurationSec
                )
                drawFrame(p, state, p.clipDurations(), bmps)
            }
        }
        AeIconButton(
            icon = AeIcon.Kind.CLOSE,
            size = 44,
            background = AeSurface3,
            tint = AeText,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
            onClick = onExit
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
                .clip(CircleShape)
                .background(AeSurface3.copy(alpha = 0.85f))
                .padding(horizontal = 18.dp, vertical = 8.dp)
        ) {
            Text(
                text = "${vm_fmtTime(playTime.toDouble())} / ${vm_fmtTime(ui.totalDurationSec)}",
                color = AeText,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

private fun vm_fmtTime(sec: Double): String {
    val s = sec.toLong().coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun TimelineRow(
    vm: AppViewModel,
    ui: AppViewModel.Ui,
    onClipClick: (Int) -> Unit,
    onJunctionClick: (Int) -> Unit
) {
    val thumbs = remember { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }
    val activeIdx = remember(ui.clipCount, ui.version) {
        val d = ui.clipDurationSec
        if (d > 0 && ui.clipCount > 0) {
            // approximate active clip from play state is not exposed per-frame;
            // first clip is subtly highlighted until playback drives it.
            0
        } else -1
    }
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .height(158.dp),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(ui.clipInfos, key = { "clip-${it.index}" }) { info ->
            val thumb = thumbs.value[info.uri]
            LaunchedEffect(info.uri, info.isVideo) {
                if (thumbs.value[info.uri] == null) {
                    val b = vm.loadThumb(info.uri, info.isVideo)
                    if (b != null) thumbs.value = thumbs.value + (info.uri to b)
                }
            }
            val selected = info.index == activeIdx
            Column(
                modifier = Modifier
                    .width(96.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .then(
                        if (selected) Modifier.border(1.5.dp, AeGold, RoundedCornerShape(14.dp))
                        else Modifier
                    )
                    .background(AeSurface)
                    .clickable { onClipClick(info.index) }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                        .background(AeSurface2),
                    contentAlignment = Alignment.Center
                ) {
                    if (thumb != null) {
                        Image(
                            bitmap = thumb,
                            contentDescription = if (info.isVideo) "Video ${info.index + 1}" else "Image ${info.index + 1}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        // lightweight skeleton while the thumbnail decodes
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(AeSurface3.copy(alpha = 0.5f))
                        )
                        AeIcon(
                            if (info.isVideo) AeIcon.Kind.VIDEO else AeIcon.Kind.IMAGE,
                            size = 22.dp,
                            tint = AeTextDim
                        )
                    }
                    if (info.index > 0) {
                        // junction marker (CapCut-style): tap to pick transition
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(5.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (info.junctionLabel != null) AeGold else AeSurface3.copy(alpha = 0.9f))
                                .clickable { onJunctionClick(info.index) }
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = info.junctionLabel?.take(6) ?: "✦",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (info.junctionLabel != null) Color(0xFF1A1405) else AeTextDim,
                                maxLines = 1
                            )
                        }
                    }
                    if (info.isVideo) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(5.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.Black.copy(alpha = 0.65f))
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AeIcon(AeIcon.Kind.PLAY, size = 10.dp, tint = AeGold)
                                Spacer(Modifier.width(3.dp))
                                Text("VIDEO", style = MaterialTheme.typography.labelSmall, color = AeGold)
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = (if (info.isVideo) "VID %03d" else "IMG %03d").format(info.index + 1),
                            style = MaterialTheme.typography.labelSmall,
                            color = AeText,
                            maxLines = 1
                        )
                        Text(
                            text = info.motionLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (info.zoomLabel != null) AeGold else AeTextDim,
                            maxLines = 1
                        )
                    }
                    AeIcon(AeIcon.Kind.DRAG, size = 16.dp, tint = AeTextFaint)
                }
            }
        }
    }
}

@Composable
private fun ActionChip(
    label: String,
    icon: AeIcon.Kind,
    gold: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val bg = if (gold) AeGold else AeSurface2
    val fg = if (gold) Color(0xFF1A1405) else AeText
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AeIcon(icon, size = 20.dp, tint = if (gold) fg else AeGold)
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            maxLines = 1
        )
    }
}


