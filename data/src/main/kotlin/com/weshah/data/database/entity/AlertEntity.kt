package com.weshah.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.weshah.core.models.Alert
import com.weshah.core.models.AlertSeverity
import com.weshah.core.models.AlertType

@Entity(
    tableName = "alerts",
    indices = [Index("timestamp"), Index("isRead"), Index("isResolved")]
)
data class AlertEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val severity: String,       // AlertSeverity.name()
    val type: String,           // AlertType.name()
    val title: String,
    val message: String,
    val deviceMac: String?,
    val portId: String?,
    val isRead: Boolean = false,
    val isResolved: Boolean = false
) {
    fun toModel() = Alert(
        id = id,
        timestamp = timestamp,
        severity = runCatching { AlertSeverity.valueOf(severity) }.getOrDefault(AlertSeverity.INFO),
        type = runCatching { AlertType.valueOf(type) }.getOrDefault(AlertType.NEW_DEVICE),
        title = title,
        message = message,
        deviceMac = deviceMac,
        portId = portId,
        isRead = isRead,
        isResolved = isResolved
    )
}

fun Alert.toEntity() = AlertEntity(
    id = id,
    timestamp = timestamp,
    severity = severity.name,
    type = type.name,
    title = title,
    message = message,
    deviceMac = deviceMac,
    portId = portId,
    isRead = isRead,
    isResolved = isResolved
)
