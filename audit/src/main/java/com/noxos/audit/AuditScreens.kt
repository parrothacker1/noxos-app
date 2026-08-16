package com.noxos.audit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.OutlinedFlag
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.noxos.audit.theme.LocalWardenTertiaryText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditListScreen(
    events: List<AuditEvent>,
    onBack: () -> Unit,
    onItemClick: (AuditEvent) -> Unit,
    onExportAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    var searchActive by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(AuditFilter.ALL) }

    val filtered = remember(events, filter, query) { filterAuditEvents(events, filter, query) }
    val now = remember { System.currentTimeMillis() }
    val grouped = remember(filtered) { filtered.groupBy { dateBucketLabel(it.timestampEpochMillis, now) } }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, contentDescription = "Back") }
                },
                title = {
                    if (searchActive) {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            placeholder = { Text("Search events") },
                            colors = TextFieldDefaults.colors(
                                unfocusedContainerColor = MaterialTheme.colorScheme.background,
                                focusedContainerColor = MaterialTheme.colorScheme.background
                            )
                        )
                    } else {
                        Text("Audit Trail")
                    }
                },
                actions = {
                    IconButton(onClick = { searchActive = !searchActive; if (!searchActive) query = "" }) {
                        Icon(Icons.Outlined.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = onExportAll) {
                        Icon(Icons.Outlined.FileUpload, contentDescription = "Export audit log")
                    }
                }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AuditFilter.entries.forEach { candidate ->
                    FilterChip(
                        selected = filter == candidate,
                        onClick = { filter = candidate },
                        label = { Text(candidate.name.lowercase().replaceFirstChar { it.uppercase() }) }
                    )
                }
            }

            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No audit events recorded yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    grouped.forEach { (bucket, bucketEvents) ->
                        item {
                            Text(
                                text = bucket.uppercase(),
                                style = MaterialTheme.typography.labelMedium,
                                color = LocalWardenTertiaryText.current,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                            )
                        }
                        items(bucketEvents) { event ->
                            AuditListItem(event = event, onClick = { onItemClick(event) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AuditListItem(event: AuditEvent, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (event.eventType == AuditEventType.FILE_SCAN) Icons.Outlined.Description else Icons.Outlined.SwapHoriz,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = event.inputDescriptor,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Text(
                text = formatTime(event.timestampEpochMillis),
                style = MaterialTheme.typography.labelMedium,
                color = LocalWardenTertiaryText.current
            )
        }
        StatusPill(event)
    }
}

@Composable
fun StatusPill(event: AuditEvent) {
    val (label, color) = when (severityOf(event)) {
        AuditSeverity.SAFE -> "Safe" to MaterialTheme.colorScheme.primary
        AuditSeverity.FLAGGED -> "Flagged" to MaterialTheme.colorScheme.tertiary
        AuditSeverity.BLOCKED -> "Blocked" to MaterialTheme.colorScheme.error
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(100))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Box(modifier = Modifier.size(6.dp).background(color, CircleShape))
        Text(label, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditDetailScreen(
    event: AuditEvent,
    onBack: () -> Unit,
    onToggleFlag: () -> Unit,
    onDelete: () -> Unit,
    onRescan: () -> Unit,
    onBlockHost: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Event Detail") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, contentDescription = "Back") }
                }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            SeverityBanner(event)

            if (event.eventType == AuditEventType.FILE_SCAN && event.stepTimingsCsv != null) {
                ContainmentTimeline(event.stepTimingsCsv)
            }

            Column {
                DetailRow("Event ID", "EVT-${event.id}")
                DetailRow("Event Type", if (event.eventType == AuditEventType.FILE_SCAN) "File Scan" else "Network Traffic")
                DetailRow("Input", event.inputDescriptor)
                if (event.eventType == AuditEventType.NETWORK_TRAFFIC && event.remoteHost != null) {
                    DetailRow("Remote Host", event.remoteHost)
                }
                DetailRow("Duration", "${event.durationMillis} ms")
                event.resultSummary?.let { DetailRow("Result", it) }
                event.errorMessage?.let { DetailRow("Error", it) }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DetailActionButton(
                    icon = if (event.flagged) Icons.Outlined.Flag else Icons.Outlined.OutlinedFlag,
                    label = if (event.flagged) "Unflag" else "Flag",
                    onClick = onToggleFlag,
                    modifier = Modifier.weight(1f)
                )
                DetailActionButton(Icons.Outlined.Share, "Share", onExport, Modifier.weight(1f))
                if (event.eventType == AuditEventType.FILE_SCAN) {
                    DetailActionButton(Icons.Outlined.Refresh, "Re-scan", onRescan, Modifier.weight(1f))
                } else if (event.remoteHost != null) {
                    DetailActionButton(
                        Icons.Outlined.Block, "Block Host", onBlockHost, Modifier.weight(1f),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
                DetailActionButton(
                    Icons.Outlined.Delete, "Delete", onDelete, Modifier.weight(1f),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun SeverityBanner(event: AuditEvent) {
    val severity = severityOf(event)
    val color = when (severity) {
        AuditSeverity.SAFE -> MaterialTheme.colorScheme.primary
        AuditSeverity.FLAGGED -> MaterialTheme.colorScheme.tertiary
        AuditSeverity.BLOCKED -> MaterialTheme.colorScheme.error
    }
    val title = when {
        severity == AuditSeverity.BLOCKED -> "BLOCKED — HOST ON BLOCKLIST"
        severity == AuditSeverity.SAFE && event.eventType == AuditEventType.FILE_SCAN -> "SANITIZED — SAFE TO USE"
        severity == AuditSeverity.SAFE -> "RELAYED — INSPECTED IN ISOLATION"
        event.eventType == AuditEventType.FILE_SCAN -> "FLAGGED — SCAN DID NOT COMPLETE CLEANLY"
        else -> "FLAGGED"
    }
    val message = event.errorMessage ?: event.resultSummary ?: when (event.eventType) {
        AuditEventType.FILE_SCAN -> "The file ran fully sealed. Nothing on this device was exposed to its contents."
        AuditEventType.NETWORK_TRAFFIC -> "Observed inside the sealed VM."
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column {
            Text(title, style = MaterialTheme.typography.labelLarge, color = color)
            Spacer(modifier = Modifier.height(3.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ContainmentTimeline(stepTimingsCsv: String) {
    val steps = remember(stepTimingsCsv) {
        stepTimingsCsv.split(",").mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size == 2) parts[0] to parts[1].toLongOrNull() else null
        }
    }
    Column {
        Text(
            "CONTAINMENT TIMELINE",
            style = MaterialTheme.typography.labelMedium,
            color = LocalWardenTertiaryText.current,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        steps.forEachIndexed { index, (name, millis) ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                Box(modifier = Modifier.size(10.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                Column {
                    Text(stepLabel(name), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${millis ?: 0} ms",
                        style = MaterialTheme.typography.labelMedium,
                        color = LocalWardenTertiaryText.current
                    )
                }
            }
        }
    }
}

private fun stepLabel(step: String): String = when (step) {
    "BOOTING" -> "Boot sealed VM"
    "EXECUTING" -> "Execute in isolation"
    "SANITIZING" -> "Sanitize output"
    "DESTROYING" -> "Destroy VM"
    else -> step
}

@Composable
private fun DetailActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, contentDescription = label, tint = tint)
        Text(label, style = MaterialTheme.typography.labelLarge, color = tint)
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatTime(epochMillis: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(epochMillis))
}
