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
    suspend fun getPortStats(): RouterResult<List<com.weshah.core.models.PortInfo>>
    suspend fun runCableDiagnostics(portId: String): RouterResult<com.weshah.core.models.CableDiagResult>
    suspend fun runHealthCheck(): RouterResult<com.weshah.core.models.NetworkHealthReport>
    suspend fun getMultiWanInterfaces(): RouterResult<List<com.weshah.core.models.WanInterface>>
    suspend fun getVlans(): RouterResult<List<com.weshah.core.models.VlanInfo>>
    suspend fun createVlan(vlan: com.weshah.core.models.VlanInfo): RouterResult<Unit>
    suspend fun updateVlan(vlan: com.weshah.core.models.VlanInfo): RouterResult<Unit>
    suspend fun deleteVlan(vlanId: Int): RouterResult<Unit>
    suspend fun getStaticLeases(): RouterResult<List<DhcpLease>>
}

enum class RouterConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED, ERROR
}
