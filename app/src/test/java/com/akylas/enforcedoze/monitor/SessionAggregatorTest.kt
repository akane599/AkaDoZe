package com.akylas.enforcedoze.monitor

import com.akylas.enforcedoze.doze.DeepState
import com.akylas.enforcedoze.doze.EventType
import com.akylas.enforcedoze.doze.LightState
import com.akylas.enforcedoze.doze.SensorMode
import com.akylas.enforcedoze.doze.parse.HistoryKind
import org.junit.Assert.*
import org.junit.Test

class SessionAggregatorTest {
    private fun event(
        time: Long,
        type: EventType = EventType.VERIFY,
        deep: DeepState? = null,
        light: LightState? = null,
        sensor: SensorMode? = null,
        battery: Int? = null,
        charging: Boolean? = null,
        boot: Int = 1,
        session: Long = 10,
        detail: String? = null,
    ) = JournalEvent(boot, time, 1_700_000_000_000 + time, session, Source.APP, type,
        deep, light, sensor, battery, charging, detail)

    private fun history(time: Long, kind: HistoryKind, reason: String? = null) =
        JournalEvent(1, time, 1_700_000_000_000 + time, 10, Source.OS_HISTORY, null,
            detail = reason, historyKind = kind)


    @Test fun selfTestsHaveDistinctExcludedIdentitiesInFreshProcessAndAfterRealSession() {
        val identity = JournalIdentity()
        identity.beginSelfTest(com.akylas.enforcedoze.access.Feature.FORCE_DOZE, 100)
        val firstId = identity.forEvent(com.akylas.enforcedoze.access.Feature.FORCE_DOZE)
        val first = event(1, deep = DeepState.IDLE, session = firstId)
        assertTrue(firstId < 0)
        assertTrue(SessionAggregator.summarize(listOf(first)).isEmpty())
        identity.endSelfTest()
        identity.beginSession(200)
        val realId = identity.sessionId
        val real = listOf(event(10, EventType.SCREEN_OFF, session = realId),
            event(20, EventType.SCREEN_ON, session = realId))
        val expected = SessionAggregator.summarize(real)
        identity.beginSelfTest(com.akylas.enforcedoze.access.Feature.MOTION_SENSORS, 100)
        val secondId = identity.forEvent(com.akylas.enforcedoze.access.Feature.MOTION_SENSORS)
        assertTrue(secondId < firstId)
        assertEquals(realId, identity.forEvent(com.akylas.enforcedoze.access.Feature.WIFI))
        val testEvents = listOf(event(21, EventType.VERIFY, deep = DeepState.IDLE, session = secondId),
            event(22, EventType.RESTORE_FAILED, session = secondId),
            event(23, EventType.RECOVERY_DEBT, session = secondId))
        assertEquals(expected, SessionAggregator.summarize(real + first + testEvents))
        identity.endSelfTest()
        assertEquals(realId, identity.forEvent(null))
        // Unrelated restoration is still part of the normal journal and surfaces real debt.
        val unrelated = event(24, EventType.RESTORE_FAILED, session = identity.forEvent(com.akylas.enforcedoze.access.Feature.WIFI))
        assertTrue(Problem.RESTORE_FAILED in SessionAggregator.summarize(real + unrelated).single().problems)
    }

    @Test fun receiverTimestampKeepsWorkerQueueDelayAsUnknownCoverage() {
        val off = JournalEvent.fromDozeEvent(
            com.akylas.enforcedoze.doze.DozeEvent(EventType.SCREEN_OFF, "SCREEN_OFF"),
            1, 1_000, 100_000, 10,
        )
        val summary = SessionAggregator.summarize(listOf(off,
            event(9_000, deep = DeepState.IDLE), event(10_000, EventType.SCREEN_ON),
        )).single()
        assertEquals(1_000L, summary.startElapsed)
        assertEquals(100_000L, summary.startWallTime)
        assertEquals(9_000L, summary.durationMs)
        assertEquals(8_000L, summary.coverageMs.getValue(Coverage.UNKNOWN))
    }

    @Test fun cleanNightHasVerifiedDeep95PercentAndElapsedDuration() {
        val summary = SessionAggregator.summarize(listOf(
            event(0, EventType.SCREEN_OFF, DeepState.ACTIVE, LightState.ACTIVE, battery = 80, charging = false),
            event(5_000, deep = DeepState.IDLE, sensor = SensorMode.RESTRICTED),
            event(100_000, EventType.SCREEN_ON, battery = 79),
        )).single()
        assertEquals(100_000L, summary.durationMs)
        assertEquals(5_000L, summary.timeToFirstDeepIdleMs)
        assertEquals(95.0, summary.coveragePercent.getValue(Coverage.DEEP_IDLE), 0.001)
        assertEquals(5.0, summary.coveragePercent.getValue(Coverage.ACTIVE), 0.001)
        assertEquals(0.0, summary.coveragePercent.getValue(Coverage.UNKNOWN), 0.001)
        assertEquals(SensorVerification.YES, summary.sensorsRestricted)
        assertTrue(summary.problems.isEmpty())
        assertEquals(1, summary.batteryDrop)
    }

    @Test fun motionExitAndReforceAreCountedWithoutAssumingIdleDuringExit() {
        val summary = SessionAggregator.summarize(listOf(
            event(0, EventType.SCREEN_OFF), history(0, HistoryKind.DEEP_IDLE),
            history(20_000, HistoryKind.NORMAL, "motion"),
            event(25_000, EventType.REFORCE), history(30_000, HistoryKind.DEEP_IDLE),
            event(100_000, EventType.SCREEN_ON),
        )).single()
        assertEquals(mapOf("motion" to 1), summary.exitsByReason)
        assertEquals(1, summary.reforceCount)
        assertEquals(90_000L, summary.coverageMs.getValue(Coverage.DEEP_IDLE))
        assertEquals(10_000L, summary.coverageMs.getValue(Coverage.ACTIVE))
    }

    @Test fun maintenanceWindowsAreTransitionsNotRepeatedReadbacks() {
        val summary = SessionAggregator.summarize(listOf(
            event(0, EventType.SCREEN_OFF), history(0, HistoryKind.DEEP_IDLE),
            history(10_000, HistoryKind.DEEP_MAINT),
            event(11_000, EventType.MAINT_START, DeepState.IDLE_MAINTENANCE, LightState.OVERRIDE),
            history(20_000, HistoryKind.DEEP_IDLE),
            history(30_000, HistoryKind.LIGHT_MAINT), history(40_000, HistoryKind.LIGHT_IDLE),
            event(50_000, EventType.SCREEN_ON),
        )).single()
        assertEquals(2, summary.maintenanceCount)
        assertEquals(20_000L, summary.coverageMs.getValue(Coverage.DEEP_IDLE))
        assertEquals(10_000L, summary.coverageMs.getValue(Coverage.LIGHT_IDLE))
        // A light-only maintenance observation cannot prove the deep state.
        assertEquals(10_000L, summary.coverageMs.getValue(Coverage.UNKNOWN))
        assertEquals(10_000L, summary.coverageMs.getValue(Coverage.ACTIVE))
    }

    @Test fun maintenanceWithoutStateDoesNotExtendPriorDeepIdleOrCountEachMarker() {
        val summary = SessionAggregator.summarize(listOf(
            event(0, EventType.SCREEN_OFF, deep = DeepState.IDLE),
            event(10_000, EventType.MAINT_START), event(11_000, EventType.ENTER_STEP),
            event(12_000, EventType.MAINT_START), event(20_000, EventType.MAINT_END),
            event(30_000, EventType.SCREEN_ON),
        )).single()
        assertEquals(1, summary.maintenanceCount)
        assertEquals(10_000L, summary.coverageMs.getValue(Coverage.DEEP_IDLE))
        assertEquals(20_000L, summary.coverageMs.getValue(Coverage.UNKNOWN))
    }

    @Test fun chargingMidSessionExcludesChargingDropAndDuration() {
        val hour = 3_600_000L
        val summary = SessionAggregator.summarize(listOf(
            event(0, EventType.SCREEN_OFF, battery = 80, charging = false),
            event(hour, battery = 78, charging = true),
            event(2 * hour, battery = 90, charging = false),
            event(3 * hour, EventType.SCREEN_ON, battery = 87),
        )).single()
        assertEquals(5, summary.batteryDrop)
        assertEquals(2 * hour, summary.nonChargingSampledMs)
        assertEquals(2.5, summary.batteryPercentPerHour ?: -1.0, 0.001)
    }

    @Test fun unsampledChargingTransitionAndUnknownChargingAreNotAttributedToDischarge() {
        val summary = SessionAggregator.summarize(listOf(
            event(0, EventType.SCREEN_OFF, battery = 80, charging = false),
            event(1_000, charging = true), event(2_000, charging = false),
            event(3_000, EventType.SCREEN_ON, battery = 78),
        )).single()
        assertNull(summary.batteryDrop)
        assertNull(summary.batteryPercentPerHour)
        val unknown = SessionAggregator.summarize(listOf(
            event(0, EventType.SCREEN_OFF, battery = 80),
            event(3_000, EventType.SCREEN_ON, battery = 78),
        )).single()
        assertNull(unknown.batteryDrop)
    }

    @Test fun missingScreenOnIsPartialAndEndsAtLastObservation() {
        val summary = SessionAggregator.summarize(listOf(
            event(1_000, EventType.SCREEN_OFF), event(4_000, deep = DeepState.IDLE),
            event(11_000, EventType.REFORCE),
        )).single()
        assertEquals(10_000L, summary.durationMs)
        assertTrue(Problem.PARTIAL_SESSION in summary.problems)
        assertEquals(3_000L, summary.coverageMs.getValue(Coverage.UNKNOWN))
    }

    @Test fun bootChangeSplitsSessionAndNeverSubtractsDifferentBootClocks() {
        val summaries = SessionAggregator.summarize(listOf(
            event(90_000, EventType.SCREEN_OFF, deep = DeepState.IDLE),
            event(100_000, EventType.REFORCE),
            event(100, deep = DeepState.ACTIVE, light = LightState.ACTIVE, boot = 2),
            event(1_000, EventType.SCREEN_ON, boot = 2),
        ))
        assertEquals(2, summaries.size)
        assertEquals(10_000L, summaries[0].durationMs)
        assertEquals(900L, summaries[1].durationMs)
        assertTrue(summaries.all { Problem.PARTIAL_SESSION in it.problems })
        assertEquals(0L, summaries[1].coverageMs.getValue(Coverage.DEEP_IDLE))
    }

    @Test fun noStateEvidenceIs100PercentUnknownNotActive() {
        val summary = SessionAggregator.summarize(listOf(
            event(0, EventType.SCREEN_OFF), event(10_000, EventType.ENTER_STEP),
            event(100_000, EventType.SCREEN_ON),
        )).single()
        assertEquals(100.0, summary.coveragePercent.getValue(Coverage.UNKNOWN), 0.001)
        assertEquals(0L, summary.coverageMs.getValue(Coverage.ACTIVE))
        assertTrue(Problem.NEVER_REACHED_DEEP in summary.problems)
        assertTrue(Problem.SENSORS_UNVERIFIED in summary.problems)
    }

    @Test fun explicitUnknownReadbackAndAccessLossClearStaleIdleEvidence() {
        val summary = SessionAggregator.summarize(listOf(
            event(0, EventType.SCREEN_OFF, deep = DeepState.IDLE),
            event(10_000, deep = DeepState.UNKNOWN),
            event(20_000, deep = DeepState.IDLE),
            event(30_000, EventType.ACCESS_CHANGED, detail = "SHIZUKU_NOT_RUNNING"),
            event(50_000, EventType.SCREEN_ON),
        )).single()
        assertEquals(20_000L, summary.coverageMs.getValue(Coverage.DEEP_IDLE))
        assertEquals(30_000L, summary.coverageMs.getValue(Coverage.UNKNOWN))
        assertTrue(Problem.ACCESS_LOST in summary.problems)
    }

    @Test fun truncatedHistoryFlagsProblemAndUncoveredPrefix() {
        val app = listOf(event(0, EventType.SCREEN_OFF), event(100_000, EventType.SCREEN_ON))
        val merge = HistoryMerger.merge(app, listOf(
            com.akylas.enforcedoze.doze.parse.HistoryEvent(HistoryKind.DEEP_IDLE, 10_000, null),
        ), 0, 1)
        val summary = SessionAggregator.summarize(merge.events).single()
        assertTrue(Problem.HISTORY_TRUNCATED in summary.problems)
        assertEquals(10_000L, summary.coverageMs.getValue(Coverage.UNKNOWN))
        assertEquals(90_000L, summary.coverageMs.getValue(Coverage.DEEP_IDLE))
    }

    @Test fun restorationDiagnosticsAfterScreenOnDoNotExtendDurationOrNegateRestriction() {
        val summary = SessionAggregator.summarize(listOf(
            event(0, EventType.SCREEN_OFF), event(1_000, sensor = SensorMode.RESTRICTED),
            event(10_000, EventType.SCREEN_ON),
            event(11_000, EventType.SENSORS_RESTORED, sensor = SensorMode.NORMAL),
            event(12_000, EventType.RESTORE_FAILED), event(13_000, EventType.RECOVERY_DEBT),
        )).single()
        assertEquals(10_000L, summary.durationMs)
        assertEquals(SensorVerification.YES, summary.sensorsRestricted)
        assertTrue(Problem.RESTORE_FAILED in summary.problems)
        assertTrue(Problem.RECOVERY_DEBT in summary.problems)
    }

    @Test fun sessionsAndWallClockChangesDoNotMixEvidenceOrDuration() {
        val first = event(1_000, EventType.SCREEN_OFF).copy(wallTime = 1_000_000)
        val end = event(11_000, EventType.SCREEN_ON).copy(wallTime = 5)
        val second = listOf(event(12_000, EventType.SCREEN_OFF, session = 11),
            event(20_000, EventType.SCREEN_ON, session = 11))
        val summaries = SessionAggregator.summarize((listOf(first, end) + second).reversed())
        assertEquals(listOf(10_000L, 8_000L), summaries.map { it.durationMs })
        assertTrue(summaries.all { it.coveragePercent.getValue(Coverage.UNKNOWN) == 100.0 })
        assertTrue(SessionAggregator.summarize(emptyList()).isEmpty())
    }

    @Test fun carryInIdleSupersededAtScreenOffIsNotADeepIdleAchievement() {
        val summary = SessionAggregator.summarize(listOf(
            history(1_000, HistoryKind.DEEP_IDLE),
            event(10_000, EventType.SCREEN_OFF, DeepState.ACTIVE, LightState.ACTIVE),
            event(20_000, EventType.SCREEN_ON),
        )).single()
        assertNull(summary.timeToFirstDeepIdleMs)
        assertTrue(Problem.NEVER_REACHED_DEEP in summary.problems)
        assertEquals(10_000L, summary.coverageMs.getValue(Coverage.ACTIVE))
        assertEquals(0L, summary.coverageMs.getValue(Coverage.DEEP_IDLE))
        val maintenance = SessionAggregator.summarize(listOf(
            history(1_000, HistoryKind.DEEP_MAINT),
            event(10_000, EventType.SCREEN_OFF, DeepState.ACTIVE, LightState.ACTIVE),
            event(20_000, EventType.SCREEN_ON),
        )).single()
        assertEquals(0, maintenance.maintenanceCount)
    }

    @Test fun sensorsNormalMeansNoAndUnknownBootIsPartial() {
        val summary = SessionAggregator.summarize(listOf(
            event(0, EventType.SCREEN_OFF, sensor = SensorMode.NORMAL, boot = -1),
            event(1_000, EventType.SCREEN_ON, boot = -1),
        )).single()
        assertEquals(SensorVerification.NO, summary.sensorsRestricted)
        assertTrue(Problem.PARTIAL_SESSION in summary.problems)
        assertFalse(Problem.SENSORS_UNVERIFIED in summary.problems)
    }
}
