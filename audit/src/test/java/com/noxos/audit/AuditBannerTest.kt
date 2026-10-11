package com.noxos.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AuditBannerTest {

    private fun scan(outcome: AuditOutcome, summary: String? = null, flagged: Boolean = false) = AuditEvent(
        id = 1L,
        timestampEpochMillis = 1L,
        eventType = AuditEventType.FILE_SCAN,
        inputDescriptor = "invoice.doc",
        outcome = outcome,
        resultSummary = summary,
        durationMillis = 10L,
        errorMessage = null,
        flagged = flagged
    )

    @Test
    fun `a completed scan that flagged something does not read as the error path`() {
        val title = bannerTitle(scan(AuditOutcome.FAILURE))
        assertEquals("FLAGGED — ISSUE FOUND IN SCAN", title)
        assertFalse(title.contains("DID NOT COMPLETE"))
    }

    @Test
    fun `only a scan that really did not complete says so`() {
        assertEquals("SCAN DID NOT COMPLETE", bannerTitle(scan(AuditOutcome.ERROR)))
    }

    @Test
    fun `clean and not-analyzed scans keep their titles`() {
        assertEquals("SANITIZED — SAFE TO USE", bannerTitle(scan(AuditOutcome.SUCCESS, "Scan completed, no issues found")))
        assertEquals(
            "NOT ANALYZED — NOT FLAGGED",
            bannerTitle(scan(AuditOutcome.SUCCESS, "$NOT_ANALYZED_SUMMARY_PREFIX (unknown) — not analyzed, not flagged"))
        )
    }

    @Test
    fun `a manually flagged clean scan is just flagged`() {
        assertEquals("FLAGGED", bannerTitle(scan(AuditOutcome.SUCCESS, flagged = true)))
    }

    @Test
    fun `network rows are no longer described as inspected in isolation or a sealed VM`() {
        val network = scan(AuditOutcome.SUCCESS).copy(eventType = AuditEventType.NETWORK_TRAFFIC)
        assertEquals("RELAYED — CHECKED", bannerTitle(network))
        listOf(network, scan(AuditOutcome.SUCCESS)).forEach {
            assertFalse(bannerFallbackMessage(it).contains("sealed", ignoreCase = true))
        }
    }
}
