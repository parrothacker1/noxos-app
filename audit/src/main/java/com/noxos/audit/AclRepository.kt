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
    val sessionOnly: Boolean = false
)

interface AclRepository {
    suspend fun allow(kind: AclKind, subject: String, reason: String, safetyScore: Float? = null, sessionOnly: Boolean = false)
    suspend fun block(kind: AclKind, subject: String, reason: String, safetyScore: Float? = null, sessionOnly: Boolean = false)
    suspend fun flagIfUnknown(kind: AclKind, subject: String, priority: AclPriority, reason: String)
    suspend fun remove(kind: AclKind, subject: String)
    fun observeAll(): Flow<List<AclEntry>>
    fun observeKind(kind: AclKind): Flow<List<AclEntry>>

    /** Up to [limit] flagged network entries, highest priority and oldest first. */
    suspend fun nextAnalysisBatch(limit: Int): List<AclEntry>

    /** Seeds well-known-safe network hosts. Never overwrites an existing entry (user or AI set). */
    suspend fun seedDefaults()

    /** Forgets AI-sourced verdicts of [kind] (sessionOnly = true) - a fresh session re-asks. Never touches user/seed entries. */
    suspend fun clearSessionVerdicts(kind: AclKind)
}

class RoomAclRepository(private val dao: AclDao) : AclRepository {

    override suspend fun allow(kind: AclKind, subject: String, reason: String, safetyScore: Float?, sessionOnly: Boolean) {
        dao.upsert(AclEntity(kind, subject, AclState.ALLOWED, AclPriority.LOW, reason, System.currentTimeMillis(), safetyScore, sessionOnly))
    }

    override suspend fun block(kind: AclKind, subject: String, reason: String, safetyScore: Float?, sessionOnly: Boolean) {
        dao.upsert(AclEntity(kind, subject, AclState.BLOCKED, AclPriority.LOW, reason, System.currentTimeMillis(), safetyScore, sessionOnly))
    }

    override suspend fun flagIfUnknown(kind: AclKind, subject: String, priority: AclPriority, reason: String) {
        dao.insertIfAbsent(AclEntity(kind, subject, AclState.FLAGGED, priority, reason, System.currentTimeMillis()))
    }

    override suspend fun remove(kind: AclKind, subject: String) {
        dao.remove(kind, subject)
    }

    override fun observeAll(): Flow<List<AclEntry>> =
        dao.observeAll().map { list -> list.map { it.toEntry() } }

    override fun observeKind(kind: AclKind): Flow<List<AclEntry>> =
        dao.observeByKind(kind).map { list -> list.map { it.toEntry() } }

    override suspend fun nextAnalysisBatch(limit: Int): List<AclEntry> {
        return dao.entriesByKindAndState(AclKind.NETWORK, AclState.FLAGGED)
            .sortedWith(compareByDescending<AclEntity> { it.priority == AclPriority.HIGH }.thenBy { it.updatedAtEpochMillis })
            .take(limit)
            .map { it.toEntry() }
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

    private fun AclEntity.toEntry() = AclEntry(kind, subject, state, priority, reason, updatedAtEpochMillis, safetyScore, sessionOnly)
}

object AclModule {
    fun create(context: Context): AclRepository {
        return RoomAclRepository(AuditDatabase.getDatabase(context).aclDao())
    }
}
