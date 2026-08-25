package com.autoedit.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.autoedit.engine.FormulaCatalog
import com.autoedit.engine.ProjectModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(vm: AppViewModel) {
    val ui by vm.ui.collectAsState()
    var showFormulas by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ProjectModel?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AeBlack)
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(40.dp))
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            AutoEditLogo(size = 110.dp)
        }
        Spacer(Modifier.height(36.dp))

        GoldButton(
            text = "NEW PROJECT",
            icon = AeIcon.Kind.SPARKLES,
            modifier = Modifier.fillMaxWidth(),
            onClick = { vm.newProject() }
        )
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(
                text = "FORMULA",
                icon = AeIcon.Kind.SETTINGS,
                modifier = Modifier.weight(1f),
                onClick = { showFormulas = true }
            )
            SecondaryButton(
                text = "STORAGE",
                icon = AeIcon.Kind.FOLDER,
                modifier = Modifier.weight(1f),
                onClick = { vm.openStorage() }
            )
        }

        Spacer(Modifier.height(28.dp))
        SectionHeader("RECENT PROJECTS")
        Spacer(Modifier.height(12.dp))

        if (ui.projects.isEmpty()) {
            EmptyState(
                title = "No projects yet",
                subtitle = "Tap NEW PROJECT, pick your images and Auto Edit turns them into a cinematic video.",
                illustration = { AeIcon(AeIcon.Kind.FILM, size = 48.dp, tint = AeGoldDim) }
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(ui.projects, key = { it.id }) { p ->
                    ProjectCard(
                        project = p,
                        totalLabel = vm.fmtTime(p.totalDuration()),
                        onClick = { vm.openProject(p.id) },
                        onLongClick = { deleteTarget = p }
                    )
                }
            }
        }
    }

    if (showFormulas) {
        AlertDialog(
            onDismissRequest = { showFormulas = false },
            containerColor = AeCard,
            titleContentColor = AeText,
            textContentColor = AeTextDim,
            title = { Text("FORMULA", color = AeGold, style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FormulaCatalog.all.forEach { f ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(AeCharcoal, RoundedCornerShape(12.dp))
                                .padding(14.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(f.name, style = MaterialTheme.typography.titleSmall, color = AeText)
                                Spacer(Modifier.width(8.dp))
                                Text(f.tagline, style = MaterialTheme.typography.labelMedium, color = AeGold)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(f.description, style = MaterialTheme.typography.bodySmall, color = AeTextDim)
                            Spacer(Modifier.height(10.dp))
                            TextButton(
                                onClick = {
                                    showFormulas = false
                                    vm.newProjectWithFormula(f.id)
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("START WITH THIS FORMULA", color = AeGold, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFormulas = false }) {
                    Text("CLOSE", color = AeText)
                }
            }
        )
    }

    deleteTarget?.let { p ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = AeCard,
            titleContentColor = AeText,
            title = { Text("Delete project?", color = AeText, style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    text = "\"${p.name}\" (${p.clips.size} images) will be removed from this device.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteProject(p.id)
                    deleteTarget = null
                }) {
                    Text("DELETE", color = AeDanger)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("CANCEL", color = AeTextDim)
                }
            }
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ProjectCard(
    project: ProjectModel,
    totalLabel: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val date = remember(project.id) {
        runCatching {
            SimpleDateFormat("dd MMM, HH:mm", Locale.US).format(Date(project.updatedAt))
        }.getOrDefault("")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AeSurface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .background(AeSurface2, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            AeIcon(AeIcon.Kind.FILM, size = 22.dp, tint = AeGold)
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = project.name,
                style = MaterialTheme.typography.titleMedium,
                color = AeText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${project.clips.size} images • $totalLabel • $date",
                style = MaterialTheme.typography.bodySmall,
                color = AeTextDim
            )
        }
        AeIcon(AeIcon.Kind.CHEVRON_RIGHT, size = 20.dp, tint = AeTextDim)
    }
}
