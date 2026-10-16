package com.weshah.core.models

/**
 * Physical Ethernet port information from the router's switch driver.
 * Availability depends on RouterCapabilities.portStats.
 */
data class PortInfo(
    val portId: String,              // e.g. "LAN1", "WAN", "eth0"
    val label: String,               // Human-readable label
    val isUp: Boolean,
    val speedMbps: Int?,             // null = unknown/not linked
    val duplexFull: Boolean?,        // null = unknown
    val autoNegotiation: Boolean?,
    val rxBytes: Long,
    val txBytes: Long,
    val rxErrors: Long,
    val txErrors: Long,
    val rxDropped: Long,
    val txDropped: Long,
    val crcErrors: Long,
    val linkFlaps: Int,              // number of up/down transitions since last reset
    val connectedMac: String?,       // MAC of connected device if known
    val connectedDevice: String?,    // resolved hostname if known
    val vlanId: Int?,
    val portType: PortType
)

enum class PortType { WAN, LAN, TRUNK, UPLINK, UNKNOWN }

/**
 * Cable diagnostics result from a TDR test or ethtool phy-diag.
 * Only populated when RouterCapabilities.cableDiagnosticsTdr == true.
 */
data class CableDiagResult(
    val portId: String,
    val testTimeMs: Long,            // when the test ran (epoch ms)
    val cableStatus: CableStatus,
    val estimatedLengthMeters: Float?,   // null if unsupported or open/short
    val pairs: List<PairResult>,
    val linkSpeedMbps: Int?,
    val duplexFull: Boolean?,
    val crcErrors: Long,
    val supported: Boolean,          // false = hardware does not support TDR
    val unsupportedReason: String?   // if !supported, why
)

enum class CableStatus {
    CONNECTED,
    OPEN,               // cable disconnected or broken
    SHORT,              // short circuit
    IMPEDANCE_MISMATCH,
    UNKNOWN
}

data class PairResult(
    val pair: String,           // e.g. "1-2", "3-6", "4-5", "7-8"
    val status: PairStatus,
    val faultDistanceMeters: Float?
)

enum class PairStatus { OK, OPEN, SHORT, CROSSTALK, UNKNOWN }
