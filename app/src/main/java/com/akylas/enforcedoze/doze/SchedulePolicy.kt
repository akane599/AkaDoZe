package com.akylas.enforcedoze.doze

/** Local minute-of-day decisions. The Android adapter resolves boundaries in its current timezone. */
object SchedulePolicy {
    data class Period(val start: Int, val end: Int)
    data class Boundary(val minuteOfDay: Int, val daysAhead: Int, val minutesAway: Int)

    @JvmStatic
    fun parse(period: String?): Period? {
        val parts = period?.split('-') ?: return null
        if (parts.size != 2) return null
        val start = minute(parts[0]) ?: return null
        val end = minute(parts[1]) ?: return null
        return if (start == end) null else Period(start, end)
    }

    private fun minute(time: String): Int? {
        val parts = time.split(':')
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        return if (hour in 0..23 && minute in 0..59) hour * 60 + minute else null
    }

    @JvmStatic
    fun isInside(periods: Collection<String>, nowMinute: Int): Boolean {
        require(nowMinute in 0 until 1440)
        if (periods.isEmpty()) return true
        return periods.mapNotNull(::parse).any {
            if (it.start < it.end) nowMinute >= it.start && nowMinute < it.end
            else nowMinute >= it.start || nowMinute < it.end
        }
    }

    /** A scheduled stop must not erase the user's permission to run at the next boundary. */
    @JvmStatic
    fun shouldRunService(userEnabled: Boolean, periods: Collection<String>, nowMinute: Int): Boolean =
        userEnabled && isInside(periods, nowMinute)

    /** Strictly future: a boundary at the current minute was already applied. */
    @JvmStatic
    fun nextBoundary(periods: Collection<String>, nowMinute: Int): Boundary? {
        require(nowMinute in 0 until 1440)
        return periods.mapNotNull(::parse).flatMap { listOf(it.start, it.end) }.map {
            val days = if (it <= nowMinute) 1 else 0
            Boundary(it, days, it - nowMinute + days * 1440)
        }.minByOrNull { it.minutesAway }
    }
}
