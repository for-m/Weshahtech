package com.weshah.router.api

import com.weshah.core.models.*
import kotlinx.coroutines.flow.Flow

/**
 * RouterAdapter is the central abstraction that decouples the UI and domain
 * logic from any specific router implementation (OpenWrt, MikroTik, AminLink, etc.).
 *
 * All router-specific implementations must implement this interface.
 * The app never calls OpenWrt APIs directly — it always goes through here.
 *
 * Router implementations run on the router itself, not on the phone.
 * The phone is the management UI; the router enforces all policies.
 */
interface RouterAdapter {

    // ─── Connection & Auth ────────────────────────────────────────────────────

    /**
     * Attempt connection to the router. Returns success/failure with reason.
     * Credentials are passed here but stored securely by the caller (Android Keystore).
     */
    suspend fun connect(config: RouterConnectionConfig): RouterConnectionResult

    /**
     * Test if the router is currently reachable and authenticated.
     */
    suspend fun isConnected(): Boolean

    /**
     * Disconnect and clear session tokens.
     */
    suspend fun disconnect()

    // ─── System Info ──────────────────────────────────────────────────────────

    suspend fun getSystemInfo(): RouterResult<RouterInfo>

    /**
     * Continuous stream of router stats (CPU, RAM, temperature, uptime).
     * Implementations should poll at a reasonable interval (5-10s).
     */
    fun getSystemStats(): Flow<RouterStats>

    // ─── Network Interfaces ───────────────────────────────────────────────────

    suspend fun getInterfaces(): RouterResult<List<com.weshah.core.models.NetworkInterface>>

    suspend fun getWanStatus(): RouterResult<WanStatus>

    // ─── DHCP & Device Discovery ──────────────────────────────────────────────

    /**
     * Get DHCP lease table from the router.
     * This is the most authoritative source of IP↔MAC mappings.
     */
    suspend fun getDhcpLeases(): RouterResult<List<DhcpLease>>

    /**
     * Get all currently connected clients (WiFi + wired).
     */
    suspend fun getConnectedClients(): RouterResult<List<ConnectedClient>>

    /**
     * Get WiFi station information (signal, rates, associated BSS).
     */
    suspend fun getWifiClients(): RouterResult<List<WifiClient>>

    // ─── Traffic Statistics ───────────────────────────────────────────────────

    /**
     * Get per-client traffic statistics.
     * Returns the router's view of cumulative RX/TX bytes per MAC.
     */
    suspend fun getClientTrafficStats(): RouterResult<List<ClientTrafficStats>>

    /**
     * Real-time traffic flow for a specific MAC address.
     * Rate is in bytes/sec. Polls internally; implementation decides interval.
     */
    fun getClientTrafficFlow(macAddress: String): Flow<TrafficSample>

    // ─── Bandwidth Control ────────────────────────────────────────────────────

    /**
     * Apply a speed limit to a device on the router.
     *
     * IMPORTANT: This call writes the policy to the router's persistent config.
     * The limit MUST survive router reboots without the app being open.
     * Implementations use tc/nftables/HTB or router-native QoS — never rely on
     * the Android app staying alive.
     */
    suspend fun setClientSpeedLimit(
        macAddress: String,
        downloadKbps: Long?,   // null = unlimited
        uploadKbps: Long?      // null = unlimited
    ): RouterResult<Unit>

    /**
     * Remove all speed limits for a device and restore unlimited access.
     */
    suspend fun removeClientSpeedLimit(macAddress: String): RouterResult<Unit>

    /**
     * Get current speed limit for a device (as stored on the router).
     */
    suspend fun getClientSpeedLimit(macAddress: String): RouterResult<DeviceSpeedLimit?>

    // ─── Block / Unblock ──────────────────────────────────────────────────────

    /**
     * Block a device's internet access (WAN blocked, LAN access preserved).
     * Policy is persisted on the router.
     *
     * @param expiresAt epoch millis; null = permanent block
     */
    suspend fun blockClient(macAddress: String, expiresAt: Long? = null): RouterResult<Unit>

    /**
     * Remove internet block for a device.
     */
    suspend fun unblockClient(macAddress: String): RouterResult<Unit>

    /**
     * Force-disconnect a device from the network (kick from WiFi association).
     * This is temporary — the device can reconnect.
     */
    suspend fun disconnectClient(macAddress: String): RouterResult<Unit>

    /**
     * Get current block status for a device.
     */
    suspend fun getClientBlockStatus(macAddress: String): RouterResult<BlockStatus?>

    // ─── DHCP Reservations ────────────────────────────────────────────────────

    suspend fun createStaticLease(macAddress: String, ipAddress: String, hostname: String?): RouterResult<Unit>

    suspend fun removeStaticLease(macAddress: String): RouterResult<Unit>

    suspend fun getStaticLeases(): RouterResult<List<DhcpLease>>

    // ─── WiFi ─────────────────────────────────────────────────────────────────

    suspend fun getWifiRadios(): RouterResult<List<WifiRadio>>
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

enum class RouterErrorCode {
    AUTHENTICATION_FAILED,
    CONNECTION_REFUSED,
    CONNECTION_TIMEOUT,
    NOT_SUPPORTED,
    PERMISSION_DENIED,
    INVALID_RESPONSE,
    NETWORK_ERROR,
    ROUTER_ERROR,
    UNKNOWN
}

// ─── Connection Config ────────────────────────────────────────────────────────

data class RouterConnectionConfig(
    val ipAddress: String,
    val port: Int = 443,
    val username: String,
    val useHttps: Boolean = true,
    // Password/token is stored in Android Keystore, referenced by this key
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
    val leaseExpiry: Long?,    // epoch seconds; null = static/permanent
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
    val rssi: Int,            // dBm
    val txRate: Int?,         // Mbps
    val rxRate: Int?,         // Mbps
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
    val uptime: Long,   // seconds
    val timestamp: Long
)

data class BlockStatus(
    val macAddress: String,
    val isBlocked: Boolean,
    val expiresAt: Long?,
    val ruleId: String?
)

data class WifiRadio(
    val name: String,    // e.g. "radio0", "radio1"
    val ssid: String?,
    val bssid: String?,
    val channel: Int?,
    val frequencyMHz: Int?,
    val band: com.weshah.core.models.WiFiBand?,
    val isEnabled: Boolean,
    val txPowerDbm: Int?,
    val standard: String?  // e.g. "802.11ax"
)
