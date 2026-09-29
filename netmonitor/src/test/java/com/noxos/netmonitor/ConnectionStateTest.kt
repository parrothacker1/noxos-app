package com.noxos.netmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionStateTest {

    private fun state(protocol: String?, tcp: TcpLifecycle? = null, inbound: Long = 0, ageMillis: Long = 0) =
        deriveConnectionState(protocol, tcp, inbound, ageMillis)

    @Test
    fun `tcp maps each lifecycle event to its Argus-style state`() {
        assertEquals("CON", state("TCP", TcpLifecycle.ESTABLISHED, ageMillis = 1_000))
        assertEquals("FIN", state("TCP", TcpLifecycle.FIN))
        assertEquals("RST", state("TCP", TcpLifecycle.RST))
    }

    @Test
    fun `tcp SYN with no completed connect is only REQ after the handshake wait`() {
        assertNull(state("TCP", TcpLifecycle.SYN_SEEN, ageMillis = TCP_HANDSHAKE_WAIT_MILLIS - 1))
        assertEquals("REQ", state("TCP", TcpLifecycle.SYN_SEEN, ageMillis = TCP_HANDSHAKE_WAIT_MILLIS))
    }

    @Test
    fun `tcp with no lifecycle event at all is undecided`() {
        assertNull(state("TCP", null, ageMillis = 10 * UDP_NO_REPLY_WAIT_MILLIS))
    }

    @Test
    fun `udp with any reply is CON regardless of age`() {
        assertEquals("CON", state("UDP", inbound = 1, ageMillis = 0))
        assertEquals("CON", state("udp", inbound = 3, ageMillis = 5 * UDP_NO_REPLY_WAIT_MILLIS))
    }

    @Test
    fun `udp with no reply stays unscored until the wait passes, then INT`() {
        assertNull(state("UDP", inbound = 0, ageMillis = 0))
        assertNull(state("UDP", inbound = 0, ageMillis = UDP_NO_REPLY_WAIT_MILLIS - 1))
        assertEquals("INT", state("UDP", inbound = 0, ageMillis = UDP_NO_REPLY_WAIT_MILLIS))
    }

    @Test
    fun `any other protocol, or none, is other`() {
        assertEquals("other", state("OTHER"))
        assertEquals("other", state(null))
    }
}
