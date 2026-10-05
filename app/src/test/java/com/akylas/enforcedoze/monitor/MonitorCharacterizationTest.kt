package com.akylas.enforcedoze.monitor

import com.akylas.enforcedoze.doze.DeepState
import com.akylas.enforcedoze.doze.EventType
import com.akylas.enforcedoze.doze.LightState
import com.akylas.enforcedoze.doze.SensorMode
import com.akylas.enforcedoze.doze.parse.HistoryEvent
import com.akylas.enforcedoze.doze.parse.HistoryKind
import com.akylas.enforcedoze.doze.parse.IdlingHistoryParser
import org.junit.Assert.assertEquals
import org.junit.Test

/** Whole-value goldens for the existing inline monitor fixtures and US-6 edge journals. */
class MonitorCharacterizationTest {
    private fun row(time: Long, type: EventType, boot: Int = 7, session: Long = 20) =
        JournalEvent(boot, time, 1_000_000 + time, session, Source.APP, type)

    private fun times(deep: Long = 0, light: Long = 0, active: Long = 0, unknown: Long = 0) =
        linkedMapOf(Coverage.DEEP_IDLE to deep, Coverage.LIGHT_IDLE to light,
            Coverage.ACTIVE to active, Coverage.UNKNOWN to unknown)

    private fun golden(
        start: Long,
        end: Long,
        coverage: Map<Coverage, Long>,
        percent: Map<Coverage, Double>,
        firstIdle: Long? = null,
        maintenance: Int = 0,
        exits: Map<String, Int> = emptyMap(),
        reforce: Int = 0,
        sensors: SensorVerification = SensorVerification.UNVERIFIED,
        drop: Int? = null,
        rate: Double? = null,
        sampled: Long = 0,
        problems: List<Problem> = emptyList(),
        boot: Int = 7,
        session: Long = 20,
    ) = SessionSummary(
        bootId = boot, sessionId = session, startElapsed = start, endElapsed = end,
        startWallTime = 1_000_000 + start, durationMs = end - start,
        timeToFirstDeepIdleMs = firstIdle, coverageMs = coverage, coveragePercent = percent,
        maintenanceCount = maintenance, exitsByReason = exits, reforceCount = reforce,
        sensorsRestricted = sensors, batteryDrop = drop, batteryPercentPerHour = rate,
        nonChargingSampledMs = sampled, problems = problems,
    )

    private fun percents(deep: Double = 0.0, light: Double = 0.0,
                         active: Double = 0.0, unknown: Double = 0.0) =
        linkedMapOf(Coverage.DEEP_IDLE to deep, Coverage.LIGHT_IDLE to light,
            Coverage.ACTIVE to active, Coverage.UNKNOWN to unknown)

    @Test fun cleanNightFixturePinsEverySummaryField() {
        val rows = listOf(
            row(0, EventType.SCREEN_OFF).copy(deep = DeepState.ACTIVE, light = LightState.ACTIVE,
                battery = 80, charging = false),
            row(5_000, EventType.VERIFY).copy(deep = DeepState.IDLE, sensor = SensorMode.RESTRICTED),
            row(100_000, EventType.SCREEN_ON).copy(battery = 79),
        )
        assertEquals(listOf(golden(0, 100_000, times(deep = 95_000, active = 5_000),
            percents(deep = 95.0, active = 5.0), firstIdle = 5_000,
            sensors = SensorVerification.YES, drop = 1, rate = 36.0, sampled = 100_000)),
            SessionAggregator.summarize(rows))
    }

    @Test fun parserFixturePinsMergeAndEverySummaryField() {
        val rows = listOf(row(10_000, EventType.SCREEN_OFF), row(100_000, EventType.SCREEN_ON))
        val history = IdlingHistoryParser.parse("""
            Idling history:
              normal: -10s (motion)
              deep-idle: -60s
              light-idle: -95s
        """.trimIndent(), 100_000)
        val merge = HistoryMerger.merge(rows, history, 10_000, 7)
        assertEquals(false, merge.truncated)
        assertEquals(listOf(5_000L, 40_000L, 90_000L), merge.importedEvents.map { it.elapsedRealtime })
        assertEquals(listOf(golden(10_000, 100_000,
            times(deep = 50_000, light = 30_000, active = 10_000),
            percents(deep = 50_000 * 100.0 / 90_000, light = 30_000 * 100.0 / 90_000,
                active = 10_000 * 100.0 / 90_000), firstIdle = 30_000,
            exits = mapOf("motion" to 1), problems = listOf(Problem.SENSORS_UNVERIFIED))),
            SessionAggregator.summarize(merge.events))
    }

    @Test fun maintenanceFixturePinsTransitionsAndEverySummaryField() {
        val rows = listOf(row(0, EventType.SCREEN_OFF),
            row(11_000, EventType.MAINT_START).copy(deep = DeepState.IDLE_MAINTENANCE, light = LightState.OVERRIDE),
            row(50_000, EventType.SCREEN_ON))
        val merge = HistoryMerger.merge(rows, listOf(
            HistoryEvent(HistoryKind.DEEP_IDLE, 0, null),
            HistoryEvent(HistoryKind.DEEP_MAINT, 10_000, null),
            HistoryEvent(HistoryKind.DEEP_IDLE, 20_000, null),
            HistoryEvent(HistoryKind.LIGHT_MAINT, 30_000, null),
            HistoryEvent(HistoryKind.LIGHT_IDLE, 40_000, null),
        ), 0, 7)
        assertEquals(listOf(golden(0, 50_000,
            times(deep = 21_000, light = 10_000, active = 9_000, unknown = 10_000),
            percents(deep = 42.0, light = 20.0, active = 18.0, unknown = 20.0),
            firstIdle = 0, maintenance = 2, problems = listOf(Problem.SENSORS_UNVERIFIED))),
            SessionAggregator.summarize(merge.events))
    }

    @Test fun preSessionRowsAndTeardownReadbackPinAllFieldsAndProblemOrder() {
        val rows = listOf(
            row(0, EventType.ACCESS_CHANGED, session = 0).copy(detail = "NO_ACCESS"),
            row(1, EventType.SENSORS_RESTORED, session = 0).copy(sensor = SensorMode.NORMAL),
            row(10_000, EventType.SCREEN_OFF),
            row(11_000, EventType.SENSORS_RESTRICTED).copy(sensor = SensorMode.RESTRICTED),
            row(12_000, EventType.VERIFY).copy(deep = DeepState.IDLE),
            row(15_000, EventType.REFORCE),
            row(18_000, EventType.VERIFY).copy(sensor = SensorMode.NORMAL, detail = "MOTION_SENSORS"),
            row(18_002, EventType.SENSORS_RESTORED).copy(sensor = SensorMode.NORMAL, detail = "MOTION_SENSORS"),
            row(20_000, EventType.SCREEN_ON),
            row(21_000, EventType.RESTORE_FAILED), row(22_000, EventType.RECOVERY_DEBT),
            row(23_000, EventType.ACCESS_CHANGED).copy(detail = "SHIZUKU_NOT_RUNNING"),
        )
        val merge = HistoryMerger.merge(rows,
            listOf(HistoryEvent(HistoryKind.NORMAL, 18_001, "motion")), 10_000, 7)
        assertEquals(listOf(golden(10_000, 20_000,
            times(deep = 6_001, active = 1_999, unknown = 2_000),
            percents(deep = 60.01, active = 19.99, unknown = 20.0), firstIdle = 2_000,
            exits = mapOf("motion" to 1), reforce = 1, sensors = SensorVerification.YES,
            problems = listOf(Problem.RESTORE_FAILED, Problem.RECOVERY_DEBT, Problem.ACCESS_LOST))),
            SessionAggregator.summarize(merge.events))
    }

    @Test fun freshBootHistoryPinsUncoveredPrefixWithoutTruncation() {
        val rows = listOf(row(10_000, EventType.SCREEN_OFF), row(100_000, EventType.SCREEN_ON))
        val merge = HistoryMerger.merge(rows,
            listOf(HistoryEvent(HistoryKind.DEEP_IDLE, 40_000, null)), 10_000, 7)
        assertEquals(false, merge.truncated)
        assertEquals(listOf(golden(10_000, 100_000, times(deep = 60_000, unknown = 30_000),
            percents(deep = 60_000 * 100.0 / 90_000, unknown = 30_000 * 100.0 / 90_000),
            firstIdle = 30_000, problems = listOf(Problem.SENSORS_UNVERIFIED))),
            SessionAggregator.summarize(merge.events))
    }

    @Test fun fullBufferHistoryPinsTruncationAndEverySummaryField() {
        val rows = listOf(row(10_000, EventType.SCREEN_OFF), row(100_000, EventType.SCREEN_ON))
        val history = (0 until IdlingHistoryParser.HISTORY_CAPACITY).map {
            HistoryEvent(HistoryKind.DEEP_IDLE, 11_000L + it * 1_001, null)
        }
        val merge = HistoryMerger.merge(rows, history, 10_000, 7)
        assertEquals(true, merge.truncated)
        assertEquals(true, merge.events.first().historyTruncated)
        assertEquals(89, merge.importedEvents.size)
        assertEquals(listOf(golden(10_000, 100_000, times(deep = 89_000, unknown = 1_000),
            percents(deep = 89_000 * 100.0 / 90_000, unknown = 1_000 * 100.0 / 90_000),
            firstIdle = 1_000, problems = listOf(Problem.HISTORY_TRUNCATED, Problem.SENSORS_UNVERIFIED))),
            SessionAggregator.summarize(merge.events))
    }

    @Test fun groupingRequiresScreenOffAndPreservesBootSessionAndSegmentOrder() {
        val rows = listOf(
            row(0, EventType.ACCESS_CHANGED, session = 0), row(1, EventType.SCREEN_ON, session = 0),
            row(2, EventType.SCREEN_OFF, session = -1),
            row(10, EventType.SCREEN_OFF), row(20, EventType.SCREEN_ON),
            row(30, EventType.SCREEN_OFF), row(40, EventType.ENTER_STEP),
            row(0, EventType.SCREEN_OFF, boot = 8, session = 0),
            row(0, EventType.SCREEN_ON, boot = 8, session = 0),
        ).reversed()
        assertEquals(listOf(
            golden(10, 20, times(unknown = 10), percents(unknown = 100.0),
                problems = listOf(Problem.NEVER_REACHED_DEEP, Problem.SENSORS_UNVERIFIED)),
            golden(30, 40, times(unknown = 10), percents(unknown = 100.0),
                problems = listOf(Problem.PARTIAL_SESSION, Problem.NEVER_REACHED_DEEP, Problem.SENSORS_UNVERIFIED)),
            golden(0, 0, times(), percents(), boot = 8, session = 0,
                problems = listOf(Problem.NEVER_REACHED_DEEP, Problem.SENSORS_UNVERIFIED)),
        ), SessionAggregator.summarize(rows))
    }
}
