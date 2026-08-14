package com.noxos.audit

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "audit_entries")
data class AuditEntryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val timestampEpochMillis: Long,
    val eventType: AuditEventType,
    val inputDescriptor: String,
    val outcome: AuditOutcome,
    val resultSummary: String?,
    val durationMillis: Long,
    val errorMessage: String?
)
