package com.weshah.core.utils

object MacUtils {
    private val MAC_REGEX = Regex("^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$")

    fun isValid(mac: String): Boolean = MAC_REGEX.matches(mac)

    fun normalize(mac: String): String =
        mac.uppercase().replace("-", ":").trim()

    fun getOuiPrefix(mac: String): String =
        normalize(mac).split(":").take(3).joinToString(":")

    fun fromLong(mac: Long): String {
        return (5 downTo 0).joinToString(":") { i ->
            String.format("%02X", (mac shr (i * 8)) and 0xFF)
        }
    }
}
