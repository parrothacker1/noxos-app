package com.noxos.audit

import org.junit.Assert.assertEquals
import org.junit.Test

class AuditListFilteringTest {

    private val fileEvent = AuditEvent(
        id = 1L,
        timestampEpochMillis = 1L,
        eventType = AuditEventType.FILE_SCAN,
        inputDescriptor = "invoice_final.pdf",
        outcome = AuditOutcome.SUCCESS,
        resultSummary = null,
        durationMillis = 0L,
        errorMessage = null,
        flagged = false
    )

    private val flaggedNetworkEvent = AuditEvent(
        id = 2L,
        timestampEpochMillis = 2L,
        eventType = AuditEventType.NETWORK_TRAFFIC,
        inputDescriptor = "UDP 10.0.0.4:1234 -> 91.203.5.12:443",
        outcome = AuditOutcome.SUCCESS,
        resultSummary = null,
        durationMillis = 0L,
        errorMessage = null,
        flagged = true,
        remoteHost = "91.203.5.12"
    )

    private val events = listOf(fileEvent, flaggedNetworkEvent)

    @Test
    fun allFilterReturnsEverything() {
        assertEquals(2, filterAuditEvents(events, AuditFilter.ALL, "").size)
    }

    @Test
    fun filesFilterReturnsOnlyFileScans() {
        assertEquals(listOf(fileEvent), filterAuditEvents(events, AuditFilter.FILES, ""))
    }

    @Test
    fun networkFilterReturnsOnlyNetworkTraffic() {
        assertEquals(listOf(flaggedNetworkEvent), filterAuditEvents(events, AuditFilter.NETWORK, ""))
    }

    @Test
    fun flaggedFilterReturnsOnlyFlaggedEvents() {
        assertEquals(listOf(flaggedNetworkEvent), filterAuditEvents(events, AuditFilter.FLAGGED, ""))
    }

    @Test
    fun queryMatchesRemoteHost() {
        assertEquals(listOf(flaggedNetworkEvent), filterAuditEvents(events, AuditFilter.ALL, "91.203"))
    }

    @Test
    fun queryMatchesInputDescriptorCaseInsensitively() {
        assertEquals(listOf(fileEvent), filterAuditEvents(events, AuditFilter.ALL, "INVOICE"))
    }

    @Test
    fun dateBucketLabelsToday() {
        val now = 1_700_000_000_000L
        assertEquals("Today", dateBucketLabel(now, now))
    }

    @Test
    fun dateBucketLabelsYesterday() {
        val now = 1_700_000_000_000L
        val oneDayMillis = 24L * 60 * 60 * 1000
        assertEquals("Yesterday", dateBucketLabel(now - oneDayMillis, now))
    }
}
