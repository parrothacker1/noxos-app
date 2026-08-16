package com.noxos.audit

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class AuditFilter { ALL, FILES, NETWORK, FLAGGED }

enum class AuditSeverity { SAFE, FLAGGED, BLOCKED }

fun severityOf(event: AuditEvent): AuditSeverity = when {
    event.outcome == AuditOutcome.BLOCKED -> AuditSeverity.BLOCKED
    event.outcome == AuditOutcome.FAILURE || event.outcome == AuditOutcome.ERROR || event.flagged -> AuditSeverity.FLAGGED
    else -> AuditSeverity.SAFE
}

fun filterAuditEvents(events: List<AuditEvent>, filter: AuditFilter, query: String): List<AuditEvent> {
    val q = query.trim().lowercase()
    return events.filter { event ->
        val matchesFilter = when (filter) {
            AuditFilter.ALL -> true
            AuditFilter.FILES -> event.eventType == AuditEventType.FILE_SCAN
            AuditFilter.NETWORK -> event.eventType == AuditEventType.NETWORK_TRAFFIC
            AuditFilter.FLAGGED -> event.flagged
        }
        val matchesQuery = q.isEmpty() ||
            event.inputDescriptor.lowercase().contains(q) ||
            (event.remoteHost?.lowercase()?.contains(q) == true)
        matchesFilter && matchesQuery
    }
}

fun dateBucketLabel(eventEpochMillis: Long, nowEpochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val eventDate = Instant.ofEpochMilli(eventEpochMillis).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(nowEpochMillis).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(eventDate, today)
    return when (days) {
        0L -> "Today"
        1L -> "Yesterday"
        else -> eventDate.toString()
    }
}
