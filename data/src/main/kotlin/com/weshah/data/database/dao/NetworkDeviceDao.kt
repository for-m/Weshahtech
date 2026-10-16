package com.weshah.data.database.dao

import androidx.room.*
import com.weshah.data.database.entity.NetworkDeviceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NetworkDeviceDao {

    @Query("SELECT * FROM network_devices ORDER BY isOnline DESC, lastSeen DESC")
    fun getAllDevices(): Flow<List<NetworkDeviceEntity>>

    @Query("SELECT * FROM network_devices WHERE isOnline = 1 ORDER BY lastSeen DESC")
    fun getOnlineDevices(): Flow<List<NetworkDeviceEntity>>

    @Query("SELECT * FROM network_devices WHERE macAddress = :mac LIMIT 1")
    suspend fun getByMac(mac: String): NetworkDeviceEntity?

    @Query("SELECT * FROM network_devices WHERE ipAddress = :ip LIMIT 1")
    suspend fun getByIp(ip: String): NetworkDeviceEntity?

    @Query("SELECT * FROM network_devices WHERE associatedSubscriberId = :subscriberId")
    fun getDevicesForSubscriber(subscriberId: String): Flow<List<NetworkDeviceEntity>>

    @Query("SELECT * FROM network_devices WHERE isFavorite = 1")
    fun getFavoriteDevices(): Flow<List<NetworkDeviceEntity>>

    @Query("SELECT * FROM network_devices WHERE isBlocked = 1")
    fun getBlockedDevices(): Flow<List<NetworkDeviceEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(device: NetworkDeviceEntity): Long

    @Update
    suspend fun update(device: NetworkDeviceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: NetworkDeviceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(devices: List<NetworkDeviceEntity>)

    @Query("UPDATE network_devices SET isOnline = 0 WHERE lastSeen < :cutoffMs")
    suspend fun markOfflineBeforeTimestamp(cutoffMs: Long)

    @Query("UPDATE network_devices SET isOnline = :online, lastSeen = :timestamp WHERE macAddress = :mac")
    suspend fun updateOnlineStatus(mac: String, online: Boolean, timestamp: Long)

    @Query("UPDATE network_devices SET customName = :name WHERE macAddress = :mac")
    suspend fun updateCustomName(mac: String, name: String)

    @Query("UPDATE network_devices SET isFavorite = :favorite WHERE macAddress = :mac")
    suspend fun updateFavorite(mac: String, favorite: Boolean)

    @Query("UPDATE network_devices SET isBlocked = :blocked WHERE macAddress = :mac")
    suspend fun updateBlocked(mac: String, blocked: Boolean)

    @Query("UPDATE network_devices SET associatedSubscriberId = :subscriberId WHERE macAddress = :mac")
    suspend fun assignSubscriber(mac: String, subscriberId: String?)

    @Query("UPDATE network_devices SET downloadRateBytes = :down, uploadRateBytes = :up WHERE macAddress = :mac")
    suspend fun updateRates(mac: String, down: Long, up: Long)

    @Query("SELECT COUNT(*) FROM network_devices WHERE isOnline = 1")
    fun getOnlineCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM network_devices WHERE isBlocked = 1")
    fun getBlockedCount(): Flow<Int>

    @Query("DELETE FROM network_devices WHERE macAddress = :mac")
    suspend fun delete(mac: String)

    @Query("SELECT * FROM network_devices WHERE customName LIKE '%' || :query || '%' OR hostname LIKE '%' || :query || '%' OR ipAddress LIKE '%' || :query || '%' OR macAddress LIKE '%' || :query || '%'")
    fun search(query: String): Flow<List<NetworkDeviceEntity>>
}
