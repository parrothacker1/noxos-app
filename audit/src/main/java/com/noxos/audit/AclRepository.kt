package com.noxos.audit

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class AclEntry(
    val host: String,
    val state: AclState,
    val reason: String,
    val updatedAtEpochMillis: Long
)

interface AclRepository {
    suspend fun allow(host: String, reason: String)
    suspend fun block(host: String, reason: String)
    suspend fun flagIfUnknown(host: String, reason: String)
    suspend fun remove(host: String)
    fun observeAll(): Flow<List<AclEntry>>
}

class RoomAclRepository(private val dao: AclDao) : AclRepository {

    override suspend fun allow(host: String, reason: String) {
        dao.upsert(AclEntity(host, AclState.ALLOWED, reason, System.currentTimeMillis()))
    }

    override suspend fun block(host: String, reason: String) {
        dao.upsert(AclEntity(host, AclState.BLOCKED, reason, System.currentTimeMillis()))
    }

    override suspend fun flagIfUnknown(host: String, reason: String) {
        dao.insertIfAbsent(AclEntity(host, AclState.FLAGGED, reason, System.currentTimeMillis()))
    }

    override suspend fun remove(host: String) {
        dao.remove(host)
    }

    override fun observeAll(): Flow<List<AclEntry>> {
        return dao.observeAll().map { list ->
            list.map { entity -> AclEntry(entity.host, entity.state, entity.reason, entity.updatedAtEpochMillis) }
        }
    }
}

object AclModule {
    fun create(context: Context): AclRepository {
        return RoomAclRepository(AuditDatabase.getDatabase(context).aclDao())
    }
}
