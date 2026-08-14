package com.noxos.netmonitor

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.noxos.audit.AuditRepository

class NetMonitor(private val auditRepository: AuditRepository) {

    fun prepareIntent(context: Context): Intent? {
        return VpnService.prepare(context)
    }

    fun start(context: Context) {
        NetMonitorService.auditRepository = auditRepository
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
