package com.weshah.data.repository

import com.weshah.core.models.RouterInfo
import com.weshah.data.database.dao.RouterDao
import com.weshah.data.database.entity.RouterEntity
import com.weshah.domain.repository.RouterConnectionState
import com.weshah.domain.repository.RouterRepository
import com.weshah.router.api.*
import com.weshah.router.openwrt.OpenWrtAdapter
import com.weshah.router.openwrt.RouterCredentialManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RouterRepositoryImpl @Inject constructor(
    private val adapter: OpenWrtAdapter,
    private val credentialManager: RouterCredentialManager,
    private val routerDao: RouterDao
) : RouterRepository {

    private val _connectionState = MutableStateFlow(RouterConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<RouterConnectionState> = _connectionState.asStateFlow()

    private val _routerInfo = MutableStateFlow<RouterInfo?>(null)
    override val routerInfo: StateFlow<RouterInfo?> = _routerInfo.asStateFlow()

    override suspend fun connectToRouter(
        ipAddress: String, port: Int, username: String,
        password: String, useHttps: Boolean
    ): RouterConnectionResult {
        _connectionState.value = RouterConnectionState.CONNECTING
        try {
            val keyAlias = "router_credential_${ipAddress.replace(".", "_")}"
            credentialManager.storeCredential(keyAlias, password)

            val config = RouterConnectionConfig(
                ipAddress = ipAddress, port = port,
                username = username, useHttps = useHttps,
                credentialKeyAlias = keyAlias
            )
            val result = adapter.connect(config)
            if (result is RouterConnectionResult.Success) {
                _connectionState.value = RouterConnectionState.CONNECTED
                routerDao.upsert(RouterEntity(
                    ipAddress = ipAddress,
                    hostname = null, model = null, firmware = null,
                    routerType = "OPENWRT",
                    credentialKeyAlias = keyAlias,
                    port = port, useHttps = useHttps, username = username,
                    isAutoConnect = true,
                    lastConnected = System.currentTimeMillis(),
                    addedAt = System.currentTimeMillis()
                ))

                // Fetch initial info
                adapter.getSystemInfo().onSuccess { info ->
                    _routerInfo.value = info.copy(ipAddress = ipAddress)
                    routerDao.updateInfo(ipAddress, info.model, info.firmware)
                }
            } else {
                _connectionState.value = RouterConnectionState.ERROR
            }
            return result
        } catch (e: Exception) {
            _connectionState.value = RouterConnectionState.ERROR
            Timber.e(e, "connectToRouter failed")
            return RouterConnectionResult.Failure(e.message ?: "Unknown", RouterErrorCode.UNKNOWN)
        }
    }

    override suspend fun disconnect() {
        adapter.disconnect()
        _connectionState.value = RouterConnectionState.DISCONNECTED
        _routerInfo.value = null
    }

    override fun getSystemStats(): Flow<RouterStats> = adapter.getSystemStats()

    override suspend fun getWanStatus() = adapter.getWanStatus()

    override suspend fun getConnectedClients() = adapter.getConnectedClients()

    override suspend fun getDhcpLeases() = adapter.getDhcpLeases()

    override suspend fun getWifiClients() = adapter.getWifiClients()

    override suspend fun setSpeedLimit(mac: String, downloadKbps: Long?, uploadKbps: Long?) =
        adapter.setClientSpeedLimit(mac, downloadKbps, uploadKbps)

    override suspend fun removeSpeedLimit(mac: String) =
        adapter.removeClientSpeedLimit(mac)

    override suspend fun blockClient(mac: String, expiresAt: Long?) =
        adapter.blockClient(mac, expiresAt)

    override suspend fun unblockClient(mac: String) = adapter.unblockClient(mac)

    override suspend fun disconnectClient(mac: String) = adapter.disconnectClient(mac)

    override suspend fun createStaticLease(mac: String, ip: String, hostname: String?) =
        adapter.createStaticLease(mac, ip, hostname)

    override suspend fun getWifiRadios() = adapter.getWifiRadios()

    override suspend fun getPortStats() = adapter.getPortStats()

    override suspend fun runCableDiagnostics(portId: String) = adapter.runCableDiagnostics(portId)

    override suspend fun runHealthCheck() = adapter.runHealthCheck()

    override suspend fun getMultiWanInterfaces() = adapter.getMultiWanInterfaces()

    override suspend fun getVlans() = adapter.getVlans()

    override suspend fun createVlan(vlan: com.weshah.core.models.VlanInfo) = adapter.createVlan(vlan)

    override suspend fun updateVlan(vlan: com.weshah.core.models.VlanInfo) = adapter.updateVlan(vlan)

    override suspend fun deleteVlan(vlanId: Int) = adapter.deleteVlan(vlanId)

    override suspend fun getStaticLeases() = adapter.getStaticLeases()

    fun getTrafficFlow(mac: String): Flow<TrafficSample> = adapter.getClientTrafficFlow(mac)

    suspend fun isConnected() = adapter.isConnected()
}
