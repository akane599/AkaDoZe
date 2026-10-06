package com.akylas.enforcedoze.doze

import com.akylas.enforcedoze.access.AccessLevel
import com.akylas.enforcedoze.access.CapabilityResolver
import com.akylas.enforcedoze.access.CommandCatalog
import com.akylas.enforcedoze.access.CommandResult
import com.akylas.enforcedoze.access.CommandRunner
import com.akylas.enforcedoze.access.Feature
import com.akylas.enforcedoze.access.Grants
import com.akylas.enforcedoze.access.Reason
import org.junit.Assert.*
import org.junit.Test

class RadioRestoreSettleTest {
    private val runner = FakeRunner()
    private val store = InMemoryLedgerStore()
    private val clock = FakeClock()
    private val events = mutableListOf<DozeEvent>()
    private val waits = mutableListOf<Long>()
    private val timeouts = mutableListOf<Pair<String, Long>>()
    private var deadline: Long? = null
    private var afterWait: () -> Unit = {}
    private val controller = DozeController(
        object : CommandRunner {
            override val level: AccessLevel get() = runner.level
            override fun run(command: String, timeoutMs: Long): CommandResult {
                timeouts.add(command to timeoutMs)
                return runner.run(command, timeoutMs)
            }
        }, CommandCatalog, CapabilityResolver, store, clock,
        DozeEventSink { events.add(it) }, 36, Grants(true, true),
        remainingBudgetMs = { deadline?.minus(clock.elapsedRealtime()) },
        sleeper = { waits.add(it); clock.elapsed += it; afterWait() },
    )

    private fun radioLedger(feature: Feature = Feature.MOBILE_DATA, airplane: Boolean = true) {
        store.save(RestoreLedger(buildList {
            add(LedgerEntry(feature, null, "1", 0, apiLevel = 36))
            if (airplane) add(LedgerEntry(Feature.AIRPLANE, null, "0", 0, apiLevel = 36))
        }))
        if (airplane) runner.replies(AIRPLANE_READ, "disabled")
    }

    @Test fun mobileDataSettlesAfterAirplaneRestoreWithoutFalseDebt() {
        radioLedger()
        runner.replies(DATA_READ, "0", "1")
        val result = controller.exit()
        assertTrue("matching settle read must clear both ledger entries", result.complete)
        assertEquals(2, runner.commands.count { it == DATA_READ })
        assertEquals(listOf(150L), waits)
        assertEquals(listOf("cmd connectivity airplane-mode disable", "svc data enable"), runner.mutations())
        assertTrue(events.none { it.type == EventType.RESTORE_FAILED || it.type == EventType.RECOVERY_DEBT })
        assertNull(events.single { it.type == EventType.VERIFY && it.feature == Feature.MOBILE_DATA }.reason)
    }

    @Test fun recorded250msLagCanSettleOnSecondReread() {
        radioLedger()
        runner.replies(DATA_READ, "0", "0", "1")
        assertTrue(controller.exit().complete)
        assertEquals(listOf(150L, 150L), waits)
        assertEquals(3, runner.commands.count { it == DATA_READ })
        assertTrue(events.none { it.type == EventType.RESTORE_FAILED })
    }

    @Test fun persistentMismatchStillFailsAfterExactlyThreeReadOnlyRetries() {
        radioLedger()
        runner.replies(DATA_READ, "0", "0", "0", "0")
        val result = controller.exit()
        assertFalse(result.complete)
        assertEquals("initial read plus three bounded retries", 4, runner.commands.count { it == DATA_READ })
        assertEquals(listOf(150L, 150L, 150L), waits)
        assertEquals(listOf(100L, 100L, 100L), timeouts.filter { it.first == DATA_READ }.drop(1).map { it.second })
        assertEquals(1, result.remaining.entries.single().attempts)
        assertEquals(Feature.MOBILE_DATA, result.remaining.entries.single().feature)
        assertEquals(1, runner.mutations().count { it == "svc data enable" })
        assertTrue(events.any { it.type == EventType.RESTORE_FAILED && it.feature == Feature.MOBILE_DATA })
    }

    @Test fun lowRemainingTeardownBudgetSkipsAllSettleReadsAndWaits() {
        radioLedger()
        deadline = clock.elapsed + 849L // 750ms settle plus 100ms command margin does not fit.
        runner.replies(DATA_READ, "0")
        assertFalse(controller.exit().complete)
        assertEquals(1, runner.commands.count { it == DATA_READ })
        assertTrue(waits.isEmpty())
    }

    @Test fun retryTimeoutsAndWaitsFitTheActualTeardownBudget() {
        radioLedger()
        deadline = clock.elapsed + 850L
        runner.replies(DATA_READ, "0")
        repeat(3) { runner.answer(DATA_READ) {
            clock.elapsed += 100L
            FakeRunner.result("0") // A slow successful mismatch can still use all three rereads.
        } }
        assertFalse(controller.exit().complete)
        assertEquals(4, runner.commands.count { it == DATA_READ })
        assertEquals(750L, clock.elapsed - 1_000L)
        assertEquals(100L, deadline!! - clock.elapsed)
    }

    @Test fun timedOutInitialReadSkipsSettleAndRetainsEachRadio() {
        for (feature in listOf(Feature.MOBILE_DATA, Feature.WIFI, Feature.BLUETOOTH)) {
            radioLedger(feature)
            val read = CommandCatalog.originalValueRead(feature, 36)!!
            runner.answer(read) { FakeRunner.result("1", timeout = true) }
            runner.replies(read, "1") // A fresh shell must not turn the timeout into success.

            val result = controller.exit()

            assertEquals("$feature: initial timeout must not issue a settle read", 1,
                runner.commands.count { it == read })
            assertFalse("$feature: timed-out stdout cannot verify restoration", result.complete)
            assertTrue("no wait after an initial timeout", waits.isEmpty())
            assertEquals(LedgerEntry(feature, null, "1", 0, attempts = 1, apiLevel = 36),
                result.remaining.entries.single())
            assertEquals(Reason.UNVERIFIED,
                events.single { it.type == EventType.VERIFY && it.feature == feature }.reason)
            assertEquals(Reason.UNVERIFIED,
                events.single { it.type == EventType.RESTORE_FAILED && it.feature == feature }.reason)
        }
    }

    @Test fun deadlineRefusedInitialReadSkipsSettleWithoutWaiting() {
        radioLedger()
        runner.answer(DATA_READ) { CommandResult(-1, emptyList(), emptyList(), 0, timedOut = true) }
        runner.replies(DATA_READ, "1")

        val result = controller.exit()

        assertEquals("deadline refusal must not issue a settle read", 1,
            runner.commands.count { it == DATA_READ })
        assertTrue(waits.isEmpty())
        assertFalse(result.complete)
        assertEquals(Feature.MOBILE_DATA, result.remaining.entries.single().feature)
    }

    @Test fun maintenanceInitialTimeoutSkipsSettleAndKeepsDurableOriginal() {
        radioLedger()
        val original = store.durable
        runner.answer(DATA_READ) { FakeRunner.result("1", timeout = true) }
        runner.replies(DATA_READ, "1")

        val result = controller.maintenance(true, controller.currentGeneration) { true }

        assertEquals("maintenance must not reread after initial timeout", 1,
            runner.commands.count { it == DATA_READ })
        assertTrue(waits.isEmpty())
        assertEquals(original, store.durable)
        assertEquals(StepStatus.UNVERIFIED, result.steps.single { it.feature == Feature.MOBILE_DATA }.status)
    }

    @Test fun nonTimeoutInitialReadFailureCanStillSettle() {
        radioLedger()
        runner.answer(DATA_READ) { FakeRunner.result("", exit = 1) }
        runner.replies(DATA_READ, "1")

        assertTrue(controller.exit().complete)
        assertEquals(2, runner.commands.count { it == DATA_READ })
        assertEquals(listOf(150L), waits)
    }

    @Test fun timedOutFirstRereadStopsSettleWithoutMoreReadsOrWaits() {
        radioLedger()
        runner.replies(DATA_READ, "0")
        runner.answer(DATA_READ) { FakeRunner.result("1", timeout = true) }
        runner.replies(DATA_READ, "1") // Must not turn a timed-out settle into a later success.
        val result = controller.exit()
        assertFalse("timeout cannot verify restoration", result.complete)
        assertEquals("initial read and only one settle reread", 2, runner.commands.count { it == DATA_READ })
        assertEquals("no sleep after the timeout", listOf(150L), waits)
        assertEquals(1, result.remaining.entries.single().attempts)
        assertEquals(Feature.MOBILE_DATA, result.remaining.entries.single().feature)
        assertEquals(1, runner.mutations().count { it == "svc data enable" })
        assertEquals(Reason.UNVERIFIED,
            events.single { it.type == EventType.VERIFY && it.feature == Feature.MOBILE_DATA }.reason)
        assertEquals(Reason.UNVERIFIED,
            events.single { it.type == EventType.RESTORE_FAILED && it.feature == Feature.MOBILE_DATA }.reason)
    }

    @Test fun interruptedSleeperAbortsSettleAndPreservesInterruptFlag() {
        radioLedger()
        runner.replies(DATA_READ, "0", "1")
        afterWait = { throw InterruptedException("settle interrupted") }
        try {
            val result = controller.exit()
            assertTrue("interruption must remain visible to the worker", Thread.currentThread().isInterrupted)
            assertFalse(result.complete)
            assertEquals("no reread after interrupted wait", 1, runner.commands.count { it == DATA_READ })
            assertEquals(listOf(150L), waits)
            assertEquals(Feature.MOBILE_DATA, result.remaining.entries.single().feature)
        } finally {
            Thread.interrupted() // Do not leak the tested worker flag to the JUnit runner.
        }
    }

    @Test fun budgetExhaustedDuringWaitDoesNotStartAnotherRead() {
        radioLedger()
        deadline = clock.elapsed + 850L
        afterWait = { deadline = clock.elapsed + 99L }
        runner.replies(DATA_READ, "0")
        assertFalse(controller.exit().complete)
        assertEquals(1, runner.commands.count { it == DATA_READ })
        assertEquals(listOf(150L), waits)
    }

    @Test fun oversleptLocalSettleDeadlineDoesNotStartAnotherRead() {
        radioLedger()
        afterWait = { clock.elapsed += 600L }
        runner.replies(DATA_READ, "0")
        assertFalse(controller.exit().complete)
        assertEquals(1, runner.commands.count { it == DATA_READ })
    }

    @Test fun cancellationDuringWaitKeepsDurableRadioIntentWithoutAnotherRead() {
        radioLedger()
        var admitted = true
        afterWait = { admitted = false }
        runner.replies(DATA_READ, "0")
        assertFalse(controller.exit(admission = { admitted }).complete)
        assertEquals(1, runner.commands.count { it == DATA_READ })
        assertEquals(0, store.load().entries.single().attempts)
    }

    @Test fun noAirplaneRestoreInThisPassDoesNotSettle() {
        radioLedger(airplane = false)
        runner.replies(DATA_READ, "0")
        assertFalse(controller.exit().complete)
        assertEquals(1, runner.commands.count { it == DATA_READ })
        assertTrue(waits.isEmpty())
    }

    @Test fun unverifiedAirplaneRestoreDoesNotAuthorizeRadioSettle() {
        radioLedger(airplane = false)
        store.save(RestoreLedger(store.load().entries + LedgerEntry(Feature.AIRPLANE, null, "0", 0, apiLevel = 36)))
        runner.replies(AIRPLANE_READ, "enabled")
        runner.replies(DATA_READ, "0")
        assertFalse(controller.exit().complete)
        assertEquals(1, runner.commands.count { it == DATA_READ })
        assertTrue(waits.isEmpty())
    }

    @Test fun wifiAndBluetoothShareSettleButLocationDoesNot() {
        for (feature in listOf(Feature.WIFI, Feature.BLUETOOTH, Feature.LOCATION)) {
            radioLedger(feature)
            val read = CommandCatalog.originalValueRead(feature, 36)!!
            runner.replies(read, "0", "1")
            assertEquals(feature != Feature.LOCATION, controller.exit().complete)
            assertEquals(if (feature == Feature.LOCATION) 1 else 2, runner.commands.count { it == read })
        }
        assertEquals(listOf(150L, 150L), waits)
    }

    @Test fun maintenanceUsesSameSettleWithoutChangingDurableOriginals() {
        radioLedger()
        val original = store.durable
        runner.replies(DATA_READ, "0", "1")
        val result = controller.maintenance(true, controller.currentGeneration) { true }
        assertTrue(result.steps.all { it.status == StepStatus.VERIFIED })
        assertEquals(original, store.durable)
        assertEquals(2, runner.commands.count { it == DATA_READ })
        assertTrue(events.none { it.type == EventType.RESTORE_FAILED })
    }

    private companion object {
        const val AIRPLANE_READ = "cmd connectivity airplane-mode"
        const val DATA_READ = "settings get global mobile_data"
    }
}
