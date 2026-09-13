package com.noxos.audit

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface QuarantineDao {
    @Insert
    suspend fun insert(entity: QuarantineEntity): Long

    @Query("SELECT * FROM quarantine_entries ORDER BY quarantinedAtEpochMillis DESC")
    fun observeAll(): Flow<List<QuarantineEntity>>

    @Query("SELECT * FROM quarantine_entries WHERE id = :id")
    suspend fun get(id: Long): QuarantineEntity?

    @Query("DELETE FROM quarantine_entries WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM quarantine_entries WHERE quarantinedAtEpochMillis < :cutoffEpochMillis")
    suspend fun olderThan(cutoffEpochMillis: Long): List<QuarantineEntity>
}
