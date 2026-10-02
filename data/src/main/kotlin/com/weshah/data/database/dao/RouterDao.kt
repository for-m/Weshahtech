package com.weshah.data.database.dao

import androidx.room.*
import com.weshah.data.database.entity.RouterEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RouterDao {

    @Query("SELECT * FROM routers ORDER BY lastConnected DESC")
    fun getAllRouters(): Flow<List<RouterEntity>>

    @Query("SELECT * FROM routers WHERE isAutoConnect = 1 LIMIT 1")
    suspend fun getAutoConnectRouter(): RouterEntity?

    @Query("SELECT * FROM routers WHERE ipAddress = :ip LIMIT 1")
    suspend fun getByIp(ip: String): RouterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(router: RouterEntity)

    @Query("UPDATE routers SET lastConnected = :timestamp WHERE ipAddress = :ip")
    suspend fun updateLastConnected(ip: String, timestamp: Long)

    @Query("UPDATE routers SET model = :model, firmware = :firmware WHERE ipAddress = :ip")
    suspend fun updateInfo(ip: String, model: String?, firmware: String?)

    @Delete
    suspend fun delete(router: RouterEntity)

    @Query("UPDATE routers SET isAutoConnect = 0")
    suspend fun clearAutoConnect()

    @Query("UPDATE routers SET isAutoConnect = 1 WHERE ipAddress = :ip")
    suspend fun setAutoConnect(ip: String)
}
