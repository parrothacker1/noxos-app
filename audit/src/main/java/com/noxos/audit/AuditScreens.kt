package com.noxos.audit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditListScreen(
    events: List<AuditEvent>,
    onItemClick: (AuditEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Audit Trail") }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        if (events.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                Text("No audit events recorded yet.")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                items(events) { event ->
                    AuditListItem(event = event, onClick = { onItemClick(event) })
                }
            }
        }
    }
}

@Composable
fun AuditListItem(
    event: AuditEvent,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = event.eventType.name,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = event.outcome.name,
                    color = when (event.outcome) {
                        AuditOutcome.SUCCESS -> MaterialTheme.colorScheme.primary
                        AuditOutcome.FAILURE -> MaterialTheme.colorScheme.error
                        AuditOutcome.ERROR -> MaterialTheme.colorScheme.error
                    },
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = event.inputDescriptor,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = formatTime(event.timestampEpochMillis),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditDetailScreen(
    event: AuditEvent,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Audit Details") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("< Back")
                    }
                }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            DetailRow(label = "ID", value = event.id.toString())
            DetailRow(label = "Timestamp", value = formatTime(event.timestampEpochMillis))
            DetailRow(label = "Event Type", value = event.eventType.name)
            DetailRow(label = "Input", value = event.inputDescriptor)
            DetailRow(label = "Outcome", value = event.outcome.name)
            DetailRow(label = "Duration", value = "${event.durationMillis} ms")
            if (event.resultSummary != null) {
                DetailRow(label = "Result", value = event.resultSummary)
            }
            if (event.errorMessage != null) {
                DetailRow(label = "Error", value = event.errorMessage)
            }
        }
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

private fun formatTime(epochMillis: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(epochMillis))
}
