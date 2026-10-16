package com.weshah.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.weshah.core.models.AlertSeverity
import com.weshah.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "weshah_settings")

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : SettingsRepository {

    private object Keys {
        val AUTO_SCAN = booleanPreferencesKey("auto_scan_enabled")
        val SCAN_INTERVAL = intPreferencesKey("scan_interval_minutes")
        val NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
        val ALERT_THRESHOLD = stringPreferencesKey("alert_notification_threshold")
        val DARK_MODE = booleanPreferencesKey("dark_mode")
    }

    override val autoScanEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.AUTO_SCAN] ?: true }

    override val scanIntervalMinutes: Flow<Int> =
        context.dataStore.data.map { it[Keys.SCAN_INTERVAL] ?: 5 }

    override val notificationsEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.NOTIFICATIONS] ?: true }

    override val alertNotificationThreshold: Flow<AlertSeverity> =
        context.dataStore.data.map {
            runCatching { AlertSeverity.valueOf(it[Keys.ALERT_THRESHOLD] ?: "") }
                .getOrDefault(AlertSeverity.WARNING)
        }

    override val darkMode: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.DARK_MODE] ?: true }

    override suspend fun setAutoScanEnabled(v: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_SCAN] = v }
    }

    override suspend fun setScanIntervalMinutes(v: Int) {
        context.dataStore.edit { it[Keys.SCAN_INTERVAL] = v.coerceIn(1, 60) }
    }

    override suspend fun setNotificationsEnabled(v: Boolean) {
        context.dataStore.edit { it[Keys.NOTIFICATIONS] = v }
    }

    override suspend fun setAlertNotificationThreshold(severity: AlertSeverity) {
        context.dataStore.edit { it[Keys.ALERT_THRESHOLD] = severity.name }
    }

    override suspend fun setDarkMode(v: Boolean) {
        context.dataStore.edit { it[Keys.DARK_MODE] = v }
    }
}
