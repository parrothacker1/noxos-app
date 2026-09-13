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

        @Volatile
        var receivedBody: String? = null
            private set

        private val thread = Thread {
            try {
                val client = socket.accept()
                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                var line: String?
                var contentLength = 0
                while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                    if (line!!.startsWith("Authorization:", ignoreCase = true)) {
                        receivedAuthHeader = line!!.substringAfter(":").trim()
                    }
                    if (line!!.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line!!.substringAfter(":").trim().toInt()
                    }
                }
                if (contentLength > 0) {
                    val buf = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = reader.read(buf, read, contentLength - read)
                        if (n == -1) break
                        read += n
                    }
                    receivedBody = String(buf, 0, read)
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
        val server = FakeServer(200, """{"verdict":"allow","safety_score":0.9}""").start()
        try {
            val response = AnalysisDispatcher.requestVerdict("http://127.0.0.1:${server.port}", "", entry())
            assertEquals("allow", response?.verdict)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `sends the api key as a bearer token when configured`() {
        val server = FakeServer(200, """{"verdict":"block"}""").start()
        try {
            val response = AnalysisDispatcher.requestVerdict("http://127.0.0.1:${server.port}", "secret-token", entry())
            assertEquals("block", response?.verdict)
            assertEquals("Bearer secret-token", server.receivedAuthHeader)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `parses reasoning and safety score alongside the verdict`() {
        val server = FakeServer(
            200,
            """{"verdict":"block","reasoning":"Known C2 beacon pattern","safety_score":0.12}"""
        ).start()
        try {
            val response = AnalysisDispatcher.requestVerdict("http://127.0.0.1:${server.port}", "", entry())
            assertEquals("block", response?.verdict)
            assertEquals("Known C2 beacon pattern", response?.reasoning)
            assertEquals(0.12f, response?.safetyScore)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `missing reasoning and safety score parse as null, not a failure`() {
        val server = FakeServer(200, """{"verdict":"allow"}""").start()
        try {
            val response = AnalysisDispatcher.requestVerdict("http://127.0.0.1:${server.port}", "", entry())
            assertEquals("allow", response?.verdict)
            assertNull(response?.reasoning)
            assertNull(response?.safetyScore)
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

    @Test
    fun `sends the model's real lowercase proto vocabulary, not the app's own TCP-UDP-OTHER labels`() {
        val server = FakeServer(200, """{"verdict":"allow"}""").start()
        try {
            val tcpEntry = entry().copy(protocol = "TCP", destPort = 443)
            AnalysisDispatcher.requestVerdict("http://127.0.0.1:${server.port}", "", tcpEntry)

            val body = server.receivedBody
            assertEquals(true, body?.contains("\"proto\":\"tcp\""))
            assertEquals(true, body?.contains("\"dst_port\":443"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `an OTHER protocol (e_g_ ICMP) omits proto entirely rather than sending an invalid value`() {
        val server = FakeServer(200, """{"verdict":"allow"}""").start()
        try {
            val otherEntry = entry().copy(protocol = "OTHER")
            AnalysisDispatcher.requestVerdict("http://127.0.0.1:${server.port}", "", otherEntry)

            assertEquals(false, server.receivedBody?.contains("\"proto\""))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `no protocol captured yet omits proto and dst_port rather than sending nulls`() {
        val server = FakeServer(200, """{"verdict":"allow"}""").start()
        try {
            AnalysisDispatcher.requestVerdict("http://127.0.0.1:${server.port}", "", entry())

            val body = server.receivedBody
            assertEquals(false, body?.contains("\"proto\""))
            assertEquals(false, body?.contains("\"dst_port\""))
        } finally {
            server.stop()
        }
    }
}
