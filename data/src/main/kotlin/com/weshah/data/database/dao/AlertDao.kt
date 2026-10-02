package com.weshah.data.database.dao

import androidx.room.*
import com.weshah.data.database.entity.AlertEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AlertDao {

    @Query("SELECT * FROM alerts WHERE isResolved = 0 ORDER BY timestamp DESC")
    fun observeActive(): Flow<List<AlertEntity>>

    @Query("SELECT COUNT(*) FROM alerts WHERE isResolved = 0 AND isRead = 0")
    fun observeUnreadCount(): Flow<Int>

    @Query("SELECT * FROM alerts ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 100): List<AlertEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(alert: AlertEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(alerts: List<AlertEntity>)

    @Query("UPDATE alerts SET isRead = 1 WHERE id = :id")
    suspend fun markRead(id: String)

    @Query("UPDATE alerts SET isRead = 1 WHERE isResolved = 0")
    suspend fun markAllRead()

    @Query("UPDATE alerts SET isResolved = 1 WHERE id = :id")
    suspend fun resolve(id: String)

    @Query("DELETE FROM alerts WHERE timestamp < :olderThanMs")
    suspend fun deleteOlderThan(olderThanMs: Long)

    @Query("SELECT COUNT(*) FROM alerts WHERE isResolved = 0")
    suspend fun activeCount(): Int
}
