package com.weshah.domain.repository

import com.weshah.core.models.NetworkDevice
import com.weshah.core.models.NetworkEvent
import com.weshah.router.api.TrafficSample
import kotlinx.coroutines.flow.Flow

interface DeviceRepository {
    fun getAllDevices(): Flow<List<NetworkDevice>>
    fun getOnlineDevices(): Flow<List<NetworkDevice>>
    fun getBlockedDevices(): Flow<List<NetworkDevice>>
    fun getFavoriteDevices(): Flow<List<NetworkDevice>>
    fun getOnlineCount(): Flow<Int>
    fun getBlockedCount(): Flow<Int>
    fun searchDevices(query: String): Flow<List<NetworkDevice>>
    suspend fun getDevice(mac: String): NetworkDevice?
    suspend fun upsertDevice(device: NetworkDevice)
    suspend fun updateCustomName(mac: String, name: String)
    suspend fun setFavorite(mac: String, favorite: Boolean)
    suspend fun markOffline(cutoffMs: Long)
    suspend fun triggerScan()
    fun getTrafficFlow(mac: String): Flow<TrafficSample>
    fun getRecentEvents(): Flow<List<NetworkEvent>>
}
