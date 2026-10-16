package com.weshah.data.database.dao

import androidx.room.*
import com.weshah.data.database.entity.SubscriberEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriberDao {

    @Query("SELECT * FROM subscribers ORDER BY name ASC")
    fun getAllSubscribers(): Flow<List<SubscriberEntity>>

    @Query("SELECT * FROM subscribers WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SubscriberEntity?

    @Query("SELECT * FROM subscribers WHERE status = :status")
    fun getByStatus(status: String): Flow<List<SubscriberEntity>>

    @Query("SELECT * FROM subscribers WHERE expiryTimestamp IS NOT NULL AND expiryTimestamp < :now")
    suspend fun getExpiredSubscribers(now: Long): List<SubscriberEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(subscriber: SubscriberEntity)

    @Update
    suspend fun update(subscriber: SubscriberEntity)

    @Delete
    suspend fun delete(subscriber: SubscriberEntity)

    @Query("UPDATE subscribers SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("UPDATE subscribers SET speedProfileId = :profileId WHERE id = :id")
    suspend fun updateSpeedProfile(id: String, profileId: String)

    @Query("UPDATE subscribers SET totalUploadBytes = totalUploadBytes + :up, totalDownloadBytes = totalDownloadBytes + :down WHERE id = :id")
    suspend fun addTraffic(id: String, up: Long, down: Long)

    @Query("SELECT COUNT(*) FROM subscribers WHERE status = 'ACTIVE'")
    fun getActiveCount(): Flow<Int>

    @Query("SELECT * FROM subscribers WHERE name LIKE '%' || :query || '%' OR phone LIKE '%' || :query || '%'")
    fun search(query: String): Flow<List<SubscriberEntity>>
}
