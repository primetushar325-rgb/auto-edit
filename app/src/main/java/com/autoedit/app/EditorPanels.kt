package com.autoedit.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.autoedit.engine.AspectRatio
import com.autoedit.engine.ExportConfig
import com.autoedit.engine.Formula
import com.autoedit.engine.FormulaCatalog
import com.autoedit.engine.Quality
import com.autoedit.engine.TransitionType
import kotlin.math.roundToInt

@Composable
fun GoldButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(AeGold)
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = AeBlack,
            letterSpacing = 1.2.sp
        )
    }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(AeCard)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = AeText,
            letterSpacing = 1.sp
        )
    }
}

/** Dimmed full-screen backdrop + bottom sheet card. */
@Composable
fun SheetScaffold(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f))
            .clickable { onClose() }
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(AeCard)
                .clickable { }
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = AeGold, modifier = Modifier.weight(1f))
                Text(
                    text = "X",
                    style = MaterialTheme.typography.titleSmall,
                    color = AeTextDim,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(AeCharcoal)
                        .clickable(onClick = onClose)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
            Spacer(Modifier.height(14.dp))
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
            ) {
                content()
            }
        }
    }
}

// ------------------------------------------------------------------ formula

@Composable
fun FormulaSheet(vm: AppViewModel) {
    val ui by vm.ui.collectAsState()
    SheetScaffold(title = "FORMULA", onClose = { vm.setSheet(showFormula = false) }) {
        Text(
            text = "A formula is the automatic recipe that turns your images into moving clips.",
            style = MaterialTheme.typography.bodySmall,
            color = AeTextDim
        )
        Spacer(Modifier.height(12.dp))
        FormulaCatalog.all.forEach { f ->
            FormulaCard(f, isActive = ui.formulaId == f.id) { vm.applyFormula(f.id) }
            Spacer(Modifier.height(10.dp))
        }
        if (ui.clipCount > 0) {
            GoldButton(text = "RANDOMIZE AGAIN", onClick = { vm.randomizeAgain() })
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun FormulaCard(f: Formula, isActive: Boolean, onApply: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (isActive) AeCardHi else AeCharcoal)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(f.name, style = MaterialTheme.typography.titleSmall, color = AeText)
            Spacer(Modifier.width(8.dp))
            Text(
                text = f.tagline,
                style = MaterialTheme.typography.labelMedium,
                color = AeGold
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(f.description, style = MaterialTheme.typography.bodySmall, color = AeTextDim)
        Spacer(Modifier.height(10.dp))
        SpecRow("MOTION", if (f.motionMode == com.autoedit.engine.MotionMode.RANDOM) "Random" else "Fixed")
        SpecRow("ZOOM", f.zoomRangeLabel())
        SpecRow("TRANSITION", "${f.transition.label()} \u2022 ${"%.2f".format(f.transitionDurationSec)}s")
        SpecRow("DURATION", "${"%.0f".format(f.clipDurationSec)} sec / image")
        Spacer(Modifier.height(12.dp))
        GoldButton(text = "APPLY FORMULA", modifier = Modifier.fillMaxWidth(), onClick = onApply)
    }
}

@Composable
private fun SpecRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = AeTextDim, modifier = Modifier.width(110.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, color = AeText)
    }
}

// ------------------------------------------------------------------ adjust

@Composable
fun AdjustSheet(vm: AppViewModel) {
    val ui by vm.ui.collectAsState()
    val a = ui.adjustments
    SheetScaffold(title = "LOOK", onClose = { vm.setSheet(showAdjust = false) }) {
        Text(
            text = "Optional adjustments. Keep it subtle.",
            style = MaterialTheme.typography.bodySmall,
            color = AeTextDim
        )
        Spacer(Modifier.height(10.dp))
        SliderRow("BRIGHTNESS", a.brightness, -50f, 50f) { vm.setAdjustments(a.copy(brightness = it)) }
        SliderRow("CONTRAST", a.contrast, -50f, 50f) { vm.setAdjustments(a.copy(contrast = it)) }
        SliderRow("SATURATION", a.saturation, -100f, 100f) { vm.setAdjustments(a.copy(saturation = it)) }
        SliderRow("VIGNETTE", a.vignette, 0f, 100f) { vm.setAdjustments(a.copy(vignette = it)) }
        SliderRow("BLUR", a.blur.toFloat(), 0f, 10f) { vm.setAdjustments(a.copy(blur = it.roundToInt())) }
        Spacer(Modifier.height(6.dp))
        SecondaryButton(text = "RESET", onClick = { vm.setAdjustments(com.autoedit.engine.Adjustments()) })
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SliderRow(label: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = AeTextDim,
            modifier = Modifier.width(96.dp)
        )
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = min..max,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = AeGold,
                activeTrackColor = AeGold,
                inactiveTrackColor = AeCardHi
            )
        )
        Text(
            text = "${value.roundToInt()}",
            style = MaterialTheme.typography.labelSmall,
            color = AeText,
            modifier = Modifier.width(36.dp)
        )
    }
}

// ------------------------------------------------------------------ export

@Composable
fun ExportPanel(vm: AppViewModel) {
    val ui by vm.ui.collectAsState()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f))
            .clickable {
                if (!ui.exporting) vm.setSheet(showExport = false)
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(AeCard)
                .clickable { }
                .padding(20.dp)
        ) {
            if (ui.exporting) {
                Text("EXPORTING\u2026", style = MaterialTheme.typography.titleMedium, color = AeText)
                Spacer(Modifier.height(6.dp))
                Text(ui.exportStage, style = MaterialTheme.typography.bodySmall, color = AeTextDim)
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { ui.exportProgress },
                    modifier = Modifier.fillMaxWidth(),
                    color = AeGold,
                    trackColor = AeCharcoal
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "${(ui.exportProgress * 100).roundToInt()}%",
                    style = MaterialTheme.typography.headlineMedium,
                    color = AeGold
                )
                Spacer(Modifier.height(20.dp))
                SecondaryButton(text = "CANCEL EXPORT", onClick = { vm.cancelExport() })
            } else {
                Text("EXPORT VIDEO", style = MaterialTheme.typography.titleMedium, color = AeText)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${ui.clipCount} images \u2022 ${vm.fmtTime(ui.totalDurationSec)} \u2022 fully offline",
                    style = MaterialTheme.typography.bodySmall,
                    color = AeTextDim
                )
                Spacer(Modifier.height(16.dp))

                ChipRow("ASPECT RATIO",
                    AspectRatio.entries.map { it.label },
                    index = AspectRatio.entries.indexOf(ui.aspect),
                    onPick = { i -> vm.setAspect(AspectRatio.entries[i]) }
                )
                Spacer(Modifier.height(12.dp))
                ChipRow("QUALITY",
                    listOf("720p", "1080p", "4K"),
                    index = Quality.entries.indexOf(ui.quality),
                    onPick = { i -> vm.setQuality(Quality.entries[i]) }
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Output: ${ui.quality.resolutionFor(ui.aspect).first}\u00d7${ui.quality.resolutionFor(ui.aspect).second} px",
                    style = MaterialTheme.typography.bodySmall,
                    color = AeTextDim
                )
                Spacer(Modifier.height(12.dp))
                ChipRow("FRAME RATE",
                    ExportConfig.FPS_OPTIONS.map { "${it} fps" },
                    index = ExportConfig.FPS_OPTIONS.indexOf(ui.fps),
                    onPick = { i -> vm.setFps(ExportConfig.FPS_OPTIONS[i]) }
                )
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton(
                        text = "CANCEL",
                        modifier = Modifier.weight(1f),
                        onClick = { vm.setSheet(showExport = false) }
                    )
                    GoldButton(
                        text = "EXPORT",
                        modifier = Modifier.weight(1f),
                        onClick = { vm.startExport() }
                    )
                }
            }
        }
    }
}

@Composable
private fun ChipRow(label: String, options: List<String>, index: Int, onPick: (Int) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelSmall, color = AeTextDim)
    Spacer(Modifier.height(6.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEachIndexed { i, o ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (i == index) AeGold else AeCharcoal)
                    .clickable { onPick(i) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    text = o,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (i == index) AeBlack else AeText
                )
            }
        }
    }
}

// ------------------------------------------------------------------ audio

@Composable
fun AudioCard(vm: AppViewModel, isVoice: Boolean) {
    val ui by vm.ui.collectAsState()
    val cfg = if (isVoice) ui.voice else ui.music
    if (cfg == null) return
    val label = if (isVoice) "VOICE" else "MUSIC"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AeCard)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = AeGold)
            Spacer(Modifier.width(10.dp))
            Text(
                text = cfg.displayName,
                style = MaterialTheme.typography.bodySmall,
                color = AeTextDim,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = vm.fmtTime(cfg.durationSec),
                style = MaterialTheme.typography.labelSmall,
                color = AeText
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "\u2715",
                style = MaterialTheme.typography.titleSmall,
                color = AeDanger,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(AeCharcoal)
                    .clickable { if (isVoice) vm.removeVoice() else vm.removeMusic() }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        SliderRow("VOLUME", cfg.volume, 0f, 1f) { v ->
            if (isVoice) vm.updateVoice { it.copy(volume = v) } else vm.updateMusic { it.copy(volume = v) }
        }
        SliderRow(
            "START",
            cfg.offsetSec.toFloat(),
            0f,
            maxOf(1f, (ui.totalDurationSec).toFloat()),
            { v ->
                val off = v.toDouble()
                if (isVoice) vm.updateVoice { it.copy(offsetSec = off) } else vm.updateMusic { it.copy(offsetSec = off) }
            }
        )
        Row {
            Box(modifier = Modifier.weight(1f)) {
                SliderRow("FADE IN", cfg.fadeInSec.toFloat(), 0f, 3f) { v ->
                    val d = v.toDouble()
                    if (isVoice) vm.updateVoice { it.copy(fadeInSec = d) } else vm.updateMusic { it.copy(fadeInSec = d) }
                }
            }
            Box(modifier = Modifier.weight(1f)) {
                SliderRow("FADE OUT", cfg.fadeOutSec.toFloat(), 0f, 3f) { v ->
                    val d = v.toDouble()
                    if (isVoice) vm.updateVoice { it.copy(fadeOutSec = d) } else vm.updateMusic { it.copy(fadeOutSec = d) }
                }
            }
        }
        if (!isVoice) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("LOOP", style = MaterialTheme.typography.labelMedium, color = AeTextDim, modifier = Modifier.weight(1f))
                SwitchCompat(cfg.loop) { b -> vm.updateMusic { it.copy(loop = b) } }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("DUCK MUSIC WHILE VOICE PLAYS", style = MaterialTheme.typography.labelMedium, color = AeTextDim, modifier = Modifier.weight(1f))
                SwitchCompat(ui.duckMusic) { vm.setDuck(it) }
            }
        }
    }
}

// ------------------------------------------------------------------ misc

@Composable
fun RenameDialog(vm: AppViewModel) {
    val ui by vm.ui.collectAsState()
    var text by remember { mutableStateOf(ui.projectName) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f))
            .clickable { vm.setSheet(showRename = false) },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(AeCard)
                .clickable { }
                .padding(20.dp)
        ) {
            Text("PROJECT NAME", style = MaterialTheme.typography.labelLarge, color = AeGold)
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(AeCharcoal)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    textStyle = MaterialTheme.typography.titleSmall.copy(color = AeText),
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Text)
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(
                    text = "CANCEL",
                    modifier = Modifier.weight(1f),
                    onClick = { vm.setSheet(showRename = false) }
                )
                GoldButton(
                    text = "SAVE",
                    modifier = Modifier.weight(1f),
                    onClick = { vm.renameProject(text) }
                )
            }
        }
    }
}

@Composable
fun ClipMenuDialog(vm: AppViewModel, index: Int) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f))
            .clickable { vm.setSheet(showClipMenu = null) },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(AeCard)
                .clickable { }
                .padding(20.dp)
        ) {
            Text(
                text = "IMAGE %03d".format(index + 1),
                style = MaterialTheme.typography.titleMedium,
                color = AeText
            )
            Spacer(Modifier.height(14.dp))
            MenuRow("\u2039 MOVE LEFT") { vm.moveClip(index, -1) }
            Spacer(Modifier.height(6.dp))
            MenuRow("MOVE RIGHT \u203A") { vm.moveClip(index, +1) }
            Spacer(Modifier.height(6.dp))
            MenuRow("\u2715 REMOVE IMAGE") { vm.removeClip(index) }
        }
    }
}

@Composable
private fun MenuRow(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AeCharcoal)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = AeText)
    }
}

@Composable
fun SwitchCompat(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    androidx.compose.material3.Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = androidx.compose.material3.SwitchDefaults.colors(
            checkedThumbColor = AeBlack,
            checkedTrackColor = AeGold,
            uncheckedThumbColor = AeTextDim,
            uncheckedTrackColor = AeCardHi
        )
    )
}
