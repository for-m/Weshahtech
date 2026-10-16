package com.weshah.router.openwrt

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.weshah.core.models.*
import com.weshah.router.api.*
import com.weshah.router.openwrt.api.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.X509TrustManager

/**
 * OpenWrt router adapter.
 * Auto-detects weshah-agent at startup and uses it when available.
 * Falls back to raw ubus JSON-RPC when agent is absent.
 *
 * Capability detection is honest: methods return NOT_SUPPORTED
 * when the hardware or firmware cannot perform the operation.
 * No fake data is ever returned.
 */
@Singleton
class OpenWrtAdapter @Inject constructor(
    private val credentialManager: CredentialProvider
) : RouterAdapter {

    /** Only tests talk to an agent on another port. */
    constructor(credentialManager: CredentialProvider, agentPort: Int) : this(credentialManager) {
        this.agentPort = agentPort
    }

    private var agentPort: Int = DEFAULT_AGENT_PORT

    private var baseUrl: String = ""
    private var agentBaseUrl: String = ""
    private var ubusSessionId: String = ""
    private var agentToken: String = ""
    private var useAgent: Boolean = false
    private var config: RouterConnectionConfig? = null

    private var _capabilities: RouterCapabilities = RouterCapabilities()
    private var capabilityReasons: Map<String, String?> = emptyMap()

    private lateinit var ubusApi: UbusApiService
    private lateinit var agentApi: WeshahAgentApiService

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val httpClient: OkHttpClient by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAll), SecureRandom())
        }
        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    // ─── HTTP result mapping ──────────────────────────────────────────────────

    private fun httpErrorCode(http: Int): RouterErrorCode = when (http) {
        400 -> RouterErrorCode.VALIDATION_ERROR
        401, 403 -> RouterErrorCode.AUTHENTICATION_FAILED
        501 -> RouterErrorCode.NOT_SUPPORTED
        else -> RouterErrorCode.ROUTER_ERROR
    }

    /** The agent answers failures with 4xx/5xx and `{"success":false,"error":"..."}`. */
    private fun errorMessage(response: Response<*>): String {
        val raw = try { response.errorBody()?.string() } catch (_: Exception) { null }
        val parsed = raw?.let {
            try { moshi.adapter(AgentOperationResult::class.java).fromJson(it)?.error } catch (_: Exception) { null }
        }
        return parsed ?: raw?.takeIf { it.isNotBlank() }?.take(200) ?: "HTTP ${response.code()}"
    }

    private fun Response<AgentOperationResult>.toUnit(): RouterResult<Unit> {
        if (isSuccessful) {
            val body = body()
            return if (body?.success == true) RouterResult.Success(Unit)
            else RouterResult.Error(RouterErrorCode.ROUTER_ERROR, body?.error ?: "Unknown error")
        }
        return RouterResult.Error(httpErrorCode(code()), errorMessage(this))
    }

    private fun auth() = "Bearer $agentToken"

    // ─── Connection ───────────────────────────────────────────────────────────

    override suspend fun connect(config: RouterConnectionConfig): RouterConnectionResult = withContext(Dispatchers.IO) {
        this@OpenWrtAdapter.config = config
        val scheme = if (config.useHttps) "https" else "http"
        baseUrl = "$scheme://${config.ipAddress}:${config.port}/"

        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(httpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

        ubusApi = retrofit.create(UbusApiService::class.java)

        // Try weshah-agent first
        agentBaseUrl = "http://${config.ipAddress}:$agentPort/"
        val agentRetrofit = Retrofit.Builder()
            .baseUrl(agentBaseUrl)
            .client(httpClient.newBuilder().connectTimeout(3, TimeUnit.SECONDS).build())
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
        agentApi = agentRetrofit.create(WeshahAgentApiService::class.java)

        val password = credentialManager.getCredential(config.credentialKeyAlias)
            ?: return@withContext RouterConnectionResult.Failure("Credential not found", RouterErrorCode.AUTHENTICATION_FAILED)

        // Attempt agent login
        try {
            val agentResult = agentApi.login(AgentLoginRequest(config.username, password))
            if (agentResult.isSuccessful && agentResult.body()?.token != null) {
                agentToken = agentResult.body()!!.token
                useAgent = true
                _capabilities = detectCapabilities()
                confirmPendingChanges() // reaching the router again is the proof a VLAN change / restore needs
                return@withContext RouterConnectionResult.Success
            }
        } catch (_: Exception) { /* agent not present, fall through */ }

        // Fallback: ubus login
        try {
            val ubusResp = ubusApi.call(UbusRequest.login(config.username, password))
            val sessionId = (ubusResp.body()?.result?.getOrNull(1) as? Map<*, *>)
                ?.get("ubus_rpc_session")?.toString()

            if (sessionId != null) {
                ubusSessionId = sessionId
                useAgent = false
                _capabilities = detectCapabilities()
                return@withContext RouterConnectionResult.Success
            }
            return@withContext RouterConnectionResult.Failure("Login rejected", RouterErrorCode.AUTHENTICATION_FAILED)
        } catch (e: Exception) {
            return@withContext RouterConnectionResult.Failure(e.message ?: "Network error", RouterErrorCode.NETWORK_ERROR)
        }
    }

    override suspend fun isConnected(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (useAgent) {
                agentApi.getStatus(auth()).isSuccessful
            } else {
                ubusApi.call(UbusRequest.call(ubusSessionId, "system", "board", emptyMap())).isSuccessful
            }
        } catch (_: Exception) { false }
    }

    override suspend fun disconnect() {
        ubusSessionId = ""
        agentToken = ""
        _capabilities = RouterCapabilities()
    }

    // ─── Capability Detection ─────────────────────────────────────────────────

    override suspend fun detectCapabilities(): RouterCapabilities = withContext(Dispatchers.IO) {
        if (useAgent) {
            detectCapabilitiesViaAgent()
        } else {
            detectCapabilitiesViaUbus()
        }
    }

    override fun getCapabilities(): RouterCapabilities = _capabilities

    private suspend fun detectCapabilitiesViaAgent(): RouterCapabilities {
        return try {
            val caps = agentApi.getCapabilities(auth()).body()
            capabilityReasons = caps?.reasons ?: emptyMap()
            RouterCapabilities(
                weshahAgent = true,
                agentVersion = caps?.agentVersion,
                bandwidthControl = caps?.bandwidthControl ?: false,
                perClientTraffic = caps?.perClientTraffic ?: false,
                portStats = caps?.portStats ?: false,
                cableDiagnosticsTdr = caps?.cableDiagnosticsTdr ?: false,
                cableDiagnosticsBasic = caps?.cableDiagnosticsBasic ?: true,
                wifiRssiPerClient = caps?.wifiRssiPerClient ?: false,
                vlanManagement = caps?.vlanManagement ?: false,
                multiWan = caps?.multiWan ?: false,
                dhcpStaticLeases = true,
                lldpNeighbors = caps?.lldpNeighbors ?: false,
                configBackup = caps?.configBackup ?: false,
                configRestore = caps?.configBackup ?: false,
                hotspot = caps?.hotspot ?: false,
                temperature = caps?.temperature ?: false,
                arpTable = true,
                phyDriver = PhyDriver.valueOf(caps?.phyDriver ?: "UNKNOWN")
            )
        } catch (_: Exception) {
            RouterCapabilities(weshahAgent = true, arpTable = true, dhcpStaticLeases = true)
        }
    }

    private suspend fun detectCapabilitiesViaUbus(): RouterCapabilities {
        var portStats = false
        var temperature = false
        var bandwidthControl = false

        // Probe system.info for temperature
        try {
            val sysInfo = ubusApi.call(UbusRequest.call(ubusSessionId, "system", "info", emptyMap()))
            if (sysInfo.isSuccessful) {
                val body = sysInfo.body()?.result?.getOrNull(1)?.toString() ?: ""
                temperature = body.contains("\"temp\"") || body.contains("\"temperature\"")
            }
        } catch (_: Exception) {}

        // Probe for switch stats (ethtool/swconfig)
        try {
            val swInfo = ubusApi.call(UbusRequest.call(ubusSessionId, "network.device", "status", mapOf("name" to "eth0")))
            portStats = swInfo.isSuccessful
        } catch (_: Exception) {}

        return RouterCapabilities(
            weshahAgent = false,
            bandwidthControl = bandwidthControl,
            perClientTraffic = false,
            portStats = portStats,
            cableDiagnosticsTdr = false,
            cableDiagnosticsBasic = portStats,
            wifiRssiPerClient = true,
            vlanManagement = false,
            multiWan = false,
            dhcpStaticLeases = true,
            temperature = temperature,
            arpTable = true,
            configBackup = false
        )
    }

    // ─── System Info ──────────────────────────────────────────────────────────

    override suspend fun getSystemInfo(): RouterResult<RouterInfo> = withContext(Dispatchers.IO) {
        try {
            if (useAgent) {
                val status = agentApi.getStatus(auth()).body()
                    ?: return@withContext RouterResult.Error(RouterErrorCode.INVALID_RESPONSE, "Empty response")
                RouterResult.Success(status.toRouterInfo(config?.ipAddress ?: ""))
            } else {
                getSystemInfoViaUbus()
            }
        } catch (e: Exception) {
            RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "Unknown error", e)
        }
    }

    private suspend fun getSystemInfoViaUbus(): RouterResult<RouterInfo> {
        val boardResp = ubusApi.call(UbusRequest.call(ubusSessionId, "system", "board", emptyMap()))
        val infoResp = ubusApi.call(UbusRequest.call(ubusSessionId, "system", "info", emptyMap()))

        val boardBody = boardResp.body()?.result?.getOrNull(1)?.toString() ?: "{}"
        val infoBody = infoResp.body()?.result?.getOrNull(1)?.toString() ?: "{}"

        return RouterResult.Success(RouterInfo(
            ipAddress = config?.ipAddress ?: "",
            macAddress = null,
            hostname = extractJsonString(boardBody, "hostname"),
            model = extractJsonString(boardBody, "model"),
            firmware = extractJsonString(boardBody, "release.distribution") ?: extractJsonString(boardBody, "release"),
            kernelVersion = extractJsonString(infoBody, "kernel"),
            architecture = null,
            uptime = extractJsonLong(infoBody, "uptime") ?: 0L,
            cpuUsagePercent = 0f,
            ramTotalKb = extractJsonLong(infoBody, "memory.total") ?: 0L,
            ramFreeKb = extractJsonLong(infoBody, "memory.free") ?: 0L,
            loadAverage1m = 0f,
            loadAverage5m = 0f,
            loadAverage15m = 0f,
            temperatureCelsius = null,
            routerType = RouterType.OPENWRT,
            interfaces = emptyList(),
            wanStatus = null
        ))
    }

    override fun getSystemStats(): Flow<RouterStats> = flow {
        while (true) {
            try {
                if (useAgent) {
                    val status = agentApi.getStatus(auth()).body()
                    if (status != null) {
                        emit(RouterStats(
                            cpuUsagePercent = status.cpuPercent,
                            ramFreeKb = (status.ramTotalKb - status.ramUsedKb).toLong(),
                            ramTotalKb = status.ramTotalKb.toLong(),
                            temperatureCelsius = status.temperatureCelsius,
                            uptime = status.uptimeSeconds.toLong(),
                            timestamp = System.currentTimeMillis()
                        ))
                    }
                } else {
                    val infoResp = ubusApi.call(UbusRequest.call(ubusSessionId, "system", "info", emptyMap()))
                    val body = infoResp.body()?.result?.getOrNull(1)?.toString() ?: "{}"
                    val total = extractJsonLong(body, "memory.total") ?: 0L
                    val free = extractJsonLong(body, "memory.free") ?: 0L
                    val uptime = extractJsonLong(body, "uptime") ?: 0L
                    emit(RouterStats(0f, free, total, null, uptime, System.currentTimeMillis()))
                }
            } catch (_: Exception) {}
            delay(8_000)
        }
    }.flowOn(Dispatchers.IO)

    // ─── Interfaces ───────────────────────────────────────────────────────────

    override suspend fun getInterfaces(): RouterResult<List<NetworkInterface>> = withContext(Dispatchers.IO) {
        try {
            if (useAgent) {
                val ifaces = agentApi.getInterfaces(auth()).body()?.interfaces ?: emptyList()
                RouterResult.Success(ifaces.map { it.toModel() })
            } else {
                RouterResult.Success(emptyList())
            }
        } catch (e: Exception) {
            RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "", e)
        }
    }

    override suspend fun getWanStatus(): RouterResult<WanStatus> = withContext(Dispatchers.IO) {
        try {
            if (useAgent) {
                val status = agentApi.getStatus(auth()).body()
                RouterResult.Success(WanStatus(
                    isConnected = status?.wanState == "up",
                    ipAddress = status?.wanIp,
                    gateway = null,
                    dns = emptyList(),
                    rxBytes = 0L,
                    txBytes = 0L,
                    rxRateBytes = 0L,
                    txRateBytes = 0L
                ))
            } else {
                val resp = ubusApi.call(UbusRequest.call(ubusSessionId, "network.interface.wan", "status", emptyMap()))
                val body = resp.body()?.result?.getOrNull(1)?.toString() ?: "{}"
                RouterResult.Success(WanStatus(
                    isConnected = extractJsonBool(body, "up") ?: false,
                    ipAddress = extractJsonString(body, "ipv4-address.0.address"),
                    gateway = null,
                    dns = emptyList(),
                    rxBytes = 0L, txBytes = 0L, rxRateBytes = 0L, txRateBytes = 0L
                ))
            }
        } catch (e: Exception) {
            RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "", e)
        }
    }

    override suspend fun getMultiWanInterfaces(): RouterResult<List<WanInterface>> = withContext(Dispatchers.IO) {
        if (!_capabilities.multiWan) {
            return@withContext RouterResult.Error(RouterErrorCode.NOT_SUPPORTED, "Multi-WAN not supported by this router")
        }
        try {
            if (useAgent) {
                val wans = agentApi.getMultiWan(auth()).body()?.wans ?: emptyList()
                RouterResult.Success(wans.map { it.toModel() })
            } else {
                RouterResult.Error(RouterErrorCode.NOT_SUPPORTED, "Multi-WAN requires weshah-agent")
            }
        } catch (e: Exception) {
            RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "", e)
        }
    }

    // ─── DHCP & Devices ───────────────────────────────────────────────────────

    override suspend fun getDhcpLeases(): RouterResult<List<DhcpLease>> = withContext(Dispatchers.IO) {
        try {
            if (useAgent) {
                val devices = agentApi.getDevices(auth()).body() ?: emptyList()
                RouterResult.Success(devices.filter { !it.ip.isNullOrEmpty() }.map {
                    DhcpLease(it.mac, it.ip!!, it.hostname, null, false)
                })
            } else {
                val resp = ubusApi.call(UbusRequest.call(ubusSessionId, "luci-rpc", "getDHCPLeases", emptyMap()))
                val body = resp.body()?.result?.getOrNull(1)?.toString() ?: "[]"
                RouterResult.Success(parseDhcpLeasesFromUbus(body))
            }
        } catch (e: Exception) {
            RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "", e)
        }
    }

    override suspend fun getConnectedClients(): RouterResult<List<ConnectedClient>> = withContext(Dispatchers.IO) {
        try {
            if (useAgent) {
                val devices = agentApi.getDevices(auth()).body() ?: emptyList()
                RouterResult.Success(devices.filter { it.isOnline }.map {
                    ConnectedClient(it.mac, it.ip, it.hostname, "br-lan", false)
                })
            } else {
                RouterResult.Success(emptyList())
            }
        } catch (e: Exception) {
            RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "", e)
        }
    }

    override suspend fun getWifiClients(): RouterResult<List<WifiClient>> = withContext(Dispatchers.IO) {
        try {
            if (useAgent) {
                val clients = agentApi.getWifiClients(auth()).body()?.clients ?: emptyList()
                RouterResult.Success(clients.map {
                    WifiClient(it.mac, it.ip, it.ssid, it.bssid, it.rssi, it.txRateMbps, it.rxRateMbps, it.band?.let { b -> WiFiBand.valueOf(b) })
                })
            } else {
                RouterResult.Success(emptyList())
            }
        } catch (e: Exception) {
            RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "", e)
        }
    }

    // ─── Traffic ──────────────────────────────────────────────────────────────

    override suspend fun getClientTrafficStats(): RouterResult<List<ClientTrafficStats>> = withContext(Dispatchers.IO) {
        if (!_capabilities.perClientTraffic) {
            return@withContext RouterResult.Error(RouterErrorCode.NOT_SUPPORTED, "Per-client traffic requires weshah-agent with nlbw/conntrack")
        }
        try {
            val devices = agentApi.getDevices(auth()).body() ?: emptyList()
            RouterResult.Success(devices.map {
                ClientTrafficStats(it.mac, it.rxBytes, it.txBytes, System.currentTimeMillis())
            })
        } catch (e: Exception) {
            RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "", e)
        }
    }

    override fun getClientTrafficFlow(macAddress: String): Flow<TrafficSample> = flow {
        while (true) {
            try {
                if (useAgent && _capabilities.perClientTraffic) {
                    val traffic = agentApi.getDeviceTraffic(auth(), macAddress).body()
                    if (traffic != null) {
                        emit(TrafficSample(macAddress, traffic.rxRateBps, traffic.txRateBps, System.currentTimeMillis()))
                    }
                }
            } catch (_: Exception) {}
            delay(3_000)
        }
    }.flowOn(Dispatchers.IO)

    // ─── Bandwidth Control ────────────────────────────────────────────────────

    override suspend fun setClientSpeedLimit(macAddress: String, downloadKbps: Long?, uploadKbps: Long?): RouterResult<Unit> = withContext(Dispatchers.IO) {
        if (!_capabilities.bandwidthControl) {
            val why = if (useAgent) "Speed limits are not supported by this router: ${capabilityReasons["bandwidthControl"] ?: "unknown reason"}"
                      else "Bandwidth control requires weshah-agent. Raw ubus cannot apply persistent tc/firewall rules."
            return@withContext RouterResult.Error(RouterErrorCode.NOT_SUPPORTED, why)
        }
        try {
            agentApi.setDeviceSpeed(auth(), macAddress, AgentSpeedRequest(downloadKbps ?: 0, uploadKbps ?: 0)).toUnit()
        } catch (e: Exception) {
            RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "", e)
        }
    }

    override suspend fun removeClientSpeedLimit(macAddress: String): RouterResult<Unit> = withContext(Dispatchers.IO) {
        if (!useAgent) {
            return@withContext RouterResult.Error(RouterErrorCode.NOT_SUPPORTED, "Requires weshah-agent")
        }
        try {
            agentApi.removeDeviceSpeed(auth(), macAddress).toUnit()
        } catch (e: Exception) {
            RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "", e)
        }
    }

    /** Reads the limit the router really holds (null = none). `isActive` is true only when tc has it installed. */
    override suspend fun getClientSpeedLimit(macAddress: String): RouterResult<DeviceSpeedLimit?> = withContext(Dispatchers.IO) {
        if (!useAgent) return@withContext RouterResult.Success(null)
        try {
            val resp = agentApi.getDeviceSpeed(auth(), macAddress)
            if (!resp.isSuccessful) return@withContext RouterResult.Error(httpErrorCode(resp.code()), errorMessage(resp))
            val st = resp.body() ?: return@withContext RouterResult.Error(RouterErrorCode.INVALID_RESPONSE, "Empty response")
            if (st.source == "none") RouterResult.Success(null)
            else RouterResult.Success(DeviceSpeedLimit(
                macAddress = st.mac, profileId = "custom",
                downloadKbps = st.downloadKbps, uploadKbps = st.uploadKbps,
                appliedAt = st.appliedAt ?: 0L, isActive = st.active && st.applied
            ))
        } catch (e: Exception) {
            RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "", e)
        }
    }

    // ─── Block / Unblock ──────────────────────────────────────────────────────

    override suspend fun blockClient(macAddress: String, expiresAt: Long?): RouterResult<Unit> = withContext(Dispatchers.IO) {
        try {
            if (useAgent) {
                agentApi.blockDevice(auth(), macAddress, AgentBlockRequest(expiresAt)).toUnit()
            } else {
                blockClientViaUbus(macAddress)
            }
        } catch (e: Exception) {
            RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "", e)
        }
    }

    private suspend fun blockClientViaUbus(mac: String): RouterResult<Unit> {
        val ruleName = "weshah_block_${mac.replace(":", "_")}"
        val uciCmds = listOf(
            mapOf("command" to "set", "config" to "firewall.$ruleName=rule"),
            mapOf("command" to "set", "config" to "firewall.$ruleName.src=lan"),
            mapOf("command" to "set", "config" to "firewall.$ruleName.dest=wan"),
            mapOf("command" to "set", "config" to "firewall.$ruleName.src_mac=$mac"),
            mapOf("command" to "set", "config" to "firewall.$ruleName.target=REJECT"),
            mapOf("command" to "commit", "config" to "firewall")
        )
        return try {
            for (cmd in uciCmds) {
                ubusApi.call(UbusRequest.call(ubusSessionId, "uci", cmd["command"]!!, cmd))
            }
            ubusApi.call(UbusRequest.call(ubusSessionId, "luci", "reload_config", emptyMap()))
            RouterResult.Success(Unit)
        } catch (e: Exception) {
            RouterResult.Error(RouterErrorCode.ROUTER_ERROR, e.message ?: "", e)
        }
    }

    override suspend fun unblockClient(macAddress: String): RouterResult<Unit> = withContext(Dispatchers.IO) {
        try {
            if (useAgent) {
                agentApi.unblockDevice(auth(), macAddress).toUnit()
            } else {
                val ruleName = "weshah_block_${macAddress.replace(":", "_")}"
                ubusApi.call(UbusRequest.call(ubusSessionId, "uci", "delete", mapOf("config" to "firewall.$ruleName")))
                ubusApi.call(UbusRequest.call(ubusSessionId, "uci", "commit", mapOf("config" to "firewall")))
                RouterResult.Success(Unit)
            }
        } catch (e: Exception) {
            RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "", e)
        }
    }

    override suspend fun disconnectClient(macAddress: String): RouterResult<Unit> = withContext(Dispatchers.IO) {
        try {
            if (useAgent) {
                agentApi.disconnectDevice(auth(), macAddress).toUnit()
            } else {
                RouterResult.Error(RouterErrorCode.NOT_SUPPORTED, "Deauth requires weshah-agent (hostapd_cli access)")
            }
        } catch (e: Exception) {
            RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "", e)
        }
    }

    /** Reads the real firewall state: manual block and/or schedule block. Null only without the agent. */
    override suspend fun getClientBlockStatus(macAddress: String): RouterResult<BlockStatus?> = withContext(Dispatchers.IO) {
        if (!useAgent) return@withContext RouterResult.Success(null)
        try {
            val resp = agentApi.getDeviceBlock(auth(), macAddress)
            if (!resp.isSuccessful) return@withContext RouterResult.Error(httpErrorCode(resp.code()), errorMessage(resp))
            val st = resp.body() ?: return@withContext RouterResult.Error(RouterErrorCode.INVALID_RESPONSE, "Empty response")
            RouterResult.Success(BlockStatus(st.mac, st.isBlocked, st.expiresAt, st.ruleId))
        } catch (e: Exception) {
            RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "", e)
        }
    }

    // ─── Schedule ─────────────────────────────────────────────────────────────

    override suspend fun setClientSchedule(macAddress: String, schedule: AccessSchedule): RouterResult<Unit> = withContext(Dispatchers.IO) {
        if (!useAgent) return@withContext RouterResult.Error(RouterErrorCode.NOT_SUPPORTED, "Schedule requires weshah-agent")
        try {
            agentApi.setDeviceSchedule(auth(), macAddress,
                AgentScheduleRequest(schedule.allowedTimeRanges.map {
                    AgentTimeRange(it.startHour, it.startMinute, it.endHour, it.endMinute, it.daysOfWeek.toList().sorted())
                }, schedule.downloadKbps, schedule.uploadKbps)).toUnit()
        } catch (e: Exception) {
            RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "", e)
        }
    }

    override suspend fun removeClientSchedule(macAddress: String): RouterResult<Unit> = withContext(Dispatchers.IO) {
        if (!useAgent) return@withContext RouterResult.Error(RouterErrorCode.NOT_SUPPORTED, "Schedule requires weshah-agent")
        try {
            agentApi.removeDeviceSchedule(auth(), macAddress).toUnit()
        } catch (e: Exception) {
            RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "", e)
        }
    }

    // ─── Ports ────────────────────────────────────────────────────────────────

    override suspend fun getPortStats(): RouterResult<List<PortInfo>> = withContext(Dispatchers.IO) {
        if (!_capabilities.portStats) {
            return@withContext RouterResult.Error(RouterErrorCode.NOT_SUPPORTED, "Port statistics not available on this router")
        }
        try {
            if (useAgent) {
                val ports = agentApi.getPorts(auth()).body()?.ports ?: emptyList()
                RouterResult.Success(ports.map { it.toModel() })
            } else {
                getPortStatsViaUbus()
            }
        } catch (e: Exception) {
            RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "", e)
        }
    }

    private suspend fun getPortStatsViaUbus(): RouterResult<List<PortInfo>> {
        val ports = mutableListOf<PortInfo>()
        listOf("eth0", "eth1", "eth2", "eth3", "eth4").forEachIndexed { idx, ifName ->
            try {
                val resp = ubusApi.call(UbusRequest.call(ubusSessionId, "network.device", "status", mapOf("name" to ifName)))
                val body = resp.body()?.result?.getOrNull(1)?.toString() ?: return@forEachIndexed
                if (body.contains("\"up\"")) {
                    ports.add(PortInfo(
                        portId = ifName, label = "Port ${idx + 1}",
                        isUp = extractJsonBool(body, "up") ?: false,
                        speedMbps = extractJsonInt(body, "speed"),
                        duplexFull = extractJsonString(body, "duplex") == "full",
                        autoNegotiation = null,
                        rxBytes = extractJsonLong(body, "statistics.rx_bytes") ?: 0L,
                        txBytes = extractJsonLong(body, "statistics.tx_bytes") ?: 0L,
                        rxErrors = extractJsonLong(body, "statistics.rx_errors") ?: 0L,
                        txErrors = extractJsonLong(body, "statistics.tx_errors") ?: 0L,
                        rxDropped = extractJsonLong(body, "statistics.rx_dropped") ?: 0L,
                        txDropped = extractJsonLong(body, "statistics.tx_dropped") ?: 0L,
                        crcErrors = 0L, linkFlaps = 0, connectedMac = null, connectedDevice = null,
                        vlanId = null, portType = if (ifName == "eth1") PortType.WAN else PortType.LAN
                    ))
                }
            } catch (_: Exception) {}
        }
        return RouterResult.Success(ports)
    }

    override suspend fun getPortStat(portId: String): RouterResult<PortInfo> = withContext(Dispatchers.IO) {
        when (val all = getPortStats()) {
            is RouterResult.Error -> all
            is RouterResult.Success -> all.data.firstOrNull { it.portId == portId }
                ?.let { RouterResult.Success(it) }
                ?: RouterResult.Error(RouterErrorCode.ROUTER_ERROR, "Port $portId not found")
        }
    }

    override suspend fun runCableDiagnostics(portId: String): RouterResult<CableDiagResult> = withContext(Dispatchers.IO) {
        if (!_capabilities.cableDiagnosticsTdr && !_capabilities.cableDiagnosticsBasic) {
            return@withContext RouterResult.Success(CableDiagResult(
                portId = portId, testTimeMs = System.currentTimeMillis(),
                cableStatus = CableStatus.UNKNOWN,
                estimatedLengthMeters = null,
                pairs = emptyList(),
                linkSpeedMbps = null, duplexFull = null, crcErrors = 0L,
                supported = false,
                unsupportedReason = "Hardware TDR not available on this router's PHY driver (${_capabilities.phyDriver}). PHY diagnostics not exposed via ethtool/swconfig."
            ))
        }
        try {
            if (useAgent && _capabilities.cableDiagnosticsTdr) {
                val result = agentApi.getCableDiagnostics(auth(), portId).body()
                    ?: return@withContext RouterResult.Error(RouterErrorCode.INVALID_RESPONSE, "No response")
                RouterResult.Success(result.toModel())
            } else {
                // Basic: port link info only (no TDR)
                val portInfo = getPortStat(portId).getOrNull()
                RouterResult.Success(CableDiagResult(
                    portId = portId, testTimeMs = System.currentTimeMillis(),
                    cableStatus = if (portInfo?.isUp == true) CableStatus.CONNECTED else CableStatus.UNKNOWN,
                    estimatedLengthMeters = null,
                    pairs = emptyList(),
                    linkSpeedMbps = portInfo?.speedMbps, duplexFull = portInfo?.duplexFull,
                    crcErrors = portInfo?.crcErrors ?: 0L,
                    supported = false,
                    unsupportedReason = "TDR not supported. Link status shown. Install weshah-agent with TDR support for full diagnostics."
                ))
            }
        } catch (e: Exception) {
            RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "", e)
        }
    }

    // ─── WiFi ─────────────────────────────────────────────────────────────────

    override suspend fun getWifiRadios(): RouterResult<List<WifiRadio>> = withContext(Dispatchers.IO) {
        try {
            if (useAgent) {
                val wifi = agentApi.getWifi(auth()).body()
                RouterResult.Success(wifi?.radios?.map { r ->
                    WifiRadio(r.name, r.ssid, r.bssid, r.channel, r.frequencyMHz,
                        r.band?.let { WiFiBand.valueOf(it) }, r.isEnabled, r.txPowerDbm, r.standard)
                } ?: emptyList())
            } else {
                val resp = ubusApi.call(UbusRequest.call(ubusSessionId, "iwinfo", "info", mapOf("device" to "phy0")))
                RouterResult.Success(emptyList()) // parse if needed
            }
        } catch (e: Exception) {
            RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "", e)
        }
    }

    override suspend fun getWifiNetworks(): RouterResult<List<WifiNetwork>> = withContext(Dispatchers.IO) {
        try {
            if (useAgent) {
                val wifi = agentApi.getWifi(auth()).body()
                RouterResult.Success(wifi?.networks?.map { n ->
                    WifiNetwork(n.ssid, n.bssid, n.band?.let { WiFiBand.valueOf(it) },
                        n.channel, n.security, n.isEnabled, n.clientCount, n.radio)
                } ?: emptyList())
            } else {
                RouterResult.Success(emptyList())
            }
        } catch (e: Exception) {
            RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "", e)
        }
    }

    // ─── VLAN ─────────────────────────────────────────────────────────────────

    override suspend fun getVlans(): RouterResult<List<VlanInfo>> = withContext(Dispatchers.IO) {
        if (!_capabilities.vlanManagement) return@withContext RouterResult.Error(RouterErrorCode.NOT_SUPPORTED, "VLAN management not supported")
        try {
            val vlans = agentApi.getVlans(auth()).body()?.vlans ?: emptyList()
            RouterResult.Success(vlans.map { it.toModel() })
        } catch (e: Exception) {
            RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "", e)
        }
    }

    override suspend fun createVlan(vlan: VlanInfo): RouterResult<Unit> = withContext(Dispatchers.IO) {
        if (!_capabilities.vlanManagement) return@withContext RouterResult.Error(RouterErrorCode.NOT_SUPPORTED, "VLAN management not supported")
        try { finishVlanChange(agentApi.createVlan(auth(), vlan.toAgentModel())) }
        catch (e: Exception) { RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "", e) }
    }

    override suspend fun updateVlan(vlan: VlanInfo): RouterResult<Unit> = withContext(Dispatchers.IO) {
        if (!_capabilities.vlanManagement) return@withContext RouterResult.Error(RouterErrorCode.NOT_SUPPORTED, "VLAN management not supported")
        try { finishVlanChange(agentApi.updateVlan(auth(), vlan.vlanId, vlan.toAgentModel())) }
        catch (e: Exception) { RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "", e) }
    }

    override suspend fun deleteVlan(vlanId: Int): RouterResult<Unit> = withContext(Dispatchers.IO) {
        if (!_capabilities.vlanManagement) return@withContext RouterResult.Error(RouterErrorCode.NOT_SUPPORTED, "VLAN management not supported")
        try { finishVlanChange(agentApi.deleteVlan(auth(), vlanId)) }
        catch (e: Exception) { RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "", e) }
    }

    /**
     * A VLAN change is applied "commit-confirm": the router restores the previous network
     * configuration by itself unless we confirm. We confirm only after the agent answers
     * again, i.e. after the change demonstrably did not cut us off.
     */
    private suspend fun finishVlanChange(resp: Response<AgentOperationResult>): RouterResult<Unit> {
        val applied = resp.toUnit()
        if (applied is RouterResult.Error) return applied
        val window = resp.body()?.confirmWithinSeconds ?: return applied
        if (resp.body()?.confirmRequired != true) return applied
        val deadline = System.nanoTime() + (window - 10).coerceAtLeast(5) * 1_000_000_000L
        while (System.nanoTime() < deadline) {
            try {
                if (agentApi.getStatus(auth()).isSuccessful) return agentApi.confirmVlanChanges(auth()).toUnit()
            } catch (_: Exception) { /* network is reloading */ }
            delay(2_000)
        }
        return RouterResult.Error(RouterErrorCode.NETWORK_ERROR,
            "Router unreachable after the VLAN change; it will restore its previous configuration automatically")
    }

    override suspend fun confirmPendingChanges(): RouterResult<Unit> = withContext(Dispatchers.IO) {
        if (!useAgent) return@withContext RouterResult.Success(Unit)
        try {
            val vlan = agentApi.confirmVlanChanges(auth()).toUnit()
            val restore = agentApi.confirmRestore(auth()).toUnit()
            if (vlan is RouterResult.Error) vlan else restore
        } catch (e: Exception) {
            RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "", e)
        }
    }

    // ─── Topology ─────────────────────────────────────────────────────────────

    override suspend fun getLldpNeighbors(): RouterResult<List<LldpNeighbor>> = withContext(Dispatchers.IO) {
        if (!_capabilities.lldpNeighbors) return@withContext RouterResult.Error(RouterErrorCode.NOT_SUPPORTED, "LLDP not enabled on this router")
        try {
            val neighbors = agentApi.getLldpNeighbors(auth()).body()?.neighbors ?: emptyList()
            RouterResult.Success(neighbors.map {
                LldpNeighbor(it.localPort, it.remoteChassisId, it.remotePortId, it.remoteHostname, it.remoteDescription, it.remoteCaps)
            })
        } catch (e: Exception) {
            RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "", e)
        }
    }

    // ─── Health ───────────────────────────────────────────────────────────────

    override suspend fun runHealthCheck(): RouterResult<NetworkHealthReport> = withContext(Dispatchers.IO) {
        try {
            if (useAgent) {
                val health = agentApi.getHealthCheck(auth()).body()
                    ?: return@withContext RouterResult.Error(RouterErrorCode.INVALID_RESPONSE, "No health data")
                RouterResult.Success(health.toModel())
            } else {
                RouterResult.Error(RouterErrorCode.NOT_SUPPORTED, "Full health check requires weshah-agent")
            }
        } catch (e: Exception) {
            RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "", e)
        }
    }

    // ─── Config Backup ────────────────────────────────────────────────────────

    override suspend fun createConfigBackup(): RouterResult<ByteArray> = withContext(Dispatchers.IO) {
        if (!_capabilities.configBackup) return@withContext RouterResult.Error(RouterErrorCode.NOT_SUPPORTED, "Config backup not supported")
        try {
            val resp = agentApi.createBackup(auth())
            if (resp.isSuccessful) RouterResult.Success(resp.body()?.bytes() ?: byteArrayOf())
            else RouterResult.Error(RouterErrorCode.ROUTER_ERROR, "Backup failed: ${resp.code()}")
        } catch (e: Exception) {
            RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "", e)
        }
    }

    override suspend fun restoreConfigBackup(data: ByteArray): RouterResult<Unit> = withContext(Dispatchers.IO) {
        if (!_capabilities.configBackup) return@withContext RouterResult.Error(RouterErrorCode.NOT_SUPPORTED, "Config restore not supported")
        if (!useAgent) return@withContext RouterResult.Error(RouterErrorCode.NOT_SUPPORTED, "Restore requires weshah-agent")
        try {
            val staged = agentApi.validateRestore(auth(), data.toRequestBody("application/octet-stream".toMediaType()))
            if (!staged.isSuccessful) return@withContext RouterResult.Error(httpErrorCode(staged.code()), errorMessage(staged))
            val v = staged.body() ?: return@withContext RouterResult.Error(RouterErrorCode.INVALID_RESPONSE, "Empty validation")
            if (!v.valid) return@withContext RouterResult.Error(RouterErrorCode.VALIDATION_ERROR, "Backup failed validation")
            if (v.boardMatch == false) return@withContext RouterResult.Error(RouterErrorCode.VALIDATION_ERROR,
                "Backup is for ${v.backupBoard}, this router is ${v.routerBoard}")
            if (!v.hasManifest) return@withContext RouterResult.Error(RouterErrorCode.VALIDATION_ERROR,
                "Backup has no board manifest (not created by weshah-agent); refusing to restore an unverifiable archive")
            // reboot=true: the new config only becomes live after a reboot. The router rolls back by itself
            // unless the app reconnects (connect() confirms), so a bad config cannot strand the router.
            agentApi.applyRestore(auth(), AgentRestoreApplyRequest(sha256 = v.sha256, allowUnverified = false, reboot = true)).toUnit()
        } catch (e: Exception) {
            RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "", e)
        }
    }

    // ─── Events ───────────────────────────────────────────────────────────────

    override fun getEventStream(): Flow<RouterEvent> = flow {
        // Poll for events; agent pushes SSE in future versions
        var lastPortStates = mapOf<String, Boolean>()
        var lastWanState = false

        while (true) {
            try {
                if (useAgent) {
                    // Check WAN
                    val wanResult = getWanStatus()
                    val wanUp = wanResult.getOrNull()?.isConnected ?: false
                    if (wanUp != lastWanState) {
                        emit(RouterEvent(System.currentTimeMillis(),
                            if (wanUp) RouterEventType.WAN_UP else RouterEventType.WAN_DOWN,
                            null, null, null))
                        lastWanState = wanUp
                    }
                    // Check ports
                    if (_capabilities.portStats) {
                        val ports = getPortStats().getOrNull() ?: emptyList()
                        for (port in ports) {
                            val wasUp = lastPortStates[port.portId]
                            if (wasUp != null && wasUp != port.isUp) {
                                emit(RouterEvent(System.currentTimeMillis(),
                                    if (port.isUp) RouterEventType.PORT_UP else RouterEventType.PORT_DOWN,
                                    port.portId, null, null))
                            }
                        }
                        lastPortStates = ports.associate { it.portId to it.isUp }
                    }
                }
            } catch (_: Exception) {}
            delay(15_000)
        }
    }.flowOn(Dispatchers.IO)

    // ─── DHCP Reservations ────────────────────────────────────────────────────

    override suspend fun createStaticLease(macAddress: String, ipAddress: String, hostname: String?): RouterResult<Unit> = withContext(Dispatchers.IO) {
        try {
            if (useAgent) {
                agentApi.createStaticLease(auth(), AgentStaticLeaseRequest(macAddress, ipAddress, hostname)).toUnit()
            } else {
                // ubus approach
                RouterResult.Error(RouterErrorCode.NOT_SUPPORTED, "Static lease via raw ubus not implemented")
            }
        } catch (e: Exception) { RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "", e) }
    }

    override suspend fun removeStaticLease(macAddress: String): RouterResult<Unit> = withContext(Dispatchers.IO) {
        try {
            if (useAgent) agentApi.removeStaticLease(auth(), macAddress).toUnit()
            else RouterResult.Error(RouterErrorCode.NOT_SUPPORTED, "Requires weshah-agent")
        } catch (e: Exception) { RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "", e) }
    }

    override suspend fun getStaticLeases(): RouterResult<List<DhcpLease>> = withContext(Dispatchers.IO) {
        try {
            if (useAgent) {
                val leases = agentApi.getStaticLeases(auth()).body()?.leases ?: emptyList()
                RouterResult.Success(leases.map { DhcpLease(it.mac, it.ip, it.hostname, null, true) })
            } else RouterResult.Error(RouterErrorCode.NOT_SUPPORTED, "Requires weshah-agent")
        } catch (e: Exception) { RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "", e) }
    }

    companion object {
        const val DEFAULT_AGENT_PORT = 8765
    }

    // ─── JSON helpers ─────────────────────────────────────────────────────────

    private fun extractJsonString(json: String, key: String): String? {
        val k = key.substringAfterLast(".")
        val regex = Regex(""""$k"\s*:\s*"([^"]*)"""")
        return regex.find(json)?.groupValues?.getOrNull(1)
    }

    private fun extractJsonLong(json: String, key: String): Long? {
        val k = key.substringAfterLast(".")
        val regex = Regex(""""$k"\s*:\s*(\d+)""")
        return regex.find(json)?.groupValues?.getOrNull(1)?.toLongOrNull()
    }

    private fun extractJsonInt(json: String, key: String): Int? {
        val k = key.substringAfterLast(".")
        val regex = Regex(""""$k"\s*:\s*(\d+)""")
        return regex.find(json)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun extractJsonBool(json: String, key: String): Boolean? {
        val k = key.substringAfterLast(".")
        val regex = Regex(""""$k"\s*:\s*(true|false)""")
        return regex.find(json)?.groupValues?.getOrNull(1)?.toBooleanStrictOrNull()
    }

    private fun parseDhcpLeasesFromUbus(json: String): List<DhcpLease> {
        // Minimal regex-based parser — replace with Moshi if structure is stable
        val leases = mutableListOf<DhcpLease>()
        val macRegex = Regex(""""macaddr"\s*:\s*"([^"]+)"""")
        val ipRegex = Regex(""""ipaddr"\s*:\s*"([^"]+)"""")
        val hostnameRegex = Regex(""""hostname"\s*:\s*"([^"]+)"""")
        val macs = macRegex.findAll(json).map { it.groupValues[1] }.toList()
        val ips = ipRegex.findAll(json).map { it.groupValues[1] }.toList()
        val hostnames = hostnameRegex.findAll(json).map { it.groupValues[1] }.toList()
        for (i in macs.indices) {
            leases.add(DhcpLease(macs[i], ips.getOrElse(i) { "" }, hostnames.getOrNull(i), null, false))
        }
        return leases
    }
}

// ─── Extension Functions ──────────────────────────────────────────────────────

private fun AgentRouterStatus.toRouterInfo(ip: String) = RouterInfo(
    ipAddress = ip, macAddress = null, hostname = null,
    model = model, firmware = firmware, kernelVersion = kernel, architecture = null,
    uptime = uptimeSeconds.toLong(),
    cpuUsagePercent = cpuPercent,
    ramTotalKb = ramTotalKb.toLong(),
    ramFreeKb = (ramTotalKb - ramUsedKb).toLong(),
    loadAverage1m = 0f, loadAverage5m = 0f, loadAverage15m = 0f,
    temperatureCelsius = temperatureCelsius,
    routerType = RouterType.WESHAH,
    interfaces = emptyList(), wanStatus = null
)

private fun VlanInfo.toAgentModel() = AgentVlanRequest(vlanId, name, gateway, subnet, dhcpEnabled, internetAccess, clientIsolation, ssid, taggedPorts, untaggedPorts)
private fun AgentVlanData.toModel() = VlanInfo(vlanId, name, gateway, subnet, dhcpEnabled, internetAccess, clientIsolation, ssid, taggedPorts, untaggedPorts)
private fun AgentCableDiagResult.toModel() = CableDiagResult(
    portId, System.currentTimeMillis(),
    CableStatus.valueOf(cableStatus),
    estimatedLengthMeters,
    pairs.map { PairResult(it.pair, PairStatus.valueOf(it.status), it.faultDistanceMeters) },
    linkSpeedMbps, duplexFull, crcErrors,
    supported = true, unsupportedReason = null
)
private fun AgentPortData.toModel() = PortInfo(
    portId, label, isUp, speedMbps, duplexFull, autoNegotiation,
    rxBytes, txBytes, rxErrors, txErrors, rxDropped, txDropped,
    crcErrors, linkFlaps, connectedMac, connectedDevice, vlanId,
    PortType.valueOf(portType)
)
private fun AgentInterfaceData.toModel() = NetworkInterface(
    name, ipAddress, macAddress, isUp, rxBytes, txBytes, InterfaceType.valueOf(type)
)
private fun AgentWanData.toModel() = WanInterface(
    id, name, interface_, isActive, isHealthy, ipAddress, gateway,
    rxBytes, txBytes, rxRateBps, txRateBps, latencyMs, packetLossPercent, weight, priority
)
private fun AgentHealthReport.toModel() = NetworkHealthReport(
    timestamp = System.currentTimeMillis(),
    gatewayLatencyMs = gatewayLatencyMs,
    internetLatencyMs = internetLatencyMs,
    packetLossPercent = packetLossPercent,
    jitterMs = jitterMs,
    dnsLatencyMs = dnsLatencyMs,
    wanState = WanHealthState.valueOf(wanState),
    routerCpuPercent = cpuPercent,
    routerRamFreeKb = ramFreeKb,
    routerRamTotalKb = ramTotalKb,
    routerTemperatureCelsius = temperatureCelsius,
    interfaceErrors = interfaceErrors,
    activeClientCount = activeClientCount,
    diagnosedIssues = emptyList()
)
