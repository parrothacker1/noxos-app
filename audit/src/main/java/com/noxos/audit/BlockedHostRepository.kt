package com.noxos.audit

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class BlockedHost(
    val host: String,
    val reason: String,
    val blockedAtEpochMillis: Long
)

interface BlockedHostRepository {
    suspend fun block(host: String, reason: String)
    suspend fun unblock(host: String)
    fun observeAll(): Flow<List<BlockedHost>>
}

class RoomBlockedHostRepository(private val dao: BlockedHostDao) : BlockedHostRepository {

    override suspend fun block(host: String, reason: String) {
        dao.insert(
            BlockedHostEntity(
                host = host,
                reason = reason,
                blockedAtEpochMillis = System.currentTimeMillis()
            )
        )
    }

    override suspend fun unblock(host: String) {
        dao.delete(host)
    }

    override fun observeAll(): Flow<List<BlockedHost>> {
        return dao.observeAll().map { list ->
            list.map { entity -> BlockedHost(entity.host, entity.reason, entity.blockedAtEpochMillis) }
        }
    }
}

object BlockedHostModule {
    fun create(context: Context): BlockedHostRepository {
        return RoomBlockedHostRepository(AuditDatabase.getDatabase(context).blockedHostDao())
    }
}
