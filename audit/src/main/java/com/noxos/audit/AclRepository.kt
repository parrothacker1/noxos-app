package com.noxos.audit

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class AclEntry(
    val kind: AclKind,
    val subject: String,
    val state: AclState,
    val priority: AclPriority,
    val reason: String,
    val updatedAtEpochMillis: Long,
    val safetyScore: Float? = null,
    val sessionOnly: Boolean = false,
    val cheapFilterChecked: Boolean = false,
    val destPort: Int? = null,
    val protocol: String? = null,
    val srcPacketCount: Long? = null,
    val srcByteCount: Long? = null,
    val dstPacketCount: Long? = null,
    val dstByteCount: Long? = null,
    val durationMillis: Long? = null,
    val handshakeLatencyMillis: Long? = null
)

interface AclRepository {
    suspend fun allow(kind: AclKind, subject: String, reason: String, safetyScore: Float? = null, sessionOnly: Boolean = false)
    suspend fun block(kind: AclKind, subject: String, reason: String, safetyScore: Float? = null, sessionOnly: Boolean = false)
    suspend fun flagIfUnknown(kind: AclKind, subject: String, priority: AclPriority, reason: String, destPort: Int? = null, protocol: String? = null)
    suspend fun remove(kind: AclKind, subject: String)
    fun observeAll(): Flow<List<AclEntry>>
    fun observeKind(kind: AclKind): Flow<List<AclEntry>>

    suspend fun nextAnalysisBatch(limit: Int): List<AclEntry>

    suspend fun nextAutoencoderBatch(limit: Int): List<AclEntry>

    suspend fun markAutoencoderFlagged(kind: AclKind, subject: String, reason: String)

    suspend fun recordConnectionStats(
        kind: AclKind,
        subject: String,
        srcPacketCount: Long,
        srcByteCount: Long,
        dstPacketCount: Long,
        dstByteCount: Long,
        durationMillis: Long,
        handshakeLatencyMillis: Long?
    )

    suspend fun seedDefaults()

    suspend fun clearSessionVerdicts(kind: AclKind)
}

class RoomAclRepository(private val dao: AclDao) : AclRepository {

    override suspend fun allow(kind: AclKind, subject: String, reason: String, safetyScore: Float?, sessionOnly: Boolean) {
        dao.upsert(AclEntity(kind, subject, AclState.ALLOWED, AclPriority.LOW, reason, System.currentTimeMillis(), safetyScore, sessionOnly))
    }

    override suspend fun block(kind: AclKind, subject: String, reason: String, safetyScore: Float?, sessionOnly: Boolean) {
        dao.upsert(AclEntity(kind, subject, AclState.BLOCKED, AclPriority.LOW, reason, System.currentTimeMillis(), safetyScore, sessionOnly))
    }

    override suspend fun flagIfUnknown(kind: AclKind, subject: String, priority: AclPriority, reason: String, destPort: Int?, protocol: String?) {
        dao.insertIfAbsent(
            AclEntity(kind, subject, AclState.FLAGGED, priority, reason, System.currentTimeMillis(), destPort = destPort, protocol = protocol)
        )
    }

    override suspend fun remove(kind: AclKind, subject: String) {
        dao.remove(kind, subject)
    }

    override fun observeAll(): Flow<List<AclEntry>> =
        dao.observeAll().map { list -> list.map { it.toEntry() } }

    override fun observeKind(kind: AclKind): Flow<List<AclEntry>> =
        dao.observeByKind(kind).map { list -> list.map { it.toEntry() } }

    override suspend fun nextAnalysisBatch(limit: Int): List<AclEntry> {
        return dao.entriesAutoencoderFlagged(AclKind.NETWORK)
            .sortedWith(compareByDescending<AclEntity> { it.priority == AclPriority.HIGH }.thenBy { it.updatedAtEpochMillis })
            .take(limit)
            .map { it.toEntry() }
    }

    override suspend fun nextAutoencoderBatch(limit: Int): List<AclEntry> {
        val cutoff = System.currentTimeMillis() - CHEAP_FILTER_STALE_MS
        return dao.entriesByKindAndState(AclKind.NETWORK, AclState.FLAGGED)
            .filter { !it.cheapFilterChecked && it.updatedAtEpochMillis >= cutoff }
            .sortedBy { it.updatedAtEpochMillis }
            .take(limit)
            .map { it.toEntry() }
    }

    override suspend fun markAutoencoderFlagged(kind: AclKind, subject: String, reason: String) {
        dao.markAutoencoderFlagged(kind, subject, reason, System.currentTimeMillis())
    }

    override suspend fun recordConnectionStats(
        kind: AclKind,
        subject: String,
        srcPacketCount: Long,
        srcByteCount: Long,
        dstPacketCount: Long,
        dstByteCount: Long,
        durationMillis: Long,
        handshakeLatencyMillis: Long?
    ) {
        dao.recordConnectionStats(kind, subject, srcPacketCount, srcByteCount, dstPacketCount, dstByteCount, durationMillis, handshakeLatencyMillis)
    }

    override suspend fun seedDefaults() {
        AclSeed.WELL_KNOWN_SAFE.forEach { host ->
            dao.insertIfAbsent(
                AclEntity(AclKind.NETWORK, host, AclState.ALLOWED, AclPriority.LOW, AclSeed.REASON, System.currentTimeMillis())
            )
        }
    }

    override suspend fun clearSessionVerdicts(kind: AclKind) {
        dao.deleteSessionOnly(kind)
    }

    private fun AclEntity.toEntry() = AclEntry(
        kind, subject, state, priority, reason, updatedAtEpochMillis,
        safetyScore, sessionOnly, cheapFilterChecked, destPort, protocol,
        srcPacketCount, srcByteCount, dstPacketCount, dstByteCount, durationMillis, handshakeLatencyMillis
    )

    companion object {
        private const val CHEAP_FILTER_STALE_MS = 5 * 60 * 1000L
    }
}

object AclModule {
    fun create(context: Context): AclRepository {
        return RoomAclRepository(AuditDatabase.getDatabase(context).aclDao())
    }
}
