package com.noxos.triggerrouter

import android.content.Context
import android.net.Uri
import com.noxos.audit.AuditRepository
import com.noxos.audit.QuarantineRepository
import com.noxos.audit.WardenSettingsRepository
import com.noxos.triggerrouter.vm.MicrodroidVmSessionFactory
import com.noxos.triggerrouter.vm.VmInstanceDirs
import kotlinx.coroutines.flow.first

/**
 * Scans a newly arrived file, quarantines it unless the scan came back clean, and posts the completion
 * notification. Owned by the long-lived network monitor service rather than the activity, so file
 * auto-scan keeps working after the user backs out of or swipes away the app.
 */
class AutoScanner(
    private val context: Context,
    auditRepository: AuditRepository,
    quarantineRepository: QuarantineRepository,
    private val settingsRepository: WardenSettingsRepository
) {
    private val triggerRouter = TriggerRouter(context, auditRepository, MicrodroidVmSessionFactory(), settingsRepository)
    private val quarantineManager = QuarantineManager(context, quarantineRepository)

    init {
        VmInstanceDirs.of(context).purgeLeftovers()
    }

    suspend fun scan(uri: Uri, filename: String) {
        val result = triggerRouter.scanFile(uri, filename)
        var quarantined = false
        if (result !is ScanResult.Success) {
            val reason = when (result) {
                is ScanResult.Failure -> result.reason
                is ScanResult.Error -> result.message
                is ScanResult.Success -> ""
            }
            quarantined = quarantineManager.quarantine(uri, filename, context.contentResolver.getType(uri), reason)
        }
        if (settingsRepository.scanCompletionAlertsEnabled.first()) {
            ScanNotifications.post(context, filename, result, quarantined)
        }
    }
}
