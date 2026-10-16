package com.weshah.core.models

/**
 * A node in the discovered network topology.
 * Edges are expressed as parentId references.
 * Confidence indicates how certain we are of the parent-child relationship.
 */
data class TopologyNode(
    val id: String,                  // MAC address or synthetic ID for unknown nodes
    val label: String,               // display name
    val ipAddress: String?,
    val macAddress: String?,
    val deviceType: DeviceType,
    val parentId: String?,           // null = root (internet gateway)
    val connectionConfidence: TopologyConfidence,
    val inferredVia: String,         // e.g. "ARP", "DHCP", "LLDP", "SNMP", "mDNS"
    val portId: String?,             // which port on the parent
    val vlanId: Int?,
    val linkSpeedMbps: Int?,
    val isOnline: Boolean,
    val depth: Int                   // 0 = internet, 1 = router, 2 = direct clients, etc.
)

enum class TopologyConfidence {
    CONFIRMED,   // LLDP/CDP/SNMP confirmed the link
    INFERRED,    // ARP/DHCP/MAC-table; confident but not protocol-confirmed
    GUESSED      // best guess based on device type heuristics
}
