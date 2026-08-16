package com.noxos.audit

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockedHostDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: BlockedHostEntity)

    @Query("DELETE FROM blocked_hosts WHERE host = :host")
    suspend fun delete(host: String)

    @Query("SELECT * FROM blocked_hosts ORDER BY blockedAtEpochMillis DESC")
    fun observeAll(): Flow<List<BlockedHostEntity>>
}
