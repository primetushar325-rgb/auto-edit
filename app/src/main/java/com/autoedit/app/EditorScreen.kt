package com.autoedit.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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

    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(500)
    ) { uris ->
        if (!uris.isNullOrEmpty()) vm.addImages(uris)
    }
    val pickVoice = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
        it?.let { u -> vm.pickVoice(u) }
    }
    val pickMusic = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
        it?.let { u -> vm.pickMusic(u) }
    }
    val storagePerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.storagePermissionResult(it)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            pickImages.launch(PickVisualMediaRequest())
        } else {
            vm.toast("Photo access is required on this Android version")
        }
    }
    LaunchedEffect(ui.needStoragePermission) {
        if (ui.needStoragePermission) {
            storagePerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    fun launchImages() {
        if (Build.VERSION.SDK_INT in 30..32) {
            if (ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                permLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES)
                return
            }
        }
        pickImages.launch(PickVisualMediaRequest())
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
                Text(
                    text = "\u2190",
                    style = MaterialTheme.typography.titleLarge,
                    color = AeText,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(AeCard)
                        .clickable { vm.goHome() }
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
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
                        .background(AeCard)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
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
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(previewAspectRatio(ui))
                        .clip(RoundedCornerShape(18.dp))
                        .clickable { vm.setPlaying(!ui.playing) }
                ) {
                    val p = proj
                    if (p != null && p.clips.isNotEmpty()) {
                        val state = TimelineMath.frameAt(
                            playTime.toDouble(),
                            p.clips.size,
                            p.effectiveClipDuration(),
                            p.transitionDurationSec
                        )
                        drawFrame(p, state, previewBmps.value)
                    } else {
                        drawRect(Color.Black)
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
                Text(
                    text = "\u27F2",
                    style = MaterialTheme.typography.titleMedium,
                    color = AeTextDim,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(AeCard)
                        .clickable {
                            playTime = 0f
                            vm.audioTick(0.0)
                        }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                )
                Spacer(Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(if (ui.playing) AeCardHi else AeGold)
                        .clickable { vm.setPlaying(!ui.playing) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (ui.playing) "\u2759\u2759" else "\u25B6",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (ui.playing) AeText else AeBlack
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
                Text(
                    text = "\u26F6",
                    style = MaterialTheme.typography.titleMedium,
                    color = AeTextDim,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(AeCard)
                        .clickable { fullscreen = true }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }

            // ------------------------------------------------ scrolling body
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionChip("IMAGES", "+", ui.clipCount == 0) { launchImages() }
                    ActionChip("FORMULA", "\u2699") { vm.setSheet(showFormula = true) }
                    ActionChip("VOICE", if (ui.voice != null) "\u2713" else "+") { pickVoice.launch("audio/*") }
                    ActionChip("MUSIC", if (ui.music != null) "\u2713" else "+") { pickMusic.launch("audio/*") }
                }
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionChip("LOOK", "\u25CB", modifier = Modifier.weight(1f)) { vm.setSheet(showAdjust = true) }
                    ActionChip("EXPORT", "\u25BC", gold = true, modifier = Modifier.weight(2f)) {
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
                            .background(AeCard)
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
                            .background(AeCard, RoundedCornerShape(14.dp))
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
                    TimelineRow(vm = vm, ui = ui, onClipClick = { i -> vm.setSheet(showClipMenu = i) })
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
            .background(AeCard),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        AutoEditLogo(size = 64.dp, withWordmark = false)
        Spacer(Modifier.height(14.dp))
        Text(
            text = "YOUR CLIPS WILL PREVIEW HERE",
            style = MaterialTheme.typography.labelMedium,
            color = AeTextDim
        )
        Spacer(Modifier.height(14.dp))
        GoldButton(text = "+ ADD IMAGES", onClick = onAdd)
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
            .background(Color.Black)
    ) {
        Canvas(modifier = Modifier.fillMaxSize().clickable { onTogglePlay() }) {
            val p = proj
            if (p != null && p.clips.isNotEmpty()) {
                val state = TimelineMath.frameAt(
                    playTime.toDouble(),
                    p.clips.size,
                    p.effectiveClipDuration(),
                    p.transitionDurationSec
                )
                drawFrame(p, state, bmps)
            } else {
                drawRect(Color.Black)
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .clip(CircleShape)
                .background(AeCard)
                .clickable { onExit() }
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Text("X", color = AeText, style = MaterialTheme.typography.titleSmall)
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
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
private fun TimelineRow(vm: AppViewModel, ui: AppViewModel.Ui, onClipClick: (Int) -> Unit) {
    val thumbs = remember { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(ui.clipInfos, key = { "clip-${it.index}" }) { info ->
            val thumb = thumbs.value[info.uri]
            LaunchedEffect(info.uri) {
                if (thumbs.value[info.uri] == null) {
                    val b = vm.loadThumb(info.uri)
                    if (b != null) thumbs.value = thumbs.value + (info.uri to b)
                }
            }
            Column(
                modifier = Modifier
                    .width(84.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AeCard)
                    .clickable { onClipClick(info.index) }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                        .background(AeCharcoal),
                    contentAlignment = Alignment.Center
                ) {
                    if (thumb != null) {
                        Image(
                            bitmap = thumb,
                            contentDescription = "Image ${info.index + 1}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                        )
                    } else {
                        Text("IMG", style = MaterialTheme.typography.labelSmall, color = AeTextDim)
                    }
                    if (info.transitionBefore) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(4.dp)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(AeGold)
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "IMG %03d".format(info.index + 1),
                        style = MaterialTheme.typography.labelSmall,
                        color = AeText
                    )
                    Text(
                        text = info.motionLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = AeGold
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionChip(
    label: String,
    icon: String,
    gold: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (gold) AeGold else AeCard)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = icon,
            style = MaterialTheme.typography.titleSmall,
            color = if (gold) AeBlack else AeGold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (gold) AeBlack else AeText,
            maxLines = 1
        )
    }
}


