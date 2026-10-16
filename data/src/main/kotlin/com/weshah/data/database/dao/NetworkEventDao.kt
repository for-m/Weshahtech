package com.weshah.data.database.dao

import androidx.room.*
import com.weshah.data.database.entity.NetworkEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NetworkEventDao {

    @Query("SELECT * FROM network_events ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentEvents(limit: Int = 100): Flow<List<NetworkEventEntity>>

    @Query("SELECT * FROM network_events WHERE macAddress = :mac ORDER BY timestamp DESC LIMIT :limit")
    fun getEventsForDevice(mac: String, limit: Int = 50): Flow<List<NetworkEventEntity>>

    @Insert
    suspend fun insert(event: NetworkEventEntity): Long

    @Query("DELETE FROM network_events WHERE timestamp < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)

    @Query("SELECT COUNT(*) FROM network_events WHERE timestamp > :since")
    suspend fun countSince(since: Long): Int

    @Query("DELETE FROM network_events WHERE id NOT IN (SELECT id FROM network_events ORDER BY timestamp DESC LIMIT 1000)")
    suspend fun trimToLatest1000()
}
