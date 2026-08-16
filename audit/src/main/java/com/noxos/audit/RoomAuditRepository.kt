package com.noxos.audit

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomAuditRepository(private val auditDao: AuditDao) : AuditRepository {

    override suspend fun record(event: AuditEvent) {
        val entity = AuditEntryEntity(
            timestampEpochMillis = event.timestampEpochMillis,
            eventType = event.eventType,
            inputDescriptor = event.inputDescriptor,
            outcome = event.outcome,
            resultSummary = event.resultSummary,
            durationMillis = event.durationMillis,
            errorMessage = event.errorMessage,
            flagged = event.flagged,
            remoteHost = event.remoteHost,
            stepTimingsCsv = event.stepTimingsCsv
        )
        auditDao.insert(entity)
    }

    override fun observeAll(): Flow<List<AuditEvent>> {
        return auditDao.observeAll().map { list -> list.map { it.toDomain() } }
    }

    override suspend fun get(id: Long): AuditEvent? {
        return auditDao.get(id)?.toDomain()
    }

    override suspend fun setFlagged(id: Long, flagged: Boolean) {
        auditDao.setFlagged(id, flagged)
    }

    override suspend fun purgeOlderThan(cutoffEpochMillis: Long): Int {
        return auditDao.deleteOlderThan(cutoffEpochMillis)
    }

    private fun AuditEntryEntity.toDomain() = AuditEvent(
        id = id,
        timestampEpochMillis = timestampEpochMillis,
        eventType = eventType,
        inputDescriptor = inputDescriptor,
        outcome = outcome,
        resultSummary = resultSummary,
        durationMillis = durationMillis,
        errorMessage = errorMessage,
        flagged = flagged,
        remoteHost = remoteHost,
        stepTimingsCsv = stepTimingsCsv
    )
}
