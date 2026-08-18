package com.noxos.netmonitor

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

internal class TcpRelayManager(
    private val scope: CoroutineScope,
    private val protect: (Socket) -> Boolean,
    private val outStream: OutputStream
) {
    companion object {
        const val FLAG_FIN = 0x01
        const val FLAG_SYN = 0x02
        const val FLAG_RST = 0x04
        const val FLAG_ACK = 0x10
        private const val TAG = "WardenTcpRelay"
    }

    private enum class State { CONNECTING, ESTABLISHED }

    private class Session(val socket: Socket) {
        @Volatile var clientNextSeq: Long = 0L
        @Volatile var ourSeq: Long = 0L
        @Volatile var state: State = State.CONNECTING
    }

    private val sessions = ConcurrentHashMap<String, Session>()

    fun handle(packet: ByteArray, len: Int): Boolean {
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl + 20 > len) return false

        val srcIp = PacketUtils.ipBytes(packet, 12)
        val dstIp = PacketUtils.ipBytes(packet, 16)
        val srcPort = PacketUtils.readUShort(packet, ihl)
        val dstPort = PacketUtils.readUShort(packet, ihl + 2)
        val seq = PacketUtils.readUInt(packet, ihl + 4)
        val dataOffset = ((packet[ihl + 12].toInt() and 0xF0) shr 4) * 4
        val flags = packet[ihl + 13].toInt() and 0xFF
        val payloadOffset = ihl + dataOffset
        val payloadLen = (len - payloadOffset).coerceAtLeast(0)

        val key = "${srcIp.joinToString(".")}:$srcPort-${dstIp.joinToString(".")}:$dstPort"
        val isSyn = flags and FLAG_SYN != 0
        val isAck = flags and FLAG_ACK != 0
        val isFin = flags and FLAG_FIN != 0
        val isRst = flags and FLAG_RST != 0

        if (isRst) {
            sessions.remove(key)?.let { closeQuietly(it) }
            return true
        }

        if (isSyn && !isAck) {
            if (sessions.containsKey(key)) return true
            val session = Session(Socket())
            session.clientNextSeq = (seq + 1) and 0xFFFFFFFFL
            sessions[key] = session
            scope.launch { connectAndRelay(key, session, srcIp, srcPort, dstIp, dstPort) }
            return true
        }

        val session = sessions[key] ?: return false

        if (isFin) {
            session.clientNextSeq = (session.clientNextSeq + 1) and 0xFFFFFFFFL
            runCatching { session.socket.shutdownOutput() }
            sendControl(session, srcIp, srcPort, dstIp, dstPort, FLAG_ACK)
            return true
        }

        if (payloadLen > 0 && session.state == State.ESTABLISHED) {
            if (seq == session.clientNextSeq) {
                val wrote = runCatching {
                    session.socket.getOutputStream().write(packet, payloadOffset, payloadLen)
                }
                if (wrote.isFailure) {
                    sessions.remove(key)
                    closeQuietly(session)
                    return true
                }
                session.clientNextSeq = (session.clientNextSeq + payloadLen) and 0xFFFFFFFFL
            }
            sendControl(session, srcIp, srcPort, dstIp, dstPort, FLAG_ACK)
        }

        return true
    }

    fun closeAll() {
        sessions.values.forEach { closeQuietly(it) }
        sessions.clear()
    }

    private suspend fun connectAndRelay(
        key: String,
        session: Session,
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int
    ) {
        session.ourSeq = Random.nextLong(0, 0x100000000L)

        val connected = runCatching {
            protect(session.socket)
            session.socket.connect(InetSocketAddress(InetAddress.getByAddress(dstIp), dstPort), 10_000)
        }
        if (connected.isFailure) {
            Log.e(TAG, "connect failed for $key: ${connected.exceptionOrNull()}")
            sessions.remove(key)
            sendControl(session, srcIp, srcPort, dstIp, dstPort, FLAG_RST or FLAG_ACK)
            return
        }

        Log.d(TAG, "connected $key")
        session.state = State.ESTABLISHED
        sendControl(session, srcIp, srcPort, dstIp, dstPort, FLAG_SYN or FLAG_ACK, consumeSeq = true)

        val buf = ByteArray(16384)
        try {
            val input = session.socket.getInputStream()
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                writeSegment(session, srcIp, srcPort, dstIp, dstPort, FLAG_ACK, buf, n)
                session.ourSeq = (session.ourSeq + n) and 0xFFFFFFFFL
            }
        } catch (_: Exception) {
        } finally {
            sendControl(session, srcIp, srcPort, dstIp, dstPort, FLAG_FIN or FLAG_ACK, consumeSeq = true)
            sessions.remove(key)
            closeQuietly(session)
        }
    }

    private fun sendControl(
        session: Session,
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int,
        flags: Int,
        consumeSeq: Boolean = false
    ) {
        writeSegment(session, srcIp, srcPort, dstIp, dstPort, flags, ByteArray(0), 0)
        if (consumeSeq) session.ourSeq = (session.ourSeq + 1) and 0xFFFFFFFFL
    }

    private fun writeSegment(
        session: Session,
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int,
        flags: Int,
        payload: ByteArray,
        payloadLen: Int
    ) {
        val segment = PacketUtils.buildTcpPacket(
            srcIp = dstIp, srcPort = dstPort,
            dstIp = srcIp, dstPort = srcPort,
            seq = session.ourSeq, ack = session.clientNextSeq, flags = flags,
            payload = payload, payloadOffset = 0, payloadLen = payloadLen
        )
        synchronized(outStream) { outStream.write(segment) }
    }

    private fun closeQuietly(session: Session) {
        runCatching { session.socket.close() }
    }
}
