package com.noxos.audit

import androidx.room.Entity

@Entity(tableName = "blocked_hosts", primaryKeys = ["host"])
data class BlockedHostEntity(
    val host: String,
    val reason: String,
    val blockedAtEpochMillis: Long
)
