package com.weshah.domain.engine

import com.weshah.core.models.Alert
import com.weshah.core.models.AlertSeverity
import kotlinx.coroutines.flow.Flow

interface AlertEngine {
    /** All active (unresolved) alerts ordered by timestamp desc. */
    fun observeAlerts(): Flow<List<Alert>>

    /** Unread alert count — drives notification badge. */
    fun observeUnreadCount(): Flow<Int>

    /** Mark a single alert as read. */
    suspend fun markRead(alertId: String)

    /** Mark all alerts as read. */
    suspend fun markAllRead()

    /** Resolve (dismiss) an alert permanently. */
    suspend fun resolveAlert(alertId: String)

    /** Minimum severity that generates a push notification. */
    suspend fun setNotificationThreshold(severity: AlertSeverity)
}
