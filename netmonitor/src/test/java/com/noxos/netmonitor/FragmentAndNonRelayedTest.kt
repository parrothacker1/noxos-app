package com.noxos.netmonitor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FragmentAndNonRelayedTest {

    private fun packetWithFragmentField(field: Int): ByteArray {
        val packet = PacketUtils.buildUdpPacket(
            srcIp = byteArrayOf(10, 0, 0, 1), srcPort = 4000,
            dstIp = byteArrayOf(8, 8, 8, 8), dstPort = 53,
            payload = ByteArray(4), payloadOffset = 0, payloadLen = 4
        )
        PacketUtils.writeUShort(packet, 6, field)
        return packet
    }

    @Test
    fun `an unfragmented packet and a first fragment are not non-first fragments`() {
        val plain = packetWithFragmentField(0x0000)
        val dontFragment = packetWithFragmentField(0x4000)
        val firstFragment = packetWithFragmentField(0x2000)
        assertFalse(PacketUtils.isNonFirstFragment(plain, plain.size))
        assertFalse(PacketUtils.isNonFirstFragment(dontFragment, dontFragment.size))
        assertFalse(PacketUtils.isNonFirstFragment(firstFragment, firstFragment.size))
    }

    @Test
    fun `any packet with a fragment offset is a non-first fragment`() {
        val middle = packetWithFragmentField(0x2000 or 185)
        val last = packetWithFragmentField(370)
        assertTrue(PacketUtils.isNonFirstFragment(middle, middle.size))
        assertTrue(PacketUtils.isNonFirstFragment(last, last.size))
    }

    @Test
    fun `a packet too short to carry the fragment field is not treated as a fragment`() {
        assertFalse(PacketUtils.isNonFirstFragment(ByteArray(4), 4))
    }

    @Test
    fun `non-relayed traffic logs one row per protocol and destination until cleared`() {
        val log = NonRelayedLog()
        assertTrue(log.firstTime(1, "1.2.3.4"))
        assertFalse(log.firstTime(1, "1.2.3.4"))
        assertFalse(log.firstTime(1, "1.2.3.4"))
        assertTrue(log.firstTime(1, "5.6.7.8"))
        assertTrue(log.firstTime(47, "1.2.3.4"))
        log.clear()
        assertTrue(log.firstTime(1, "1.2.3.4"))
    }
}
