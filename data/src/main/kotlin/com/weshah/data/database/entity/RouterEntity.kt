package com.weshah.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "routers")
data class RouterEntity(
    @PrimaryKey val ipAddress: String,
    val hostname: String?,
    val model: String?,
    val firmware: String?,
    val routerType: String,
    val credentialKeyAlias: String,
    val port: Int,
    val useHttps: Boolean,
    val username: String,
    val isAutoConnect: Boolean,
    val lastConnected: Long?,
    val addedAt: Long
)
