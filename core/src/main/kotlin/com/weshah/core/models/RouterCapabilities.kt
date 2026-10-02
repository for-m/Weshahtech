package com.weshah.core.models

/**
 * Capability flags detected at runtime from the connected router.
 * The UI and engines must check these before offering any feature.
 * Never assume a capability — always detect it.
 */
data class RouterCapabilities(
    // Bandwidth / QoS
    val bandwidthControl: Boolean = false,
    val perClientTraffic: Boolean = false,

    // Ports
    val portStats: Boolean = false,
    val portErrors: Boolean = false,

    // Cable diagnostics (hardware TDR — not available on most consumer routers)
    val cableDiagnosticsTdr: Boolean = false,
    val cableDiagnosticsBasic: Boolean = false,  // link speed / duplex at minimum

    // WiFi
    val wifiRssiPerClient: Boolean = false,
    val wifiChannelScan: Boolean = false,
    val wifiMultiSsid: Boolean = false,

    // Network
    val vlanManagement: Boolean = false,
    val multiWan: Boolean = false,
    val dhcpStaticLeases: Boolean = false,

    // Discovery / topology
    val lldpNeighbors: Boolean = false,
    val snmp: Boolean = false,
    val arpTable: Boolean = false,

    // System
    val configBackup: Boolean = false,
    val configRestore: Boolean = false,
    val temperature: Boolean = false,

    // Hotspot / subscriber
    val hotspot: Boolean = false,
    val subscriberManagement: Boolean = false,

    // Agent
    val weshahAgent: Boolean = false,
    val agentVersion: String? = null,

    // PHY info — which PHY driver is detected (informational)
    val phyDriver: PhyDriver = PhyDriver.UNKNOWN
)

enum class PhyDriver {
    ATHEROS_AR8327,
    ATHEROS_AR8216,
    QUALCOMM_QCA8075,
    QUALCOMM_QCA8337,
    MEDIATEK_MT7531,
    MEDIATEK_MT7621,
    BROADCOM_BCM53XX,
    LANTIQ_GSWIP,
    REALTEK_RTL83XX,
    MARVELL_88E6XXX,
    UNKNOWN
}

/**
 * Human-readable summary of capabilities for display.
 */
fun RouterCapabilities.toDisplayList(): List<Pair<String, Boolean>> = listOf(
    "Bandwidth Control" to bandwidthControl,
    "Per-Client Traffic" to perClientTraffic,
    "Port Statistics" to portStats,
    "Cable Diagnostics (TDR)" to cableDiagnosticsTdr,
    "WiFi RSSI per Client" to wifiRssiPerClient,
    "VLAN Management" to vlanManagement,
    "Multi-WAN" to multiWan,
    "DHCP Reservations" to dhcpStaticLeases,
    "LLDP Neighbors" to lldpNeighbors,
    "Config Backup" to configBackup,
    "Hotspot" to hotspot,
    "WESHAH Agent" to weshahAgent,
    "Temperature Sensor" to temperature,
)
