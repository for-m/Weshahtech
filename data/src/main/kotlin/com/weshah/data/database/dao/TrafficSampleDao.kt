package com.weshah.data.database.dao

import androidx.room.*
import com.weshah.data.database.entity.TrafficSampleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrafficSampleDao {

    @Query("SELECT * FROM traffic_samples WHERE macAddress = :mac ORDER BY timestamp DESC LIMIT :limit")
    fun getSamplesForDevice(mac: String, limit: Int = 120): Flow<List<TrafficSampleEntity>>

    @Query("SELECT * FROM traffic_samples WHERE timestamp > :since ORDER BY timestamp ASC")
    suspend fun getSamplesSince(since: Long): List<TrafficSampleEntity>

    @Insert
    suspend fun insert(sample: TrafficSampleEntity)

    @Query("DELETE FROM traffic_samples WHERE timestamp < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)

    @Query("SELECT SUM(totalRxBytes) FROM traffic_samples WHERE macAddress = :mac")
    suspend fun getTotalRxForDevice(mac: String): Long?

    @Query("SELECT SUM(totalTxBytes) FROM traffic_samples WHERE macAddress = :mac")
    suspend fun getTotalTxForDevice(mac: String): Long?
}
