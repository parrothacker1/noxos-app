package com.noxos.audit

enum class AuditEventType {
    FILE_SCAN,
    NETWORK_TRAFFIC
}

enum class AuditOutcome {
    SUCCESS,
    FAILURE,
    ERROR,
    BLOCKED
}

data class AuditEvent(
    val id: Long = 0L,
    val timestampEpochMillis: Long,
    val eventType: AuditEventType,
    val inputDescriptor: String,
    val outcome: AuditOutcome,
    val resultSummary: String?,
    val durationMillis: Long,
    val errorMessage: String?,
    val flagged: Boolean = false,
    val remoteHost: String? = null,
    val stepTimingsCsv: String? = null
)
