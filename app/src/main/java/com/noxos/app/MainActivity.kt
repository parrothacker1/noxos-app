package com.noxos.app

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.noxos.audit.*
import com.noxos.audit.theme.WardenTheme
import com.noxos.netmonitor.NetMonitor
import com.noxos.triggerrouter.ScanResult
import com.noxos.triggerrouter.TriggerRouter
import com.noxos.triggerrouter.vm.RealVmSessionFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed class Screen {
    object Home : Screen()
    object AuditList : Screen()
    data class AuditDetail(val eventId: Long) : Screen()
    object BlockedHosts : Screen()
    object Settings : Screen()
}

class MainActivity : ComponentActivity() {

    private lateinit var auditRepository: AuditRepository
    private lateinit var blockedHostRepository: BlockedHostRepository
    private lateinit var settingsRepository: WardenSettingsRepository
    private lateinit var triggerRouter: TriggerRouter
    private lateinit var netMonitor: NetMonitor

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            netMonitor.start(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        auditRepository = AuditModule.create(applicationContext)
        blockedHostRepository = BlockedHostModule.create(applicationContext)
        settingsRepository = WardenSettingsRepository(applicationContext)
        triggerRouter = TriggerRouter(applicationContext, auditRepository, RealVmSessionFactory(), settingsRepository)
        netMonitor = NetMonitor(auditRepository, blockedHostRepository, settingsRepository)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        lifecycleScope.launch {
            val retentionDays = settingsRepository.auditRetentionDays.first()
            auditRepository.purgeOlderThan(RetentionPolicy.cutoffEpochMillis(System.currentTimeMillis(), retentionDays))
        }

        val versionLabel = runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION") info.versionCode.toLong()
            }
            "${info.versionName} (build $code)"
        }.getOrDefault("")

        setContent {
            var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }
            var netMonitorActive by remember { mutableStateOf(false) }
            var scanJob by remember { mutableStateOf<Job?>(null) }
            var pendingExportJson by remember { mutableStateOf("") }
            val coroutineScope = rememberCoroutineScope()

            val themeMode by settingsRepository.themeMode.collectAsState(initial = ThemeMode.DARK)
            val events by auditRepository.observeAll().collectAsState(initial = emptyList())
            val blockedHosts by blockedHostRepository.observeAll().collectAsState(initial = emptyList())
            val scanProgress by triggerRouter.progress.collectAsState()
            val connectionsInspected by netMonitor.connectionsInspected.collectAsState()
            val vmTimeout by settingsRepository.vmSessionTimeoutSeconds.collectAsState(initial = 60)
            val flaggedAlerts by settingsRepository.flaggedEventAlertsEnabled.collectAsState(initial = true)
            val scanCompletionAlerts by settingsRepository.scanCompletionAlertsEnabled.collectAsState(initial = true)
            val retentionDays by settingsRepository.auditRetentionDays.collectAsState(initial = 90)

            val exportLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/json")
            ) { uri ->
                if (uri != null) {
                    coroutineScope.launch(Dispatchers.IO) { AuditExport.write(applicationContext, uri, pendingExportJson) }
                }
            }

            fun scanFile(uri: Uri) {
                val filename = uri.lastPathSegment ?: "unknown_file"
                scanJob = coroutineScope.launch {
                    val result = triggerRouter.scanFile(uri, filename)
                    scanJob = null
                    if (settingsRepository.scanCompletionAlertsEnabled.first()) {
                        postScanCompletionNotification(filename, result)
                    }
                }
            }

            WardenTheme(themeMode) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when (val screen = currentScreen) {
                        is Screen.Home -> HomeScreen(
                            isScanning = scanJob != null,
                            scanProgress = scanProgress,
                            onCancelScan = {
                                scanJob?.cancel()
                                scanJob = null
                            },
                            netMonitorActive = netMonitorActive,
                            connectionsInspected = connectionsInspected,
                            onScanFile = ::scanFile,
                            onToggleNetMonitor = {
                                if (netMonitorActive) {
                                    netMonitor.stop(this@MainActivity)
                                    netMonitorActive = false
                                } else {
                                    val permIntent = netMonitor.prepareIntent(this@MainActivity)
                                    if (permIntent != null) {
                                        vpnPermissionLauncher.launch(permIntent)
                                    } else {
                                        netMonitor.start(this@MainActivity)
                                        netMonitorActive = true
                                    }
                                }
                            },
                            onViewAudit = { currentScreen = Screen.AuditList },
                            onOpenSettings = { currentScreen = Screen.Settings },
                            auditEventCount = events.size
                        )

                        is Screen.AuditList -> AuditListScreen(
                            events = events,
                            onBack = { currentScreen = Screen.Home },
                            onItemClick = { currentScreen = Screen.AuditDetail(it.id) },
                            onExportAll = {
                                pendingExportJson = AuditExport.toJson(events)
                                exportLauncher.launch("warden-audit-log.json")
                            }
                        )

                        is Screen.AuditDetail -> {
                            val event = events.find { it.id == screen.eventId }
                            if (event == null) {
                                currentScreen = Screen.AuditList
                            } else {
                                AuditDetailScreen(
                                    event = event,
                                    onBack = { currentScreen = Screen.AuditList },
                                    onToggleFlag = {
                                        coroutineScope.launch { auditRepository.setFlagged(event.id, !event.flagged) }
                                    },
                                    onDelete = {
                                        coroutineScope.launch { auditRepository.delete(event.id) }
                                        currentScreen = Screen.AuditList
                                    },
                                    onRescan = { currentScreen = Screen.Home },
                                    onBlockHost = {
                                        event.remoteHost?.let { host ->
                                            coroutineScope.launch {
                                                blockedHostRepository.block(host, "blocked from EVT-${event.id}")
                                            }
                                        }
                                        currentScreen = Screen.BlockedHosts
                                    },
                                    onExport = {
                                        pendingExportJson = AuditExport.toJson(event)
                                        exportLauncher.launch("warden-event-${event.id}.json")
                                    }
                                )
                            }
                        }

                        is Screen.BlockedHosts -> BlockedHostsScreen(
                            hosts = blockedHosts,
                            onBack = { currentScreen = Screen.Home },
                            onUnblock = { host -> coroutineScope.launch { blockedHostRepository.unblock(host) } },
                            onBlockManually = { host ->
                                coroutineScope.launch { blockedHostRepository.block(host, "blocked manually") }
                            }
                        )

                        is Screen.Settings -> SettingsScreen(
                            themeMode = themeMode,
                            onThemeModeChange = { mode -> coroutineScope.launch { settingsRepository.setThemeMode(mode) } },
                            vmSessionTimeoutSeconds = vmTimeout,
                            onVmTimeoutSelected = { seconds ->
                                coroutineScope.launch { settingsRepository.setVmSessionTimeoutSeconds(seconds) }
                            },
                            blockedHostsCount = blockedHosts.size,
                            onViewBlockedHosts = { currentScreen = Screen.BlockedHosts },
                            flaggedAlertsEnabled = flaggedAlerts,
                            onFlaggedAlertsChange = { enabled ->
                                coroutineScope.launch { settingsRepository.setFlaggedEventAlertsEnabled(enabled) }
                            },
                            scanCompletionAlertsEnabled = scanCompletionAlerts,
                            onScanCompletionAlertsChange = { enabled ->
                                coroutineScope.launch { settingsRepository.setScanCompletionAlertsEnabled(enabled) }
                            },
                            retentionDays = retentionDays,
                            onRetentionDaysSelected = { days ->
                                coroutineScope.launch {
                                    settingsRepository.setAuditRetentionDays(days)
                                    auditRepository.purgeOlderThan(RetentionPolicy.cutoffEpochMillis(System.currentTimeMillis(), days))
                                }
                            },
                            onExportAuditLog = {
                                pendingExportJson = AuditExport.toJson(events)
                                exportLauncher.launch("warden-audit-log.json")
                            },
                            versionLabel = versionLabel,
                            onBack = { currentScreen = Screen.Home }
                        )
                    }
                }
            }
        }
    }

    private fun postScanCompletionNotification(filename: String, result: ScanResult) {
        val channelId = "warden_scan_completion"
        val channel = NotificationChannel(channelId, "Warden Scan Completion", NotificationManager.IMPORTANCE_DEFAULT)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val text = when (result) {
            is ScanResult.Success -> "Scan complete — sanitized"
            is ScanResult.Failure -> "Scan flagged: ${result.reason}"
            is ScanResult.Error -> "Scan error: ${result.message}"
        }
        val notification = Notification.Builder(this, channelId)
            .setContentTitle(filename)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(filename.hashCode(), notification)
    }
}

