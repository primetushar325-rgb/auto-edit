package com.autoedit.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Storage usage screen: total app project storage + per-project size with
 * full delete (project.json + source_images + audio + export + temp).
 */
@Composable
fun StorageScreen(vm: AppViewModel) {
    val ui by vm.ui.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AeBlack)
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AeIconButton(AeIcon.Kind.BACK, size = 40, background = AeSurface2, tint = AeText) {
                vm.closeStorage()
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "STORAGE",
                style = MaterialTheme.typography.titleMedium,
                color = AeGold,
                modifier = Modifier.weight(1f)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(AeSurface)
                .padding(16.dp)
        ) {
            Column {
                Text(
                    "TOTAL USED BY PROJECTS",
                    style = MaterialTheme.typography.labelSmall,
                    color = AeTextDim
                )
                Text(
                    vm.fmtBytes(ui.storageTotal),
                    style = MaterialTheme.typography.headlineMedium,
                    color = AeGold
                )
                Text(
                    "Each project lives in its own folder: source images, audio, exported videos and render temp (auto-cleaned).",
                    style = MaterialTheme.typography.bodySmall,
                    color = AeTextDim
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        if (ui.storageRows.isEmpty()) {
            EmptyState(
                title = "Nothing stored yet",
                subtitle = "Exported videos and project files will appear here, all kept privately on this device.",
                illustration = { AeIcon(AeIcon.Kind.FOLDER, size = 48.dp, tint = AeGoldDim) }
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(ui.storageRows, key = { it.id }) { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(AeSurface)
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                row.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = AeText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                vm.fmtBytes(row.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = AeTextDim
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(AeSurface2)
                                .clickable { vm.deleteProject(row.id) }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AeIcon(AeIcon.Kind.TRASH, size = 16.dp, tint = AeDanger)
                                Spacer(Modifier.width(6.dp))
                                Text("DELETE", style = MaterialTheme.typography.labelMedium, color = AeDanger)
                            }
                        }
                    }
                }
            }
        }
    }
}
