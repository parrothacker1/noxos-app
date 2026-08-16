package com.noxos.netmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PacketUtilsTest {

    @Test
    fun `buildUdpPacket round-trips through extractFlowDescriptor`() {
        val srcIp = byteArrayOf(8, 8, 8, 8)
        val dstIp = byteArrayOf(10, 0, 0, 2)
        val payload = "hello".toByteArray()

        val packet = PacketUtils.buildUdpPacket(
            srcIp = srcIp, srcPort = 53,
            dstIp = dstIp, dstPort = 5000,
            payload = payload, payloadOffset = 0, payloadLen = payload.size
        )

        assertEquals(28 + payload.size, packet.size)
        assertEquals(
            "UDP 8.8.8.8:53 → 10.0.0.2:5000",
            PacketUtils.extractFlowDescriptor(packet, packet.size)
        )
    }

    @Test
    fun `ip header checksum is internally consistent`() {
        val packet = PacketUtils.buildUdpPacket(
            srcIp = byteArrayOf(1, 2, 3, 4), srcPort = 1111,
            dstIp = byteArrayOf(5, 6, 7, 8), dstPort = 2222,
            payload = ByteArray(0), payloadOffset = 0, payloadLen = 0
        )

        var sum = 0
        var i = 0
        while (i < 20) {
            sum += PacketUtils.readUShort(packet, i)
            i += 2
        }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        assertEquals(0xFFFF, sum)
    }

    @Test
    fun `extractFlowDescriptor rejects non-ipv4 length`() {
        assertNull(PacketUtils.extractFlowDescriptor(ByteArray(10), 10))
    }

    @Test
    fun `writeUShort readUShort round trip`() {
        val buf = ByteArray(4)
        PacketUtils.writeUShort(buf, 0, 0xBEEF)
        assertEquals(0xBEEF, PacketUtils.readUShort(buf, 0))
    }
}
