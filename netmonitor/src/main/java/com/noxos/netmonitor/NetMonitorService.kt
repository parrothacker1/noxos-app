package com.noxos.netmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.noxos.audit.AuditEvent
import com.noxos.audit.AuditEventType
import com.noxos.audit.AuditOutcome
import com.noxos.audit.AuditRepository
import com.noxos.audit.BlockedHostRepository
import com.noxos.audit.WardenSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

class NetMonitorService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var captureJob: Job? = null
    private var blocklistJob: Job? = null

    private val udpSessions = ConcurrentHashMap<String, DatagramSocket>()
    private val lastLoggedAt = ConcurrentHashMap<String, Long>()
    private val logThrottleMs = 5_000L
    private val udpIdleTimeoutMs = 30_000

    private var tcpRelay: TcpRelayManager? = null

    @Volatile
    private var blockedHostsCache: Set<String> = emptySet()

    companion object {
        var auditRepository: AuditRepository? = null
        var blockedHostRepository: BlockedHostRepository? = null
        var settingsRepository: WardenSettingsRepository? = null

        val connectionsInspected = MutableStateFlow(0)

        const val ACTION_START = "com.noxos.netmonitor.START"
        const val ACTION_STOP  = "com.noxos.netmonitor.STOP"

        private const val NOTIFICATION_CHANNEL_ID = "noxos_netmonitor"
        private const val NOTIFICATION_ID = 1001
        private const val FLAGGED_NOTIFICATION_CHANNEL_ID = "noxos_flagged_events"
        private const val FLAGGED_NOTIFICATION_ID = 1002
        private const val TAG = "WardenNetMonitor"
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
        if (vpnInterface != null) {
            Log.i(TAG, "startMonitor() called while already active, ignoring")
            return
        }
        connectionsInspected.value = 0

        val builder = Builder()
            .setSession("Warden Network Monitor")
            .addAddress("10.0.0.1", 32)
            .addRoute("0.0.0.0", 0)

        val iface = builder.establish()
        if (iface == null) {
            Log.e(TAG, "builder.establish() returned null, tunnel not created")
            stopSelf()
            return
        }
        vpnInterface = iface
        Log.i(TAG, "tunnel established")

        val repo = auditRepository
        if (repo == null) {
            Log.e(TAG, "auditRepository was null at startMonitor(), stopping")
            stopMonitor()
            stopSelf()
            return
        }

        blocklistJob = blockedHostRepository?.let { hostsRepo ->
            serviceScope.launch {
                hostsRepo.observeAll().collect { list ->
                    blockedHostsCache = list.map { it.host }.toSet()
                }
            }
        }

        captureJob = serviceScope.launch {
            runCaptureLoop(vpnInterface!!, repo)
        }
    }

    private fun stopMonitor() {
        captureJob?.cancel()
        blocklistJob?.cancel()
        udpSessions.values.forEach { it.close() }
        udpSessions.clear()
        lastLoggedAt.clear()
        tcpRelay?.closeAll()
        tcpRelay = null
        vpnInterface?.close()
        vpnInterface = null
    }

    override fun onDestroy() {
        stopMonitor()
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun runCaptureLoop(
        vpnIface: ParcelFileDescriptor,
        repo: AuditRepository
    ) {
        val inStream  = FileInputStream(vpnIface.fileDescriptor)
        val outStream = FileOutputStream(vpnIface.fileDescriptor)

        val tcp = TcpRelayManager(serviceScope, ::protect, outStream)
        tcpRelay = tcp

        Log.i(TAG, "capture loop started")
        try {
            runCaptureLoopBody(inStream, outStream, tcp, repo)
        } catch (e: Exception) {
            Log.e(TAG, "capture loop crashed", e)
        }
        Log.i(TAG, "capture loop exited")
    }

    private suspend fun runCaptureLoopBody(
        inStream: FileInputStream,
        outStream: FileOutputStream,
        tcp: TcpRelayManager,
        repo: AuditRepository
    ) {
        val buf = ByteArray(32767)
        while (true) {
            val len = try {
                inStream.read(buf)
            } catch (e: IOException) {
                -1
            }
            if (len < 0) {
                delay(20)
                continue
            }
            if (len < 20) continue

            val version = (buf[0].toInt() and 0xF0) shr 4
            if (version != 4) continue

            val descriptor = PacketUtils.extractFlowDescriptor(buf, len)
            val protocol = buf[9].toInt() and 0xFF
            val dstIpStr = PacketUtils.formatIp(buf, 16)

            if (BlockedHostChecker.isBlocked(dstIpStr, blockedHostsCache)) {
                if (descriptor != null) {
                    logFlow(descriptor, repo, AuditOutcome.BLOCKED, flagged = false, resultSummary = "blocked host", remoteHost = dstIpStr)
                }
                continue
            }

            val forwarded = when (protocol) {
                17 -> relayUdp(buf, len, outStream)
                6  -> tcp.handle(buf, len)
                else -> false
            }

            if (descriptor != null) {
                val summary = when {
                    forwarded && protocol == 6 -> "relayed (tcp)"
                    forwarded -> "relayed"
                    protocol == 17 -> "udp relay failed"
                    protocol == 6 -> "tcp relay failed"
                    else -> "not relayed"
                }
                logFlow(descriptor, repo, AuditOutcome.SUCCESS, flagged = !forwarded, resultSummary = summary, remoteHost = dstIpStr)
            }
        }
    }

    private fun relayUdp(packet: ByteArray, len: Int, outStream: FileOutputStream): Boolean {
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl + 8 > len) return false

        val srcIp = PacketUtils.ipBytes(packet, 12)
        val dstIp = PacketUtils.ipBytes(packet, 16)
        val srcPort = PacketUtils.readUShort(packet, ihl)
        val dstPort = PacketUtils.readUShort(packet, ihl + 2)
        val udpLen = PacketUtils.readUShort(packet, ihl + 4)
        val payloadOffset = ihl + 8
        val payloadLen = (udpLen - 8).coerceAtMost(len - payloadOffset).coerceAtLeast(0)

        val clientKey = "${srcIp.joinToString(".")}:$srcPort"
        val socket = udpSessions.getOrPut(clientKey) {
            DatagramSocket().also { sock ->
                protect(sock)
                sock.soTimeout = udpIdleTimeoutMs
                serviceScope.launch { pumpUdpReplies(clientKey, sock, srcIp, srcPort, outStream) }
            }
        }

        return try {
            val dstAddr = InetAddress.getByAddress(dstIp)
            socket.send(DatagramPacket(packet, payloadOffset, payloadLen, dstAddr, dstPort))
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun pumpUdpReplies(
        clientKey: String,
        socket: DatagramSocket,
        clientIp: ByteArray,
        clientPort: Int,
        outStream: FileOutputStream
    ) {
        val buf = ByteArray(32767)
        try {
            while (!socket.isClosed) {
                val reply = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(reply)
                } catch (_: SocketTimeoutException) {
                    break
                }

                val remoteIp = reply.address.address
                if (remoteIp.size != 4) continue

                val response = PacketUtils.buildUdpPacket(
                    srcIp = remoteIp, srcPort = reply.port,
                    dstIp = clientIp, dstPort = clientPort,
                    payload = reply.data, payloadOffset = reply.offset, payloadLen = reply.length
                )
                synchronized(outStream) { outStream.write(response) }
            }
        } catch (_: Exception) {
        } finally {
            udpSessions.remove(clientKey)
            socket.close()
        }
    }

    private suspend fun logFlow(
        descriptor: String,
        repo: AuditRepository,
        outcome: AuditOutcome,
        flagged: Boolean,
        resultSummary: String,
        remoteHost: String
    ) {
        val now = System.currentTimeMillis()
        val last = lastLoggedAt[descriptor]
        if (last != null && now - last < logThrottleMs) return
        lastLoggedAt[descriptor] = now

        repo.record(
            AuditEvent(
                timestampEpochMillis = now,
                eventType = AuditEventType.NETWORK_TRAFFIC,
                inputDescriptor = descriptor,
                outcome = outcome,
                resultSummary = resultSummary,
                durationMillis = 0L,
                errorMessage = null,
                flagged = flagged,
                remoteHost = remoteHost
            )
        )
        connectionsInspected.value += 1

        val alertsEnabled = settingsRepository?.flaggedEventAlertsEnabled?.first() ?: true
        if (flagged && alertsEnabled) {
            notifyFlaggedEvent(descriptor, resultSummary)
        }
    }

    private fun notifyFlaggedEvent(descriptor: String, resultSummary: String) {
        val channel = NotificationChannel(
            FLAGGED_NOTIFICATION_CHANNEL_ID,
            "Warden Flagged Events",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)

        val notification = Notification.Builder(this, FLAGGED_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Flagged: $descriptor")
            .setContentText(resultSummary)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(FLAGGED_NOTIFICATION_ID, notification)
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
