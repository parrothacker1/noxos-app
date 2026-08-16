package com.noxos.audit

object RetentionPolicy {
    fun cutoffEpochMillis(nowEpochMillis: Long, retentionDays: Int): Long =
        nowEpochMillis - retentionDays.toLong() * 24 * 60 * 60 * 1000
}
