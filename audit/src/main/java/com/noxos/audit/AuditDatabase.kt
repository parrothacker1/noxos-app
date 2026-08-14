package com.noxos.audit

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [AuditEntryEntity::class], version = 1, exportSchema = false)
@TypeConverters(AuditConverters::class)
abstract class AuditDatabase : RoomDatabase() {
    abstract fun auditDao(): AuditDao

    companion object {
        @Volatile
        private var INSTANCE: AuditDatabase? = null

        fun getDatabase(context: Context): AuditDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AuditDatabase::class.java,
                    "audit_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
