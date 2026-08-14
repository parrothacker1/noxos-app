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
}
