package com.weshah.core.models

/**
 * Result of a network health measurement cycle.
 * All values are from real measurements — never synthesized.
 */
data class NetworkHealthReport(
    val timestamp: Long,
    val gatewayLatencyMs: Float?,       // null = unreachable / not measured
    val internetLatencyMs: Float?,
    val packetLossPercent: Float?,
    val jitterMs: Float?,
    val dnsLatencyMs: Float?,
    val wanState: WanHealthState,
    val routerCpuPercent: Float?,
    val routerRamFreeKb: Long?,
    val routerRamTotalKb: Long?,
    val routerTemperatureCelsius: Float?,
    val interfaceErrors: Map<String, Long>,  // interface name → error count
    val activeClientCount: Int,
    val diagnosedIssues: List<NetworkIssue>
)

enum class WanHealthState {
    CONNECTED,
    DEGRADED,       // connected but packet loss or speed mismatch
    DISCONNECTED,
    UNKNOWN
}

/**
 * A single diagnosed issue produced by the rule engine.
 */
data class NetworkIssue(
    val id: String,
    val severity: IssueSeverity,
    val category: IssueCategory,
    val title: String,
    val description: String,
    val evidence: List<String>,         // list of measurements that triggered this
    val recommendation: String
)

enum class IssueSeverity { INFO, WARNING, CRITICAL }

enum class IssueCategory {
    WAN,
    LAN,
    WIFI,
    DNS,
    ROUTER_PERFORMANCE,
    CABLE,
    CLIENT
}

/**
 * Result of a single latency probe (ICMP or TCP).
 */
data class LatencyProbe(
    val target: String,
    val latencyMs: Float?,      // null = timeout
    val packetLoss: Boolean,
    val timestamp: Long
)

/**
 * Aggregated latency stats over multiple probes.
 */
data class LatencyStats(
    val target: String,
    val minMs: Float,
    val maxMs: Float,
    val avgMs: Float,
    val jitterMs: Float,
    val packetLossPercent: Float,
    val probeCount: Int
)
