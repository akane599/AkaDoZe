package com.akylas.enforcedoze.monitor

import com.akylas.enforcedoze.doze.DeepState
import com.akylas.enforcedoze.doze.EventType
import com.akylas.enforcedoze.doze.LightState
import com.akylas.enforcedoze.doze.SensorMode
import com.akylas.enforcedoze.doze.parse.HistoryKind

enum class Problem {
    NEVER_REACHED_DEEP, SENSORS_UNVERIFIED, RESTORE_FAILED, RECOVERY_DEBT,
    HISTORY_TRUNCATED, PARTIAL_SESSION, ACCESS_LOST,
}

enum class SensorVerification { YES, NO, UNVERIFIED }
enum class Coverage { DEEP_IDLE, LIGHT_IDLE, ACTIVE, UNKNOWN }

data class SessionSummary(
    val bootId: Int,
    val sessionId: Long,
    val startElapsed: Long,
    val endElapsed: Long,
    val startWallTime: Long,
    val durationMs: Long,
    val timeToFirstDeepIdleMs: Long?,
    val coverageMs: Map<Coverage, Long>,
    val coveragePercent: Map<Coverage, Double>,
    val maintenanceCount: Int,
    val exitsByReason: Map<String, Int>,
    val reforceCount: Int,
    val sensorsRestricted: SensorVerification,
    val batteryDrop: Int?,
    val batteryPercentPerHour: Double?,
    val nonChargingSampledMs: Long,
    val problems: List<Problem>,
)

object SessionAggregator {
    /**
     * Session ids are scoped to a boot. Reboots never contribute elapsed duration to another boot.
     * Evidence describes the interval until the next state observation; an explicit unknown snapshot
     * or access loss clears that evidence. Missing initial evidence is always UNKNOWN.
     * Open sessions end at their last observation, not at an invented current time.
     */
    @JvmStatic
    fun summarize(events: List<JournalEvent>): List<SessionSummary> {
        val result = mutableListOf<SessionSummary>()
        val groups = events.filter { it.sessionId >= 0 }.groupBy { it.bootId to it.sessionId }
            .toSortedMap(compareBy<Pair<Int, Long>> { it.first }.thenBy { it.second })
        for ((_, group) in groups) {
            val sorted = group.sortedBy { it.elapsedRealtime }
            var segment = mutableListOf<JournalEvent>()
            for (event in sorted) {
                if (event.type == EventType.SCREEN_OFF && segment.any { it.type == EventType.SCREEN_OFF }) {
                    result += summarizeSession(segment)
                    segment = mutableListOf()
                }
                segment += event
            }
            if (segment.isNotEmpty()) result += summarizeSession(segment)
        }
        return result
    }

    private fun summarizeSession(events: List<JournalEvent>): SessionSummary {
        val off = events.firstOrNull { it.type == EventType.SCREEN_OFF }
        val start = off?.elapsedRealtime ?: events.first().elapsedRealtime
        val on = events.firstOrNull { it.type == EventType.SCREEN_ON && it.elapsedRealtime >= start }
        val end = on?.elapsedRealtime ?: events.last().elapsedRealtime
        val duration = (end - start).coerceAtLeast(0)
        val problems = linkedSetOf<Problem>()
        if (off == null || on == null || events.first().bootId < 0) problems += Problem.PARTIAL_SESSION
        if (events.any { it.historyTruncated }) problems += Problem.HISTORY_TRUNCATED
        if (events.any { it.type == EventType.RESTORE_FAILED }) problems += Problem.RESTORE_FAILED
        if (events.any { it.type == EventType.RECOVERY_DEBT }) problems += Problem.RECOVERY_DEBT
        if (events.any { accessLost(it) }) problems += Problem.ACCESS_LOST

        val times = Coverage.entries.associateWith { 0L }.toMutableMap()
        var deep: DeepState? = null
        var light: LightState? = null
        var previous = start
        var firstIdle: Long? = null
        var maintenance = false
        var maintenanceCount = 0
        var lastSensor: SensorMode? = null
        val exits = linkedMapOf<String, Int>()
        val exitKeys = mutableListOf<Pair<String, Long>>()
        for (event in events) {
            if (event.elapsedRealtime > end) continue
            val at = event.elapsedRealtime.coerceAtLeast(start)
            val category = coverage(deep, light)
            times[category] = times.getValue(category) + (at - previous).coerceAtLeast(0)
            previous = at
            val wasIdle = deep == DeepState.IDLE || light == LightState.IDLE
            val observation = event.historyKind?.let { HistoryMerger.statesFor(it) }
            val stateObserved = observation != null || event.deep != null || event.light != null ||
                (event.type in setOf(EventType.VERIFY, EventType.IDLE_CHANGED) && event.sensor == null) ||
                event.type in setOf(EventType.MAINT_START, EventType.MAINT_END) || accessLost(event)
            if (accessLost(event)) {
                deep = null
                light = null
            } else if (observation != null) {
                deep = observation.first
                light = observation.second
            } else if (stateObserved) {
                deep = event.deep
                light = event.light
            }
            if (event.elapsedRealtime >= start && deep == DeepState.IDLE && firstIdle == null) firstIdle = at - start
            val nowMaintenance = deep == DeepState.IDLE_MAINTENANCE || light == LightState.IDLE_MAINTENANCE
            if (event.elapsedRealtime >= start && (!maintenance || event.type == EventType.SCREEN_OFF) &&
                (nowMaintenance || event.type == EventType.MAINT_START)
            ) maintenanceCount++
            maintenance = when {
                event.type == EventType.MAINT_END -> false
                event.type == EventType.MAINT_START -> true
                stateObserved -> nowMaintenance
                else -> maintenance
            }
            // OS reasons survive even if an APP observation arrived just before the history transition.
            if (event.historyKind == HistoryKind.NORMAL && !event.detail.isNullOrBlank()) {
                val reason = event.detail
                if (exitKeys.none { it.first == reason && kotlin.math.abs(it.second - at) <= 1_000 } &&
                    (wasIdle || event.elapsedRealtime > start)
                ) {
                    exits[reason] = (exits[reason] ?: 0) + 1
                    exitKeys += reason to at
                }
            }
            // Restoration at/after SCREEN_ON is not evidence that restriction failed while screen-off.
            if (event.sensor != null && (on == null || event.elapsedRealtime < end)) lastSensor = event.sensor
        }
        val category = coverage(deep, light)
        times[category] = times.getValue(category) + (end - previous).coerceAtLeast(0)
        if (firstIdle == null) problems += Problem.NEVER_REACHED_DEEP
        val sensors = when (lastSensor) {
            SensorMode.RESTRICTED -> SensorVerification.YES
            SensorMode.NORMAL, SensorMode.OTHER -> SensorVerification.NO
            else -> SensorVerification.UNVERIFIED
        }
        if (sensors == SensorVerification.UNVERIFIED) problems += Problem.SENSORS_UNVERIFIED
        val battery = battery(events.filter { it.elapsedRealtime in start..end })
        return SessionSummary(
            events.first().bootId, events.first().sessionId, start, end,
            off?.wallTime ?: events.first().wallTime, duration, firstIdle,
            times.toMap(), times.mapValues { if (duration == 0L) 0.0 else it.value * 100.0 / duration },
            maintenanceCount, exits.toMap(), events.count { it.type == EventType.REFORCE }, sensors,
            battery.first, battery.first?.let { if (battery.second > 0) it * 3_600_000.0 / battery.second else null },
            battery.second, problems.toList(),
        )
    }

    private fun coverage(deep: DeepState?, light: LightState?): Coverage = when {
        deep == DeepState.IDLE -> Coverage.DEEP_IDLE
        light == LightState.IDLE -> Coverage.LIGHT_IDLE
        deep != null && deep != DeepState.UNKNOWN && light != null && light != LightState.UNKNOWN -> Coverage.ACTIVE
        else -> Coverage.UNKNOWN
    }

    private fun accessLost(event: JournalEvent): Boolean = event.type == EventType.ACCESS_CHANGED &&
        event.detail?.split(Regex("[^A-Z_]+"))?.any {
            it in setOf("NONE", "NO_ACCESS", "SHIZUKU_NOT_RUNNING", "SHIZUKU_PERMISSION_MISSING")
        } == true

    /** Only sample pairs whose whole interval is explicitly non-charging can measure discharge. */
    private fun battery(events: List<JournalEvent>): Pair<Int?, Long> {
        var charging: Boolean? = null
        var previous: JournalEvent? = null
        var intervalNonCharging = false
        var drop = 0
        var duration = 0L
        for (event in events) {
            // A transition at the endpoint applies after the preceding interval.
            val prior = previous
            if (event.battery != null && prior != null) {
                val delta = event.elapsedRealtime - prior.elapsedRealtime
                if (intervalNonCharging && delta > 0) {
                    drop += ((prior.battery ?: event.battery) - event.battery).coerceAtLeast(0)
                    duration += delta
                }
            }
            if (event.charging != null) charging = event.charging
            if (event.battery != null) {
                previous = event
                intervalNonCharging = charging == false
            } else if (charging != false) {
                intervalNonCharging = false
            }
        }
        return (if (duration > 0) drop else null) to duration
    }
}
