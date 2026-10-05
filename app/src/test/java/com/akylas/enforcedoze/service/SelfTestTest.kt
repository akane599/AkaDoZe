package com.akylas.enforcedoze.service

import com.akylas.enforcedoze.access.AccessLevel
import com.akylas.enforcedoze.access.CapabilityResolver
import com.akylas.enforcedoze.access.CommandCatalog
import com.akylas.enforcedoze.access.Feature
import com.akylas.enforcedoze.access.Grants
import com.akylas.enforcedoze.access.Reason
import com.akylas.enforcedoze.doze.DeepState
import com.akylas.enforcedoze.doze.DozeConfig
import com.akylas.enforcedoze.doze.DozeController
import com.akylas.enforcedoze.doze.DozeEvent
import com.akylas.enforcedoze.doze.DozeEventSink
import com.akylas.enforcedoze.doze.EventType
import com.akylas.enforcedoze.doze.FakeClock
import com.akylas.enforcedoze.doze.FakeRunner
import com.akylas.enforcedoze.doze.InMemoryLedgerStore
import com.akylas.enforcedoze.doze.SensorMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfTestTest {
    private val runner = FakeRunner()
    private val store = InMemoryLedgerStore()
    private val sinks = EventSinks()
    private val events = mutableListOf<DozeEvent>()
    private val grants = Grants(true, true)
    private val controller = DozeController(runner, CommandCatalog, CapabilityResolver, store, FakeClock(), sinks, 36, grants)
    private val config = DozeConfig(36, AccessLevel.SHELL, grants, allowToken = TOKEN)
    private var sessionActive = false
    private var safetyChecks = 0
    private var subscribed = 0

    init {
        sinks.addSink(DozeEventSink { events.add(it) })
    }

    private val selfTest = SelfTest(
        controller, CapabilityResolver,
        { sinks.addSink(it); subscribed++ }, { sinks.removeSink(it); subscribed-- },
        { sessionActive }, { safetyChecks++ },
    )

    @Test fun dozeTestForcesVerifiesThenRestoresThroughLedgerAndChecksSafety() {
        runner.replies("dumpsys deviceidle", "mForceIdle=false", "mForceIdle=false")
        runner.replies("cmd deviceidle get deep", "IDLE")
        runner.beforeMutation = { command ->
            if (command == FORCE) assertTrue("durable intent before force", store.load().entries.any { it.feature == Feature.FORCE_DOZE })
        }

        val result = selfTest.run(SelfTestKind.DOZE, config)

        assertEquals(SelfTestOutcome.PASSED, result.outcome)
        assertEquals(DeepState.IDLE, result.deep)
        assertTrue(result.restoreComplete)
        assertEquals(listOf(FORCE, UNFORCE), runner.mutations())
        assertTrue(store.load().entries.isEmpty())
        assertEquals(1, safetyChecks)
        assertEquals(0, subscribed)
    }

    @Test fun sensorTestRestrictsReadsModeThenEnablesWithoutForcingIdle() {
        runner.replies(SENSORS, "Mode : NORMAL", "Mode : RESTRICTED : $TOKEN", "Mode : NORMAL")

        val result = selfTest.run(SelfTestKind.SENSORS, config)

        assertEquals(SelfTestOutcome.PASSED, result.outcome)
        assertEquals(SensorMode.RESTRICTED, result.sensor)
        assertEquals(listOf(RESTRICT, ENABLE), runner.mutations())
        assertFalse(runner.commands.any { "deviceidle" in it })
        assertTrue(store.load().entries.isEmpty())
        assertEquals(1, safetyChecks)
    }

    @Test fun appDumpSensorTestDurablyRestrictsVerifiesAndRestoresWithoutForce() {
        runner.level = AccessLevel.APP
        runner.replies(SENSORS, "Mode : NORMAL", "Mode : RESTRICTED : $TOKEN", "Mode : NORMAL")
        runner.beforeMutation = { assertEquals("NORMAL", store.load().entries.single().originalValue) }

        val result = selfTest.run(SelfTestKind.SENSORS, config.copy(level = AccessLevel.APP))

        assertEquals(SelfTestOutcome.PASSED, result.outcome)
        assertEquals(SensorMode.RESTRICTED, result.sensor)
        assertEquals(null, result.deep)
        assertTrue(result.restoreComplete)
        assertEquals(listOf(RESTRICT, ENABLE), runner.mutations())
        assertFalse(runner.commands.any { "deviceidle" in it })
        assertTrue(store.load().entries.isEmpty())
        assertEquals(1, safetyChecks)
        assertEquals(0, subscribed)
    }

    @Test fun appDumpUnknownSensorReadbackIsNotPassedEvenWithZeroExit() {
        runner.level = AccessLevel.APP
        runner.replies(SENSORS, "Mode : NORMAL", "Permission Denial: requires DUMP", "Mode : NORMAL")

        val result = selfTest.run(SelfTestKind.SENSORS, config.copy(level = AccessLevel.APP))

        assertEquals(SelfTestOutcome.NOT_VERIFIED, result.outcome)
        assertEquals(SensorMode.UNVERIFIED, result.sensor)
        assertTrue(result.restoreComplete)
        assertEquals(listOf(RESTRICT, ENABLE), runner.mutations())
    }

    @Test fun appSensorRestoreMismatchKeepsOriginalIntentAndCannotPass() {
        runner.level = AccessLevel.APP
        runner.replies(SENSORS, "Mode : NORMAL", "Mode : RESTRICTED : $TOKEN", "OEM", "OEM")
        val result = selfTest.run(SelfTestKind.SENSORS, config.copy(level = AccessLevel.APP))
        assertEquals(SelfTestOutcome.RESTORE_INCOMPLETE, result.outcome)
        assertFalse(result.restoreComplete)
        assertEquals("NORMAL", store.load().entries.single().originalValue)
        assertEquals(1, safetyChecks)
        assertEquals(0, subscribed)
    }

    @Test fun appSensorTestCancelledDuringRestrictStillRestoresOriginal() {
        runner.level = AccessLevel.APP
        runner.replies(SENSORS, "Mode : NORMAL", "Mode : NORMAL")
        runner.afterCommand = { if (it == RESTRICT) controller.bumpGeneration() }
        val result = selfTest.run(SelfTestKind.SENSORS, config.copy(level = AccessLevel.APP))
        assertEquals(SelfTestOutcome.CANCELLED, result.outcome)
        assertTrue(result.restoreComplete)
        assertEquals(listOf(RESTRICT, ENABLE), runner.mutations())
        assertTrue(store.load().entries.isEmpty())
        assertEquals(1, safetyChecks)
    }

    @Test fun appSensorTestIsBusyWithoutCommandsOrSubscriptions() {
        runner.level = AccessLevel.APP
        sessionActive = true
        assertEquals(SelfTestOutcome.BUSY,
            selfTest.run(SelfTestKind.SENSORS, config.copy(level = AccessLevel.APP)).outcome)
        assertTrue(runner.commands.isEmpty())
        assertEquals(0, subscribed)
        assertEquals(0, safetyChecks)
    }

    @Test fun appDumpDozeTestRemainsUnavailableWithoutCommands() {
        runner.level = AccessLevel.APP
        val result = selfTest.run(SelfTestKind.DOZE, config.copy(level = AccessLevel.APP))
        assertEquals(SelfTestOutcome.UNAVAILABLE, result.outcome)
        assertEquals(Reason.NO_ACCESS, result.reason)
        assertTrue(runner.commands.isEmpty())
        assertEquals(0, subscribed)
    }

    /** Negative control: an unverified restore must never read as a pass and must stay as debt. */
    @Test fun unverifiedRestoreFailsTheTestAndKeepsLedgerDebt() {
        runner.replies("dumpsys deviceidle", "mForceIdle=false", "mForceIdle=true", "mForceIdle=true", "mForceIdle=true")
        runner.replies("cmd deviceidle get deep", "IDLE")

        val result = selfTest.run(SelfTestKind.DOZE, config)

        assertEquals(SelfTestOutcome.RESTORE_INCOMPLETE, result.outcome)
        assertFalse(result.restoreComplete)
        assertTrue(store.load().entries.any { it.feature == Feature.FORCE_DOZE })
        assertEquals(1, safetyChecks)
    }

    @Test fun activeSessionIsBusyAndRunsNothing() {
        sessionActive = true

        val result = selfTest.run(SelfTestKind.DOZE, config)

        assertEquals(SelfTestOutcome.BUSY, result.outcome)
        assertTrue(runner.commands.isEmpty())
        assertEquals(0, safetyChecks)
    }

    @Test fun unavailableAccessRunsNothing() {
        val result = selfTest.run(SelfTestKind.SENSORS, config.copy(level = AccessLevel.APP, grants = Grants(false, false)))

        assertEquals(SelfTestOutcome.UNAVAILABLE, result.outcome)
        assertTrue(result.reason != null && result.reason != Reason.UNVERIFIED)
        assertTrue(runner.commands.isEmpty())
    }

    @Test fun ledgerWriteFailureStillRestoresAndChecksSafety() {
        runner.replies("dumpsys deviceidle", "mForceIdle=false")
        store.failSave = true

        val result = selfTest.run(SelfTestKind.DOZE, config)

        assertEquals(SelfTestOutcome.FAILED, result.outcome)
        assertTrue(runner.mutations().isEmpty())
        assertEquals(1, safetyChecks)
        assertEquals(0, subscribed)
    }


    @Test fun unrelatedRestoreDebtDoesNotFlipDozeVerdictAndIsJournaledNormally() {
        store.save(com.akylas.enforcedoze.doze.RestoreLedger(listOf(
            com.akylas.enforcedoze.doze.LedgerEntry(Feature.WIFI, null, "1", 0, debt = true),
        )))
        runner.replies("dumpsys deviceidle", "mForceIdle=false", "mForceIdle=false")
        runner.replies("cmd deviceidle get deep", "IDLE")
        runner.replies("settings get global wifi_on", "0")

        val result = selfTest.run(SelfTestKind.DOZE, config)

        assertEquals(SelfTestOutcome.PASSED, result.outcome)
        assertTrue(result.restoreComplete)
        assertEquals(listOf(Feature.WIFI), store.load().entries.map { it.feature })
        assertTrue(runner.commands.contains("cmd wifi set-wifi-enabled enabled"))
        assertTrue(events.any { it.type == EventType.RESTORE_FAILED && it.feature == Feature.WIFI })
    }

    @Test fun unrelatedDebtDoesNotFlipSensorVerdict() {
        store.save(com.akylas.enforcedoze.doze.RestoreLedger(listOf(
            com.akylas.enforcedoze.doze.LedgerEntry(Feature.WIFI, null, "1", 0, debt = true),
        )))
        runner.replies(SENSORS, "Mode : NORMAL", "Mode : RESTRICTED : $TOKEN", "Mode : NORMAL")
        runner.replies("settings get global wifi_on", "0")

        val result = selfTest.run(SelfTestKind.SENSORS, config)

        assertEquals(SelfTestOutcome.PASSED, result.outcome)
        assertTrue(result.restoreComplete)
        assertEquals(listOf(Feature.WIFI), store.load().entries.map { it.feature })
    }

    @Test fun alreadyForcedByAnotherOwnerIsNotVerifiedAndNeverUnforced() {
        runner.replies("dumpsys deviceidle", "mForceIdle=true")
        runner.replies("cmd deviceidle get deep", "IDLE")

        val result = selfTest.run(SelfTestKind.DOZE, config)

        assertEquals(SelfTestOutcome.NOT_VERIFIED, result.outcome)
        assertTrue(runner.mutations().isEmpty())
        assertTrue(store.load().entries.isEmpty())
        assertTrue(result.restoreComplete)
    }

    @Test fun screenOffDuringMutationCancelsAtNextAdmissionAndStillRestores() {
        runner.replies("dumpsys deviceidle", "mForceIdle=false", "mForceIdle=false")
        runner.afterCommand = { if (it == FORCE) controller.bumpGeneration() }

        val result = selfTest.run(SelfTestKind.DOZE, config)

        assertEquals(SelfTestOutcome.CANCELLED, result.outcome)
        assertEquals(listOf(FORCE, UNFORCE), runner.mutations())
        assertFalse(runner.commands.contains("cmd deviceidle get deep"))
        assertTrue(store.load().entries.isEmpty())
        assertEquals(1, safetyChecks)
    }

    @Test fun detachedRuntimeQueueCancelsWithoutCreatingWorkerOrRunningCommands() {
        val queue = SelfTestQueue()
        val results = mutableListOf<SelfTestResult>()
        queue.request(SelfTestKind.DOZE, { results += it }, { error("must not create worker") },
            { selfTest.run(SelfTestKind.DOZE, config) })

        assertEquals(listOf(SelfTestOutcome.CANCELLED), results.map { it.outcome })
        assertTrue(runner.commands.isEmpty())
    }

    @Test fun queuedTestCompletesCancelledAfterDetachEvenIfReplacementAttaches() {
        val queue = SelfTestQueue()
        val jobs = mutableListOf<Runnable>()
        val results = mutableListOf<SelfTestResult>()
        queue.attach()
        queue.request(SelfTestKind.DOZE, { results += it }, { jobs += it; true },
            { error("detached queued test must not run") })
        queue.detach()
        queue.attach()
        jobs.single().run()
        jobs.single().run()

        assertEquals(listOf(SelfTestOutcome.CANCELLED), results.map { it.outcome })
    }

    @Test fun attachedQueueRunsTestAndFailedPostCompletesCancelled() {
        val queue = SelfTestQueue()
        val results = mutableListOf<SelfTestResult>()
        queue.attach()
        queue.request(SelfTestKind.DOZE, { results += it }, { it.run(); true },
            { SelfTestResult(SelfTestKind.DOZE, SelfTestOutcome.PASSED) })
        queue.request(SelfTestKind.SENSORS, { results += it }, { false }, { error("post failed") })
        assertEquals(listOf(SelfTestOutcome.PASSED, SelfTestOutcome.CANCELLED), results.map { it.outcome })
    }

    @Test fun recorderBoundsBothStreamsAndLongLinesWithoutChangingRunnerOutput() {
        val output = List(250) { "line-$it" }
        val original = com.akylas.enforcedoze.access.CommandResult(0, output, listOf("x".repeat(100_000)), 1, false)
        val recorded = SelfTestCommand.of("dumpsys sensorservice", original)
        assertEquals(201, recorded.stdout.size)
        assertEquals(output.take(200), recorded.stdout.take(200))
        assertEquals("[truncated 50 lines]", recorded.stdout.last())
        assertEquals(1_001, recorded.stderr.single().length)
        assertEquals(250, original.stdout.size)
        assertEquals(100_000, original.stderr.single().length)
    }

    private companion object {
        const val TOKEN = "com.akylas.enforcedoze"
        const val SENSORS = "dumpsys sensorservice"
        const val RESTRICT = "dumpsys sensorservice restrict $TOKEN"
        const val ENABLE = "dumpsys sensorservice enable"
        const val FORCE = "cmd deviceidle force-idle deep"
        const val UNFORCE = "cmd deviceidle unforce"
    }
}
