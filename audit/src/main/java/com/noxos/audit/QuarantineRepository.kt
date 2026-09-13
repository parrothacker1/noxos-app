package com.noxos.audit

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class QuarantineEntry(
    val id: Long,
    val originalDisplayName: String,
    val mimeType: String?,
    val storedFileName: String,
    val reason: String,
    val quarantinedAtEpochMillis: Long
)

interface QuarantineRepository {
    suspend fun add(originalDisplayName: String, mimeType: String?, storedFileName: String, reason: String): Long
    fun observeAll(): Flow<List<QuarantineEntry>>
    suspend fun get(id: Long): QuarantineEntry?
    suspend fun remove(id: Long)
    suspend fun entriesOlderThan(cutoffEpochMillis: Long): List<QuarantineEntry>
}

class RoomQuarantineRepository(private val dao: QuarantineDao) : QuarantineRepository {

    override suspend fun add(originalDisplayName: String, mimeType: String?, storedFileName: String, reason: String): Long {
        return dao.insert(
            QuarantineEntity(
                originalDisplayName = originalDisplayName,
                mimeType = mimeType,
                storedFileName = storedFileName,
                reason = reason,
                quarantinedAtEpochMillis = System.currentTimeMillis()
            )
        )
    }

    override fun observeAll(): Flow<List<QuarantineEntry>> =
        dao.observeAll().map { list -> list.map { it.toEntry() } }

    override suspend fun get(id: Long): QuarantineEntry? = dao.get(id)?.toEntry()

    override suspend fun remove(id: Long) {
        dao.delete(id)
    }

    override suspend fun entriesOlderThan(cutoffEpochMillis: Long): List<QuarantineEntry> =
        dao.olderThan(cutoffEpochMillis).map { it.toEntry() }

    private fun QuarantineEntity.toEntry() =
        QuarantineEntry(id, originalDisplayName, mimeType, storedFileName, reason, quarantinedAtEpochMillis)
}

object QuarantineModule {
    fun create(context: Context): QuarantineRepository {
        return RoomQuarantineRepository(AuditDatabase.getDatabase(context).quarantineDao())
    }
}
