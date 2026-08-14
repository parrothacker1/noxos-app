package com.noxos.app

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.noxos.audit.*
import com.noxos.netmonitor.NetMonitor
import com.noxos.triggerrouter.TriggerRouter
import com.noxos.triggerrouter.vm.RealVmSessionFactory
import kotlinx.coroutines.launch

sealed class Screen {
    object Home : Screen()
    object AuditList : Screen()
    data class AuditDetail(val event: AuditEvent) : Screen()
}

class MainActivity : ComponentActivity() {

    private lateinit var auditRepository: AuditRepository
    private lateinit var triggerRouter: TriggerRouter
    private lateinit var netMonitor: NetMonitor

    // Launcher for VPN permission (system dialog).
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
        triggerRouter = TriggerRouter(applicationContext, auditRepository, RealVmSessionFactory())
        netMonitor = NetMonitor(auditRepository)

        setContent {
            var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }
            var netMonitorActive by remember { mutableStateOf(false) }
            val coroutineScope = rememberCoroutineScope()
            val auditEvents by auditRepository.observeAll().collectAsState(initial = emptyList())

            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    when (val screen = currentScreen) {
                        is Screen.Home -> {
                            HomeScreen(
                                netMonitorActive = netMonitorActive,
                                onScanFile = { uri ->
                                    coroutineScope.launch {
                                        triggerRouter.scanFile(
                                            uri,
                                            uri.lastPathSegment ?: "unknown_file"
                                        )
                                    }
                                },
                                onToggleNetMonitor = {
                                    if (netMonitorActive) {
                                        netMonitor.stop(this@MainActivity)
                                        netMonitorActive = false
                                    } else {
                                        val permIntent = netMonitor.prepareIntent(this@MainActivity)
                                        if (permIntent != null) {
                                            vpnPermissionLauncher.launch(permIntent)
                                        } else {
                                            // Permission already granted.
                                            netMonitor.start(this@MainActivity)
                                            netMonitorActive = true
                                        }
                                    }
                                },
                                onViewAudit = { currentScreen = Screen.AuditList }
                            )
                        }
                        is Screen.AuditList -> {
                            AuditListScreen(
                                events = auditEvents,
                                onItemClick = { event ->
                                    currentScreen = Screen.AuditDetail(event)
                                }
                            )
                        }
                        is Screen.AuditDetail -> {
                            AuditDetailScreen(
                                event = screen.event,
                                onBack = { currentScreen = Screen.AuditList }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    netMonitorActive: Boolean,
    onScanFile: (Uri) -> Unit,
    onToggleNetMonitor: () -> Unit,
    onViewAudit: () -> Unit
) {
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) onScanFile(uri)
        }
    )

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Warden") })
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Isolated File Scanner",
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Parse untrusted files safely inside an ephemeral Microdroid pVM.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Select & Scan File")
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onToggleNetMonitor,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (netMonitorActive) "Stop Network Monitor"
                    else "Start Network Monitor"
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onViewAudit,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("View Audit Logs")
            }
        }
    }
}
