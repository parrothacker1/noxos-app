package com.noxos.netmonitor

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.noxos.audit.AuditRepository
import com.noxos.audit.BlockedHostRepository
import com.noxos.audit.WardenSettingsRepository
import kotlinx.coroutines.flow.StateFlow

class NetMonitor(
    private val auditRepository: AuditRepository,
    private val blockedHostRepository: BlockedHostRepository,
    private val settingsRepository: WardenSettingsRepository
) {

    val connectionsInspected: StateFlow<Int> = NetMonitorService.connectionsInspected

    fun prepareIntent(context: Context): Intent? {
        return VpnService.prepare(context)
    }

    fun start(context: Context) {
        NetMonitorService.auditRepository = auditRepository
        NetMonitorService.blockedHostRepository = blockedHostRepository
        NetMonitorService.settingsRepository = settingsRepository
        val intent = Intent(context, NetMonitorService::class.java)
            .setAction(NetMonitorService.ACTION_START)
        context.startForegroundService(intent)
    }

    fun stop(context: Context) {
        val intent = Intent(context, NetMonitorService::class.java)
            .setAction(NetMonitorService.ACTION_STOP)
        context.startService(intent)
    }
}
