package com.weshah.domain.repository

import com.weshah.core.models.AlertSeverity
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val autoScanEnabled: Flow<Boolean>
    val scanIntervalMinutes: Flow<Int>
    val notificationsEnabled: Flow<Boolean>
    val alertNotificationThreshold: Flow<AlertSeverity>
    val darkMode: Flow<Boolean>

    suspend fun setAutoScanEnabled(v: Boolean)
    suspend fun setScanIntervalMinutes(v: Int)
    suspend fun setNotificationsEnabled(v: Boolean)
    suspend fun setAlertNotificationThreshold(severity: AlertSeverity)
    suspend fun setDarkMode(v: Boolean)
}
