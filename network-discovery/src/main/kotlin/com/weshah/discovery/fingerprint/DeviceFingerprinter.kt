package com.weshah.discovery.fingerprint

import com.weshah.core.models.DeviceType
import com.weshah.core.utils.OuiDatabase

/**
 * Identifies device type from available signals:
 * 1. MAC OUI vendor → device type hints
 * 2. Hostname patterns
 * 3. Open TCP ports
 * 4. mDNS service types (if discovered)
 */
object DeviceFingerprinter {

    fun identify(
        macAddress: String?,
        hostname: String?,
        openPorts: Set<Int> = emptySet(),
        mdnsServices: List<String> = emptyList()
    ): DeviceType {
        // mDNS service type matching (highest confidence)
        for (svc in mdnsServices) {
            when {
                svc.contains("_airplay") || svc.contains("_raop") -> return DeviceType.TV
                svc.contains("_ipp") || svc.contains("_pdl-datastream") -> return DeviceType.PRINTER
                svc.contains("_smb") || svc.contains("_afpovertcp") -> return DeviceType.NAS
                svc.contains("_googlecast") -> return DeviceType.TV
                svc.contains("_homekit") || svc.contains("_hap") -> return DeviceType.SMART_HOME
            }
        }

        // Port-based fingerprinting
        when {
            9100 in openPorts || 515 in openPorts -> return DeviceType.PRINTER
            554 in openPorts || 8554 in openPorts -> return DeviceType.CAMERA
            (80 in openPorts || 443 in openPorts) && (22 in openPorts || 23 in openPorts) -> return DeviceType.ROUTER
            9200 in openPorts -> return DeviceType.NAS // Synology
            5000 in openPorts -> return DeviceType.NAS // Synology DSM
        }

        // Hostname pattern matching
        if (hostname != null) {
            val h = hostname.lowercase()
            return when {
                h.contains("router") || h.contains("gateway") || h.contains("openwrt") ||
                        h.contains("mikrotik") || h.contains("asus") || h.contains("tplink") -> DeviceType.ROUTER
                h.contains("android") || h.contains("phone") || h.contains("pixel") ||
                        h.contains("samsung") || h.contains("xiaomi") || h.contains("huawei") -> DeviceType.PHONE
                h.contains("iphone") || h.startsWith("iphone") -> DeviceType.PHONE
                h.contains("ipad") -> DeviceType.TABLET
                h.contains("macbook") || h.contains("imac") || h.contains("mac-") -> DeviceType.COMPUTER
                h.contains("laptop") || h.contains("desktop") || h.contains("pc") ||
                        h.contains("computer") || h.contains("workstation") -> DeviceType.COMPUTER
                h.contains("printer") || h.contains("print") || h.contains("hp") -> DeviceType.PRINTER
                h.contains("camera") || h.contains("cam") || h.contains("ipc") ||
                        h.contains("nvr") || h.contains("dvr") -> DeviceType.CAMERA
                h.contains("tv") || h.contains("firetv") || h.contains("appletv") ||
                        h.contains("chromecast") || h.contains("roku") -> DeviceType.TV
                h.contains("nas") || h.contains("synology") || h.contains("qnap") -> DeviceType.NAS
                h.contains("xbox") || h.contains("playstation") || h.contains("ps4") ||
                        h.contains("ps5") || h.contains("nintendo") -> DeviceType.GAME_CONSOLE
                h.contains("esp") || h.contains("shelly") || h.contains("sonoff") ||
                        h.contains("wemo") || h.contains("hue") -> DeviceType.SMART_HOME
                h.contains("ap-") || h.contains("access-point") || h.contains("unifi") -> DeviceType.AP
                else -> DeviceType.UNKNOWN
            }
        }

        // OUI-based vendor type guessing
        if (macAddress != null) {
            val vendor = OuiDatabase.lookupVendor(macAddress)?.lowercase() ?: ""
            return when {
                vendor.contains("cisco") || vendor.contains("mikrotik") ||
                        vendor.contains("ubiquiti") || vendor.contains("asus") ||
                        vendor.contains("netgear") || vendor.contains("tp-link") ||
                        vendor.contains("d-link") -> DeviceType.ROUTER
                vendor.contains("apple") -> DeviceType.PHONE // Most Apple MACs are phones/macs
                vendor.contains("samsung") || vendor.contains("xiaomi") ||
                        vendor.contains("huawei") -> DeviceType.PHONE
                vendor.contains("raspberry") -> DeviceType.COMPUTER
                vendor.contains("microsoft") -> DeviceType.COMPUTER
                else -> DeviceType.UNKNOWN
            }
        }

        return DeviceType.UNKNOWN
    }

    fun deviceTypeIcon(type: DeviceType): String = when (type) {
        DeviceType.ROUTER -> "router"
        DeviceType.COMPUTER -> "computer"
        DeviceType.PHONE -> "smartphone"
        DeviceType.TABLET -> "tablet"
        DeviceType.TV -> "tv"
        DeviceType.PRINTER -> "print"
        DeviceType.CAMERA -> "videocam"
        DeviceType.SMART_HOME -> "home"
        DeviceType.GAME_CONSOLE -> "sports_esports"
        DeviceType.NAS -> "storage"
        DeviceType.AP -> "wifi"
        DeviceType.UNKNOWN -> "device_unknown"
    }
}
