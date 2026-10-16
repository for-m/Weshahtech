package com.weshah.router.openwrt

/**
 * Cleartext HTTP is only acceptable on the local network. Android's network-security-config cannot match
 * address ranges, so the platform allows cleartext and this guard enforces "private addresses only".
 * Only IP literals qualify: a host name could resolve anywhere.
 */
object NetworkGuard {

    fun isPrivateLiteral(host: String): Boolean {
        val h = host.trim().removePrefix("[").removeSuffix("]")
        return if (':' in h) isPrivateIpv6(h) else isPrivateIpv4(h)
    }

    private fun isPrivateIpv4(h: String): Boolean {
        val p = h.split('.')
        if (p.size != 4) return false
        val n = p.map { it.toIntOrNull()?.takeIf { v -> v in 0..255 && it.length <= 3 } ?: return false }
        return n[0] == 10 ||
            (n[0] == 172 && n[1] in 16..31) ||
            (n[0] == 192 && n[1] == 168) ||
            (n[0] == 169 && n[1] == 254) ||
            n[0] == 127
    }

    private fun isPrivateIpv6(h: String): Boolean {
        val x = h.lowercase().substringBefore('%')
        if (x == "::1") return true
        val first = x.substringBefore(':').padStart(4, '0')
        val v = first.toIntOrNull(16) ?: return false
        return (v and 0xfe00) == 0xfc00 || (v and 0xffc0) == 0xfe80   // fc00::/7 ULA, fe80::/10 link-local
    }
}
