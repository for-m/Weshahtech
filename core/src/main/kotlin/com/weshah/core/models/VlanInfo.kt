package com.weshah.core.models

data class VlanInfo(
    val vlanId: Int,
    val name: String,
    val gateway: String?,
    val subnet: String?,
    val dhcpEnabled: Boolean,
    val internetAccess: Boolean,
    val clientIsolation: Boolean,
    val ssid: String?,              // associated WiFi SSID, if any
    val taggedPorts: List<String>,
    val untaggedPorts: List<String>
)

data class ConfigBackup(
    val id: String,
    val timestamp: Long,
    val routerModel: String?,
    val routerIp: String,
    val firmware: String?,
    val sizeBytes: Long,
    val checksum: String,
    val localPath: String           // file path on device (encrypted)
)

data class WanInterface(
    val id: String,
    val name: String,              // e.g. "WAN1", "LTE"
    val interface_: String,        // e.g. "eth1", "pppoe-wan"
    val isActive: Boolean,
    val isHealthy: Boolean,
    val ipAddress: String?,
    val gateway: String?,
    val rxBytes: Long,
    val txBytes: Long,
    val rxRateBps: Long,
    val txRateBps: Long,
    val latencyMs: Float?,
    val packetLossPercent: Float?,
    val weight: Int,               // load balancing weight
    val priority: Int              // failover priority
)
