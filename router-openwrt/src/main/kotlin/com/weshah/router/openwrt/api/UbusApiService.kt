package com.weshah.router.openwrt.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

/**
 * OpenWrt ubus JSON-RPC 2.0 API service.
 * Endpoint: /ubus
 *
 * Auth flow:
 *   1. POST /ubus with session.login to get auth token
 *   2. Use token in subsequent calls
 */
interface UbusApiService {

    @POST("ubus")
    suspend fun call(@Body request: UbusRequest): Response<UbusResponse>

    @POST("ubus")
    suspend fun callBatch(@Body requests: List<UbusRequest>): Response<List<UbusResponse>>
}

@JsonClass(generateAdapter = true)
data class UbusRequest(
    @Json(name = "jsonrpc") val jsonrpc: String = "2.0",
    @Json(name = "id") val id: Int = 1,
    @Json(name = "method") val method: String,
    @Json(name = "params") val params: List<Any>
) {
    companion object {
        fun call(
            sessionId: String,
            service: String,
            procedure: String,
            args: Map<String, Any> = emptyMap()
        ) = UbusRequest(
            method = "call",
            params = listOf(sessionId, service, procedure, args)
        )

        fun login(username: String, password: String) = UbusRequest(
            method = "call",
            params = listOf(
                "00000000000000000000000000000000", "session", "login",
                mapOf("username" to username, "password" to password)
            )
        )
    }
}

@JsonClass(generateAdapter = true)
data class UbusResponse(
    @Json(name = "id") val id: Int?,
    @Json(name = "result") val result: List<Any?>?,
    @Json(name = "error") val error: UbusError?
) {
    val statusCode: Int get() = (result?.firstOrNull() as? Double)?.toInt() ?: -1
    val isSuccess: Boolean get() = statusCode == 0
    val data: Map<*, *>? get() = result?.getOrNull(1) as? Map<*, *>
}

@JsonClass(generateAdapter = true)
data class UbusError(
    @Json(name = "code") val code: Int,
    @Json(name = "message") val message: String
)

// ─── WESHAH Agent v2 REST API ─────────────────────────────────────────────────
// weshah-agent on OpenWrt, default port 8765.
// All methods require Bearer token from login().

interface WeshahAgentApiService {

    // ── Auth ──────────────────────────────────────────────────────────────────

    @POST("api/v1/auth/login")
    suspend fun login(@Body request: AgentLoginRequest): Response<AgentAuthResponse>

    // ── System ────────────────────────────────────────────────────────────────

    @GET("api/v1/status")
    suspend fun getStatus(
        @Header("Authorization") authToken: String
    ): Response<AgentRouterStatus>

    @GET("api/v1/capabilities")
    suspend fun getCapabilities(
        @Header("Authorization") authToken: String
    ): Response<AgentCapabilitiesResponse>

    // ── Network interfaces ────────────────────────────────────────────────────

    @GET("api/v1/interfaces")
    suspend fun getInterfaces(
        @Header("Authorization") authToken: String
    ): Response<AgentInterfaceList>

    @GET("api/v1/multi-wan")
    suspend fun getMultiWan(
        @Header("Authorization") authToken: String
    ): Response<AgentMultiWanResponse>

    // ── Devices / DHCP ────────────────────────────────────────────────────────

    @GET("api/v1/devices")
    suspend fun getDevices(
        @Header("Authorization") authToken: String
    ): Response<List<AgentDevice>>

    @GET("api/v1/devices/{mac}/traffic")
    suspend fun getDeviceTraffic(
        @Header("Authorization") authToken: String,
        @Path("mac") mac: String
    ): Response<AgentDeviceTraffic>

    @POST("api/v1/devices/{mac}/speed")
    suspend fun setDeviceSpeed(
        @Header("Authorization") authToken: String,
        @Path("mac") mac: String,
        @Body request: AgentSpeedRequest
    ): Response<AgentOperationResult>

    @DELETE("api/v1/devices/{mac}/speed")
    suspend fun removeDeviceSpeed(
        @Header("Authorization") authToken: String,
        @Path("mac") mac: String
    ): Response<AgentOperationResult>

    @POST("api/v1/devices/{mac}/block")
    suspend fun blockDevice(
        @Header("Authorization") authToken: String,
        @Path("mac") mac: String,
        @Body request: AgentBlockRequest
    ): Response<AgentOperationResult>

    @POST("api/v1/devices/{mac}/unblock")
    suspend fun unblockDevice(
        @Header("Authorization") authToken: String,
        @Path("mac") mac: String
    ): Response<AgentOperationResult>

    @POST("api/v1/devices/{mac}/disconnect")
    suspend fun disconnectDevice(
        @Header("Authorization") authToken: String,
        @Path("mac") mac: String
    ): Response<AgentOperationResult>

    @POST("api/v1/devices/{mac}/schedule")
    suspend fun setDeviceSchedule(
        @Header("Authorization") authToken: String,
        @Path("mac") mac: String,
        @Body request: AgentScheduleRequest
    ): Response<AgentOperationResult>

    @DELETE("api/v1/devices/{mac}/schedule")
    suspend fun removeDeviceSchedule(
        @Header("Authorization") authToken: String,
        @Path("mac") mac: String
    ): Response<AgentOperationResult>

    // ── DHCP static leases ────────────────────────────────────────────────────

    @GET("api/v1/dhcp/static")
    suspend fun getStaticLeases(
        @Header("Authorization") authToken: String
    ): Response<AgentStaticLeaseList>

    @POST("api/v1/dhcp/static")
    suspend fun createStaticLease(
        @Header("Authorization") authToken: String,
        @Body request: AgentStaticLeaseRequest
    ): Response<AgentOperationResult>

    @DELETE("api/v1/dhcp/static/{mac}")
    suspend fun removeStaticLease(
        @Header("Authorization") authToken: String,
        @Path("mac") mac: String
    ): Response<AgentOperationResult>

    // ── Ethernet ports ────────────────────────────────────────────────────────

    @GET("api/v1/ports")
    suspend fun getPorts(
        @Header("Authorization") authToken: String
    ): Response<AgentPortList>

    @GET("api/v1/ports/{portId}/diagnostics")
    suspend fun getCableDiagnostics(
        @Header("Authorization") authToken: String,
        @Path("portId") portId: String
    ): Response<AgentCableDiagResult>

    // ── WiFi ──────────────────────────────────────────────────────────────────

    @GET("api/v1/wifi")
    suspend fun getWifi(
        @Header("Authorization") authToken: String
    ): Response<AgentWifiResponse>

    @GET("api/v1/wifi/clients")
    suspend fun getWifiClients(
        @Header("Authorization") authToken: String
    ): Response<AgentWifiClientList>

    // ── VLAN ──────────────────────────────────────────────────────────────────

    @GET("api/v1/vlans")
    suspend fun getVlans(
        @Header("Authorization") authToken: String
    ): Response<AgentVlanList>

    @POST("api/v1/vlans")
    suspend fun createVlan(
        @Header("Authorization") authToken: String,
        @Body request: AgentVlanRequest
    ): Response<AgentOperationResult>

    @PUT("api/v1/vlans/{vlanId}")
    suspend fun updateVlan(
        @Header("Authorization") authToken: String,
        @Path("vlanId") vlanId: Int,
        @Body request: AgentVlanRequest
    ): Response<AgentOperationResult>

    @DELETE("api/v1/vlans/{vlanId}")
    suspend fun deleteVlan(
        @Header("Authorization") authToken: String,
        @Path("vlanId") vlanId: Int
    ): Response<AgentOperationResult>

    // ── Topology ──────────────────────────────────────────────────────────────

    @GET("api/v1/lldp")
    suspend fun getLldpNeighbors(
        @Header("Authorization") authToken: String
    ): Response<AgentLldpResponse>

    // ── Health ────────────────────────────────────────────────────────────────

    @GET("api/v1/health")
    suspend fun getHealthCheck(
        @Header("Authorization") authToken: String
    ): Response<AgentHealthReport>

    // ── Config backup ─────────────────────────────────────────────────────────

    @POST("api/v1/backup")
    suspend fun createBackup(
        @Header("Authorization") authToken: String
    ): Response<ResponseBody>
}

// ─── Auth ─────────────────────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class AgentLoginRequest(
    @Json(name = "username") val username: String,
    @Json(name = "password") val password: String
)

@JsonClass(generateAdapter = true)
data class AgentAuthResponse(
    @Json(name = "token") val token: String,
    @Json(name = "expires") val expires: Long
)

// ─── System status ────────────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class AgentRouterStatus(
    @Json(name = "model") val model: String?,
    @Json(name = "firmware") val firmware: String?,
    @Json(name = "kernel") val kernel: String?,
    @Json(name = "hostname") val hostname: String?,
    @Json(name = "uptimeSeconds") val uptimeSeconds: Long,
    @Json(name = "cpuPercent") val cpuPercent: Float,
    @Json(name = "ramTotalKb") val ramTotalKb: Long,
    @Json(name = "ramUsedKb") val ramUsedKb: Long,
    @Json(name = "temperatureCelsius") val temperatureCelsius: Float?,
    @Json(name = "load1m") val load1m: Float,
    @Json(name = "load5m") val load5m: Float,
    @Json(name = "load15m") val load15m: Float,
    @Json(name = "wanState") val wanState: String,   // "up" | "down"
    @Json(name = "wanIp") val wanIp: String?
)

// ─── Capabilities ─────────────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class AgentCapabilitiesResponse(
    @Json(name = "agentVersion") val agentVersion: String?,
    @Json(name = "bandwidthControl") val bandwidthControl: Boolean,
    @Json(name = "perClientTraffic") val perClientTraffic: Boolean,
    @Json(name = "portStats") val portStats: Boolean,
    @Json(name = "cableDiagnosticsTdr") val cableDiagnosticsTdr: Boolean,
    @Json(name = "cableDiagnosticsBasic") val cableDiagnosticsBasic: Boolean,
    @Json(name = "wifiRssiPerClient") val wifiRssiPerClient: Boolean,
    @Json(name = "vlanManagement") val vlanManagement: Boolean,
    @Json(name = "multiWan") val multiWan: Boolean,
    @Json(name = "lldpNeighbors") val lldpNeighbors: Boolean,
    @Json(name = "configBackup") val configBackup: Boolean,
    @Json(name = "hotspot") val hotspot: Boolean,
    @Json(name = "temperature") val temperature: Boolean,
    @Json(name = "phyDriver") val phyDriver: String?   // matches PhyDriver enum name
)

// ─── Devices ──────────────────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class AgentDevice(
    @Json(name = "mac") val mac: String,
    @Json(name = "ip") val ip: String?,
    @Json(name = "hostname") val hostname: String?,
    @Json(name = "interface") val interface_: String?,
    @Json(name = "isWifi") val isWifi: Boolean,
    @Json(name = "isOnline") val isOnline: Boolean,
    @Json(name = "rssi") val rssi: Int?,
    @Json(name = "band") val band: String?,           // "BAND_2_4GHZ" | "BAND_5GHZ" | "BAND_6GHZ"
    @Json(name = "rxBytes") val rxBytes: Long,
    @Json(name = "txBytes") val txBytes: Long,
    @Json(name = "rxRateBps") val rxRateBps: Long?,
    @Json(name = "txRateBps") val txRateBps: Long?,
    @Json(name = "isBlocked") val isBlocked: Boolean,
    @Json(name = "speedDownKbps") val speedDownKbps: Long?,
    @Json(name = "speedUpKbps") val speedUpKbps: Long?
)

@JsonClass(generateAdapter = true)
data class AgentDeviceTraffic(
    @Json(name = "mac") val mac: String,
    @Json(name = "rxBytes") val rxBytes: Long,
    @Json(name = "txBytes") val txBytes: Long,
    @Json(name = "rxRateBps") val rxRateBps: Long,
    @Json(name = "txRateBps") val txRateBps: Long,
    @Json(name = "timestamp") val timestamp: Long
)

// ─── Speed / Block ────────────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class AgentSpeedRequest(
    @Json(name = "downloadKbps") val downloadKbps: Long?,
    @Json(name = "uploadKbps") val uploadKbps: Long?
)

@JsonClass(generateAdapter = true)
data class AgentBlockRequest(
    @Json(name = "expiresAt") val expiresAt: Long? = null
)

@JsonClass(generateAdapter = true)
data class AgentOperationResult(
    @Json(name = "success") val success: Boolean,
    @Json(name = "error") val error: String?
)

// ─── Schedule ─────────────────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class AgentScheduleRequest(
    @Json(name = "timeRanges") val timeRanges: List<AgentTimeRange>,
    @Json(name = "downloadKbps") val downloadKbps: Long?,
    @Json(name = "uploadKbps") val uploadKbps: Long?
)

@JsonClass(generateAdapter = true)
data class AgentTimeRange(
    @Json(name = "startHour") val startHour: Int,
    @Json(name = "startMinute") val startMinute: Int,
    @Json(name = "endHour") val endHour: Int,
    @Json(name = "endMinute") val endMinute: Int,
    @Json(name = "daysOfWeek") val daysOfWeek: List<Int>   // 1=Mon … 7=Sun
)

// ─── Interfaces ───────────────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class AgentInterfaceList(
    @Json(name = "interfaces") val interfaces: List<AgentInterfaceData>
)

@JsonClass(generateAdapter = true)
data class AgentInterfaceData(
    @Json(name = "name") val name: String,
    @Json(name = "ipAddress") val ipAddress: String?,
    @Json(name = "macAddress") val macAddress: String?,
    @Json(name = "isUp") val isUp: Boolean,
    @Json(name = "rxBytes") val rxBytes: Long,
    @Json(name = "txBytes") val txBytes: Long,
    @Json(name = "type") val type: String   // matches InterfaceType enum name
)

// ─── Multi-WAN ────────────────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class AgentMultiWanResponse(
    @Json(name = "wans") val wans: List<AgentWanData>
)

@JsonClass(generateAdapter = true)
data class AgentWanData(
    @Json(name = "id") val id: String,
    @Json(name = "name") val name: String,
    @Json(name = "interface") val interface_: String,
    @Json(name = "isActive") val isActive: Boolean,
    @Json(name = "isHealthy") val isHealthy: Boolean,
    @Json(name = "ipAddress") val ipAddress: String?,
    @Json(name = "gateway") val gateway: String?,
    @Json(name = "rxBytes") val rxBytes: Long,
    @Json(name = "txBytes") val txBytes: Long,
    @Json(name = "rxRateBps") val rxRateBps: Long,
    @Json(name = "txRateBps") val txRateBps: Long,
    @Json(name = "latencyMs") val latencyMs: Float?,
    @Json(name = "packetLossPercent") val packetLossPercent: Float?,
    @Json(name = "weight") val weight: Int,
    @Json(name = "priority") val priority: Int
)

// ─── DHCP static leases ───────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class AgentStaticLeaseList(
    @Json(name = "leases") val leases: List<AgentStaticLeaseData>
)

@JsonClass(generateAdapter = true)
data class AgentStaticLeaseData(
    @Json(name = "mac") val mac: String,
    @Json(name = "ip") val ip: String,
    @Json(name = "hostname") val hostname: String?
)

@JsonClass(generateAdapter = true)
data class AgentStaticLeaseRequest(
    @Json(name = "mac") val mac: String,
    @Json(name = "ip") val ip: String,
    @Json(name = "hostname") val hostname: String?
)

// ─── Ethernet ports ───────────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class AgentPortList(
    @Json(name = "ports") val ports: List<AgentPortData>
)

@JsonClass(generateAdapter = true)
data class AgentPortData(
    @Json(name = "portId") val portId: String,
    @Json(name = "label") val label: String,
    @Json(name = "isUp") val isUp: Boolean,
    @Json(name = "speedMbps") val speedMbps: Int?,
    @Json(name = "duplexFull") val duplexFull: Boolean?,
    @Json(name = "autoNegotiation") val autoNegotiation: Boolean?,
    @Json(name = "rxBytes") val rxBytes: Long,
    @Json(name = "txBytes") val txBytes: Long,
    @Json(name = "rxErrors") val rxErrors: Long,
    @Json(name = "txErrors") val txErrors: Long,
    @Json(name = "rxDropped") val rxDropped: Long,
    @Json(name = "txDropped") val txDropped: Long,
    @Json(name = "crcErrors") val crcErrors: Long,
    @Json(name = "linkFlaps") val linkFlaps: Int,
    @Json(name = "connectedMac") val connectedMac: String?,
    @Json(name = "connectedDevice") val connectedDevice: String?,
    @Json(name = "vlanId") val vlanId: Int?,
    @Json(name = "portType") val portType: String   // matches PortType enum name
)

// ─── Cable diagnostics ────────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class AgentCableDiagResult(
    @Json(name = "portId") val portId: String,
    @Json(name = "cableStatus") val cableStatus: String,   // matches CableStatus enum name
    @Json(name = "estimatedLengthMeters") val estimatedLengthMeters: Float?,
    @Json(name = "pairs") val pairs: List<AgentCablePairResult>,
    @Json(name = "linkSpeedMbps") val linkSpeedMbps: Int?,
    @Json(name = "duplexFull") val duplexFull: Boolean?,
    @Json(name = "crcErrors") val crcErrors: Long
)

@JsonClass(generateAdapter = true)
data class AgentCablePairResult(
    @Json(name = "pair") val pair: String,           // e.g. "1-2", "3-6"
    @Json(name = "status") val status: String,        // matches PairStatus enum name
    @Json(name = "faultDistanceMeters") val faultDistanceMeters: Float?
)

// ─── WiFi ─────────────────────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class AgentWifiResponse(
    @Json(name = "radios") val radios: List<AgentWifiRadio>,
    @Json(name = "networks") val networks: List<AgentWifiNetwork>
)

@JsonClass(generateAdapter = true)
data class AgentWifiRadio(
    @Json(name = "name") val name: String,
    @Json(name = "ssid") val ssid: String?,
    @Json(name = "bssid") val bssid: String?,
    @Json(name = "channel") val channel: Int?,
    @Json(name = "frequencyMHz") val frequencyMHz: Int?,
    @Json(name = "band") val band: String?,           // "BAND_2_4GHZ" | "BAND_5GHZ" | "BAND_6GHZ"
    @Json(name = "isEnabled") val isEnabled: Boolean,
    @Json(name = "txPowerDbm") val txPowerDbm: Int?,
    @Json(name = "standard") val standard: String?    // "802.11n", "802.11ac", etc.
)

@JsonClass(generateAdapter = true)
data class AgentWifiNetwork(
    @Json(name = "ssid") val ssid: String,
    @Json(name = "bssid") val bssid: String?,
    @Json(name = "band") val band: String?,
    @Json(name = "channel") val channel: Int?,
    @Json(name = "security") val security: String?,
    @Json(name = "isEnabled") val isEnabled: Boolean,
    @Json(name = "clientCount") val clientCount: Int,
    @Json(name = "radio") val radio: String          // radio name this network runs on
)

@JsonClass(generateAdapter = true)
data class AgentWifiClientList(
    @Json(name = "clients") val clients: List<AgentWifiClientData>
)

@JsonClass(generateAdapter = true)
data class AgentWifiClientData(
    @Json(name = "mac") val mac: String,
    @Json(name = "ip") val ip: String?,
    @Json(name = "ssid") val ssid: String?,
    @Json(name = "bssid") val bssid: String?,
    @Json(name = "rssi") val rssi: Int,
    @Json(name = "txRateMbps") val txRateMbps: Int?,
    @Json(name = "rxRateMbps") val rxRateMbps: Int?,
    @Json(name = "band") val band: String?
)

// ─── VLAN ─────────────────────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class AgentVlanList(
    @Json(name = "vlans") val vlans: List<AgentVlanData>
)

@JsonClass(generateAdapter = true)
data class AgentVlanData(
    @Json(name = "vlanId") val vlanId: Int,
    @Json(name = "name") val name: String,
    @Json(name = "gateway") val gateway: String?,
    @Json(name = "subnet") val subnet: String?,
    @Json(name = "dhcpEnabled") val dhcpEnabled: Boolean,
    @Json(name = "internetAccess") val internetAccess: Boolean,
    @Json(name = "clientIsolation") val clientIsolation: Boolean,
    @Json(name = "ssid") val ssid: String?,
    @Json(name = "taggedPorts") val taggedPorts: List<String>,
    @Json(name = "untaggedPorts") val untaggedPorts: List<String>
)

@JsonClass(generateAdapter = true)
data class AgentVlanRequest(
    @Json(name = "vlanId") val vlanId: Int,
    @Json(name = "name") val name: String,
    @Json(name = "gateway") val gateway: String?,
    @Json(name = "subnet") val subnet: String?,
    @Json(name = "dhcpEnabled") val dhcpEnabled: Boolean,
    @Json(name = "internetAccess") val internetAccess: Boolean,
    @Json(name = "clientIsolation") val clientIsolation: Boolean,
    @Json(name = "ssid") val ssid: String?,
    @Json(name = "taggedPorts") val taggedPorts: List<String>,
    @Json(name = "untaggedPorts") val untaggedPorts: List<String>
)

// ─── LLDP ─────────────────────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class AgentLldpResponse(
    @Json(name = "neighbors") val neighbors: List<AgentLldpNeighbor>
)

@JsonClass(generateAdapter = true)
data class AgentLldpNeighbor(
    @Json(name = "localPort") val localPort: String,
    @Json(name = "remoteChassisId") val remoteChassisId: String,
    @Json(name = "remotePortId") val remotePortId: String,
    @Json(name = "remoteHostname") val remoteHostname: String?,
    @Json(name = "remoteDescription") val remoteDescription: String?,
    @Json(name = "remoteCaps") val remoteCaps: List<String>
)

// ─── Health check ─────────────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class AgentHealthReport(
    @Json(name = "gatewayLatencyMs") val gatewayLatencyMs: Float?,
    @Json(name = "internetLatencyMs") val internetLatencyMs: Float?,
    @Json(name = "packetLossPercent") val packetLossPercent: Float?,
    @Json(name = "jitterMs") val jitterMs: Float?,
    @Json(name = "dnsLatencyMs") val dnsLatencyMs: Float?,
    @Json(name = "wanState") val wanState: String,      // matches WanHealthState enum name
    @Json(name = "cpuPercent") val cpuPercent: Float?,
    @Json(name = "ramFreeKb") val ramFreeKb: Long?,
    @Json(name = "ramTotalKb") val ramTotalKb: Long?,
    @Json(name = "temperatureCelsius") val temperatureCelsius: Float?,
    @Json(name = "interfaceErrors") val interfaceErrors: Map<String, Long>,
    @Json(name = "activeClientCount") val activeClientCount: Int
)
