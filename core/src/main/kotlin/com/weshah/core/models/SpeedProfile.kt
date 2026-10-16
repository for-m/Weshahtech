package com.weshah.core.models

data class SpeedProfile(
    val id: String,
    val name: String,
    val downloadKbps: Long?,    // null = unlimited
    val uploadKbps: Long?,      // null = unlimited
    val isBuiltIn: Boolean
) {
    companion object {
        val UNLIMITED = SpeedProfile("unlimited", "Unlimited", null, null, true)
        val PRESET_1MBPS = SpeedProfile("1mbps", "1 Mbps", 1024, 1024, true)
        val PRESET_2MBPS = SpeedProfile("2mbps", "2 Mbps", 2048, 2048, true)
        val PRESET_5MBPS = SpeedProfile("5mbps", "5 Mbps", 5120, 5120, true)
        val PRESET_10MBPS = SpeedProfile("10mbps", "10 Mbps", 10240, 10240, true)
        val PRESET_20MBPS = SpeedProfile("20mbps", "20 Mbps", 20480, 20480, true)

        val BUILT_IN_PROFILES = listOf(
            UNLIMITED, PRESET_1MBPS, PRESET_2MBPS,
            PRESET_5MBPS, PRESET_10MBPS, PRESET_20MBPS
        )
    }
}

data class DeviceSpeedLimit(
    val macAddress: String,
    val profileId: String,
    val downloadKbps: Long?,
    val uploadKbps: Long?,
    val appliedAt: Long,
    val isActive: Boolean
)
