package com.noxos.audit

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [AuditEntryEntity::class, BlockedHostEntity::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(AuditConverters::class)
abstract class AuditDatabase : RoomDatabase() {
    abstract fun auditDao(): AuditDao
    abstract fun blockedHostDao(): BlockedHostDao

    companion object {
        @Volatile
        private var INSTANCE: AuditDatabase? = null

        fun getDatabase(context: Context): AuditDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AuditDatabase::class.java,
                    "audit_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
