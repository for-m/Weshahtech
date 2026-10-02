package com.weshah.data.repository

import com.weshah.core.models.Alert
import com.weshah.core.models.AlertSeverity
import com.weshah.data.database.dao.AlertDao
import com.weshah.data.database.entity.toEntity
import com.weshah.domain.engine.AlertEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlertEngineImpl @Inject constructor(
    private val alertDao: AlertDao
) : AlertEngine {

    override fun observeAlerts(): Flow<List<Alert>> =
        alertDao.observeActive().map { entities -> entities.map { it.toModel() } }

    override fun observeUnreadCount(): Flow<Int> = alertDao.observeUnreadCount()

    override suspend fun markRead(alertId: String) = alertDao.markRead(alertId)

    override suspend fun markAllRead() = alertDao.markAllRead()

    override suspend fun resolveAlert(alertId: String) = alertDao.resolve(alertId)

    /** Persists threshold in DataStore (wired in Phase 2 DataStore step). No-op until then. */
    override suspend fun setNotificationThreshold(severity: AlertSeverity) {
        // TODO: persist to DataStore in SettingsRepository phase
    }

    /** Called by the event ingestion pipeline to emit new alerts. */
    suspend fun emit(alert: Alert) = alertDao.insert(alert.toEntity())
}
