package com.noxos.audit

import androidx.room.TypeConverter

class AuditConverters {
    @TypeConverter
    fun fromEventType(value: AuditEventType): String = value.name

    @TypeConverter
    fun toEventType(value: String): AuditEventType = AuditEventType.valueOf(value)

    @TypeConverter
    fun fromOutcome(value: AuditOutcome): String = value.name

    @TypeConverter
    fun toOutcome(value: String): AuditOutcome = AuditOutcome.valueOf(value)

    @TypeConverter
    fun fromAclState(value: AclState): String = value.name

    @TypeConverter
    fun toAclState(value: String): AclState = AclState.valueOf(value)

    @TypeConverter
    fun fromAclKind(value: AclKind): String = value.name

    @TypeConverter
    fun toAclKind(value: String): AclKind = AclKind.valueOf(value)

    @TypeConverter
    fun fromAclPriority(value: AclPriority): String = value.name

    @TypeConverter
    fun toAclPriority(value: String): AclPriority = AclPriority.valueOf(value)
}
