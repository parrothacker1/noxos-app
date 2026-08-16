package com.noxos.audit

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AuditDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: AuditEntryEntity)

    @Query("SELECT * FROM audit_entries ORDER BY timestampEpochMillis DESC")
    fun observeAll(): Flow<List<AuditEntryEntity>>

    @Query("SELECT * FROM audit_entries WHERE id = :id")
    suspend fun get(id: Long): AuditEntryEntity?

    @Query("UPDATE audit_entries SET flagged = :flagged WHERE id = :id")
    suspend fun setFlagged(id: Long, flagged: Boolean)

    @Query("DELETE FROM audit_entries WHERE timestampEpochMillis < :cutoffEpochMillis")
    suspend fun deleteOlderThan(cutoffEpochMillis: Long): Int
}
