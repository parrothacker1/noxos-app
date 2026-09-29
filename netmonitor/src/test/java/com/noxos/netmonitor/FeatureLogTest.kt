package com.noxos.netmonitor

import com.noxos.audit.AclEntry
import com.noxos.audit.AclKind
import com.noxos.audit.AclPriority
import com.noxos.audit.AclState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class FeatureLogTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun record(port: Int = 443, proto: String = "tcp", state: String = "CON") = FeatureLogRecord(
        day = "2026-09-30", scoredAfterSeconds = 12, proto = proto, dstPort = port, state = state,
        srcByteCount = 5231, srcPacketCount = 41, dstByteCount = 88214, dstChunkCount = 37,
        durationMillis = 12044, handshakeLatencyMillis = 31.2
    )

    private fun newLog(max: Int = FeatureLog.MAX_RECORDS): Pair<FeatureLog, File> {
        val file = File(folder.root, "feature_log.jsonl")
        return FeatureLog(file, max) to file
    }

    @Test
    fun `a record serializes to exactly the agreed JSON Lines shape`() {
        assertEquals(
            "{\"v\":1,\"def\":1,\"day\":\"2026-09-30\",\"scored_after_s\":12,\"proto\":\"tcp\",\"dst_port\":443," +
                "\"state\":\"CON\",\"src_byte_count\":5231,\"src_packet_count\":41,\"dst_byte_count\":88214," +
                "\"dst_chunk_count\":37,\"duration_millis\":12044,\"handshake_latency_millis\":31.2,\"later_blocked\":false}",
            record().toJsonLine()
        )
    }

    @Test
    fun `a record can only hold fixed-set strings so no identifier can be written`() {
        assertThrows(IllegalArgumentException::class.java) { record(proto = "1.2.3.4") }
        assertThrows(IllegalArgumentException::class.java) { record(proto = "icmp") }
        assertThrows(IllegalArgumentException::class.java) { record(state = "example.com") }
        assertThrows(IllegalArgumentException::class.java) {
            FeatureLogRecord("2026-09-30T10:15:00Z", 0, "tcp", 80, "CON", 0, 0, 0, 0, 0, 0.0)
        }
    }

    @Test
    fun `the record is built from the scored flow snapshot, not from identifiers`() {
        val flow = AclEntry(
            kind = AclKind.NETWORK, subject = "203.0.113.7", state = AclState.FLAGGED, priority = AclPriority.HIGH,
            reason = "new destination", updatedAtEpochMillis = 1L, destPort = 8443, protocol = "UDP",
            srcPacketCount = 4, srcByteCount = 400, dstPacketCount = 2, dstByteCount = 3000,
            durationMillis = 61_500, handshakeLatencyMillis = null
        )

        val line = buildFeatureLogRecord(flow, "INT", 61_500, day = "2026-09-30")!!.toJsonLine()

        assertTrue(line.contains("\"scored_after_s\":61"))
        assertTrue(line.contains("\"proto\":\"udp\""))
        assertTrue(line.contains("\"dst_chunk_count\":2"))
        assertTrue(line.contains("\"handshake_latency_millis\":0.0"))
        assertFalse(line.contains("203.0.113.7"))
    }

    @Test
    fun `flows that are not relayed tcp or udp with a port are not logged`() {
        val base = AclEntry(
            kind = AclKind.NETWORK, subject = "x", state = AclState.FLAGGED, priority = AclPriority.HIGH,
            reason = "r", updatedAtEpochMillis = 1L, destPort = 53, protocol = "OTHER"
        )

        assertNull(buildFeatureLogRecord(base, "other", 0))
        assertNull(buildFeatureLogRecord(base.copy(protocol = "TCP", destPort = null), "CON", 0))
    }

    @Test
    fun `appending writes one line per record and count matches`() {
        val (log, file) = newLog()

        log.append("a", record(port = 1))
        log.append("b", record(port = 2))

        assertEquals(2, log.count())
        assertEquals(2, file.readLines().size)
        assertTrue(file.readLines()[1].contains("\"dst_port\":2"))
    }

    @Test
    fun `a new instance over an existing file continues from its record count`() {
        val (log, file) = newLog()
        log.append("a", record())
        log.append("b", record())

        assertEquals(2, FeatureLog(file).count())
    }

    @Test
    fun `beyond the cap the oldest records are dropped first`() {
        val (log, file) = newLog(max = 10)

        (1..25).forEach { log.append("d$it", record(port = it)) }

        val ports = file.readLines().map { Regex("\"dst_port\":(\\d+)").find(it)!!.groupValues[1].toInt() }
        assertTrue(ports.size <= 10)
        assertEquals(log.count(), ports.size)
        assertEquals(25, ports.last())
        assertFalse(1 in ports)
        assertEquals(ports.sorted(), ports)
    }

    @Test
    fun `a later block flips only that destination's record`() {
        val (log, file) = newLog()
        log.append("a", record(port = 1))
        log.append("b", record(port = 2))
        log.append("c", record(port = 3))

        log.markLaterBlocked("b")

        val lines = file.readLines()
        assertTrue(lines[0].endsWith("\"later_blocked\":false}"))
        assertTrue(lines[1].endsWith("\"later_blocked\":true}"))
        assertTrue(lines[2].endsWith("\"later_blocked\":false}"))
        assertEquals(3, lines.size)
    }

    @Test
    fun `marking still hits the right line after the oldest records were trimmed`() {
        val (log, file) = newLog(max = 10)
        (1..14).forEach { log.append("d$it", record(port = it)) }

        log.markLaterBlocked("d14")
        log.markLaterBlocked("d1")

        val lines = file.readLines()
        assertTrue(lines.last().contains("\"dst_port\":14") && lines.last().endsWith("\"later_blocked\":true}"))
        assertEquals(1, lines.count { it.endsWith("\"later_blocked\":true}") })
    }

    @Test
    fun `marking an unknown or already marked destination changes nothing`() {
        val (log, file) = newLog()
        log.append("a", record())

        log.markLaterBlocked("nobody")
        log.markLaterBlocked("a")
        log.markLaterBlocked("a")

        assertEquals(listOf(record().toJsonLine().removeSuffix("false}") + "true}"), file.readLines())
    }

    @Test
    fun `clear removes everything and old destinations can no longer be marked`() {
        val (log, file) = newLog()
        log.append("a", record())

        log.clear()
        log.append("b", record(port = 9))
        log.markLaterBlocked("a")

        assertEquals(1, log.count())
        assertEquals(1, file.readLines().size)
        assertTrue(file.readLines().single().endsWith("\"later_blocked\":false}"))
    }

    @Test
    fun `export copies the file contents verbatim and an empty log exports nothing`() {
        val (log, _) = newLog()
        val empty = ByteArrayOutputStream().also { log.copyTo(it) }
        assertEquals(0, empty.size())

        log.append("a", record())
        val out = ByteArrayOutputStream().also { log.copyTo(it) }
        assertEquals(record().toJsonLine() + "\n", out.toString(Charsets.UTF_8))
        assertNotNull(out)
    }
}
