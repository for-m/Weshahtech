package com.weshah.router.openwrt.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * OpenWrt ubus JSON-RPC 2.0 API service.
 * Endpoint: /ubus  (or /cgi-bin/luci/rpc/uci for UCI)
 *
 * Auth flow:
 *   1. POST /ubus with session.login to get auth token
 *   2. Use token in subsequent calls
 *
 * All calls use the same endpoint with different params.
 */
interface UbusApiService {

    @POST("ubus")
    suspend fun call(@Body request: UbusRequest): UbusResponse

    @POST("ubus")
    suspend fun callBatch(@Body requests: List<UbusRequest>): List<UbusResponse>
}

/**
 * ubus JSON-RPC 2.0 request
 */
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
            params = listOf("00000000000000000000000000000000", "session", "login",
                mapOf("username" to username, "password" to password))
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

// ─── WESHAH Agent REST API (weshah-agent on OpenWrt) ──────────────────────────
// When weshah-agent is installed, we prefer this cleaner REST interface

interface WeshahAgentApiService {

    @POST("api/auth/login")
    suspend fun login(@Body request: AgentLoginRequest): AgentAuthResponse

    @retrofit2.http.GET("api/router/status")
    suspend fun getRouterStatus(): AgentRouterStatus

    @retrofit2.http.GET("api/devices")
    suspend fun getDevices(): AgentDeviceList

    @retrofit2.http.GET("api/devices/{mac}/traffic")
    suspend fun getDeviceTraffic(@retrofit2.http.Path("mac") mac: String): AgentDeviceTraffic

    @POST("api/devices/{mac}/speed")
    suspend fun setDeviceSpeed(
        @retrofit2.http.Path("mac") mac: String,
        @Body request: AgentSpeedRequest
    ): AgentOperationResult

    @POST("api/devices/{mac}/block")
    suspend fun blockDevice(
        @retrofit2.http.Path("mac") mac: String,
        @Body request: AgentBlockRequest
    ): AgentOperationResult

    @POST("api/devices/{mac}/unblock")
    suspend fun unblockDevice(@retrofit2.http.Path("mac") mac: String): AgentOperationResult

    @POST("api/devices/{mac}/disconnect")
    suspend fun disconnectDevice(@retrofit2.http.Path("mac") mac: String): AgentOperationResult
}

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

@JsonClass(generateAdapter = true)
data class AgentRouterStatus(
    @Json(name = "model") val model: String?,
    @Json(name = "firmware") val firmware: String?,
    @Json(name = "kernel") val kernel: String?,
    @Json(name = "hostname") val hostname: String?,
    @Json(name = "uptime") val uptime: Long,
    @Json(name = "cpu_percent") val cpuPercent: Float,
    @Json(name = "ram_total_kb") val ramTotalKb: Long,
    @Json(name = "ram_free_kb") val ramFreeKb: Long,
    @Json(name = "load_1m") val load1m: Float,
    @Json(name = "load_5m") val load5m: Float,
    @Json(name = "load_15m") val load15m: Float,
    @Json(name = "temperature") val temperature: Float?,
    @Json(name = "wan_ip") val wanIp: String?,
    @Json(name = "wan_connected") val wanConnected: Boolean
)

@JsonClass(generateAdapter = true)
data class AgentDeviceList(
    @Json(name = "devices") val devices: List<AgentDevice>
)

@JsonClass(generateAdapter = true)
data class AgentDevice(
    @Json(name = "mac") val mac: String,
    @Json(name = "ip") val ip: String?,
    @Json(name = "hostname") val hostname: String?,
    @Json(name = "interface") val interface_: String?,
    @Json(name = "is_wifi") val isWifi: Boolean,
    @Json(name = "rssi") val rssi: Int?,
    @Json(name = "band") val band: String?,
    @Json(name = "rx_bytes") val rxBytes: Long,
    @Json(name = "tx_bytes") val txBytes: Long,
    @Json(name = "rx_rate") val rxRate: Long?,   // bytes/sec
    @Json(name = "tx_rate") val txRate: Long?,
    @Json(name = "is_blocked") val isBlocked: Boolean,
    @Json(name = "speed_down_kbps") val speedDownKbps: Long?,
    @Json(name = "speed_up_kbps") val speedUpKbps: Long?
)

@JsonClass(generateAdapter = true)
data class AgentDeviceTraffic(
    @Json(name = "mac") val mac: String,
    @Json(name = "rx_bytes") val rxBytes: Long,
    @Json(name = "tx_bytes") val txBytes: Long,
    @Json(name = "rx_rate") val rxRate: Long,
    @Json(name = "tx_rate") val txRate: Long,
    @Json(name = "timestamp") val timestamp: Long
)

@JsonClass(generateAdapter = true)
data class AgentSpeedRequest(
    @Json(name = "download_kbps") val downloadKbps: Long?,
    @Json(name = "upload_kbps") val uploadKbps: Long?
)

@JsonClass(generateAdapter = true)
data class AgentBlockRequest(
    @Json(name = "expires_at") val expiresAt: Long? = null
)

@JsonClass(generateAdapter = true)
data class AgentOperationResult(
    @Json(name = "success") val success: Boolean,
    @Json(name = "message") val message: String?
)
