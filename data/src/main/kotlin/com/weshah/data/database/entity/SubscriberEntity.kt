package com.weshah.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.weshah.core.models.Subscriber
import com.weshah.core.models.SubscriberStatus
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.squareup.moshi.Types

@Entity(tableName = "subscribers")
data class SubscriberEntity(
    @PrimaryKey val id: String,
    val name: String,
    val phone: String?,
    val notes: String?,
    val macAddressesJson: String,    // JSON array
    val speedProfileId: String,
    val expiryTimestamp: Long?,
    val status: String,
    val createdAt: Long,
    val totalUploadBytes: Long,
    val totalDownloadBytes: Long
) {
    fun toModel(): Subscriber {
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val type = Types.newParameterizedType(List::class.java, String::class.java)
        val adapter = moshi.adapter<List<String>>(type)
        val macs = runCatching { adapter.fromJson(macAddressesJson) ?: emptyList() }.getOrDefault(emptyList())
        return Subscriber(
            id = id, name = name, phone = phone, notes = notes,
            macAddresses = macs, speedProfileId = speedProfileId,
            expiryTimestamp = expiryTimestamp,
            status = runCatching { SubscriberStatus.valueOf(status) }.getOrDefault(SubscriberStatus.ACTIVE),
            createdAt = createdAt,
            totalUploadBytes = totalUploadBytes,
            totalDownloadBytes = totalDownloadBytes
        )
    }
}

fun Subscriber.toEntity(): SubscriberEntity {
    val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    val type = Types.newParameterizedType(List::class.java, String::class.java)
    val adapter = moshi.adapter<List<String>>(type)
    return SubscriberEntity(
        id = id, name = name, phone = phone, notes = notes,
        macAddressesJson = adapter.toJson(macAddresses),
        speedProfileId = speedProfileId,
        expiryTimestamp = expiryTimestamp,
        status = status.name,
        createdAt = createdAt,
        totalUploadBytes = totalUploadBytes,
        totalDownloadBytes = totalDownloadBytes
    )
}
