package com.noxos.netmonitor

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ConnectionStatsTest {

    @Before
    @After
    fun clearSharedState() {
        NetMonitorService.connectionStats.clear()
        NetMonitorService.pendingPacketSamples.clear()
    }

    @Test
    fun `recordOutboundPacket and recordInboundPacket are no-ops for a destination nobody is tracking`() {
        NetMonitorService.recordOutboundPacket("1.2.3.4", 100)
        NetMonitorService.recordInboundPacket("1.2.3.4", 50)

        assertNull(NetMonitorService.connectionStats["1.2.3.4"])
    }

    @Test
    fun `outbound and inbound packets accumulate independently once a destination is tracked`() {
        NetMonitorService.connectionStats["1.2.3.4"] = ConnectionStats()

        NetMonitorService.recordOutboundPacket("1.2.3.4", 100)
        NetMonitorService.recordOutboundPacket("1.2.3.4", 200)
        NetMonitorService.recordInboundPacket("1.2.3.4", 500)

        val stats = NetMonitorService.connectionStats.getValue("1.2.3.4")
        assertEquals(2L, stats.srcPacketCount.get())
        assertEquals(300L, stats.srcByteCount.get())
        assertEquals(1L, stats.dstPacketCount.get())
        assertEquals(500L, stats.dstByteCount.get())
    }

    @Test
    fun `recordHandshakeLatency sets the value only for a tracked destination`() {
        NetMonitorService.connectionStats["1.2.3.4"] = ConnectionStats()

        NetMonitorService.recordHandshakeLatency("1.2.3.4", 42L)
        NetMonitorService.recordHandshakeLatency("9.9.9.9", 99L)

        assertEquals(42L, NetMonitorService.connectionStats.getValue("1.2.3.4").handshakeLatencyMillis)
        assertNull(NetMonitorService.connectionStats["9.9.9.9"])
    }

    @Test
    fun `appendPendingSample also counts the sample as an inbound packet`() {
        NetMonitorService.connectionStats["1.2.3.4"] = ConnectionStats()
        NetMonitorService.pendingPacketSamples["1.2.3.4"] = java.util.concurrent.CopyOnWriteArrayList()

        NetMonitorService.appendPendingSample("1.2.3.4", byteArrayOf(1, 2, 3, 4, 5))

        val stats = NetMonitorService.connectionStats.getValue("1.2.3.4")
        assertEquals(1L, stats.dstPacketCount.get())
        assertEquals(5L, stats.dstByteCount.get())
    }
}
