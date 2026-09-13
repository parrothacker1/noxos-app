package com.noxos.audit

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "quarantine_entries")
data class QuarantineEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val originalDisplayName: String,
    val mimeType: String?,
    val storedFileName: String,
    val reason: String,
    val quarantinedAtEpochMillis: Long
)
