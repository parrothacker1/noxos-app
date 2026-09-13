package com.noxos.audit

import androidx.room.Entity

@Entity(tableName = "acl_entries", primaryKeys = ["kind", "subject"])
data class AclEntity(
    val kind: AclKind,
    val subject: String,
    val state: AclState,
    val priority: AclPriority,
    val reason: String,
    val updatedAtEpochMillis: Long,
    val safetyScore: Float? = null,
    val sessionOnly: Boolean = false,
    val cheapFilterChecked: Boolean = false,
    val destPort: Int? = null,
    val protocol: String? = null
)
