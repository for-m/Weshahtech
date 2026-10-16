package com.weshah.core.models

data class Subscriber(
    val id: String,
    val name: String,
    val phone: String?,
    val notes: String?,
    val macAddresses: List<String>,
    val speedProfileId: String,
    val expiryTimestamp: Long?,    // null = no expiry
    val status: SubscriberStatus,
    val createdAt: Long,
    val totalUploadBytes: Long,
    val totalDownloadBytes: Long
)

enum class SubscriberStatus {
    ACTIVE, BLOCKED, EXPIRED, SUSPENDED
}
