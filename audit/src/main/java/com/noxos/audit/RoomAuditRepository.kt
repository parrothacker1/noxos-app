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
            errorMessage = event.errorMessage
        )
        auditDao.insert(entity)
    }

    override fun observeAll(): Flow<List<AuditEvent>> {
        return auditDao.observeAll().map { list ->
            list.map { entity ->
                AuditEvent(
                    id = entity.id,
                    timestampEpochMillis = entity.timestampEpochMillis,
                    eventType = entity.eventType,
                    inputDescriptor = entity.inputDescriptor,
                    outcome = entity.outcome,
                    resultSummary = entity.resultSummary,
                    durationMillis = entity.durationMillis,
                    errorMessage = entity.errorMessage
                )
            }
        }
    }

    override suspend fun get(id: Long): AuditEvent? {
        val entity = auditDao.get(id) ?: return null
        return AuditEvent(
            id = entity.id,
            timestampEpochMillis = entity.timestampEpochMillis,
            eventType = entity.eventType,
            inputDescriptor = entity.inputDescriptor,
            outcome = entity.outcome,
            resultSummary = entity.resultSummary,
            durationMillis = entity.durationMillis,
            errorMessage = entity.errorMessage
        )
    }
}
