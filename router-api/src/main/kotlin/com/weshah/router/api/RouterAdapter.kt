package com.weshah.router.api

import com.weshah.core.models.*
import kotlinx.coroutines.flow.Flow

/**
 * RouterAdapter — central abstraction decoupling the app from any router implementation.
 *
 * Rules:
 * - The UI never calls router APIs directly.
 * - All persistent policies (speed limits, blocks, schedules) are written to the router.
 * - The phone is the management console; the router enforces all policies.
 * - Every capability-dependent method MUST check RouterCapabilities before executing.
 * - Return RouterResult.Error(NOT_SUPPORTED, ...) if the hardware/firmware cannot do it.
 */
interface RouterAdapter {

    // ─── Connection & Auth ────────────────────────────────────────────────────

    suspend fun connect(config: RouterConnectionConfig): RouterConnectionResult
    suspend fun isConnected(): Boolean
    suspend fun disconnect()

    // ─── Capability Detection ─────────────────────────────────────────────────

    /**
     * Detect what this router hardware/firmware can actually do.
     * Called once after connect; result is cached for the session.
     * Never assume — probe the hardware.
     */
    suspend fun detectCapabilities(): RouterCapabilities

    /**
     * Returns the cached capabilities (from last detectCapabilities call).
     */
    fun getCapabilities(): RouterCapabilities

    // ─── System Info ──────────────────────────────────────────────────────────

    suspend fun getSystemInfo(): RouterResult<RouterInfo>
    fun getSystemStats(): Flow<RouterStats>

    // ─── Network Interfaces ───────────────────────────────────────────────────

    suspend fun getInterfaces(): RouterResult<List<NetworkInterface>>
    suspend fun getWanStatus(): RouterResult<WanStatus>
    suspend fun getMultiWanInterfaces(): RouterResult<List<WanInterface>>

    // ─── DHCP & Device Discovery ──────────────────────────────────────────────

    suspend fun getDhcpLeases(): RouterResult<List<DhcpLease>>
    suspend fun getConnectedClients(): RouterResult<List<ConnectedClient>>
    suspend fun getWifiClients(): RouterResult<List<WifiClient>>

    // DHCP reservations
    suspend fun createStaticLease(macAddress: String, ipAddress: String, hostname: String?): RouterResult<Unit>
    suspend fun removeStaticLease(macAddress: String): RouterResult<Unit>
    suspend fun getStaticLeases(): RouterResult<List<DhcpLease>>

    // ─── Traffic Statistics ───────────────────────────────────────────────────

    suspend fun getClientTrafficStats(): RouterResult<List<ClientTrafficStats>>
    fun getClientTrafficFlow(macAddress: String): Flow<TrafficSample>

    // ─── Bandwidth Control ────────────────────────────────────────────────────

    /**
     * Persist speed limit on the router. Survives reboot.
     * Requires RouterCapabilities.bandwidthControl == true.
     */
    suspend fun setClientSpeedLimit(
        macAddress: String,
        downloadKbps: Long?,
        uploadKbps: Long?
    ): RouterResult<Unit>

    suspend fun removeClientSpeedLimit(macAddress: String): RouterResult<Unit>
    suspend fun getClientSpeedLimit(macAddress: String): RouterResult<DeviceSpeedLimit?>

    // ─── Block / Unblock ──────────────────────────────────────────────────────

    suspend fun blockClient(macAddress: String, expiresAt: Long? = null): RouterResult<Unit>
    suspend fun unblockClient(macAddress: String): RouterResult<Unit>
    suspend fun disconnectClient(macAddress: String): RouterResult<Unit>
    suspend fun getClientBlockStatus(macAddress: String): RouterResult<BlockStatus?>

    // ─── Schedule ─────────────────────────────────────────────────────────────

    suspend fun setClientSchedule(macAddress: String, schedule: AccessSchedule): RouterResult<Unit>
    suspend fun removeClientSchedule(macAddress: String): RouterResult<Unit>

    // ─── Ethernet Ports ───────────────────────────────────────────────────────

    /**
     * Get physical Ethernet port statistics.
     * Requires RouterCapabilities.portStats == true.
     */
    suspend fun getPortStats(): RouterResult<List<PortInfo>>

    /**
     * Get stats for a single port.
     */
    suspend fun getPortStat(portId: String): RouterResult<PortInfo>

    /**
     * Run cable diagnostics on a port.
     * Returns CableDiagResult with supported=false if hardware cannot do TDR.
     * Never returns fake length measurements.
     */
    suspend fun runCableDiagnostics(portId: String): RouterResult<CableDiagResult>

    // ─── WiFi ─────────────────────────────────────────────────────────────────

    suspend fun getWifiRadios(): RouterResult<List<WifiRadio>>
    suspend fun getWifiNetworks(): RouterResult<List<WifiNetwork>>

    // ─── VLAN ─────────────────────────────────────────────────────────────────

    /**
     * Requires RouterCapabilities.vlanManagement == true.
     */
    suspend fun getVlans(): RouterResult<List<VlanInfo>>
    suspend fun createVlan(vlan: VlanInfo): RouterResult<Unit>
    suspend fun updateVlan(vlan: VlanInfo): RouterResult<Unit>
    suspend fun deleteVlan(vlanId: Int): RouterResult<Unit>

    // ─── Topology ─────────────────────────────────────────────────────────────

    /**
     * Get LLDP neighbors if available.
     * Requires RouterCapabilities.lldpNeighbors == true.
     */
    suspend fun getLldpNeighbors(): RouterResult<List<LldpNeighbor>>

    // ─── Health & Diagnostics ─────────────────────────────────────────────────

    /**
     * Run a full health check on the router and network.
     * Returns real measurements only; never synthesized data.
     */
    suspend fun runHealthCheck(): RouterResult<NetworkHealthReport>

    // ─── Configuration Backup ─────────────────────────────────────────────────

    /**
     * Requires RouterCapabilities.configBackup == true.
     */
    suspend fun createConfigBackup(): RouterResult<ByteArray>
    suspend fun restoreConfigBackup(data: ByteArray): RouterResult<Unit>

    // ─── Events ───────────────────────────────────────────────────────────────

    /**
     * Stream of router-side events (port changes, WAN state, client connects).
     * Events are pushed or polled depending on what the router supports.
     */
    fun getEventStream(): Flow<RouterEvent>
}

// ─── Result Types ─────────────────────────────────────────────────────────────

sealed class RouterResult<out T> {
    data class Success<T>(val data: T) : RouterResult<T>()
    data class Error(val code: RouterErrorCode, val message: String, val cause: Exception? = null) : RouterResult<Nothing>()
}

inline fun <T> RouterResult<T>.onSuccess(block: (T) -> Unit): RouterResult<T> {
    if (this is RouterResult.Success) block(data)
    return this
}

inline fun <T> RouterResult<T>.onError(block: (RouterErrorCode, String) -> Unit): RouterResult<T> {
    if (this is RouterResult.Error) block(code, message)
    return this
}

fun <T> RouterResult<T>.getOrNull(): T? = if (this is RouterResult.Success) data else null

fun <T, R> RouterResult<T>.map(transform: (T) -> R): RouterResult<R> = when (this) {
    is RouterResult.Success -> RouterResult.Success(transform(data))
    is RouterResult.Error -> this
}

enum class RouterErrorCode {
    AUTHENTICATION_FAILED,
    CONNECTION_REFUSED,
    CONNECTION_TIMEOUT,
    NOT_SUPPORTED,       // Hardware/firmware cannot do this
    PERMISSION_DENIED,
    INVALID_RESPONSE,
    NETWORK_ERROR,
    ROUTER_ERROR,
    VALIDATION_ERROR,
    UNKNOWN
}

// ─── Connection ───────────────────────────────────────────────────────────────

data class RouterConnectionConfig(
    val ipAddress: String,
    val port: Int = 443,
    val username: String,
    val useHttps: Boolean = true,
    val credentialKeyAlias: String = "router_credential_${ipAddress.replace(".", "_")}"
)

sealed class RouterConnectionResult {
    object Success : RouterConnectionResult()
    data class Failure(val reason: String, val code: RouterErrorCode) : RouterConnectionResult()
}

// ─── Data Classes ─────────────────────────────────────────────────────────────

data class DhcpLease(
    val macAddress: String,
    val ipAddress: String,
    val hostname: String?,
    val leaseExpiry: Long?,
    val isStatic: Boolean
)

data class ConnectedClient(
    val macAddress: String,
    val ipAddress: String?,
    val hostname: String?,
    val interface_: String,
    val isWifi: Boolean
)

data class WifiClient(
    val macAddress: String,
    val ipAddress: String?,
    val ssid: String?,
    val bssid: String?,
    val rssi: Int,
    val txRate: Int?,
    val rxRate: Int?,
    val band: com.weshah.core.models.WiFiBand?
)

data class ClientTrafficStats(
    val macAddress: String,
    val rxBytes: Long,
    val txBytes: Long,
    val timestamp: Long
)

data class TrafficSample(
    val macAddress: String,
    val downloadBps: Long,
    val uploadBps: Long,
    val timestamp: Long
)

data class RouterStats(
    val cpuUsagePercent: Float,
    val ramFreeKb: Long,
    val ramTotalKb: Long,
    val temperatureCelsius: Float?,
    val uptime: Long,
    val timestamp: Long
)

data class BlockStatus(
    val macAddress: String,
    val isBlocked: Boolean,
    val expiresAt: Long?,
    val ruleId: String?
)

data class WifiRadio(
    val name: String,
    val ssid: String?,
    val bssid: String?,
    val channel: Int?,
    val frequencyMHz: Int?,
    val band: com.weshah.core.models.WiFiBand?,
    val isEnabled: Boolean,
    val txPowerDbm: Int?,
    val standard: String?
)

data class WifiNetwork(
    val ssid: String,
    val bssid: String?,
    val band: com.weshah.core.models.WiFiBand?,
    val channel: Int?,
    val securityMode: String?,
    val isEnabled: Boolean,
    val clientCount: Int,
    val radioName: String
)

data class LldpNeighbor(
    val localPort: String,
    val remoteChassisId: String,
    val remotePortId: String,
    val remoteHostname: String?,
    val remoteDescription: String?,
    val remoteCaps: List<String>
)

data class AccessSchedule(
    val macAddress: String,
    val allowedTimeRanges: List<TimeRange>,
    val downloadKbps: Long?,
    val uploadKbps: Long?
)

data class TimeRange(
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
    val daysOfWeek: Set<Int>   // 1=Mon … 7=Sun
)

data class RouterEvent(
    val timestamp: Long,
    val type: RouterEventType,
    val portId: String?,
    val macAddress: String?,
    val detail: String?
)

enum class RouterEventType {
    PORT_UP, PORT_DOWN, PORT_SPEED_CHANGE,
    CLIENT_CONNECTED, CLIENT_DISCONNECTED,
    WAN_UP, WAN_DOWN, WAN_FAILOVER,
    CONFIG_CHANGED,
    ROUTER_REBOOT
}
