package com.noxos.app

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
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
import com.noxos.triggerrouter.FileArrivalWatcher
import com.noxos.triggerrouter.QuarantineManager
import com.noxos.triggerrouter.ScanResult
import com.noxos.triggerrouter.TriggerRouter
import com.noxos.triggerrouter.classifier.ModelUpdateManager
import com.noxos.triggerrouter.vm.MicrodroidVmSessionFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed class Screen {
    object Home : Screen()
    object AuditList : Screen()
    data class AuditDetail(val eventId: Long) : Screen()
    object Acl : Screen()
    object Quarantine : Screen()
    object Settings : Screen()
}

class MainActivity : ComponentActivity() {

    private lateinit var auditRepository: AuditRepository
    private lateinit var aclRepository: AclRepository
    private lateinit var quarantineRepository: QuarantineRepository
    private lateinit var settingsRepository: WardenSettingsRepository
    private lateinit var triggerRouter: TriggerRouter
    private lateinit var netMonitor: NetMonitor
    private lateinit var fileArrivalWatcher: FileArrivalWatcher
    private lateinit var quarantineManager: QuarantineManager
    private lateinit var modelUpdateManager: ModelUpdateManager

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val allFilesAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    private var monitoringActive by mutableStateOf(false)

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            netMonitor.start(this)
            fileArrivalWatcher.start()
            monitoringActive = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        auditRepository = AuditModule.create(applicationContext)
        aclRepository = AclModule.create(applicationContext)
        quarantineRepository = QuarantineModule.create(applicationContext)
        settingsRepository = WardenSettingsRepository(applicationContext)
        triggerRouter = TriggerRouter(applicationContext, auditRepository, MicrodroidVmSessionFactory(), settingsRepository)
        netMonitor = NetMonitor(auditRepository, aclRepository, settingsRepository)
        fileArrivalWatcher = FileArrivalWatcher(applicationContext, aclRepository) { uri, displayName -> handleAutoScan(uri, displayName) }
        quarantineManager = QuarantineManager(applicationContext, quarantineRepository)
        modelUpdateManager = ModelUpdateManager(applicationContext)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            allFilesAccessLauncher.launch(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        lifecycleScope.launch {
            val retentionDays = settingsRepository.auditRetentionDays.first()
            auditRepository.purgeOlderThan(RetentionPolicy.cutoffEpochMillis(System.currentTimeMillis(), retentionDays))
        }
        lifecycleScope.launch {
            val quarantineRetentionDays = settingsRepository.quarantineRetentionDays.first()
            quarantineManager.purgeOlderThan(RetentionPolicy.cutoffEpochMillis(System.currentTimeMillis(), quarantineRetentionDays))
        }
        lifecycleScope.launch { aclRepository.seedDefaults() }
        lifecycleScope.launch { modelUpdateManager.checkForUpdateIfStale() }

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
            var scanJob by remember { mutableStateOf<Job?>(null) }
            var pendingExportJson by remember { mutableStateOf("") }
            var modelLoadError by remember { mutableStateOf<String?>(null) }
            val coroutineScope = rememberCoroutineScope()

            val themeMode by settingsRepository.themeMode.collectAsState(initial = ThemeMode.DARK)
            val events by auditRepository.observeAll().collectAsState(initial = emptyList())
            val aclEntries by aclRepository.observeAll().collectAsState(initial = emptyList())
            val quarantineEntries by quarantineRepository.observeAll().collectAsState(initial = emptyList())
            val scanProgress by triggerRouter.progress.collectAsState()
            val connectionsInspected by netMonitor.connectionsInspected.collectAsState()
            val vmTimeout by settingsRepository.vmSessionTimeoutSeconds.collectAsState(initial = 60)
            val flaggedAlerts by settingsRepository.flaggedEventAlertsEnabled.collectAsState(initial = true)
            val scanCompletionAlerts by settingsRepository.scanCompletionAlertsEnabled.collectAsState(initial = true)
            val aiNetworkAnalysisEnabled by settingsRepository.aiNetworkAnalysisEnabled.collectAsState(initial = true)
            val aiFileAnalysisEnabled by settingsRepository.aiFileAnalysisEnabled.collectAsState(initial = true)
            val inferenceEndpointUrl by settingsRepository.inferenceEndpointUrl.collectAsState(initial = "")
            val inferenceApiKey by settingsRepository.inferenceApiKey.collectAsState(initial = "")
            val retentionDays by settingsRepository.auditRetentionDays.collectAsState(initial = 90)
            val quarantineRetentionDays by settingsRepository.quarantineRetentionDays.collectAsState(initial = 30)

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
                    var quarantined = false
                    if (result !is ScanResult.Success) {
                        val reason = when (result) {
                            is ScanResult.Failure -> result.reason
                            is ScanResult.Error -> result.message
                            is ScanResult.Success -> ""
                        }
                        val mimeType = contentResolver.getType(uri)
                        quarantined = quarantineManager.quarantine(uri, filename, mimeType, reason)
                    }
                    if (settingsRepository.scanCompletionAlertsEnabled.first()) {
                        postScanCompletionNotification(filename, result, quarantined)
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
                            monitoringActive = monitoringActive,
                            connectionsInspected = connectionsInspected,
                            modelLoadError = modelLoadError,
                            onScanFile = ::scanFile,
                            onToggleMonitoring = {
                                if (monitoringActive) {
                                    netMonitor.stop(this@MainActivity)
                                    fileArrivalWatcher.stop()
                                    monitoringActive = false
                                } else {
                                    modelLoadError = null
                                    coroutineScope.launch {
                                        val modelReady = modelUpdateManager.ensureModelLoaded()
                                        if (!modelReady) {
                                            modelLoadError = "Couldn't load the threat model — check your connection and try again"
                                            return@launch
                                        }
                                        val permIntent = netMonitor.prepareIntent(this@MainActivity)
                                        if (permIntent != null) {
                                            vpnPermissionLauncher.launch(permIntent)
                                        } else {
                                            netMonitor.start(this@MainActivity)
                                            fileArrivalWatcher.start()
                                            monitoringActive = true
                                        }
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
                                                aclRepository.block(AclKind.NETWORK, host, "blocked from EVT-${event.id}")
                                            }
                                        }
                                        currentScreen = Screen.Acl
                                    },
                                    onExport = {
                                        pendingExportJson = AuditExport.toJson(event)
                                        exportLauncher.launch("warden-event-${event.id}.json")
                                    }
                                )
                            }
                        }

                        is Screen.Acl -> AclScreen(
                            entries = aclEntries,
                            onBack = { currentScreen = Screen.Home },
                            onAllow = { kind, subject -> coroutineScope.launch { aclRepository.allow(kind, subject, "allowed manually") } },
                            onBlock = { kind, subject -> coroutineScope.launch { aclRepository.block(kind, subject, "blocked manually") } },
                            onRemove = { kind, subject -> coroutineScope.launch { aclRepository.remove(kind, subject) } }
                        )

                        is Screen.Quarantine -> QuarantineScreen(
                            entries = quarantineEntries,
                            onBack = { currentScreen = Screen.Home },
                            onRestore = { id -> coroutineScope.launch { quarantineManager.restore(id) } },
                            onDelete = { id -> coroutineScope.launch { quarantineManager.deletePermanently(id) } }
                        )

                        is Screen.Settings -> SettingsScreen(
                            themeMode = themeMode,
                            onThemeModeChange = { mode -> coroutineScope.launch { settingsRepository.setThemeMode(mode) } },
                            vmSessionTimeoutSeconds = vmTimeout,
                            onVmTimeoutSelected = { seconds ->
                                coroutineScope.launch { settingsRepository.setVmSessionTimeoutSeconds(seconds) }
                            },
                            aclEntryCount = aclEntries.size,
                            onViewAcl = { currentScreen = Screen.Acl },
                            quarantineEntryCount = quarantineEntries.size,
                            onViewQuarantine = { currentScreen = Screen.Quarantine },
                            flaggedAlertsEnabled = flaggedAlerts,
                            onFlaggedAlertsChange = { enabled ->
                                coroutineScope.launch { settingsRepository.setFlaggedEventAlertsEnabled(enabled) }
                            },
                            scanCompletionAlertsEnabled = scanCompletionAlerts,
                            onScanCompletionAlertsChange = { enabled ->
                                coroutineScope.launch { settingsRepository.setScanCompletionAlertsEnabled(enabled) }
                            },
                            aiNetworkAnalysisEnabled = aiNetworkAnalysisEnabled,
                            onAiNetworkAnalysisChange = { enabled ->
                                coroutineScope.launch { settingsRepository.setAiNetworkAnalysisEnabled(enabled) }
                            },
                            aiFileAnalysisEnabled = aiFileAnalysisEnabled,
                            onAiFileAnalysisChange = { enabled ->
                                coroutineScope.launch { settingsRepository.setAiFileAnalysisEnabled(enabled) }
                            },
                            inferenceEndpointUrl = inferenceEndpointUrl,
                            onInferenceEndpointUrlChange = { url ->
                                coroutineScope.launch { settingsRepository.setInferenceEndpointUrl(url) }
                            },
                            inferenceApiKey = inferenceApiKey,
                            onInferenceApiKeyChange = { key ->
                                coroutineScope.launch { settingsRepository.setInferenceApiKey(key) }
                            },
                            retentionDays = retentionDays,
                            onRetentionDaysSelected = { days ->
                                coroutineScope.launch {
                                    settingsRepository.setAuditRetentionDays(days)
                                    auditRepository.purgeOlderThan(RetentionPolicy.cutoffEpochMillis(System.currentTimeMillis(), days))
                                }
                            },
                            quarantineRetentionDays = quarantineRetentionDays,
                            onQuarantineRetentionDaysSelected = { days ->
                                coroutineScope.launch {
                                    settingsRepository.setQuarantineRetentionDays(days)
                                    quarantineManager.purgeOlderThan(RetentionPolicy.cutoffEpochMillis(System.currentTimeMillis(), days))
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

    override fun onDestroy() {
        fileArrivalWatcher.stop()
        super.onDestroy()
    }

    private fun handleAutoScan(uri: Uri, filename: String) {
        lifecycleScope.launch {
            val result = triggerRouter.scanFile(uri, filename)
            var quarantined = false
            if (result !is ScanResult.Success) {
                val reason = when (result) {
                    is ScanResult.Failure -> result.reason
                    is ScanResult.Error -> result.message
                    is ScanResult.Success -> ""
                }
                val mimeType = contentResolver.getType(uri)
                quarantined = quarantineManager.quarantine(uri, filename, mimeType, reason)
            }
            if (settingsRepository.scanCompletionAlertsEnabled.first()) {
                postScanCompletionNotification(filename, result, quarantined)
            }
        }
    }

    private fun postScanCompletionNotification(filename: String, result: ScanResult, quarantined: Boolean = false) {
        val channelId = "warden_scan_completion"
        val channel = NotificationChannel(channelId, "Warden Scan Completion", NotificationManager.IMPORTANCE_DEFAULT)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val text = when (result) {
            is ScanResult.Success -> "Scan complete — sanitized"
            is ScanResult.Failure -> if (quarantined) "Quarantined: ${result.reason}" else "Scan flagged: ${result.reason}"
            is ScanResult.Error -> if (quarantined) "Quarantined: ${result.message}" else "Scan error: ${result.message}"
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

