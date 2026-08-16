package com.noxos.audit

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { DARK, LIGHT, SYSTEM }

private val Context.wardenDataStore by preferencesDataStore(name = "warden_settings")

class WardenSettingsRepository(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val VM_TIMEOUT_SECONDS = intPreferencesKey("vm_timeout_seconds")
        val FLAGGED_ALERTS_ENABLED = booleanPreferencesKey("flagged_alerts_enabled")
        val SCAN_COMPLETION_ALERTS_ENABLED = booleanPreferencesKey("scan_completion_alerts_enabled")
        val RETENTION_DAYS = intPreferencesKey("retention_days")
    }

    val themeMode: Flow<ThemeMode> = context.wardenDataStore.data.map { prefs ->
        prefs[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.DARK
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.wardenDataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    val vmSessionTimeoutSeconds: Flow<Int> = context.wardenDataStore.data.map { it[Keys.VM_TIMEOUT_SECONDS] ?: 60 }

    suspend fun setVmSessionTimeoutSeconds(seconds: Int) {
        context.wardenDataStore.edit { it[Keys.VM_TIMEOUT_SECONDS] = seconds }
    }

    val flaggedEventAlertsEnabled: Flow<Boolean> = context.wardenDataStore.data.map { it[Keys.FLAGGED_ALERTS_ENABLED] ?: true }

    suspend fun setFlaggedEventAlertsEnabled(enabled: Boolean) {
        context.wardenDataStore.edit { it[Keys.FLAGGED_ALERTS_ENABLED] = enabled }
    }

    val scanCompletionAlertsEnabled: Flow<Boolean> = context.wardenDataStore.data.map { it[Keys.SCAN_COMPLETION_ALERTS_ENABLED] ?: true }

    suspend fun setScanCompletionAlertsEnabled(enabled: Boolean) {
        context.wardenDataStore.edit { it[Keys.SCAN_COMPLETION_ALERTS_ENABLED] = enabled }
    }

    val auditRetentionDays: Flow<Int> = context.wardenDataStore.data.map { it[Keys.RETENTION_DAYS] ?: 90 }

    suspend fun setAuditRetentionDays(days: Int) {
        context.wardenDataStore.edit { it[Keys.RETENTION_DAYS] = days }
    }
}
