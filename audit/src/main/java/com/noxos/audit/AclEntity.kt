package com.noxos.audit

import androidx.room.Entity

@Entity(tableName = "acl_entries", primaryKeys = ["host"])
data class AclEntity(
    val host: String,
    val state: AclState,
    val reason: String,
    val updatedAtEpochMillis: Long
)
