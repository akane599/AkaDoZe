package com.akylas.enforcedoze.doze

import com.akylas.enforcedoze.access.AccessLevel
import com.akylas.enforcedoze.access.CapabilityResolver
import com.akylas.enforcedoze.access.CommandCatalog
import com.akylas.enforcedoze.access.Feature
import com.akylas.enforcedoze.access.Grants
import org.junit.Assert.*
import org.junit.Test

class DozeDiagnosticsTest {
    @Test fun groupLedgerLoadFailureIsJournaledWithoutEscapingOrRetrying() {
        val runner = FakeRunner()
        runner.replies("cmd deviceidle get deep", "IDLE")
        val error = IllegalStateException("ledger unreadable")
        var loads = 0
        var saves = 0
        val store = object : LedgerStore {
            override fun load(): RestoreLedger { loads++; throw error }
            override fun save(ledger: RestoreLedger) { saves++ }
        }
        val events = mutableListOf<DozeEvent>()
        val controller = DozeController(runner, CommandCatalog, CapabilityResolver, store,
            FakeClock(), DozeEventSink { events += it }, 36, Grants(true, true))
        val config = DozeConfig(36, AccessLevel.SHELL, Grants(true, true), features = setOf(Feature.WIFI))

        val result = runCatching {
            controller.enterGroupsSafely(config, controller.currentGeneration, { true }, "FEATURE_SELECTION_FAILED")
        }

        assertTrue("Group failure must not escape the worker boundary: $result", result.isSuccess)
        assertNull(result.getOrNull())
        assertEquals(1, loads)
        assertEquals(0, saves)
        assertTrue(runner.mutations().isEmpty())
        assertEquals(listOf("FEATURE_SELECTION_FAILED"), events.filter { it.type == EventType.ERROR }.map { it.detail })
    }

    @Test fun groupLedgerSaveFailureStopsBeforeMutationAndDoesNotRetry() {
        val runner = FakeRunner()
        runner.replies("cmd deviceidle get deep", "IDLE")
        runner.replies("settings get global wifi_on", "1")
        var saves = 0
        val store = object : LedgerStore {
            override fun load() = RestoreLedger()
            override fun save(ledger: RestoreLedger) { saves++; throw IllegalStateException("disk unavailable") }
        }
        val events = mutableListOf<DozeEvent>()
        val logged = mutableListOf<Throwable>()
        val controller = DozeController(runner, CommandCatalog, CapabilityResolver, store,
            FakeClock(), DozeEventSink { events += it }, 36, Grants(true, true),
            diagnosticLogger = { _, error -> logged += error })
        val config = DozeConfig(36, AccessLevel.SHELL, Grants(true, true), features = setOf(Feature.WIFI))

        assertNull(controller.enterGroupsSafely(config, controller.currentGeneration, { true }, "REFORCE_FAILED"))
        assertEquals(1, saves)
        assertTrue(runner.mutations().isEmpty())
        assertEquals(listOf("REFORCE_FAILED"), events.filter { it.type == EventType.ERROR }.map { it.detail })
        assertEquals("disk unavailable", logged.single().message)
    }

    @Test fun throwingControllerSinkReachesLoggerWithoutRecursiveEventsOrStoppingEnter() {
        val runner = FakeRunner()
        runner.replies("dumpsys deviceidle", "mForceIdle=false")
        runner.replies("cmd deviceidle get deep", "IDLE")
        val error = IllegalStateException("sink gone")
        val logged = mutableListOf<Throwable>()
        var emissions = 0
        val controller = DozeController(runner, CommandCatalog, CapabilityResolver, InMemoryLedgerStore(),
            FakeClock(), DozeEventSink { emissions++; throw error }, 36, Grants(true, true),
            diagnosticLogger = { _, failure -> logged += failure })

        val result = controller.enterCore(DozeConfig(36, AccessLevel.SHELL, Grants(true, true), restrictSensors = false),
            controller.currentGeneration) { true }

        assertEquals(EnterStatus.COMPLETED, result.status)
        assertEquals(listOf("cmd deviceidle force-idle deep"), runner.mutations())
        assertTrue("Each throwing sink must reach the diagnostic logger", logged.isNotEmpty())
        assertEquals(emissions, logged.size)
        assertTrue(logged.all { it === error })
    }
}
