package com.noxos.audit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val vmTimeoutOptionsSeconds = listOf(15, 30, 60, 120)
private val retentionOptionsDays = listOf(30, 90, 180, 365)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    vmSessionTimeoutSeconds: Int,
    onVmTimeoutSelected: (Int) -> Unit,
    blockedHostsCount: Int,
    onViewBlockedHosts: () -> Unit,
    flaggedAlertsEnabled: Boolean,
    onFlaggedAlertsChange: (Boolean) -> Unit,
    scanCompletionAlertsEnabled: Boolean,
    onScanCompletionAlertsChange: (Boolean) -> Unit,
    retentionDays: Int,
    onRetentionDaysSelected: (Int) -> Unit,
    onExportAuditLog: () -> Unit,
    versionLabel: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, contentDescription = "Back") }
                }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            SettingsSection("Isolation policy") {
                SettingsDropdownRow(
                    label = "VM session timeout",
                    options = vmTimeoutOptionsSeconds,
                    selected = vmSessionTimeoutSeconds,
                    displayText = { "${it}s" },
                    onSelect = onVmTimeoutSelected
                )
                SettingsSwitchRow("Auto-destroy on completion", checked = true, enabled = false, onCheckedChange = {})
                SettingsRow("Blocked hosts", "$blockedHostsCount", onClick = onViewBlockedHosts)
            }

            SettingsSection("Appearance") {
                SingleChoiceSegmentedButtonRow {
                    ThemeMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = themeMode == mode,
                            onClick = { onThemeModeChange(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = ThemeMode.entries.size)
                        ) {
                            Text(mode.name.lowercase().replaceFirstChar { it.uppercase() })
                        }
                    }
                }
            }

            SettingsSection("Notifications") {
                SettingsSwitchRow("Flagged event alerts", flaggedAlertsEnabled, onCheckedChange = onFlaggedAlertsChange)
                SettingsSwitchRow("Scan completion", scanCompletionAlertsEnabled, onCheckedChange = onScanCompletionAlertsChange)
            }

            SettingsSection("Data") {
                SettingsDropdownRow(
                    label = "Audit log retention",
                    options = retentionOptionsDays,
                    selected = retentionDays,
                    displayText = { "$it days" },
                    onSelect = onRetentionDaysSelected
                )
                SettingsRow("Export audit log", "", onClick = onExportAuditLog)
            }

            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Warden", style = MaterialTheme.typography.titleMedium)
                Text(versionLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 10.dp)
        )
        content()
    }
}

@Composable
private fun SettingsRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun <T> SettingsDropdownRow(
    label: String,
    options: List<T>,
    selected: T,
    displayText: (T) -> String,
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().clickable { expanded = true }.padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(displayText(selected), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(displayText(option)) },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        },
                        leadingIcon = if (option == selected) {
                            { Icon(Icons.Outlined.Check, contentDescription = null) }
                        } else null
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
