package com.noxos.netmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.noxos.audit.AuditEvent
import com.noxos.audit.AuditEventType
import com.noxos.audit.AuditOutcome
import com.noxos.audit.AuditRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

class NetMonitorService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var captureJob: Job? = null

    // Injected by the owning component before startService().
    // In a real DI setup this would be injected; here we use the companion
    // object pattern so MainActivity can wire it before starting the service.
    companion object {
        var auditRepository: AuditRepository? = null

        const val ACTION_START = "com.noxos.netmonitor.START"
        const val ACTION_STOP  = "com.noxos.netmonitor.STOP"

        private const val NOTIFICATION_CHANNEL_ID = "noxos_netmonitor"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                stopMonitor()
                stopSelf()
                START_NOT_STICKY
            }
            else -> {
                startMonitor()
                START_STICKY
            }
        }
    }

    private fun startMonitor() {
        startForeground(NOTIFICATION_ID, buildNotification())

        val builder = Builder()
            .setSession("Warden Network Monitor")
            .addAddress("10.0.0.1", 32)
            .addRoute("0.0.0.0", 0)

        vpnInterface = builder.establish() ?: return

        val repo = auditRepository ?: return
        captureJob = serviceScope.launch {
            runCaptureLoop(vpnInterface!!, repo)
        }
    }

    private fun stopMonitor() {
        captureJob?.cancel()
        vpnInterface?.close()
        vpnInterface = null
    }

    override fun onDestroy() {
        stopMonitor()
        serviceScope.cancel()
        super.onDestroy()
    }

    // Reads IP packets from the VPN tun interface, classifies them, and
    // forwards (passes through) or records them. Currently records metadata
    // only — no deep-packet inspection yet (that's P10).
    private suspend fun runCaptureLoop(
        vpnIface: ParcelFileDescriptor,
        repo: AuditRepository
    ) {
        val inStream  = FileInputStream(vpnIface.fileDescriptor)
        val outStream = FileOutputStream(vpnIface.fileDescriptor)
        val buf = ByteBuffer.allocate(32767)

        while (true) {
            buf.clear()
            val len = inStream.read(buf.array())
            if (len <= 0) break

            buf.limit(len)
            val descriptor = extractFlowDescriptor(buf.array(), len)

            // Pass the packet through unchanged (forward it back to the tun).
            outStream.write(buf.array(), 0, len)

            if (descriptor != null) {
                val startMs = System.currentTimeMillis()
                repo.record(
                    AuditEvent(
                        timestampEpochMillis = startMs,
                        eventType = AuditEventType.NETWORK_TRAFFIC,
                        inputDescriptor = descriptor,
                        outcome = AuditOutcome.SUCCESS,
                        resultSummary = "forwarded",
                        durationMillis = 0L,
                        errorMessage = null
                    )
                )
            }
        }
    }

    // Extracts a human-readable flow descriptor from raw IP packet bytes.
    // Returns null for packets that can't be parsed (non-IP, fragmented, etc.).
    private fun extractFlowDescriptor(packet: ByteArray, len: Int): String? {
        if (len < 20) return null

        val version = (packet[0].toInt() and 0xF0) shr 4
        if (version != 4) return null  // IPv6 not yet handled

        val protocol = packet[9].toInt() and 0xFF
        val protoName = when (protocol) {
            6   -> "TCP"
            17  -> "UDP"
            1   -> "ICMP"
            else -> "IP/$protocol"
        }

        val srcIp = formatIp(packet, 12)
        val dstIp = formatIp(packet, 16)

        if (len >= 24 && (protocol == 6 || protocol == 17)) {
            val ihl = (packet[0].toInt() and 0x0F) * 4
            if (ihl + 4 <= len) {
                val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or
                              (packet[ihl + 1].toInt() and 0xFF)
                val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or
                              (packet[ihl + 3].toInt() and 0xFF)
                return "$protoName $srcIp:$srcPort → $dstIp:$dstPort"
            }
        }

        return "$protoName $srcIp → $dstIp"
    }

    private fun formatIp(packet: ByteArray, offset: Int): String {
        return "${packet[offset].toInt() and 0xFF}.${packet[offset+1].toInt() and 0xFF}" +
               ".${packet[offset+2].toInt() and 0xFF}.${packet[offset+3].toInt() and 0xFF}"
    }

    private fun buildNotification(): Notification {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Warden Network Monitor",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)

        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Warden Network Monitor")
            .setContentText("Monitoring network traffic for untrusted flows")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
    }
}
