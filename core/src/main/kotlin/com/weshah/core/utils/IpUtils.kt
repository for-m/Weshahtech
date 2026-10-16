package com.weshah.core.utils

import java.net.Inet4Address
import java.net.InetAddress

object IpUtils {
    fun isValidIpv4(ip: String): Boolean = try {
        val parts = ip.split(".")
        parts.size == 4 && parts.all { it.toInt() in 0..255 }
    } catch (e: NumberFormatException) { false }

    fun ipToLong(ip: String): Long {
        val parts = ip.split(".").map { it.toLong() }
        return (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
    }

    fun longToIp(ip: Long): String =
        "${(ip shr 24) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 8) and 0xFF}.${ip and 0xFF}"

    fun getNetworkPrefix(ip: String, prefixLength: Int): String {
        val mask = if (prefixLength == 0) 0L else (-1L shl (32 - prefixLength)) and 0xFFFFFFFFL
        val ipLong = ipToLong(ip)
        return longToIp(ipLong and mask)
    }

    fun getHostsInSubnet(baseIp: String, prefixLength: Int): List<String> {
        val hostCount = (1 shl (32 - prefixLength)) - 2
        if (hostCount <= 0 || hostCount > 65534) return emptyList()
        val networkLong = ipToLong(getNetworkPrefix(baseIp, prefixLength))
        return (1..hostCount).map { longToIp(networkLong + it) }
    }

    fun getDefaultGateway(): String? = try {
        val socket = java.net.DatagramSocket()
        socket.connect(InetAddress.getByName("8.8.8.8"), 80)
        val localAddress = socket.localAddress.hostAddress
        socket.close()
        // Derive gateway heuristically from local IP
        localAddress?.let { ip ->
            val parts = ip.split(".")
            if (parts.size == 4) "${parts[0]}.${parts[1]}.${parts[2]}.1" else null
        }
    } catch (e: Exception) { null }

    fun bytesToHuman(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> String.format("%.2f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> String.format("%.2f MB", bytes / 1_048_576.0)
        bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    fun bpsToHuman(bps: Long): String = when {
        bps >= 1_000_000L -> String.format("%.2f Mbps", bps / 1_000_000.0)
        bps >= 1000L -> String.format("%.1f Kbps", bps / 1000.0)
        else -> "$bps bps"
    }
}
