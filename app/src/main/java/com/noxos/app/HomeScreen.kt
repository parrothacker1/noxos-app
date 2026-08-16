package com.noxos.app

import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.noxos.audit.theme.ContainmentMark
import com.noxos.audit.theme.LocalWardenTertiaryText
import com.noxos.triggerrouter.ScanProgress
import com.noxos.triggerrouter.ScanStep

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    isScanning: Boolean,
    scanProgress: ScanProgress,
    onCancelScan: () -> Unit,
    netMonitorActive: Boolean,
    connectionsInspected: Int,
    onScanFile: (Uri) -> Unit,
    onToggleNetMonitor: () -> Unit,
    onViewAudit: () -> Unit,
    onOpenSettings: () -> Unit,
    auditEventCount: Int,
    modifier: Modifier = Modifier
) {
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> if (uri != null) onScanFile(uri) }
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("WARDEN", style = MaterialTheme.typography.titleLarge, letterSpacing = 1.sp)
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            if (isScanning) {
                ScanningContent(scanProgress, onCancelScan)
            } else {
                IdleContent(
                    netMonitorActive = netMonitorActive,
                    connectionsInspected = connectionsInspected,
                    auditEventCount = auditEventCount,
                    onScanFile = { filePickerLauncher.launch(arrayOf("*/*")) },
                    onToggleNetMonitor = onToggleNetMonitor,
                    onViewAudit = onViewAudit
                )
            }
        }
    }
}

@Composable
private fun IdleContent(
    netMonitorActive: Boolean,
    connectionsInspected: Int,
    auditEventCount: Int,
    onScanFile: () -> Unit,
    onToggleNetMonitor: () -> Unit,
    onViewAudit: () -> Unit
) {
    Column {
        Text(
            "NOXOS SECURITY LAYER",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text("Isolated File Scanner", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            "Untrusted files and flagged traffic run inside a disposable VM, sealed from your device, before anything real comes back.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ContainmentMark(active = netMonitorActive)
        Text(
            if (netMonitorActive) "MONITORING — $connectionsInspected CONNECTIONS INSPECTED" else "IDLE — NO ACTIVE SESSION",
            style = MaterialTheme.typography.labelMedium,
            color = if (netMonitorActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = onScanFile,
            enabled = !netMonitorActive,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Outlined.UploadFile, contentDescription = null)
            Spacer(modifier = Modifier.width(10.dp))
            Text("Select & Scan File")
        }
        if (netMonitorActive) {
            Button(onClick = onToggleNetMonitor, modifier = Modifier.fillMaxWidth()) {
                Text("Stop Network Monitor")
            }
        } else {
            OutlinedButton(onClick = onToggleNetMonitor, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Wifi, contentDescription = null)
                Spacer(modifier = Modifier.width(10.dp))
                Text("Start Network Monitor")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onViewAudit) { Text("View Audit Trail") }
            Text("$auditEventCount", style = MaterialTheme.typography.labelMedium, color = LocalWardenTertiaryText.current)
        }
    }
}

@Composable
private fun ScanningContent(progress: ScanProgress, onCancelScan: () -> Unit) {
    Column {
        Text(
            "SCANNING",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(progress.filename ?: "file", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ContainmentMark(active = true, diameter = 80.dp)
        Text(
            "SEALED — EXECUTING INSIDE VM",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        listOf(ScanStep.BOOTING, ScanStep.EXECUTING, ScanStep.SANITIZING, ScanStep.DESTROYING).forEach { step ->
            ScanStepRow(
                label = stepLabel(step),
                durationMillis = progress.stepDurationsMillis[step],
                isCurrent = progress.step == step
            )
        }
    }

    OutlinedButton(
        onClick = onCancelScan,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Cancel Scan")
    }
}

@Composable
private fun ScanStepRow(label: String, durationMillis: Long?, isCurrent: Boolean) {
    val done = durationMillis != null
    val color = when {
        done -> MaterialTheme.colorScheme.primary
        isCurrent -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (isCurrent && !done) {
            val infiniteTransition = rememberInfiniteTransition(label = "step-spin")
            val angle by infiniteTransition.animateFloat(
                initialValue = 0f, targetValue = 360f,
                animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
                label = "step-spin-angle"
            )
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .rotate(angle)
                    .border(1.5.dp, color, CircleShape)
            )
        } else {
            Box(modifier = Modifier.size(14.dp).background(color, CircleShape))
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
            color = if (done || isCurrent) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (done) {
            Text("${durationMillis}ms", style = MaterialTheme.typography.labelMedium, color = LocalWardenTertiaryText.current)
        }
    }
}

private fun stepLabel(step: ScanStep): String = when (step) {
    ScanStep.BOOTING -> "Boot sealed VM"
    ScanStep.EXECUTING -> "Executing in isolation"
    ScanStep.SANITIZING -> "Sanitize result"
    ScanStep.DESTROYING -> "Destroy VM"
    else -> step.name
}
