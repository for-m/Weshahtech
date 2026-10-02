package com.weshah.core.models

data class RouterInfo(
    val ipAddress: String,
    val macAddress: String?,
    val hostname: String?,
    val model: String?,
    val firmware: String?,
    val kernelVersion: String?,
    val architecture: String?,
    val uptime: Long,              // seconds
    val cpuUsagePercent: Float,
    val ramTotalKb: Long,
    val ramFreeKb: Long,
    val loadAverage1m: Float,
    val loadAverage5m: Float,
    val loadAverage15m: Float,
    val temperatureCelsius: Float?,
    val routerType: RouterType,
    val interfaces: List<NetworkInterface>,
    val wanStatus: WanStatus?
)

enum class RouterType {
    OPENWRT,
    WESHAH,      // WESHAH-customized OpenWrt with weshah-agent
    MIKROTIK,
    AMINLINK,
    UNKNOWN
}

data class WanStatus(
    val isConnected: Boolean,
    val ipAddress: String?,
    val gateway: String?,
    val dns: List<String>,
    val rxBytes: Long,
    val txBytes: Long,
    val rxRateBytes: Long,
    val txRateBytes: Long
)

data class NetworkInterface(
    val name: String,
    val ipAddress: String?,
    val macAddress: String?,
    val isUp: Boolean,
    val rxBytes: Long,
    val txBytes: Long,
    val type: InterfaceType
)

enum class InterfaceType { WAN, LAN, WIFI, LOOPBACK, OTHER }
