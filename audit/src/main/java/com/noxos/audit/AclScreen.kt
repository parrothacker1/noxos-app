package com.noxos.audit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Warning
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
fun AclScreen(
    entries: List<AclEntry>,
    onBack: () -> Unit,
    onAllow: (AclKind, String) -> Unit,
    onBlock: (AclKind, String) -> Unit,
    onRemove: (AclKind, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Access Control List") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, contentDescription = "Back") }
                }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 20.dp)) {
            Text(
                "Blocked entries are terminated before they reach the real network stack or scanner. " +
                    "Flagged entries are new and awaiting analysis — traffic and files still flow.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp)
            )

            if (entries.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    Text("No entries in the ACL yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(entries, key = { "${it.kind}:${it.subject}" }) { entry ->
                        AclRow(
                            entry = entry,
                            onAllow = { onAllow(entry.kind, entry.subject) },
                            onBlock = { onBlock(entry.kind, entry.subject) },
                            onRemove = { onRemove(entry.kind, entry.subject) }
                        )
                    }
                }
            }

            TextButton(
                onClick = { showAddDialog = true },
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add an entry manually")
            }
        }
    }

    if (showAddDialog) {
        var input by remember { mutableStateOf("") }
        var selectedKind by remember { mutableStateOf(AclKind.NETWORK) }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add an entry") },
            text = {
                Column {
                    SingleChoiceSegmentedButtonRow {
                        AclKind.entries.forEachIndexed { index, kind ->
                            SegmentedButton(
                                selected = selectedKind == kind,
                                onClick = { selectedKind = kind },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = AclKind.entries.size)
                            ) {
                                Text(if (kind == AclKind.NETWORK) "Host" else "App source")
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        singleLine = true,
                        label = { Text(if (selectedKind == AclKind.NETWORK) "IP address" else "Package name") }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (input.isNotBlank()) onBlock(selectedKind, input.trim())
                    showAddDialog = false
                }) { Text("Block") }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (input.isNotBlank()) onAllow(selectedKind, input.trim())
                    showAddDialog = false
                }) { Text("Allow") }
            }
        )
    }
}

@Composable
private fun AclRow(entry: AclEntry, onAllow: () -> Unit, onBlock: () -> Unit, onRemove: () -> Unit) {
    val (icon, tint) = when (entry.state) {
        AclState.ALLOWED -> Icons.Outlined.CheckCircle to MaterialTheme.colorScheme.primary
        AclState.BLOCKED -> Icons.Outlined.Block to MaterialTheme.colorScheme.error
        AclState.FLAGGED -> Icons.Outlined.Warning to MaterialTheme.colorScheme.tertiary
    }
    val kindLabel = if (entry.kind == AclKind.NETWORK) "Network" else "App source"
    val priorityLabel = if (entry.state == AclState.FLAGGED && entry.priority == AclPriority.HIGH) " · high priority" else ""
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = entry.state.name, tint = tint)
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.subject, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "$kindLabel · ${entry.reason} · ${formatDate(entry.updatedAtEpochMillis)}$priorityLabel",
                style = MaterialTheme.typography.labelMedium,
                color = LocalWardenTertiaryText.current
            )
            entry.safetyScore?.let { score ->
                Text(
                    "Safety score: ${(score * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalWardenTertiaryText.current
                )
            }
        }
        if (entry.state != AclState.ALLOWED) {
            IconButton(onClick = onAllow) { Icon(Icons.Outlined.CheckCircle, contentDescription = "Allow") }
        }
        if (entry.state != AclState.BLOCKED) {
            IconButton(onClick = onBlock) { Icon(Icons.Outlined.Block, contentDescription = "Block") }
        }
        IconButton(onClick = onRemove) { Icon(Icons.Outlined.Delete, contentDescription = "Remove") }
    }
}

private fun formatDate(epochMillis: Long): String =
    SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(epochMillis))
