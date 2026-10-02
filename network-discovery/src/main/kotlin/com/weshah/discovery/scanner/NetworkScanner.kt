package com.weshah.discovery.scanner

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.wifi.WifiManager
import com.weshah.core.models.*
import com.weshah.core.utils.IpUtils
import com.weshah.core.utils.MacUtils
import com.weshah.core.utils.OuiDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.net.*
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Network Scanner — discovers LAN devices using multiple probing strategies.
 *
 * Strategies (applied in parallel):
 * 1. ARP cache reading (/proc/net/arp) — instant, no network traffic
 * 2. ICMP ping — fast host detection (Android limitations apply)
 * 3. TCP probe on common ports (80, 443, 554, 22, 23) — discovers non-ICMP hosts
 * 4. Hostname resolution via DNS/mDNS
 * 5. DHCP lease data from router (if connected) — most authoritative
 *
 * Android limitations:
 * - Raw ICMP requires root on Android 10+ → we use InetAddress.isReachable() which
 *   falls back to TCP echo (port 7), which may timeout for most devices.
 *   This is documented in ANDROID_LIMITATIONS.md.
 * - ARP injection requires root → not attempted
 * - MAC reading from ARP cache does NOT require root
 *
 * The most reliable scan requires router API access for DHCP leases.
 */
@Singleton
class NetworkScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {
    data class ScanProgress(
        val total: Int,
        val completed: Int,
        val discovered: Int
    )

    data class ScanResult(
        val devices: List<DiscoveredDevice>,
        val localIp: String?,
        val gatewayIp: String?,
        val subnetPrefix: Int,
        val durationMs: Long
    )

    data class DiscoveredDevice(
        val ipAddress: String,
        val macAddress: String?,
        val hostname: String?,
        val manufacturer: String?,
        val isOnline: Boolean,
        val discoveryMethod: String
    )

    private val _scanProgress = MutableStateFlow(ScanProgress(0, 0, 0))
    val scanProgress: StateFlow<ScanProgress> = _scanProgress.asStateFlow()

    suspend fun scanNetwork(
        onProgress: ((ScanProgress) -> Unit)? = null
    ): ScanResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val networkInfo = getNetworkInfo()
        val localIp = networkInfo.first
        val prefix = networkInfo.second

        if (localIp == null) {
            return@withContext ScanResult(emptyList(), null, null, 24, 0)
        }

        val gatewayIp = deriveGateway(localIp, prefix)
        val hostsToScan = if (prefix >= 16) {
            IpUtils.getHostsInSubnet(localIp, prefix)
        } else {
            // Very large subnet — limit to /24
            IpUtils.getHostsInSubnet(localIp, 24)
        }

        val total = hostsToScan.size
        _scanProgress.value = ScanProgress(total, 0, 0)

        val discoveredMap = mutableMapOf<String, DiscoveredDevice>()
        val mutex = kotlinx.coroutines.sync.Mutex()

        // Step 1: Read ARP cache first (instant, no network)
        readArpCache().forEach { (ip, mac) ->
            val vendor = mac?.let { OuiDatabase.lookupVendor(it) }
            discoveredMap[ip] = DiscoveredDevice(ip, mac, null, vendor, true, "arp_cache")
        }

        // Step 2: Parallel probe all hosts
        val semaphore = kotlinx.coroutines.sync.Semaphore(64) // Max 64 concurrent
        var completed = 0

        hostsToScan.map { ip ->
            async {
                semaphore.withPermit {
                    val existing = discoveredMap[ip]
                    val isOnline = probeHost(ip, timeout = 300)

                    if (isOnline || existing != null) {
                        val mac = existing?.macAddress ?: getArpEntry(ip)
                        val hostname = resolveHostname(ip)
                        val vendor = mac?.let { OuiDatabase.lookupVendor(it) }

                        val device = DiscoveredDevice(
                            ipAddress = ip,
                            macAddress = mac,
                            hostname = hostname,
                            manufacturer = vendor,
                            isOnline = isOnline || existing != null,
                            discoveryMethod = when {
                                existing != null && isOnline -> "arp_cache+ping"
                                existing != null -> "arp_cache"
                                isOnline -> "ping"
                                else -> "arp_cache"
                            }
                        )

                        mutex.withLock {
                            discoveredMap[ip] = device
                        }
                    }

                    val newCompleted = ++completed
                    val progress = ScanProgress(total, newCompleted, discoveredMap.size)
                    _scanProgress.value = progress
                    onProgress?.invoke(progress)
                }
            }
        }.awaitAll()

        // Always include gateway if reachable
        if (gatewayIp != null && !discoveredMap.containsKey(gatewayIp)) {
            if (probeHost(gatewayIp, timeout = 500)) {
                val mac = getArpEntry(gatewayIp)
                discoveredMap[gatewayIp] = DiscoveredDevice(
                    gatewayIp, mac, "gateway",
                    mac?.let { OuiDatabase.lookupVendor(it) }, true, "gateway_probe"
                )
            }
        }

        ScanResult(
            devices = discoveredMap.values.toList().sortedBy { IpUtils.ipToLong(it.ipAddress) },
            localIp = localIp,
            gatewayIp = gatewayIp,
            subnetPrefix = prefix,
            durationMs = System.currentTimeMillis() - startTime
        )
    }

    /**
     * Read the ARP table from /proc/net/arp — no root required on any Android version.
     * Returns map of IP -> MAC (normalized).
     */
    fun readArpCache(): Map<String, String?> {
        return try {
            val result = mutableMapOf<String, String?>()
            java.io.File("/proc/net/arp").bufferedReader().use { reader ->
                reader.readLine() // skip header
                reader.forEachLine { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 6) {
                        val ip = parts[0]
                        val mac = parts[3]
                        val flags = parts[2]
                        // flags 0x0 = incomplete, 0x2 = complete
                        if (IpUtils.isValidIpv4(ip) && mac != "00:00:00:00:00:00" && flags != "0x0") {
                            result[ip] = MacUtils.normalize(mac)
                        }
                    }
                }
            }
            result
        } catch (e: Exception) {
            Timber.w(e, "Failed to read ARP cache")
            emptyMap()
        }
    }

    private fun getArpEntry(ip: String): String? {
        return readArpCache()[ip]
    }

    /**
     * Probe a host using InetAddress.isReachable() + TCP port checks.
     *
     * Android limitation: InetAddress.isReachable() uses ICMP only if the app has
     * CAP_NET_RAW (not available to normal apps). It falls back to TCP echo (port 7)
     * which most modern hosts don't respond to. This means many live hosts will show
     * as unreachable via ping. ARP cache entries are more reliable indicators of
     * online presence on LAN.
     *
     * See ANDROID_LIMITATIONS.md for details.
     */
    private fun probeHost(ip: String, timeout: Int): Boolean {
        // Try InetAddress.isReachable first
        try {
            if (InetAddress.getByName(ip).isReachable(timeout)) return true
        } catch (e: Exception) { /* continue */ }

        // TCP probe on common ports
        return TCP_PROBE_PORTS.any { port -> tcpProbe(ip, port, timeout) }
    }

    private fun tcpProbe(ip: String, port: Int, timeout: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeout)
                true
            }
        } catch (e: Exception) { false }
    }

    private fun resolveHostname(ip: String): String? {
        return try {
            val addr = InetAddress.getByName(ip)
            val hostname = addr.canonicalHostName
            if (hostname == ip) null else hostname
        } catch (e: Exception) { null }
    }

    private fun getNetworkInfo(): Pair<String?, Int> {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return Pair(null, 24)
            val lp: LinkProperties = cm.getLinkProperties(network) ?: return Pair(null, 24)

            for (linkAddr in lp.linkAddresses) {
                val addr = linkAddr.address
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    return Pair(addr.hostAddress, linkAddr.prefixLength)
                }
            }
            Pair(null, 24)
        } catch (e: Exception) {
            // Fallback for API level issues
            try {
                val wifiMgr = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val dhcpInfo = wifiMgr.dhcpInfo
                if (dhcpInfo.ipAddress != 0) {
                    val ip = if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
                        Integer.reverseBytes(dhcpInfo.ipAddress)
                    } else {
                        dhcpInfo.ipAddress
                    }
                    val ipStr = IpUtils.longToIp(ip.toLong() and 0xFFFFFFFFL)
                    Pair(ipStr, 24)
                } else Pair(null, 24)
            } catch (e2: Exception) {
                Pair(null, 24)
            }
        }
    }

    private fun deriveGateway(localIp: String, prefix: Int): String? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return null
            val lp = cm.getLinkProperties(network) ?: return null
            lp.routes.firstOrNull { it.isDefaultRoute && it.gateway != null }
                ?.gateway?.hostAddress
        } catch (e: Exception) {
            IpUtils.getDefaultGateway()
        }
    }

    companion object {
        private val TCP_PROBE_PORTS = intArrayOf(80, 443, 22, 554, 8080, 8443, 53, 21, 23)
    }
}
