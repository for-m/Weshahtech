package com.weshah.router.openwrt

import com.weshah.core.models.*
import com.weshah.router.api.*
import com.weshah.router.openwrt.api.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * OpenWrt RouterAdapter implementation.
 *
 * STRATEGY:
 * 1. Attempt to detect if weshah-agent is installed → use WeshahAgentApiService (preferred)
 * 2. Fall back to raw ubus JSON-RPC
 *
 * All bandwidth control rules are persisted on the router (not on the phone).
 * The phone is only the UI — rules survive phone reboots and app uninstalls.
 *
 * Bandwidth control uses nftables + tc HTB (via weshah-agent) or UCI QoS rules.
 * Rules are written to /etc/config/weshah-qos on the router for persistence.
 */
class OpenWrtAdapter @Inject constructor(
    private val credentialManager: RouterCredentialManager
) : RouterAdapter {

    private var ubusService: UbusApiService? = null
    private var agentService: WeshahAgentApiService? = null
    private var sessionToken: String = ""
    private var agentToken: String = ""
    private var useAgent: Boolean = false
    private var baseUrl: String = ""

    override suspend fun connect(config: RouterConnectionConfig): RouterConnectionResult {
        baseUrl = buildBaseUrl(config)
        val client = buildOkHttpClient()

        return try {
            // Try weshah-agent first (port 8080 by default, or same port/api prefix)
            val agentUrl = "${baseUrl}weshah/"
            val agentRetrofit = buildRetrofit(agentUrl, client)
            val candidateAgent = agentRetrofit.create(WeshahAgentApiService::class.java)

            val password = credentialManager.getCredential(config.credentialKeyAlias)
                ?: return RouterConnectionResult.Failure(
                    "No credentials stored for ${config.ipAddress}",
                    RouterErrorCode.AUTHENTICATION_FAILED
                )

            try {
                val authResponse = candidateAgent.login(AgentLoginRequest(config.username, password))
                agentToken = authResponse.token
                agentService = candidateAgent
                useAgent = true
                Timber.i("Connected via weshah-agent to ${config.ipAddress}")
                RouterConnectionResult.Success
            } catch (agentEx: Exception) {
                Timber.w("weshah-agent not available, falling back to ubus: ${agentEx.message}")
                // Fall back to ubus
                val ubusRetrofit = buildRetrofit(baseUrl, client)
                val ubus = ubusRetrofit.create(UbusApiService::class.java)
                val loginResponse = ubus.call(UbusRequest.login(config.username, password))

                if (loginResponse.isSuccess) {
                    val data = loginResponse.data
                    sessionToken = (data?.get("ubus_rpc_session") as? String) ?: ""
                    if (sessionToken.isEmpty()) {
                        return RouterConnectionResult.Failure("Authentication failed", RouterErrorCode.AUTHENTICATION_FAILED)
                    }
                    ubusService = ubus
                    useAgent = false
                    Timber.i("Connected via ubus to ${config.ipAddress}")
                    RouterConnectionResult.Success
                } else {
                    RouterConnectionResult.Failure(
                        "ubus login failed: code ${loginResponse.statusCode}",
                        RouterErrorCode.AUTHENTICATION_FAILED
                    )
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to connect to ${config.ipAddress}")
            RouterConnectionResult.Failure(e.message ?: "Unknown error", RouterErrorCode.CONNECTION_REFUSED)
        }
    }

    override suspend fun isConnected(): Boolean {
        return if (useAgent) {
            try { agentService?.getRouterStatus(); true } catch (e: Exception) { false }
        } else {
            try {
                val resp = ubusService?.call(
                    UbusRequest.call(sessionToken, "session", "access", emptyMap())
                )
                resp?.isSuccess == true
            } catch (e: Exception) { false }
        }
    }

    override suspend fun disconnect() {
        try {
            ubusService?.call(
                UbusRequest.call(sessionToken, "session", "destroy",
                    mapOf("ubus_rpc_session" to sessionToken))
            )
        } catch (e: Exception) { /* ignore */ }
        sessionToken = ""
        agentToken = ""
        ubusService = null
        agentService = null
    }

    override suspend fun getSystemInfo(): RouterResult<RouterInfo> {
        return try {
            if (useAgent) {
                val status = agentService!!.getRouterStatus()
                RouterResult.Success(status.toRouterInfo())
            } else {
                getSystemInfoViaUbus()
            }
        } catch (e: Exception) {
            RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "Failed", e)
        }
    }

    override fun getSystemStats(): Flow<RouterStats> = flow {
        while (true) {
            try {
                val info = getSystemInfo()
                if (info is RouterResult.Success) {
                    emit(RouterStats(
                        cpuUsagePercent = info.data.cpuUsagePercent,
                        ramFreeKb = info.data.ramFreeKb,
                        ramTotalKb = info.data.ramTotalKb,
                        temperatureCelsius = info.data.temperatureCelsius,
                        uptime = info.data.uptime,
                        timestamp = System.currentTimeMillis()
                    ))
                }
            } catch (e: Exception) {
                Timber.w(e, "getSystemStats poll failed")
            }
            delay(8_000L)
        }
    }

    override suspend fun getInterfaces(): RouterResult<List<NetworkInterface>> {
        return try {
            if (useAgent) {
                // weshah-agent includes interface data in status
                val status = agentService!!.getRouterStatus()
                // Basic implementation - weshah-agent v2 will have detailed interfaces
                RouterResult.Success(buildBasicInterfaces(status))
            } else {
                getInterfacesViaUbus()
            }
        } catch (e: Exception) {
            RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "Failed", e)
        }
    }

    override suspend fun getWanStatus(): RouterResult<WanStatus> {
        return try {
            if (useAgent) {
                val status = agentService!!.getRouterStatus()
                RouterResult.Success(WanStatus(
                    isConnected = status.wanConnected,
                    ipAddress = status.wanIp,
                    gateway = null, // NOT IMPLEMENTED in agent v1
                    dns = emptyList(),
                    rxBytes = 0L,
                    txBytes = 0L,
                    rxRateBytes = 0L,
                    txRateBytes = 0L
                ))
            } else {
                getWanStatusViaUbus()
            }
        } catch (e: Exception) {
            RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "Failed", e)
        }
    }

    override suspend fun getDhcpLeases(): RouterResult<List<DhcpLease>> {
        return try {
            if (useAgent) {
                val deviceList = agentService!!.getDevices()
                RouterResult.Success(deviceList.devices.mapNotNull { d ->
                    d.ip?.let { ip ->
                        DhcpLease(d.mac, ip, d.hostname, null, false)
                    }
                })
            } else {
                getDhcpLeasesViaUbus()
            }
        } catch (e: Exception) {
            RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "Failed", e)
        }
    }

    override suspend fun getConnectedClients(): RouterResult<List<ConnectedClient>> {
        return try {
            if (useAgent) {
                val deviceList = agentService!!.getDevices()
                RouterResult.Success(deviceList.devices.map { d ->
                    ConnectedClient(d.mac, d.ip, d.hostname, d.interface_ ?: "br-lan", d.isWifi)
                })
            } else {
                getConnectedClientsViaUbus()
            }
        } catch (e: Exception) {
            RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "Failed", e)
        }
    }

    override suspend fun getWifiClients(): RouterResult<List<WifiClient>> {
        return try {
            if (useAgent) {
                val deviceList = agentService!!.getDevices()
                RouterResult.Success(deviceList.devices.filter { it.isWifi }.map { d ->
                    WifiClient(
                        macAddress = d.mac,
                        ipAddress = d.ip,
                        ssid = null, // agent v1 doesn't include SSID per client
                        bssid = null,
                        rssi = d.rssi ?: -100,
                        txRate = null,
                        rxRate = null,
                        band = when (d.band) {
                            "2.4GHz" -> WiFiBand.BAND_2_4GHZ
                            "5GHz" -> WiFiBand.BAND_5GHZ
                            "6GHz" -> WiFiBand.BAND_6GHZ
                            else -> null
                        }
                    )
                })
            } else {
                getWifiClientsViaUbus()
            }
        } catch (e: Exception) {
            RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "Failed", e)
        }
    }

    override suspend fun getClientTrafficStats(): RouterResult<List<ClientTrafficStats>> {
        return try {
            if (useAgent) {
                val deviceList = agentService!!.getDevices()
                RouterResult.Success(deviceList.devices.map { d ->
                    ClientTrafficStats(d.mac, d.rxBytes, d.txBytes, System.currentTimeMillis())
                })
            } else {
                // ubus: use conntrack or nlbw if available
                getClientTrafficViaUbus()
            }
        } catch (e: Exception) {
            RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "Failed", e)
        }
    }

    override fun getClientTrafficFlow(macAddress: String): Flow<TrafficSample> = flow {
        var prevRx = 0L
        var prevTx = 0L
        var prevTime = System.currentTimeMillis()

        while (true) {
            try {
                val now = System.currentTimeMillis()
                val elapsedSec = (now - prevTime) / 1000.0

                val rx: Long
                val tx: Long

                if (useAgent) {
                    val traffic = agentService!!.getDeviceTraffic(macAddress)
                    rx = traffic.rxBytes
                    tx = traffic.txBytes

                    if (prevRx > 0 && elapsedSec > 0) {
                        val downloadBps = ((rx - prevRx) / elapsedSec).toLong().coerceAtLeast(0)
                        val uploadBps = ((tx - prevTx) / elapsedSec).toLong().coerceAtLeast(0)
                        emit(TrafficSample(macAddress, downloadBps, uploadBps, now))
                    } else if (traffic.rxRate > 0 || traffic.txRate > 0) {
                        // agent provides rates directly
                        emit(TrafficSample(macAddress, traffic.rxRate, traffic.txRate, now))
                    }
                } else {
                    // ubus fallback: poll conntrack
                    val stats = getClientTrafficViaUbus()
                    if (stats is RouterResult.Success) {
                        val client = stats.data.firstOrNull { it.macAddress == macAddress }
                        if (client != null) {
                            rx = client.rxBytes
                            tx = client.txBytes
                            if (prevRx > 0 && elapsedSec > 0) {
                                val downloadBps = ((rx - prevRx) / elapsedSec).toLong().coerceAtLeast(0)
                                val uploadBps = ((tx - prevTx) / elapsedSec).toLong().coerceAtLeast(0)
                                emit(TrafficSample(macAddress, downloadBps, uploadBps, now))
                            }
                        } else {
                            rx = 0L
                            tx = 0L
                        }
                    } else {
                        rx = prevRx
                        tx = prevTx
                    }
                }

                prevRx = rx
                prevTx = tx
                prevTime = now
            } catch (e: Exception) {
                Timber.w(e, "Traffic poll failed for $macAddress")
            }
            delay(3_000L)
        }
    }

    override suspend fun setClientSpeedLimit(
        macAddress: String,
        downloadKbps: Long?,
        uploadKbps: Long?
    ): RouterResult<Unit> {
        return try {
            if (useAgent) {
                val result = agentService!!.setDeviceSpeed(
                    macAddress,
                    AgentSpeedRequest(downloadKbps, uploadKbps)
                )
                if (result.success) RouterResult.Success(Unit)
                else RouterResult.Error(RouterErrorCode.ROUTER_ERROR, result.message ?: "Failed")
            } else {
                // ubus fallback: configure via UCI qos or nlbw
                setSpeedLimitViaUbus(macAddress, downloadKbps, uploadKbps)
            }
        } catch (e: Exception) {
            RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "Failed", e)
        }
    }

    override suspend fun removeClientSpeedLimit(macAddress: String): RouterResult<Unit> =
        setClientSpeedLimit(macAddress, null, null)

    override suspend fun getClientSpeedLimit(macAddress: String): RouterResult<DeviceSpeedLimit?> {
        return try {
            if (useAgent) {
                val devices = agentService!!.getDevices()
                val device = devices.devices.firstOrNull { it.mac == macAddress }
                if (device?.speedDownKbps != null || device?.speedUpKbps != null) {
                    RouterResult.Success(DeviceSpeedLimit(
                        macAddress = macAddress,
                        profileId = "custom",
                        downloadKbps = device?.speedDownKbps,
                        uploadKbps = device?.speedUpKbps,
                        appliedAt = System.currentTimeMillis(),
                        isActive = true
                    ))
                } else {
                    RouterResult.Success(null)
                }
            } else {
                RouterResult.Success(null) // NOT IMPLEMENTED via raw ubus
            }
        } catch (e: Exception) {
            RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "Failed", e)
        }
    }

    override suspend fun blockClient(macAddress: String, expiresAt: Long?): RouterResult<Unit> {
        return try {
            if (useAgent) {
                val result = agentService!!.blockDevice(macAddress, AgentBlockRequest(expiresAt))
                if (result.success) RouterResult.Success(Unit)
                else RouterResult.Error(RouterErrorCode.ROUTER_ERROR, result.message ?: "Failed")
            } else {
                blockClientViaUbus(macAddress, expiresAt)
            }
        } catch (e: Exception) {
            RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "Failed", e)
        }
    }

    override suspend fun unblockClient(macAddress: String): RouterResult<Unit> {
        return try {
            if (useAgent) {
                val result = agentService!!.unblockDevice(macAddress)
                if (result.success) RouterResult.Success(Unit)
                else RouterResult.Error(RouterErrorCode.ROUTER_ERROR, result.message ?: "Failed")
            } else {
                unblockClientViaUbus(macAddress)
            }
        } catch (e: Exception) {
            RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "Failed", e)
        }
    }

    override suspend fun disconnectClient(macAddress: String): RouterResult<Unit> {
        return try {
            if (useAgent) {
                val result = agentService!!.disconnectDevice(macAddress)
                if (result.success) RouterResult.Success(Unit)
                else RouterResult.Error(RouterErrorCode.ROUTER_ERROR, result.message ?: "Failed")
            } else {
                // kick deauth via hostapd_cli
                val resp = ubusService?.call(
                    UbusRequest.call(sessionToken, "hostapd.*", "del_client",
                        mapOf("addr" to macAddress, "reason" to 5, "deauth" to true, "ban_time" to 0))
                )
                if (resp?.isSuccess == true) RouterResult.Success(Unit)
                else RouterResult.Error(RouterErrorCode.NOT_SUPPORTED, "Disconnect requires weshah-agent or hostapd access")
            }
        } catch (e: Exception) {
            RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "Failed", e)
        }
    }

    override suspend fun getClientBlockStatus(macAddress: String): RouterResult<BlockStatus?> {
        return try {
            if (useAgent) {
                val devices = agentService!!.getDevices()
                val device = devices.devices.firstOrNull { it.mac == macAddress }
                RouterResult.Success(
                    if (device != null) BlockStatus(macAddress, device.isBlocked, null, null)
                    else null
                )
            } else {
                RouterResult.Success(null) // NOT IMPLEMENTED via raw ubus without weshah-agent
            }
        } catch (e: Exception) {
            RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "Failed", e)
        }
    }

    override suspend fun createStaticLease(macAddress: String, ipAddress: String, hostname: String?): RouterResult<Unit> {
        return try {
            val args = mutableMapOf<String, Any>(
                "config" to "dhcp",
                "type" to "host",
                "values" to mapOf(
                    "mac" to macAddress,
                    "ip" to ipAddress,
                    "name" to (hostname ?: "")
                )
            )
            val resp = ubusService?.call(UbusRequest.call(sessionToken, "uci", "add", args))
            if (resp?.isSuccess == true) {
                ubusService?.call(UbusRequest.call(sessionToken, "uci", "commit", mapOf("config" to "dhcp")))
                RouterResult.Success(Unit)
            } else {
                RouterResult.Error(RouterErrorCode.ROUTER_ERROR, "Failed to create static lease")
            }
        } catch (e: Exception) {
            RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "Failed", e)
        }
    }

    override suspend fun removeStaticLease(macAddress: String): RouterResult<Unit> {
        // NOT IMPLEMENTED — requires listing and deleting specific UCI section
        return RouterResult.Error(RouterErrorCode.NOT_SUPPORTED, "NOT IMPLEMENTED: removeStaticLease via ubus requires section enumeration")
    }

    override suspend fun getStaticLeases(): RouterResult<List<DhcpLease>> {
        return getDhcpLeasesViaUbus(staticOnly = true)
    }

    override suspend fun getWifiRadios(): RouterResult<List<WifiRadio>> {
        return try {
            getWifiRadiosViaUbus()
        } catch (e: Exception) {
            RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "Failed", e)
        }
    }

    // ─── ubus fallback implementations ────────────────────────────────────────

    private suspend fun getSystemInfoViaUbus(): RouterResult<RouterInfo> {
        val sysResp = ubusService?.call(
            UbusRequest.call(sessionToken, "system", "board", emptyMap())
        ) ?: return RouterResult.Error(RouterErrorCode.CONNECTION_REFUSED, "Not connected")

        val infoResp = ubusService?.call(
            UbusRequest.call(sessionToken, "system", "info", emptyMap())
        )

        if (!sysResp.isSuccess) {
            return RouterResult.Error(RouterErrorCode.ROUTER_ERROR, "system.board failed: ${sysResp.statusCode}")
        }

        val board = sysResp.data ?: emptyMap<String, Any>()
        val info = infoResp?.data ?: emptyMap<String, Any>()

        @Suppress("UNCHECKED_CAST")
        val memory = info["memory"] as? Map<String, Any>
        val totalRam = (memory?.get("total") as? Double)?.toLong() ?: 0L
        val freeRam = (memory?.get("free") as? Double)?.toLong() ?: 0L
        val buffered = (memory?.get("buffered") as? Double)?.toLong() ?: 0L

        @Suppress("UNCHECKED_CAST")
        val load = info["load"] as? List<Double>

        return RouterResult.Success(RouterInfo(
            ipAddress = "", // filled by caller
            macAddress = null,
            hostname = board["hostname"] as? String,
            model = board["model"] as? String,
            firmware = (board["release"] as? Map<*, *>)?.get("description") as? String,
            kernelVersion = board["kernel"] as? String,
            architecture = board["system"] as? String,
            uptime = (info["uptime"] as? Double)?.toLong() ?: 0L,
            cpuUsagePercent = 0f, // requires separate calculation
            ramTotalKb = totalRam / 1024,
            ramFreeKb = (freeRam + buffered) / 1024,
            loadAverage1m = load?.getOrNull(0)?.toFloat()?.div(65536f) ?: 0f,
            loadAverage5m = load?.getOrNull(1)?.toFloat()?.div(65536f) ?: 0f,
            loadAverage15m = load?.getOrNull(2)?.toFloat()?.div(65536f) ?: 0f,
            temperatureCelsius = null, // NOT IMPLEMENTED via standard ubus
            routerType = RouterType.OPENWRT,
            interfaces = emptyList(),
            wanStatus = null
        ))
    }

    private suspend fun getInterfacesViaUbus(): RouterResult<List<NetworkInterface>> {
        val resp = ubusService?.call(
            UbusRequest.call(sessionToken, "network.interface", "dump", emptyMap())
        ) ?: return RouterResult.Error(RouterErrorCode.CONNECTION_REFUSED, "Not connected")

        if (!resp.isSuccess) return RouterResult.Success(emptyList())

        @Suppress("UNCHECKED_CAST")
        val ifaces = resp.data?.get("interface") as? List<Map<String, Any>> ?: emptyList()

        return RouterResult.Success(ifaces.mapNotNull { iface ->
            val name = iface["interface"] as? String ?: return@mapNotNull null
            val ipv4Data = iface["ipv4-address"] as? List<Map<String, Any>>
            val ip = ipv4Data?.firstOrNull()?.get("address") as? String
            NetworkInterface(
                name = name,
                ipAddress = ip,
                macAddress = null,
                isUp = iface["up"] as? Boolean ?: false,
                rxBytes = 0L,
                txBytes = 0L,
                type = when {
                    name.startsWith("wan") || name == "pppoe-wan" -> InterfaceType.WAN
                    name.startsWith("lan") -> InterfaceType.LAN
                    name.startsWith("wlan") || name.startsWith("wl") -> InterfaceType.WIFI
                    else -> InterfaceType.OTHER
                }
            )
        })
    }

    private suspend fun getWanStatusViaUbus(): RouterResult<WanStatus> {
        val resp = ubusService?.call(
            UbusRequest.call(sessionToken, "network.interface.wan", "status", emptyMap())
        ) ?: return RouterResult.Error(RouterErrorCode.CONNECTION_REFUSED, "Not connected")

        if (!resp.isSuccess) return RouterResult.Success(WanStatus(false, null, null, emptyList(), 0, 0, 0, 0))

        val data = resp.data ?: emptyMap<String, Any>()
        val isUp = data["up"] as? Boolean ?: false
        @Suppress("UNCHECKED_CAST")
        val ipv4 = (data["ipv4-address"] as? List<Map<String, Any>>)?.firstOrNull()
        val ip = ipv4?.get("address") as? String
        @Suppress("UNCHECKED_CAST")
        val dnsServers = (data["dns-server"] as? List<String>) ?: emptyList()

        return RouterResult.Success(WanStatus(isUp, ip, null, dnsServers, 0, 0, 0, 0))
    }

    private suspend fun getDhcpLeasesViaUbus(staticOnly: Boolean = false): RouterResult<List<DhcpLease>> {
        return try {
            val resp = ubusService?.call(
                UbusRequest.call(sessionToken, "luci-rpc", "getDHCPLeases", emptyMap())
            ) ?: return RouterResult.Error(RouterErrorCode.CONNECTION_REFUSED, "Not connected")

            @Suppress("UNCHECKED_CAST")
            val leases = (resp.data?.get("dhcp_leases") as? List<Map<String, Any>>) ?: emptyList()
            val staticLeases = (resp.data?.get("dhcp6_leases") as? List<Map<String, Any>>) ?: emptyList()

            RouterResult.Success(leases.mapNotNull { lease ->
                val mac = lease["macaddr"] as? String ?: return@mapNotNull null
                val ip = lease["ipaddr"] as? String ?: return@mapNotNull null
                DhcpLease(mac, ip, lease["hostname"] as? String,
                    (lease["expires"] as? Double)?.toLong(), false)
            })
        } catch (e: Exception) {
            RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "Failed", e)
        }
    }

    private suspend fun getConnectedClientsViaUbus(): RouterResult<List<ConnectedClient>> {
        // Use iwinfo + ARP table
        return try {
            val wifiClients = getWifiClientsViaUbus()
            val clients = mutableListOf<ConnectedClient>()

            if (wifiClients is RouterResult.Success) {
                clients.addAll(wifiClients.data.map {
                    ConnectedClient(it.macAddress, it.ipAddress, null, "wlan", true)
                })
            }
            RouterResult.Success(clients)
        } catch (e: Exception) {
            RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "Failed", e)
        }
    }

    private suspend fun getWifiClientsViaUbus(): RouterResult<List<WifiClient>> {
        return try {
            val resp = ubusService?.call(
                UbusRequest.call(sessionToken, "iwinfo", "assoclist", mapOf("device" to "wlan0"))
            ) ?: return RouterResult.Error(RouterErrorCode.CONNECTION_REFUSED, "Not connected")

            @Suppress("UNCHECKED_CAST")
            val results = (resp.data?.get("results") as? List<Map<String, Any>>) ?: emptyList()

            RouterResult.Success(results.mapNotNull { sta ->
                val mac = sta["mac"] as? String ?: return@mapNotNull null
                WifiClient(
                    macAddress = mac,
                    ipAddress = null,
                    ssid = null,
                    bssid = null,
                    rssi = (sta["signal"] as? Double)?.toInt() ?: -100,
                    txRate = ((sta["tx"] as? Map<*, *>)?.get("rate") as? Double)?.toInt()?.div(1000),
                    rxRate = ((sta["rx"] as? Map<*, *>)?.get("rate") as? Double)?.toInt()?.div(1000),
                    band = null
                )
            })
        } catch (e: Exception) {
            RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "Failed", e)
        }
    }

    private suspend fun getClientTrafficViaUbus(): RouterResult<List<ClientTrafficStats>> {
        // Uses nlbw if available on OpenWrt
        return try {
            val resp = ubusService?.call(
                UbusRequest.call(sessionToken, "luci-rpc", "getNetworkDevices", emptyMap())
            ) ?: return RouterResult.Success(emptyList())

            RouterResult.Success(emptyList()) // NOT IMPLEMENTED: requires nlbw or conntrack parsing
        } catch (e: Exception) {
            RouterResult.Success(emptyList())
        }
    }

    private suspend fun getWifiRadiosViaUbus(): RouterResult<List<WifiRadio>> {
        return try {
            val resp = ubusService?.call(
                UbusRequest.call(sessionToken, "iwinfo", "info", mapOf("device" to "wlan0"))
            ) ?: return RouterResult.Success(emptyList())

            val data = resp.data ?: return RouterResult.Success(emptyList())
            RouterResult.Success(listOf(WifiRadio(
                name = "radio0",
                ssid = data["ssid"] as? String,
                bssid = data["bssid"] as? String,
                channel = (data["channel"] as? Double)?.toInt(),
                frequencyMHz = (data["frequency"] as? Double)?.toInt(),
                band = when ((data["frequency"] as? Double)?.toInt()) {
                    in 2400..2500 -> WiFiBand.BAND_2_4GHZ
                    in 5000..6000 -> WiFiBand.BAND_5GHZ
                    else -> null
                },
                isEnabled = true,
                txPowerDbm = (data["txpower"] as? Double)?.toInt(),
                standard = data["hwmodes"]?.toString()
            )))
        } catch (e: Exception) {
            RouterResult.Success(emptyList())
        }
    }

    private suspend fun setSpeedLimitViaUbus(
        macAddress: String, downloadKbps: Long?, uploadKbps: Long?
    ): RouterResult<Unit> {
        // NOT IMPLEMENTED: requires tc/nftables access via ubus which is non-standard
        // weshah-agent is the recommended path for bandwidth control
        return RouterResult.Error(
            RouterErrorCode.NOT_SUPPORTED,
            "NOT IMPLEMENTED: Bandwidth control via raw ubus requires weshah-agent. Install weshah-agent on OpenWrt."
        )
    }

    private suspend fun blockClientViaUbus(macAddress: String, expiresAt: Long?): RouterResult<Unit> {
        // Use nftables via ubus (requires luci-mod-network or custom rpcd access)
        // Write firewall rule to block MAC from WAN
        return try {
            val resp = ubusService?.call(
                UbusRequest.call(sessionToken, "uci", "add", mapOf(
                    "config" to "firewall",
                    "type" to "rule",
                    "values" to mapOf(
                        "name" to "weshah_block_${macAddress.replace(":", "")}",
                        "src" to "lan",
                        "dest" to "wan",
                        "src_mac" to macAddress,
                        "target" to "REJECT",
                        "enabled" to "1"
                    )
                ))
            )
            if (resp?.isSuccess == true) {
                ubusService?.call(UbusRequest.call(sessionToken, "uci", "commit", mapOf("config" to "firewall")))
                ubusService?.call(UbusRequest.call(sessionToken, "service", "firewall", mapOf("action" to "restart")))
                RouterResult.Success(Unit)
            } else {
                RouterResult.Error(RouterErrorCode.ROUTER_ERROR, "Failed to add firewall rule. Consider installing weshah-agent.")
            }
        } catch (e: Exception) {
            RouterResult.Error(RouterErrorCode.NETWORK_ERROR, e.message ?: "Failed", e)
        }
    }

    private suspend fun unblockClientViaUbus(macAddress: String): RouterResult<Unit> {
        // NOT IMPLEMENTED: requires finding and deleting the specific UCI section
        return RouterResult.Error(
            RouterErrorCode.NOT_SUPPORTED,
            "NOT IMPLEMENTED: Unblock via raw ubus requires weshah-agent for reliable rule tracking."
        )
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun buildBaseUrl(config: RouterConnectionConfig): String {
        val scheme = if (config.useHttps) "https" else "http"
        return "$scheme://${config.ipAddress}:${config.port}/"
    }

    private fun buildOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .hostnameVerifier { _, _ -> true } // Router certs are self-signed
        .build()

    private fun buildRetrofit(baseUrl: String, client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()

    private fun AgentRouterStatus.toRouterInfo() = RouterInfo(
        ipAddress = "",
        macAddress = null,
        hostname = hostname,
        model = model,
        firmware = firmware,
        kernelVersion = kernel,
        architecture = null,
        uptime = uptime,
        cpuUsagePercent = cpuPercent,
        ramTotalKb = ramTotalKb,
        ramFreeKb = ramFreeKb,
        loadAverage1m = load1m,
        loadAverage5m = load5m,
        loadAverage15m = load15m,
        temperatureCelsius = temperature,
        routerType = RouterType.WESHAH,
        interfaces = emptyList(),
        wanStatus = WanStatus(wanConnected, wanIp, null, emptyList(), 0, 0, 0, 0)
    )

    private fun buildBasicInterfaces(status: AgentRouterStatus): List<NetworkInterface> = listOfNotNull(
        status.wanIp?.let {
            NetworkInterface("wan", it, null, status.wanConnected, 0, 0, InterfaceType.WAN)
        }
    )
}
