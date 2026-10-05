package com.akylas.enforcedoze.monitor

import com.akylas.enforcedoze.access.Feature
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
        val groups = events.filter { it.sessionId >= 0 }.groupBy { it.bootId to it.sessionId }
            .toSortedMap(compareBy<Pair<Int, Long>> { it.first }.thenBy { it.second })
        return groups.values.flatMap { summarizeGroup(it) }
    }

    private fun summarizeGroup(events: List<JournalEvent>): List<SessionSummary> {
        val result = mutableListOf<SessionSummary>()
        var segment = mutableListOf<JournalEvent>()
        for (event in events.sortedBy { it.elapsedRealtime }) {
            if (startsNextSegment(event, segment)) {
                result += summarizeSession(segment)
                segment = mutableListOf()
            }
            segment += event
        }
        if (hasScreenOff(segment)) result += summarizeSession(segment)
        return result
    }

    private fun hasScreenOff(events: List<JournalEvent>): Boolean {
        return events.any { it.type == EventType.SCREEN_OFF }
    }

    private fun startsNextSegment(event: JournalEvent, segment: List<JournalEvent>): Boolean {
        return event.type == EventType.SCREEN_OFF && hasScreenOff(segment)
    }

    private fun summarizeSession(events: List<JournalEvent>): SessionSummary {
        val off = events.firstOrNull { it.type == EventType.SCREEN_OFF }
        val start = sessionStart(events, off)
        val on = screenOn(events, start)
        val end = on?.elapsedRealtime ?: events.last().elapsedRealtime
        val duration = (end - start).coerceAtLeast(0)
        val observations = stateObservations(events.filter { it.elapsedRealtime <= end })
        val times = coverageTimes(observations, start, end)
        val firstIdle = firstDeepIdle(observations, start)
        val sensors = sensorVerification(screenOffSensor(sensorWindow(events, start, end, on)))
        val battery = battery(events.filter { it.elapsedRealtime in start..end })
        return SessionSummary(
            events.first().bootId, events.first().sessionId, start, end,
            sessionWallTime(events, off), duration, firstIdle,
            times, coveragePercent(times, duration), maintenanceCount(observations, start),
            exitReasons(observations, start), events.count { it.type == EventType.REFORCE }, sensors,
            battery.first, batteryRate(battery), battery.second,
            sessionProblems(events, off, on, firstIdle, sensors),
        )
    }

    private fun sessionStart(events: List<JournalEvent>, off: JournalEvent?): Long {
        return off?.elapsedRealtime ?: events.first().elapsedRealtime
    }

    private fun sessionWallTime(events: List<JournalEvent>, off: JournalEvent?): Long {
        return off?.wallTime ?: events.first().wallTime
    }

    private fun screenOn(events: List<JournalEvent>, start: Long): JournalEvent? {
        return events.firstOrNull { it.type == EventType.SCREEN_ON && it.elapsedRealtime >= start }
    }

    private fun sessionProblems(
        events: List<JournalEvent>, off: JournalEvent?, on: JournalEvent?,
        firstIdle: Long?, sensors: SensorVerification,
    ): List<Problem> {
        val problems = linkedSetOf<Problem>()
        addProblem(problems, isPartial(events, off, on), Problem.PARTIAL_SESSION)
        addProblem(problems, events.any { it.historyTruncated }, Problem.HISTORY_TRUNCATED)
        addProblem(problems, events.any { it.type == EventType.RESTORE_FAILED }, Problem.RESTORE_FAILED)
        addProblem(problems, events.any { it.type == EventType.RECOVERY_DEBT }, Problem.RECOVERY_DEBT)
        addProblem(problems, events.any { accessLost(it) }, Problem.ACCESS_LOST)
        addProblem(problems, firstIdle == null, Problem.NEVER_REACHED_DEEP)
        addProblem(problems, sensors == SensorVerification.UNVERIFIED, Problem.SENSORS_UNVERIFIED)
        return problems.toList()
    }

    private fun isPartial(events: List<JournalEvent>, off: JournalEvent?, on: JournalEvent?): Boolean {
        return off == null || on == null || events.first().bootId < 0
    }

    private fun addProblem(problems: MutableSet<Problem>, present: Boolean, problem: Problem) {
        if (present) problems += problem
    }

    /** Each row carries the state after its observation; non-state rows retain prior evidence. */
    private fun stateObservations(
        events: List<JournalEvent>,
    ): List<Pair<JournalEvent, Pair<DeepState?, LightState?>>> {
        var states: Pair<DeepState?, LightState?> = null to null
        return events.map { event ->
            states = observedStates(event, states)
            event to states
        }
    }

    private fun observedStates(
        event: JournalEvent, previous: Pair<DeepState?, LightState?>,
    ): Pair<DeepState?, LightState?> {
        if (accessLost(event)) return null to null
        val observation = event.historyKind?.let { HistoryMerger.statesFor(it) }
        if (observation != null) return observation
        return if (stateObserved(event)) event.deep to event.light else previous
    }

    private fun stateObserved(event: JournalEvent): Boolean {
        return hasStateValues(event) || hasStateMarker(event) || accessLost(event)
    }

    private fun hasStateValues(event: JournalEvent): Boolean {
        return event.historyKind != null || event.deep != null || event.light != null
    }

    private fun hasStateMarker(event: JournalEvent): Boolean {
        return (event.type in setOf(EventType.VERIFY, EventType.IDLE_CHANGED) && event.sensor == null) ||
                event.type in setOf(EventType.MAINT_START, EventType.MAINT_END)
    }

    private fun coverageTimes(
        observations: List<Pair<JournalEvent, Pair<DeepState?, LightState?>>>, start: Long, end: Long,
    ): Map<Coverage, Long> {
        val times = Coverage.entries.associateWith { 0L }.toMutableMap()
        var states: Pair<DeepState?, LightState?> = null to null
        var previous = start
        for ((event, observed) in observations) {
            val at = event.elapsedRealtime.coerceAtLeast(start)
            accumulateCoverage(times, states, at - previous)
            previous = at
            states = observed
        }
        accumulateCoverage(times, states, end - previous)
        return times.toMap()
    }

    private fun accumulateCoverage(
        times: MutableMap<Coverage, Long>, states: Pair<DeepState?, LightState?>, duration: Long,
    ) {
        val category = coverage(states.first, states.second)
        times[category] = times.getValue(category) + duration.coerceAtLeast(0)
    }

    private fun coveragePercent(times: Map<Coverage, Long>, duration: Long): Map<Coverage, Double> {
        return times.mapValues { if (duration == 0L) 0.0 else it.value * 100.0 / duration }
    }

    private fun firstDeepIdle(
        observations: List<Pair<JournalEvent, Pair<DeepState?, LightState?>>>, start: Long,
    ): Long? {
        return observations.firstOrNull {
            it.first.elapsedRealtime >= start && it.second.first == DeepState.IDLE
        }?.first?.elapsedRealtime?.minus(start)
    }

    private fun maintenanceCount(
        observations: List<Pair<JournalEvent, Pair<DeepState?, LightState?>>>, start: Long,
    ): Int {
        var maintenance = false
        var count = 0
        for ((event, states) in observations) {
            val nowMaintenance = inMaintenance(states)
            if (startsMaintenance(event, start, maintenance, nowMaintenance)) count++
            maintenance = maintenanceState(event, maintenance, nowMaintenance)
        }
        return count
    }

    private fun inMaintenance(states: Pair<DeepState?, LightState?>): Boolean {
        return states.first == DeepState.IDLE_MAINTENANCE || states.second == LightState.IDLE_MAINTENANCE
    }

    private fun startsMaintenance(
        event: JournalEvent, start: Long, maintenance: Boolean, nowMaintenance: Boolean,
    ): Boolean {
        return event.elapsedRealtime >= start &&
            (!maintenance || event.type == EventType.SCREEN_OFF) &&
            (nowMaintenance || event.type == EventType.MAINT_START)
    }

    private fun maintenanceState(event: JournalEvent, previous: Boolean, nowMaintenance: Boolean): Boolean {
        return when {
            event.type == EventType.MAINT_END -> false
            event.type == EventType.MAINT_START -> true
            stateObserved(event) -> nowMaintenance
            else -> previous
        }
    }

    private fun exitReasons(
        observations: List<Pair<JournalEvent, Pair<DeepState?, LightState?>>>, start: Long,
    ): Map<String, Int> {
        val exits = linkedMapOf<String, Int>()
        val exitKeys = mutableListOf<Pair<String, Long>>()
        var states: Pair<DeepState?, LightState?> = null to null
        for ((event, observed) in observations) {
            val at = event.elapsedRealtime.coerceAtLeast(start)
            if (isReasonedExit(event, states, start)) recordExit(exits, exitKeys, event.detail.orEmpty(), at)
            states = observed
        }
        return exits.toMap()
    }

    // OS reasons survive even if an APP observation arrived just before the history transition.
    private fun isReasonedExit(event: JournalEvent, previous: Pair<DeepState?, LightState?>, start: Long): Boolean {
        return event.historyKind == HistoryKind.NORMAL && !event.detail.isNullOrBlank() &&
                (wasIdle(previous) || event.elapsedRealtime > start)
    }

    private fun wasIdle(states: Pair<DeepState?, LightState?>): Boolean {
        return states.first == DeepState.IDLE || states.second == LightState.IDLE
    }

    private fun recordExit(
        exits: MutableMap<String, Int>, keys: MutableList<Pair<String, Long>>, reason: String, at: Long,
    ) {
        if (keys.any { it.first == reason && kotlin.math.abs(it.second - at) <= 1_000 }) return
        exits[reason] = (exits[reason] ?: 0) + 1
        keys += reason to at
    }

    private fun sensorWindow(
        events: List<JournalEvent>, start: Long, end: Long, on: JournalEvent?,
    ): List<JournalEvent> {
        return events.filter {
            it.elapsedRealtime >= start && (on == null || it.elapsedRealtime < end)
        }
    }

    private fun sensorVerification(sensor: SensorMode?): SensorVerification {
        return when (sensor) {
            SensorMode.RESTRICTED -> SensorVerification.YES
            SensorMode.NORMAL, SensorMode.OTHER -> SensorVerification.NO
            SensorMode.UNVERIFIED, null -> SensorVerification.UNVERIFIED
        }
    }

    private fun batteryRate(battery: Pair<Int?, Long>): Double? {
        return battery.first?.let { if (battery.second > 0) it * 3_600_000.0 / battery.second else null }
    }

    /** Restore markers identify the immediately preceding app VERIFY as a restore readback. */
    private fun screenOffSensor(events: List<JournalEvent>): SensorMode? {
        var restoring = false
        // OS history can be interleaved with app rows, but never carries sensor observations.
        for (event in events.filter { it.source == Source.APP }.asReversed()) {
            if (event.type == EventType.SENSORS_RESTORED) {
                restoring = isMotionSensors(event)
                continue
            }
            if (isRestoreReadback(event, restoring)) {
                restoring = false
                continue
            }
            restoring = false
            if (event.sensor != null) return event.sensor
        }
        return null
    }

    private fun isMotionSensors(event: JournalEvent): Boolean {
        return event.detail?.substringBefore(':') == Feature.MOTION_SENSORS.name
    }

    private fun isRestoreReadback(event: JournalEvent, restoring: Boolean): Boolean {
        return restoring && event.type == EventType.VERIFY && isMotionSensors(event)
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
