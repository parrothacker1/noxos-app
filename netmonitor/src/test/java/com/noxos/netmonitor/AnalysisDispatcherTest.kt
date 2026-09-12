package com.noxos.netmonitor

import com.noxos.audit.AclEntry
import com.noxos.audit.AclKind
import com.noxos.audit.AclPriority
import com.noxos.audit.AclState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket

class AnalysisDispatcherTest {

    private fun entry(subject: String = "203.0.113.7") = AclEntry(
        kind = AclKind.NETWORK,
        subject = subject,
        state = AclState.FLAGGED,
        priority = AclPriority.HIGH,
        reason = "new destination, pending analysis",
        updatedAtEpochMillis = 1_700_000_000_000L
    )

    private class FakeServer(status: Int, body: String) {
        private val socket = ServerSocket(0)

        @Volatile
        var receivedAuthHeader: String? = null
            private set

        private val thread = Thread {
            try {
                val client = socket.accept()
                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                var line: String?
                while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                    if (line!!.startsWith("Authorization:", ignoreCase = true)) {
                        receivedAuthHeader = line!!.substringAfter(":").trim()
                    }
                }
                val bytes = body.toByteArray(Charsets.UTF_8)
                val out = client.getOutputStream()
                out.write("HTTP/1.1 $status OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
                out.write(bytes)
                out.flush()
                client.close()
            } catch (_: Exception) {
            }
        }

        fun start(): FakeServer {
            thread.start()
            return this
        }

        val port: Int get() = socket.localPort

        fun stop() {
            socket.close()
        }
    }

    @Test
    fun `parses an allow verdict from a real HTTP response`() {
        val server = FakeServer(200, """{"verdict":"allow","confidence":0.9}""").start()
        try {
            val verdict = AnalysisDispatcher.requestVerdict("http://127.0.0.1:${server.port}", "", entry())
            assertEquals("allow", verdict)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `sends the api key as a bearer token when configured`() {
        val server = FakeServer(200, """{"verdict":"block"}""").start()
        try {
            val verdict = AnalysisDispatcher.requestVerdict("http://127.0.0.1:${server.port}", "secret-token", entry())
            assertEquals("block", verdict)
            assertEquals("Bearer secret-token", server.receivedAuthHeader)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `returns null on a non-200 response instead of throwing`() {
        val server = FakeServer(500, """{"error":"boom"}""").start()
        try {
            assertNull(AnalysisDispatcher.requestVerdict("http://127.0.0.1:${server.port}", "", entry()))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `returns null when the endpoint is unreachable`() {
        assertNull(AnalysisDispatcher.requestVerdict("http://127.0.0.1:1", "", entry()))
    }
}
