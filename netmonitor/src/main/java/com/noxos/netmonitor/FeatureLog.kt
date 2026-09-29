package com.noxos.netmonitor

import android.content.Context
import com.noxos.audit.AclEntry
import java.io.File
import java.io.OutputStream
import java.time.LocalDate
import java.time.ZoneOffset

internal const val FEATURE_LOG_SCHEMA_VERSION = 1

// def 1: counters as of ddb104b - every inbound chunk counted; src bytes are whole IP packets
// (headers and pure ACKs included); dst bytes are payload only; duration is first-seen to the
// scoring snapshot; UDP with no reply is INT only after 60 s, TCP SYN with no connect is REQ
// only after 12 s. Bump on ANY change to how a logged value is measured.
internal const val FEATURE_DEFINITION_VERSION = 1

private val LOGGABLE_PROTOCOLS = setOf("tcp", "udp")
private val LOGGABLE_STATES = setOf("FIN", "CON", "INT", "REQ", "RST", "ECO", "other")
private val DAY_FORMAT = Regex("""\d{4}-\d{2}-\d{2}""")

/**
 * One scoring snapshot: raw ingredients only, no identifiers. Every field is a number or a value from a
 * fixed set, so nothing that identifies a destination, app or user can be written even by mistake.
 */
data class FeatureLogRecord(
    val day: String,
    val scoredAfterSeconds: Long,
    val proto: String,
    val dstPort: Int,
    val state: String,
    val srcByteCount: Long,
    val srcPacketCount: Long,
    val dstByteCount: Long,
    val dstChunkCount: Long,
    val durationMillis: Long,
    val handshakeLatencyMillis: Double
) {
    init {
        require(DAY_FORMAT.matches(day)) { "day must be YYYY-MM-DD" }
        require(proto in LOGGABLE_PROTOCOLS) { "unloggable protocol" }
        require(state in LOGGABLE_STATES) { "unloggable state" }
    }

    fun toJsonLine(): String = buildString {
        append("{\"v\":").append(FEATURE_LOG_SCHEMA_VERSION)
        append(",\"def\":").append(FEATURE_DEFINITION_VERSION)
        append(",\"day\":\"").append(day).append('"')
        append(",\"scored_after_s\":").append(scoredAfterSeconds)
        append(",\"proto\":\"").append(proto).append('"')
        append(",\"dst_port\":").append(dstPort)
        append(",\"state\":\"").append(state).append('"')
        append(",\"src_byte_count\":").append(srcByteCount)
        append(",\"src_packet_count\":").append(srcPacketCount)
        append(",\"dst_byte_count\":").append(dstByteCount)
        append(",\"dst_chunk_count\":").append(dstChunkCount)
        append(",\"duration_millis\":").append(durationMillis)
        append(",\"handshake_latency_millis\":").append(handshakeLatencyMillis)
        append(",\"later_blocked\":false}")
    }
}

/**
 * Builds the record from the exact [flow] snapshot the model is scored on (see AutoencoderDispatcher),
 * or null when the flow isn't a relayed TCP/UDP flow with a known port.
 */
internal fun buildFeatureLogRecord(
    flow: AclEntry,
    state: String,
    ageMillis: Long,
    day: String = LocalDate.now(ZoneOffset.UTC).toString()
): FeatureLogRecord? {
    val proto = flow.protocol?.lowercase()
    val port = flow.destPort
    if (proto !in LOGGABLE_PROTOCOLS || port == null) return null
    return FeatureLogRecord(
        day = day,
        scoredAfterSeconds = ageMillis / 1000,
        proto = proto!!,
        dstPort = port,
        state = state,
        srcByteCount = flow.srcByteCount ?: 0L,
        srcPacketCount = flow.srcPacketCount ?: 0L,
        dstByteCount = flow.dstByteCount ?: 0L,
        dstChunkCount = flow.dstPacketCount ?: 0L,
        durationMillis = flow.durationMillis ?: 0L,
        handshakeLatencyMillis = flow.handshakeLatencyMillis?.toDouble() ?: 0.0
    )
}

/**
 * Opt-in, local-only JSON Lines log of scoring snapshots for training a model on normal traffic.
 * Lives in noBackupFilesDir so Android Auto Backup can never copy it off the device; the only way
 * out is the user's own export. Which line belongs to which destination is remembered in memory only
 * (never written), purely so a later block can flip that record's later_blocked flag.
 */
class FeatureLog internal constructor(private val file: File, private val maxRecords: Int = MAX_RECORDS) {

    private val lock = Any()
    private var loaded = false
    private var recordCount = 0
    private var firstId = 0L
    private var nextId = 0L
    private val idBySubject = HashMap<String, Long>()

    fun append(subject: String, record: FeatureLogRecord) = synchronized(lock) {
        ensureLoaded()
        file.appendText(record.toJsonLine() + "\n")
        idBySubject[subject] = nextId++
        recordCount++
        if (recordCount > maxRecords) trimOldest()
    }

    fun markLaterBlocked(subject: String) = synchronized(lock) {
        val id = idBySubject.remove(subject) ?: return@synchronized
        if (id < firstId || !file.exists()) return@synchronized
        val lines = file.readLines().toMutableList()
        val index = (id - firstId).toInt()
        val line = lines.getOrNull(index) ?: return@synchronized
        if (!line.endsWith(NOT_BLOCKED_SUFFIX)) return@synchronized
        lines[index] = line.removeSuffix(NOT_BLOCKED_SUFFIX) + BLOCKED_SUFFIX
        rewrite(lines)
    }

    fun count(): Int = synchronized(lock) {
        ensureLoaded()
        recordCount
    }

    fun clear() = synchronized(lock) {
        file.delete()
        loaded = true
        recordCount = 0
        firstId = nextId
        idBySubject.clear()
    }

    fun copyTo(out: OutputStream) = synchronized(lock) {
        if (file.exists()) file.inputStream().use { it.copyTo(out) }
    }

    private fun ensureLoaded() {
        if (loaded) return
        recordCount = if (file.exists()) file.useLines { lines -> lines.count() } else 0
        firstId = 0L
        nextId = recordCount.toLong()
        loaded = true
    }

    private fun trimOldest() {
        val keep = maxRecords * 9 / 10
        val dropCount = recordCount - keep
        rewrite(file.readLines().drop(dropCount))
        firstId += dropCount
        recordCount = keep
        idBySubject.entries.removeAll { it.value < firstId }
    }

    private fun rewrite(lines: List<String>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(lines.joinToString(separator = "\n", postfix = "\n"))
        tmp.renameTo(file)
    }

    companion object {
        const val MAX_RECORDS = 50_000
        private const val NOT_BLOCKED_SUFFIX = "\"later_blocked\":false}"
        private const val BLOCKED_SUFFIX = "\"later_blocked\":true}"

        @Volatile
        private var instance: FeatureLog? = null

        fun get(context: Context): FeatureLog = instance ?: synchronized(this) {
            instance ?: FeatureLog(File(context.applicationContext.noBackupFilesDir, "feature_log.jsonl"))
                .also { instance = it }
        }
    }
}
