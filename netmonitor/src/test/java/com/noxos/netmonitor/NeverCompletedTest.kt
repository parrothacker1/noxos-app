package com.noxos.netmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeverCompletedTest {

    private fun never(
        protocol: String? = "TCP",
        tcp: TcpLifecycle? = TcpLifecycle.SYN_SEEN,
        handshake: Long? = null,
        inbound: Long = 0,
        ageMillis: Long = TCP_HANDSHAKE_WAIT_MILLIS
    ) = connectionNeverCompleted(protocol, tcp, handshake, inbound, ageMillis)

    @Test
    fun `a tcp SYN with no handshake and no data after the wait never completed`() {
        assertTrue(never())
    }

    @Test
    fun `a tcp SYN is still pending before the handshake wait`() {
        assertFalse(never(ageMillis = TCP_HANDSHAKE_WAIT_MILLIS - 1))
    }

    @Test
    fun `a reset with no handshake and no data never completed`() {
        assertTrue(never(tcp = TcpLifecycle.RST, ageMillis = 50))
    }

    @Test
    fun `a connection that completed its handshake is scored even if it was later reset`() {
        assertFalse(never(tcp = TcpLifecycle.RST, handshake = 40))
    }

    @Test
    fun `a connection that received data is scored`() {
        assertFalse(never(tcp = TcpLifecycle.RST, inbound = 1))
    }

    @Test
    fun `established and finished connections are scored`() {
        assertFalse(never(tcp = TcpLifecycle.ESTABLISHED, handshake = 12))
        assertFalse(never(tcp = TcpLifecycle.FIN, handshake = 12, inbound = 3))
    }

    @Test
    fun `udp and unknown protocols are never treated as refused connections`() {
        assertFalse(never(protocol = "UDP", tcp = null))
        assertFalse(never(protocol = null))
    }

    @Test
    fun `the autoencoder state mapping is unchanged for the same inputs`() {
        assertEquals("REQ", deriveConnectionState("TCP", TcpLifecycle.SYN_SEEN, 0, TCP_HANDSHAKE_WAIT_MILLIS))
        assertEquals("RST", deriveConnectionState("TCP", TcpLifecycle.RST, 0, 0))
    }
}
