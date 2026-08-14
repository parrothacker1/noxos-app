package com.noxos.audit

import android.content.Context
import kotlinx.coroutines.flow.Flow

interface AuditRepository {
    suspend fun record(event: AuditEvent)
    fun observeAll(): Flow<List<AuditEvent>>
    suspend fun get(id: Long): AuditEvent?
}

object AuditModule {
    fun create(context: Context): AuditRepository {
        return RoomAuditRepository(AuditDatabase.getDatabase(context).auditDao())
    }
}
