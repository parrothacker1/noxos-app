package com.noxos.netmonitor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class TcpRelayManagerTest {

    private class CapturingOutputStream : OutputStream() {
        val packets = LinkedBlockingQueue<ByteArray>()
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {
            packets.put(b.copyOfRange(off, off + len))
        }
    }

    private fun CapturingOutputStream.takePacket(): ByteArray =
        packets.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("timed out waiting for relayed packet")

    private fun tcpPayload(packet: ByteArray): ByteArray {
        val ihl = (packet[0].toInt() and 0x0F) * 4
        val dataOffset = ((packet[ihl + 12].toInt() and 0xF0) shr 4) * 4
        val start = ihl + dataOffset
        return packet.copyOfRange(start, packet.size)
    }

    private fun tcpFlags(packet: ByteArray): Int {
        val ihl = (packet[0].toInt() and 0x0F) * 4
        return packet[ihl + 13].toInt() and 0xFF
    }

    @Test
    fun `relays a full tcp handshake, data exchange, and close`() {
        val server = ServerSocket(0)
        val serverPort = server.localPort
        val serverReceived = LinkedBlockingQueue<ByteArray>()

        val serverThread = Thread {
            val client = server.accept()
            val buf = ByteArray(1024)
            val n = client.getInputStream().read(buf)
            serverReceived.put(buf.copyOfRange(0, n))
            client.getOutputStream().write("world".toByteArray())
            client.close()
        }
        serverThread.start()

        val out = CapturingOutputStream()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val relay = TcpRelayManager(scope, protect = { _: Socket -> true }, outStream = out)

        val clientIp = byteArrayOf(10, 0, 0, 2)
        val clientPort = 40000
        val serverIp = byteArrayOf(127, 0, 0, 1)
        val clientIsn = 1000L

        val syn = PacketUtils.buildTcpPacket(
            srcIp = clientIp, srcPort = clientPort,
            dstIp = serverIp, dstPort = serverPort,
            seq = clientIsn, ack = 0, flags = TcpRelayManager.FLAG_SYN,
            payload = ByteArray(0), payloadOffset = 0, payloadLen = 0
        )
        assertTrue(relay.handle(syn, syn.size))

        val synAck = out.takePacket()
        assertEquals(TcpRelayManager.FLAG_SYN or TcpRelayManager.FLAG_ACK, tcpFlags(synAck))
        val ihl = (synAck[0].toInt() and 0x0F) * 4
        val serverIsn = PacketUtils.readUInt(synAck, ihl + 4)

        val dataPacket = PacketUtils.buildTcpPacket(
            srcIp = clientIp, srcPort = clientPort,
            dstIp = serverIp, dstPort = serverPort,
            seq = clientIsn + 1, ack = serverIsn + 1, flags = TcpRelayManager.FLAG_ACK,
            payload = "hello".toByteArray(), payloadOffset = 0, payloadLen = 5
        )
        assertTrue(relay.handle(dataPacket, dataPacket.size))

        // The mock server closes its socket right after writing "world", so its FIN can race
        // into this same window alongside our ack-for-data and the relayed reply - identify
        // each of the three by what it actually is, not by arrival order or a fixed count.
        var ackForData: ByteArray? = null
        var replyPacket: ByteArray? = null
        var finPacket: ByteArray? = null
        var attempts = 0
        while ((ackForData == null || replyPacket == null || finPacket == null) && attempts < 6) {
            val packet = out.takePacket()
            attempts++
            when {
                tcpFlags(packet) and TcpRelayManager.FLAG_FIN != 0 -> finPacket = packet
                tcpPayload(packet).contentEquals("world".toByteArray()) -> replyPacket = packet
                tcpFlags(packet) == TcpRelayManager.FLAG_ACK && tcpPayload(packet).isEmpty() -> ackForData = packet
            }
        }

        assertEquals(TcpRelayManager.FLAG_ACK, tcpFlags(ackForData ?: throw AssertionError("never saw the ack for our data")))
        assertTrue(tcpPayload(ackForData).isEmpty())

        val receivedByServer = serverReceived.poll(5, TimeUnit.SECONDS)
            ?: throw AssertionError("server never received relayed bytes")
        assertArrayEquals("hello".toByteArray(), receivedByServer)

        assertArrayEquals("world".toByteArray(), tcpPayload(replyPacket ?: throw AssertionError("never saw the relayed reply")))

        assertEquals(
            TcpRelayManager.FLAG_FIN or TcpRelayManager.FLAG_ACK,
            tcpFlags(finPacket ?: throw AssertionError("never saw the relayed fin"))
        )

        server.close()
        serverThread.join(2000)
    }
}
