package com.weshah.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.weshah.core.models.*

@Entity(
    tableName = "network_devices",
    indices = [Index("macAddress", unique = true), Index("ipAddress"), Index("isOnline")]
)
data class NetworkDeviceEntity(
    @PrimaryKey val macAddress: String,
    val ipAddress: String,
    val hostname: String?,
    val customName: String?,
    val manufacturer: String?,
    val deviceType: String,          // DeviceType.name()
    val connectionType: String,
    val isOnline: Boolean,
    val firstSeen: Long,
    val lastSeen: Long,
    val interface_: String?,
    val rssi: Int?,
    val band: String?,               // WiFiBand.name()
    val uploadRateBytes: Long,
    val downloadRateBytes: Long,
    val totalUploadBytes: Long,
    val totalDownloadBytes: Long,
    val associatedSubscriberId: String?,
    val isFavorite: Boolean = false,
    val isBlocked: Boolean = false,
    val speedProfileId: String?
) {
    fun toModel() = NetworkDevice(
        id = macAddress,
        ipAddress = ipAddress,
        macAddress = macAddress,
        hostname = hostname,
        customName = customName,
        manufacturer = manufacturer,
        deviceType = runCatching { DeviceType.valueOf(deviceType) }.getOrDefault(DeviceType.UNKNOWN),
        connectionType = runCatching { ConnectionType.valueOf(connectionType) }.getOrDefault(ConnectionType.UNKNOWN),
        isOnline = isOnline,
        isBlocked = isBlocked,
        isFavorite = isFavorite,
        firstSeen = firstSeen,
        lastSeen = lastSeen,
        interface_ = interface_,
        rssi = rssi,
        band = band?.let { runCatching { WiFiBand.valueOf(it) }.getOrNull() },
        uploadRateBytes = uploadRateBytes,
        downloadRateBytes = downloadRateBytes,
        totalUploadBytes = totalUploadBytes,
        totalDownloadBytes = totalDownloadBytes,
        associatedSubscriberId = associatedSubscriberId
    )
}

fun NetworkDevice.toEntity(
    isFavorite: Boolean = false,
    isBlocked: Boolean = false,
    speedProfileId: String? = null
) = NetworkDeviceEntity(
    macAddress = macAddress,
    ipAddress = ipAddress,
    hostname = hostname,
    customName = customName,
    manufacturer = manufacturer,
    deviceType = deviceType.name,
    connectionType = connectionType.name,
    isOnline = isOnline,
    firstSeen = firstSeen,
    lastSeen = lastSeen,
    interface_ = interface_,
    rssi = rssi,
    band = band?.name,
    uploadRateBytes = uploadRateBytes,
    downloadRateBytes = downloadRateBytes,
    totalUploadBytes = totalUploadBytes,
    totalDownloadBytes = totalDownloadBytes,
    associatedSubscriberId = associatedSubscriberId,
    isFavorite = isFavorite,
    isBlocked = isBlocked,
    speedProfileId = speedProfileId
)
