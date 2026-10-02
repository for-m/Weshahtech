package com.weshah.domain.repository

import com.weshah.core.models.RouterInfo
import com.weshah.router.api.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface RouterRepository {
    val connectionState: StateFlow<RouterConnectionState>
    val routerInfo: StateFlow<RouterInfo?>
    suspend fun connectToRouter(ipAddress: String, port: Int, username: String, password: String, useHttps: Boolean): RouterConnectionResult
    suspend fun disconnect()
    fun getSystemStats(): Flow<RouterStats>
    suspend fun getWanStatus(): RouterResult<WanStatus>
    suspend fun getConnectedClients(): RouterResult<List<ConnectedClient>>
    suspend fun getDhcpLeases(): RouterResult<List<DhcpLease>>
    suspend fun getWifiClients(): RouterResult<List<WifiClient>>
    suspend fun setSpeedLimit(mac: String, downloadKbps: Long?, uploadKbps: Long?): RouterResult<Unit>
    suspend fun removeSpeedLimit(mac: String): RouterResult<Unit>
    suspend fun blockClient(mac: String, expiresAt: Long?): RouterResult<Unit>
    suspend fun unblockClient(mac: String): RouterResult<Unit>
    suspend fun disconnectClient(mac: String): RouterResult<Unit>
    suspend fun createStaticLease(mac: String, ip: String, hostname: String?): RouterResult<Unit>
    suspend fun getWifiRadios(): RouterResult<List<WifiRadio>>
}

enum class RouterConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED, ERROR
}
