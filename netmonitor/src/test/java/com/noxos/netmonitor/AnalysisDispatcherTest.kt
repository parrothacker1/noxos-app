package com.noxos.netmonitor

import com.noxos.audit.AclEntry
import com.noxos.audit.AclKind
import com.noxos.audit.AclPriority
import com.noxos.audit.AclState
import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetSocketAddress

class AnalysisDispatcherTest {

    private fun entry(subject: String = "203.0.113.7") = AclEntry(
        kind = AclKind.NETWORK,
        subject = subject,
        state = AclState.FLAGGED,
        priority = AclPriority.HIGH,
        reason = "new destination, pending analysis",
        updatedAtEpochMillis = 1_700_000_000_000L
    )

    private fun fakeServer(status: Int, body: String, headerCheck: ((String?) -> Unit)? = null): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/analyze/network") { exchange ->
            headerCheck?.invoke(exchange.requestHeaders.getFirst("Authorization"))
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server
    }

    @Test
    fun `parses an allow verdict from a real HTTP response`() {
        val server = fakeServer(200, """{"verdict":"allow","confidence":0.9}""")
        try {
            val endpoint = "http://127.0.0.1:${server.address.port}"
            val verdict = AnalysisDispatcher.requestVerdict(endpoint, "", entry())
            assertEquals("allow", verdict)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `sends the api key as a bearer token when configured`() {
        var receivedAuth: String? = null
        val server = fakeServer(200, """{"verdict":"block"}""") { receivedAuth = it }
        try {
            val endpoint = "http://127.0.0.1:${server.address.port}"
            val verdict = AnalysisDispatcher.requestVerdict(endpoint, "secret-token", entry())
            assertEquals("block", verdict)
            assertEquals("Bearer secret-token", receivedAuth)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `returns null on a non-200 response instead of throwing`() {
        val server = fakeServer(500, """{"error":"boom"}""")
        try {
            val endpoint = "http://127.0.0.1:${server.address.port}"
            assertNull(AnalysisDispatcher.requestVerdict(endpoint, "", entry()))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `returns null when the endpoint is unreachable`() {
        assertNull(AnalysisDispatcher.requestVerdict("http://127.0.0.1:1", "", entry()))
    }
}
