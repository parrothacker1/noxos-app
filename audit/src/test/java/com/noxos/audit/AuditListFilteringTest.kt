package com.noxos.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    @Test
    fun `a clean file scan that was not analyzed is identified so it is never shown as sanitized`() {
        val notAnalyzed = fileEvent.copy(resultSummary = "$NOT_ANALYZED_SUMMARY_PREFIX (png) — not analyzed, not flagged")

        assertTrue(isNotAnalyzed(notAnalyzed))
    }

    @Test
    fun `a really analyzed scan, a failure and a network event are not marked not-analyzed`() {
        val prefix = "$NOT_ANALYZED_SUMMARY_PREFIX (png) — not analyzed, not flagged"

        assertFalse(isNotAnalyzed(fileEvent.copy(resultSummary = "Scan completed, no issues found")))
        assertFalse(isNotAnalyzed(fileEvent.copy(resultSummary = null)))
        assertFalse(isNotAnalyzed(fileEvent.copy(outcome = AuditOutcome.FAILURE, resultSummary = prefix)))
        assertFalse(isNotAnalyzed(fileEvent.copy(flagged = true, resultSummary = prefix)))
        assertFalse(isNotAnalyzed(flaggedNetworkEvent.copy(flagged = false, resultSummary = prefix)))
    }
}
