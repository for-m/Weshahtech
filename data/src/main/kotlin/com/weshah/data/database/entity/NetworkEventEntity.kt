package com.weshah.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.weshah.core.models.EventSeverity
import com.weshah.core.models.NetworkEvent
import com.weshah.core.models.NetworkEventType

@Entity(tableName = "network_events", indices = [androidx.room.Index("timestamp")])
data class NetworkEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val macAddress: String?,
    val ipAddress: String?,
    val deviceName: String?,
    val message: String,
    val timestamp: Long,
    val severity: String
) {
    fun toModel() = NetworkEvent(
        id = id, message = message, timestamp = timestamp,
        macAddress = macAddress, ipAddress = ipAddress, deviceName = deviceName,
        type = runCatching { NetworkEventType.valueOf(type) }.getOrDefault(NetworkEventType.SCAN_COMPLETED),
        severity = runCatching { EventSeverity.valueOf(severity) }.getOrDefault(EventSeverity.INFO)
    )
}

fun NetworkEvent.toEntity() = NetworkEventEntity(
    id = id, message = message, timestamp = timestamp,
    macAddress = macAddress, ipAddress = ipAddress, deviceName = deviceName,
    type = type.name, severity = severity.name
)
