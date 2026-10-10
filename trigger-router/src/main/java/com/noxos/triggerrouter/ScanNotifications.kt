package com.noxos.triggerrouter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object ScanNotifications {
    private const val CHANNEL_ID = "warden_scan_completion"

    internal fun completionText(result: ScanResult, quarantined: Boolean): String = when (result) {
        is ScanResult.Success -> if (result.exifData.metadata.keys == setOf("file_type")) {
            "Scan complete — type not deeply inspected, not flagged"
        } else {
            "Scan complete — sanitized"
        }
        is ScanResult.Failure -> if (quarantined) "Quarantined: ${result.reason}" else "Scan flagged: ${result.reason}"
        is ScanResult.Error -> if (quarantined) "Quarantined: ${result.message}" else "Scan error: ${result.message}"
    }

    fun post(context: Context, filename: String, result: ScanResult, quarantined: Boolean = false) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Warden Scan Completion", NotificationManager.IMPORTANCE_DEFAULT)
        )

        val text = completionText(result, quarantined)
        val advisoryText = (result as? ScanResult.Success)?.advisory?.displayText
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(filename)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .apply { if (advisoryText != null) setStyle(Notification.BigTextStyle().bigText("$text\n$advisoryText")) }
            .build()
        manager.notify(filename.hashCode(), notification)
    }
}
