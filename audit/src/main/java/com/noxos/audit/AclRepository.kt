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
    val protocol: String? = null
)

interface AclRepository {
    suspend fun allow(kind: AclKind, subject: String, reason: String, safetyScore: Float? = null, sessionOnly: Boolean = false)
    suspend fun block(kind: AclKind, subject: String, reason: String, safetyScore: Float? = null, sessionOnly: Boolean = false)
    suspend fun flagIfUnknown(kind: AclKind, subject: String, priority: AclPriority, reason: String, destPort: Int? = null, protocol: String? = null)
    suspend fun remove(kind: AclKind, subject: String)
    fun observeAll(): Flow<List<AclEntry>>
    fun observeKind(kind: AclKind): Flow<List<AclEntry>>

    /** Up to [limit] flagged network entries that already cleared the pVM cheap filter, highest priority and oldest first. */
    suspend fun nextAnalysisBatch(limit: Int): List<AclEntry>

    /** Up to [limit] flagged network entries still awaiting the pVM cheap filter, oldest first. */
    suspend fun nextCheapFilterBatch(limit: Int): List<AclEntry>

    /** Records that the pVM cheap filter itself flagged this destination - stays FLAGGED, but is now eligible for [nextAnalysisBatch]. */
    suspend fun markCheapFilterFlagged(kind: AclKind, subject: String, reason: String)

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
        return dao.entriesByKindAndState(AclKind.NETWORK, AclState.FLAGGED)
            .filter { it.cheapFilterChecked }
            .sortedWith(compareByDescending<AclEntity> { it.priority == AclPriority.HIGH }.thenBy { it.updatedAtEpochMillis })
            .take(limit)
            .map { it.toEntry() }
    }

    override suspend fun nextCheapFilterBatch(limit: Int): List<AclEntry> {
        // A destination whose sample was lost (process restart, or a prior VM attempt that came
        // back Unknown) never gets cheapFilterChecked set and never gets a fresh sample either -
        // it would otherwise sit at the front of this oldest-first query forever, permanently
        // filling every batch once enough of them accumulate. Excluding anything past a staleness
        // window gives up on it for good (matches the accepted "explicitly accept the loss"
        // stance) instead of it silently starving every destination flagged after it.
        val cutoff = System.currentTimeMillis() - CHEAP_FILTER_STALE_MS
        return dao.entriesByKindAndState(AclKind.NETWORK, AclState.FLAGGED)
            .filter { !it.cheapFilterChecked && it.updatedAtEpochMillis >= cutoff }
            .sortedBy { it.updatedAtEpochMillis }
            .take(limit)
            .map { it.toEntry() }
    }

    override suspend fun markCheapFilterFlagged(kind: AclKind, subject: String, reason: String) {
        dao.markCheapFilterChecked(kind, subject, reason, System.currentTimeMillis())
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
        safetyScore, sessionOnly, cheapFilterChecked, destPort, protocol
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
