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

    private companion object {
        const val TOKEN = "com.akylas.enforcedoze"
        const val SENSORS = "dumpsys sensorservice"
        const val RESTRICT = "dumpsys sensorservice restrict $TOKEN"
        const val ENABLE = "dumpsys sensorservice enable"
        const val FORCE = "cmd deviceidle force-idle deep"
        const val UNFORCE = "cmd deviceidle unforce"
    }
}
