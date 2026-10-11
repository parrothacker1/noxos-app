package com.noxos.netmonitor

import com.noxos.audit.AclEntry
import com.noxos.audit.AclKind
import com.noxos.audit.AclPriority
import com.noxos.audit.AclRepository
import com.noxos.audit.AclState
import com.noxos.netmonitor.AnalysisDispatcher.NetworkVerdictResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket

class AnalysisDispatcherTest {

    private fun entry(
        protocol: String? = "TCP",
        destPort: Int? = 443,
        srcPackets: Long? = 10,
        srcBytes: Long? = 1200,
        dstPackets: Long? = 12,
        dstBytes: Long? = 9000,
        durationMillis: Long? = 3000,
        handshake: Long? = 25
    ) = AclEntry(
        kind = AclKind.NETWORK,
        subject = "203.0.113.7",
        state = AclState.BLOCKED,
        priority = AclPriority.HIGH,
        reason = "autoencoder: flagged (reconstruction error 0.5), escalating to student",
        updatedAtEpochMillis = 1_700_000_000_000L,
        destPort = destPort,
        protocol = protocol,
        srcPacketCount = srcPackets,
        srcByteCount = srcBytes,
        dstPacketCount = dstPackets,
        dstByteCount = dstBytes,
        durationMillis = durationMillis,
        handshakeLatencyMillis = handshake
    )

    private class FakeServer(private val status: Int, private val body: String, private val stallMillis: Long = 0) {
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
                if (stallMillis > 0) Thread.sleep(stallMillis)
                val bytes = body.toByteArray(Charsets.UTF_8)
                val out = client.getOutputStream()
                out.write("HTTP/1.1 $status X\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
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

    private fun <T> withServer(status: Int, body: String, stallMillis: Long = 0, block: (FakeServer) -> T): T {
        val server = FakeServer(status, body, stallMillis).start()
        try {
            return block(server)
        } finally {
            server.stop()
        }
    }

    private fun url(server: FakeServer) = "http://127.0.0.1:${server.port}"

    @Test
    fun `the request carries the ten flow features with proto as a lowercase string and plain numbers`() {
        withServer(200, """{"verdict":"allow"}""") { server ->
            AnalysisDispatcher.requestVerdict(url(server), "", entry())

            val body = server.receivedBody!!
            assertTrue(body, body.contains("\"proto\":\"tcp\""))
            assertTrue(body, body.contains("\"dst_port\":443"))
            assertTrue(body, body.contains("\"src_byte_count\":1200"))
            assertTrue(body, body.contains("\"src_packet_count\":10"))
            assertTrue(body, body.contains("\"dst_byte_count\":9000"))
            assertTrue(body, body.contains("\"dst_packet_count\":12"))
            assertTrue(body, body.contains("\"duration_millis\":3000"))
            assertTrue(body, body.contains("\"handshake_latency_millis\":25"))
            assertTrue(body, body.contains("\"smean\":120"))
            assertTrue(body, body.contains("\"dmean\":750"))
            assertFalse(body, body.contains(".0,") || body.contains(".0}"))
        }
    }

    @Test
    fun `a one way udp burst sends zero downstream packets and no dmean`() {
        withServer(200, """{"verdict":"block"}""") { server ->
            AnalysisDispatcher.requestVerdict(
                url(server), "",
                entry(protocol = "UDP", destPort = 44790, srcPackets = 1400, srcBytes = 2_000_000, dstPackets = 0, dstBytes = 0, handshake = null)
            )

            val body = server.receivedBody!!
            assertTrue(body, body.contains("\"proto\":\"udp\""))
            assertTrue(body, body.contains("\"dst_packet_count\":0"))
            assertTrue(body, body.contains("\"src_byte_count\":2000000"))
            assertFalse(body, body.contains("\"dmean\""))
            assertFalse(body, body.contains("\"handshake_latency_millis\""))
            assertTrue(body, body.contains("\"smean\""))
        }
    }

    @Test
    fun `the api key goes only in the bearer header, never in the body`() {
        withServer(200, """{"verdict":"block"}""") { server ->
            AnalysisDispatcher.requestVerdict(url(server), "secret-token", entry())

            assertEquals("Bearer secret-token", server.receivedAuthHeader)
            assertFalse(server.receivedBody!!.contains("secret-token"))
        }
    }

    @Test
    fun `an entry without the required live stats is not sent at all`() {
        withServer(200, """{"verdict":"allow"}""") { server ->
            val result = AnalysisDispatcher.requestVerdict(url(server), "", entry(srcBytes = null, durationMillis = null))

            assertTrue(result is NetworkVerdictResult.Rejected)
            assertEquals(0, (result as NetworkVerdictResult.Rejected).status)
            assertEquals(listOf("src_byte_count", "duration_millis"), result.missing)
            assertNull(server.receivedBody)
        }
    }

    @Test
    fun `a protocol other than tcp or udp is not sent because proto is required`() {
        withServer(200, """{"verdict":"allow"}""") { server ->
            val result = AnalysisDispatcher.requestVerdict(url(server), "", entry(protocol = "OTHER"))

            assertEquals(listOf("proto"), (result as NetworkVerdictResult.Rejected).missing)
            assertNull(server.receivedBody)
        }
    }

    @Test
    fun `allow block and uncertain are parsed with reasoning and score`() {
        listOf("allow", "block", "uncertain").forEach { verdict ->
            withServer(200, """{"verdict":"$verdict","safety_score":0.5,"reasoning":"because"}""") { server ->
                val result = AnalysisDispatcher.requestVerdict(url(server), "", entry())

                assertEquals(NetworkVerdictResult.Verdict(verdict, "because", 0.5f), result)
            }
        }
    }

    @Test
    fun `a null reasoning is fine`() {
        withServer(200, """{"verdict":"allow","safety_score":0.9,"reasoning":null}""") { server ->
            val result = AnalysisDispatcher.requestVerdict(url(server), "", entry()) as NetworkVerdictResult.Verdict

            assertNull(result.reasoning)
        }
    }

    @Test
    fun `a 200 without a usable verdict is unavailable, never an allow`() {
        withServer(200, """{"detail":"something odd"}""") { server ->
            assertTrue(AnalysisDispatcher.requestVerdict(url(server), "", entry()) is NetworkVerdictResult.Unavailable)
        }
        withServer(200, """{"verdict":"maybe"}""") { server ->
            assertTrue(AnalysisDispatcher.requestVerdict(url(server), "", entry()) is NetworkVerdictResult.Unavailable)
        }
    }

    @Test
    fun `a 422 is rejected with the server's missing and invalid names and no verdict`() {
        withServer(422, """{"detail":"no verdict: missing required features","missing":["src_byte_count","dst_packet_count"],"invalid":["proto"]}""") { server ->
            val result = AnalysisDispatcher.requestVerdict(url(server), "", entry())

            assertEquals(NetworkVerdictResult.Rejected(422, listOf("src_byte_count", "dst_packet_count"), listOf("proto")), result)
        }
    }

    @Test
    fun `400 and 401 are rejected, 503 and 500 are unavailable`() {
        withServer(400, """{"detail":"invalid JSON body"}""") { server ->
            assertEquals(400, (AnalysisDispatcher.requestVerdict(url(server), "", entry()) as NetworkVerdictResult.Rejected).status)
        }
        withServer(401, """{"detail":"invalid or missing API key"}""") { server ->
            assertEquals(401, (AnalysisDispatcher.requestVerdict(url(server), "", entry()) as NetworkVerdictResult.Rejected).status)
        }
        withServer(503, """{"detail":"model not loaded"}""") { server ->
            assertTrue(AnalysisDispatcher.requestVerdict(url(server), "", entry()) is NetworkVerdictResult.Unavailable)
        }
        withServer(500, """{"error":"boom"}""") { server ->
            assertTrue(AnalysisDispatcher.requestVerdict(url(server), "", entry()) is NetworkVerdictResult.Unavailable)
        }
    }

    @Test
    fun `a server that never answers times out as unavailable`() {
        withServer(200, """{"verdict":"allow"}""", stallMillis = 3000) { server ->
            val started = System.currentTimeMillis()
            val result = AnalysisDispatcher.requestVerdict(url(server), "", entry(), timeoutMillis = 300)

            assertTrue(result is NetworkVerdictResult.Unavailable)
            assertTrue(System.currentTimeMillis() - started < 2500)
        }
    }

    @Test
    fun `no connection is unavailable`() {
        assertTrue(AnalysisDispatcher.requestVerdict("http://127.0.0.1:1", "", entry()) is NetworkVerdictResult.Unavailable)
    }

    private class RecordingAcl : AclRepository {
        val calls = mutableListOf<String>()

        override suspend fun allow(kind: AclKind, subject: String, reason: String, safetyScore: Float?, sessionOnly: Boolean) {
            calls += "allow|$subject|$reason|$sessionOnly"
        }

        override suspend fun block(kind: AclKind, subject: String, reason: String, safetyScore: Float?, sessionOnly: Boolean) {
            calls += "block|$subject|$reason|$sessionOnly"
        }

        override suspend fun flagIfUnknown(kind: AclKind, subject: String, priority: AclPriority, reason: String, destPort: Int?, protocol: String?) {}
        override suspend fun remove(kind: AclKind, subject: String) {}
        override fun observeAll(): Flow<List<AclEntry>> = flowOf(emptyList())
        override fun observeKind(kind: AclKind): Flow<List<AclEntry>> = flowOf(emptyList())
        override suspend fun nextAnalysisBatch(limit: Int): List<AclEntry> = emptyList()
        override suspend fun nextAutoencoderBatch(limit: Int): List<AclEntry> = emptyList()
        override suspend fun markAutoencoderFlagged(kind: AclKind, subject: String, reason: String) {}
        override suspend fun recordConnectionStats(
            kind: AclKind, subject: String, srcPacketCount: Long, srcByteCount: Long,
            dstPacketCount: Long, dstByteCount: Long, durationMillis: Long, handshakeLatencyMillis: Long?
        ) {}
        override suspend fun seedDefaults() {}
        override suspend fun clearSessionVerdicts(kind: AclKind) {}
        override suspend fun resetUnresolved(kind: AclKind) {}
        override suspend fun resetAutoencoderVerdicts(kind: AclKind) {}
    }

    private fun apply(result: NetworkVerdictResult): List<String> {
        val acl = RecordingAcl()
        runBlocking { AnalysisDispatcher.applyResult(acl, entry(), result) }
        return acl.calls
    }

    @Test
    fun `only a server allow allows, and only for the session`() {
        val calls = apply(NetworkVerdictResult.Verdict("allow", "fine", 0.9f))

        assertEquals(listOf("allow|203.0.113.7|ai: fine|true"), calls)
    }

    @Test
    fun `a server block blocks`() {
        val calls = apply(NetworkVerdictResult.Verdict("block", "attack-like", 0.1f))

        assertEquals(listOf("block|203.0.113.7|ai: attack-like|true"), calls)
    }

    @Test
    fun `uncertain stays blocked and waits for the user, it is never an allow`() {
        val calls = apply(NetworkVerdictResult.Verdict("uncertain", null, 0.5f))

        assertEquals(1, calls.size)
        assertTrue(calls.single().startsWith("block|203.0.113.7|ai: uncertain"))
    }

    @Test
    fun `a rejected request stays blocked and waits for the user with the reason recorded`() {
        val calls = apply(NetworkVerdictResult.Rejected(422, listOf("src_byte_count"), emptyList()))

        assertEquals(1, calls.size)
        assertTrue(calls.single().startsWith("block|203.0.113.7|ai: no verdict (HTTP 422, missing [src_byte_count]"))
    }

    @Test
    fun `an unavailable server changes nothing so the destination stays blocked and is asked again later`() {
        assertEquals(emptyList<String>(), apply(NetworkVerdictResult.Unavailable("timeout")))
    }

    @Test
    fun `nothing the server says can ever produce an allow other than a verdict of allow`() {
        val results = listOf(
            NetworkVerdictResult.Verdict("block", null, null),
            NetworkVerdictResult.Verdict("uncertain", null, null),
            NetworkVerdictResult.Rejected(422, emptyList(), emptyList()),
            NetworkVerdictResult.Rejected(401, emptyList(), emptyList()),
            NetworkVerdictResult.Unavailable("x")
        )

        results.forEach { result -> assertFalse(apply(result).any { it.startsWith("allow|") }) }
    }
}
