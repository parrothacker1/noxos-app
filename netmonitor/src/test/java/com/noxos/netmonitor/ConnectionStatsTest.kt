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
    fun `every inbound chunk is counted, not just the first one or two`() {
        NetMonitorService.connectionStats["1.2.3.4"] = ConnectionStats()

        repeat(6) { NetMonitorService.recordInboundPacket("1.2.3.4", 100) }

        val stats = NetMonitorService.connectionStats.getValue("1.2.3.4")
        assertEquals(6L, stats.dstPacketCount.get())
        assertEquals(600L, stats.dstByteCount.get())
    }

    @Test
    fun `recordTcpLifecycle keeps the latest event and ignores an untracked destination`() {
        NetMonitorService.connectionStats["1.2.3.4"] = ConnectionStats()

        NetMonitorService.recordTcpLifecycle("1.2.3.4", TcpLifecycle.SYN_SEEN)
        NetMonitorService.recordTcpLifecycle("1.2.3.4", TcpLifecycle.ESTABLISHED)
        NetMonitorService.recordTcpLifecycle("1.2.3.4", TcpLifecycle.FIN)
        NetMonitorService.recordTcpLifecycle("9.9.9.9", TcpLifecycle.RST)

        assertEquals(TcpLifecycle.FIN, NetMonitorService.connectionStats.getValue("1.2.3.4").tcpLifecycle)
        assertNull(NetMonitorService.connectionStats["9.9.9.9"])
    }
}
