package com.weshah.core.models

data class AccessRule(
    val id: String,
    val macAddress: String,
    val ruleType: AccessRuleType,
    val allowedHours: List<TimeRange>?,  // null = always applies
    val createdAt: Long,
    val expiresAt: Long?,                // null = permanent
    val comment: String?
)

enum class AccessRuleType {
    BLOCK_INTERNET,     // block WAN, allow LAN
    BLOCK_COMPLETE,     // block all traffic
    SCHEDULE            // time-based access control
}

data class TimeRange(
    val startHour: Int,    // 0-23
    val startMinute: Int,  // 0-59
    val endHour: Int,
    val endMinute: Int,
    val daysOfWeek: Set<Int> // 1=Mon, 7=Sun; empty = all days
) {
    fun isCurrentlyAllowed(): Boolean {
        val now = java.util.Calendar.getInstance()
        val currentMinutes = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
        val startMinutes = startHour * 60 + startMinute
        val endMinutes = endHour * 60 + endMinute
        val currentDay = now.get(java.util.Calendar.DAY_OF_WEEK)
        if (daysOfWeek.isNotEmpty() && currentDay !in daysOfWeek) return false
        return if (startMinutes <= endMinutes) {
            currentMinutes in startMinutes..endMinutes
        } else {
            currentMinutes >= startMinutes || currentMinutes <= endMinutes
        }
    }
}
