package com.noxos.audit

import android.content.Context
import kotlinx.coroutines.flow.Flow

interface AuditRepository {
    suspend fun record(event: AuditEvent)
    fun observeAll(): Flow<List<AuditEvent>>
    suspend fun get(id: Long): AuditEvent?
    suspend fun setFlagged(id: Long, flagged: Boolean)
    suspend fun purgeOlderThan(cutoffEpochMillis: Long): Int
}

object AuditModule {
    fun create(context: Context): AuditRepository {
        return RoomAuditRepository(AuditDatabase.getDatabase(context).auditDao())
    }
}
