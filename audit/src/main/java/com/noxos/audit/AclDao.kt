package com.noxos.audit

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AclDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AclEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: AclEntity)

    @Query("DELETE FROM acl_entries WHERE kind = :kind AND subject = :subject")
    suspend fun remove(kind: AclKind, subject: String)

    @Query("SELECT * FROM acl_entries ORDER BY updatedAtEpochMillis DESC")
    fun observeAll(): Flow<List<AclEntity>>

    @Query("SELECT * FROM acl_entries WHERE kind = :kind ORDER BY updatedAtEpochMillis DESC")
    fun observeByKind(kind: AclKind): Flow<List<AclEntity>>

    @Query("SELECT * FROM acl_entries WHERE kind = :kind AND state = :state")
    suspend fun entriesByKindAndState(kind: AclKind, state: AclState): List<AclEntity>
}
