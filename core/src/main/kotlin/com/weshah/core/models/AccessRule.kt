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

/**
 * An allowed window. Semantics are identical to weshah-agent (the router enforces them):
 *  - times are router-local; [endHour]:[endMinute] is exclusive, except 23:59 which means end of day
 *  - start == end means the whole day
 *  - start > end wraps past midnight and belongs to the day it started on
 *  - [daysOfWeek] uses ISO numbering (1 = Monday ... 7 = Sunday); empty = every day
 */
data class TimeRange(
    val startHour: Int,    // 0-23
    val startMinute: Int,  // 0-59
    val endHour: Int,
    val endMinute: Int,
    val daysOfWeek: Set<Int> // 1=Mon, 7=Sun; empty = all days
) {
    private val startMinuteOfDay get() = startHour * 60 + startMinute
    private val endMinuteOfDay get() = if (endHour == 23 && endMinute == 59) 1440 else endHour * 60 + endMinute

    private fun dayMatches(isoDay: Int) = daysOfWeek.isEmpty() || isoDay in daysOfWeek

    /** @param isoDay 1 = Monday ... 7 = Sunday @param minuteOfDay 0..1439 */
    fun isAllowedAt(isoDay: Int, minuteOfDay: Int): Boolean {
        val s = startMinuteOfDay
        val e = endMinuteOfDay
        val previousDay = (isoDay + 5) % 7 + 1
        return when {
            s == e -> dayMatches(isoDay)
            s < e -> dayMatches(isoDay) && minuteOfDay >= s && minuteOfDay < e
            else -> (dayMatches(isoDay) && minuteOfDay >= s) || (dayMatches(previousDay) && minuteOfDay < e)
        }
    }

    fun isCurrentlyAllowed(): Boolean {
        val now = java.util.Calendar.getInstance()
        val minuteOfDay = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
        // Calendar: 1 = Sunday ... 7 = Saturday  ->  ISO: 1 = Monday ... 7 = Sunday
        val isoDay = (now.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7 + 1
        return isAllowedAt(isoDay, minuteOfDay)
    }
}
