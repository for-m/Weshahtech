package com.weshah.core.models

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class NetworkDevice(
    val id: String,                    // MAC address (canonical identifier)
    val ipAddress: String,
    val macAddress: String,
    val hostname: String?,
    val customName: String?,
    val manufacturer: String?,         // OUI lookup result
    val deviceType: DeviceType,
    val connectionType: ConnectionType,
    val isOnline: Boolean,
    val isBlocked: Boolean = false,
    val isFavorite: Boolean = false,
    val firstSeen: Long,               // epoch millis
    val lastSeen: Long,
    val interface_: String?,           // e.g. "br-lan", "wlan0"
    val rssi: Int?,                    // dBm, null if wired
    val band: WiFiBand?,
    val uploadRateBytes: Long,         // current bytes/sec
    val downloadRateBytes: Long,
    val totalUploadBytes: Long,
    val totalDownloadBytes: Long,
    val associatedSubscriberId: String?
)

enum class DeviceType {
    ROUTER, COMPUTER, PHONE, TABLET, TV, PRINTER, CAMERA,
    SMART_HOME, GAME_CONSOLE, NAS, AP, UNKNOWN
}

enum class ConnectionType { WIFI, WIRED, UNKNOWN }

enum class WiFiBand { BAND_2_4GHZ, BAND_5GHZ, BAND_6GHZ }
