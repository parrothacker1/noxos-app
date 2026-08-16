package com.noxos.audit

import org.junit.Assert.assertEquals
import org.junit.Test

class RetentionPolicyTest {

    @Test
    fun cutoffIsExactlyRetentionDaysBeforeNow() {
        val now = 1_000_000_000_000L
        val cutoff = RetentionPolicy.cutoffEpochMillis(now, retentionDays = 90)
        assertEquals(now - 90L * 24 * 60 * 60 * 1000, cutoff)
    }

    @Test
    fun zeroRetentionDaysMeansCutoffIsNow() {
        val now = 1_000_000_000_000L
        assertEquals(now, RetentionPolicy.cutoffEpochMillis(now, retentionDays = 0))
    }
}
