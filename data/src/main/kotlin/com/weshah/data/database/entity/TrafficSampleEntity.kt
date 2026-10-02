package com.weshah.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "traffic_samples",
    indices = [Index("macAddress"), Index("timestamp")]
)
data class TrafficSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val macAddress: String,
    val downloadBps: Long,
    val uploadBps: Long,
    val totalRxBytes: Long,
    val totalTxBytes: Long,
    val timestamp: Long
)
