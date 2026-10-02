package com.weshah.core.models

data class NetworkEvent(
    val id: Long = 0,
    val type: NetworkEventType,
    val macAddress: String?,
    val ipAddress: String?,
    val deviceName: String?,
    val message: String,
    val timestamp: Long,
    val severity: EventSeverity
)

enum class NetworkEventType {
    NEW_DEVICE_CONNECTED,
    KNOWN_DEVICE_ONLINE,
    DEVICE_OFFLINE,
    ROUTER_OFFLINE,
    ROUTER_ONLINE,
    WAN_OFFLINE,
    WAN_ONLINE,
    HIGH_TRAFFIC_DEVICE,
    IP_CONFLICT_SUSPECTED,
    BLOCK_APPLIED,
    BLOCK_REMOVED,
    SPEED_LIMIT_APPLIED,
    SPEED_LIMIT_REMOVED,
    SCAN_COMPLETED
}

enum class EventSeverity { INFO, WARNING, CRITICAL }
