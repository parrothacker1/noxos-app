package com.noxos.audit

enum class AuditEventType {
    FILE_SCAN,
    NETWORK_TRAFFIC
}

enum class AuditOutcome {
    SUCCESS,
    FAILURE,
    ERROR
}

data class AuditEvent(
    val id: Long = 0L,
    val timestampEpochMillis: Long,
    val eventType: AuditEventType,
    val inputDescriptor: String,
    val outcome: AuditOutcome,
    val resultSummary: String?,
    val durationMillis: Long,
    val errorMessage: String?
)
