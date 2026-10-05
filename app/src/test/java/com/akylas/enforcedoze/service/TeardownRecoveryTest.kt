package com.akylas.enforcedoze.service

import com.akylas.enforcedoze.access.*
import com.akylas.enforcedoze.doze.*
import com.akylas.enforcedoze.monitor.JournalIdentity
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

class TeardownRecoveryTest {
    private val grants = Grants(true, true)
    private val events = mutableListOf<DozeEvent>()
    private val store = InMemoryLedgerStore()
    private fun controller(runner: CommandRunner, clock: Clock = FakeClock()) = DozeController(
        runner, CommandCatalog, CapabilityResolver, store, clock,
        DozeEventSink { events.add(it) }, 36, grants,
    )
    private fun debtKeys() = events.filter { it.type == EventType.RECOVERY_DEBT }.map { it.feature to it.target }

    @Test fun queuedButNotStartedTeardownDoesNotEmitTimeoutDebt() {
        if (TeardownTimeout.shouldReport(started = false, finished = false)) {
            events += DozeEvent(EventType.RECOVERY_DEBT, "TEARDOWN_TIMEOUT")
        }
        assertTrue("a busy worker is not evidence of failed teardown", events.isEmpty())
    }

    @Test fun timeoutDebtRequiresStartedAndUnfinishedTeardown() {
        assertTrue(TeardownTimeout.shouldReport(started = true, finished = false))
        assertFalse(TeardownTimeout.shouldReport(started = true, finished = true))
        assertFalse(TeardownTimeout.shouldReport(started = false, finished = true))
    }

    @Test fun serviceMarksTeardownStartedOnWorkerAndGatesTimeoutDebt() {
        val source = File("src/main/java/com/akylas/enforcedoze/ForceDozeService.java").readText()
        val teardown = source.substringAfter("public void onDestroy()").substringBefore("public int onStartCommand(")
        assertTrue(teardown.contains("AtomicBoolean started = new AtomicBoolean();"))
        assertTrue(teardown.contains("runtime.detachService(() -> {\n            started.set(true);\n            long deadline ="))
        assertTrue(teardown.contains("&& TeardownTimeout.shouldReport(started.get(), stopped.getCount() == 0)"))
    }

    @Test fun budgetExhaustionDefersMidCommandAndUnreachedEntriesWithoutFalseDebt() {
        val clock = FakeClock(0)
        val entries = listOf("first", "second", "third").map {
            LedgerEntry(Feature.APP_SUSPEND, "com.example.$it", "0", 0)
        }
        store.save(RestoreLedger(entries))
        val deadline = clock.elapsedRealtime() + SessionLifecycle.TEARDOWN_COMMAND_MS
        var bounded = true
        val commands = mutableListOf<String>()
        val core = controller(object : CommandRunner {
            override val level = AccessLevel.SHELL
            override fun run(command: String, timeoutMs: Long): CommandResult {
                val remaining = if (bounded) deadline - clock.elapsedRealtime() else timeoutMs
                if (remaining <= 0) return CommandResult(-1, emptyList(), emptyList(), 0, true)
                commands.add(command)
                val duration = minOf(1_000L, remaining)
                clock.elapsed += duration
                val timedOut = duration < 1_000L
                return CommandResult(
                    if (timedOut) -1 else 0,
                    if (command.startsWith("dumpsys package") && !timedOut)
                        packageState(command.substringAfterLast(' '), false).lines() else emptyList(),
                    emptyList(), duration, timedOut,
                )
            }
        }, clock)
        val generation = core.currentGeneration
        val exit = core.exit { clock.elapsedRealtime() < deadline }
        assertEquals("exit still invalidates queued enter work", generation + 1, core.currentGeneration)
        assertFalse("budget-limited exit is incomplete", exit.complete)
        assertEquals("only the fully verified entry is restored", listOf(entries[2]), exit.restored)
        assertEquals("mid-command and unreached entries keep all ledger fields", entries.take(2), exit.remaining.entries)
        assertTrue("deferred entries are not restore failures", events.none { it.type == EventType.RESTORE_FAILED })
        assertTrue("deferred entries are not recovery debt", debtKeys().isEmpty())
        assertEquals(listOf(
            "pm unsuspend com.example.third", "dumpsys package com.example.third",
            "pm unsuspend com.example.second", "dumpsys package com.example.second",
        ), commands)
        bounded = false
        val followUp = core.exit()
        assertTrue("deadline-free follow-up restores all deferred entries", followUp.complete)
        assertEquals(entries.take(2).toSet(), followUp.restored.toSet())
        assertTrue("successful follow-up never announces debt", debtKeys().isEmpty())
    }

    @Test fun unchangedRestoreMismatchAnnouncesDebtOnlyOnceAcrossScreenCycles() {
        val target = "com.example.app"
        val runner = FakeRunner().apply {
            replies("dumpsys deviceidle", "mForceIdle=false", "mForceIdle=false", "mForceIdle=false", "mForceIdle=false")
            replies("cmd deviceidle get deep", "IDLE", "IDLE")
            replies("dumpsys package $target", packageState(target, false),
                packageState(target, true), packageState(target, true),
                packageState(target, true), packageState(target, true))
        }
        val core = controller(runner)
        val config = DozeConfig(36, AccessLevel.SHELL, grants, restrictSensors = false, appsToSuspend = setOf(target))
        repeat(2) {
            core.enter(config, core.currentGeneration) { true }
            assertFalse(core.exit().complete)
        }
        val appEvents = events.filter { it.feature == Feature.APP_SUSPEND && it.target == target }
        assertEquals("forward verification succeeds on both cycles", 2,
            appEvents.count { it.type == EventType.VERIFY && it.reason == null })
        assertEquals("persistent mismatch announces recovery debt only once", 1,
            appEvents.count { it.type == EventType.RECOVERY_DEBT })
        assertEquals("each restore pass retains failure evidence", 2,
            appEvents.count { it.type == EventType.RESTORE_FAILED })
        assertEquals(2, store.load().entries.single().attempts)
        assertFalse(store.load().entries.single().debt)
    }

    @Test fun debtFlagTransitionsAnnounceAgainButUnchangedDebtDoesNot() {
        store.save(RestoreLedger(listOf(LedgerEntry(Feature.WIFI, null, "1", 0))))
        val runner = FakeRunner().apply { replies("settings get global wifi_on", "0", "0") }
        val core = controller(runner)
        core.exit()
        assertEquals(1, debtKeys().size)
        assertFalse(store.load().entries.single().debt)
        runner.level = AccessLevel.NONE
        core.exit()
        assertEquals("backend loss is a new debt transition", 2, debtKeys().size)
        assertTrue(store.load().entries.single().debt)
        core.exit()
        assertEquals("unchanged unavailable backend is not a new transition", 2, debtKeys().size)
        runner.level = AccessLevel.SHELL
        core.exit()
        assertEquals("backend recovery with a persistent mismatch also changes debt", 3, debtKeys().size)
        assertFalse(store.load().entries.single().debt)
        assertEquals(4, events.count { it.type == EventType.RESTORE_FAILED })
    }

    @Test fun repeatedCommandTimeoutsRetainFailuresWithoutReannouncingUnchangedDebt() {
        val entries = listOf(
            LedgerEntry(Feature.MOTION_SENSORS, TOKEN, "NORMAL", 0),
            LedgerEntry(Feature.FORCE_DOZE, null, "0", 0),
            LedgerEntry(Feature.APP_SUSPEND, "com.example.app", "0", 0),
        )
        store.save(RestoreLedger(entries))
        val core = controller(object : CommandRunner {
            override val level = AccessLevel.SHELL
            override fun run(command: String, timeoutMs: Long) = CommandResult(-1, emptyList(), emptyList(), 0, true)
        })
        val exit = core.exit()
        assertFalse(exit.complete)
        assertEquals(3, exit.remaining.entries.size)
        assertEquals(entries.map { it.feature to it.target }.toSet(), debtKeys().toSet())
        assertEquals("one debt event per newly failed entry", 3, debtKeys().size)
        assertTrue(events.filter { it.type == EventType.RECOVERY_DEBT }.all { it.reason == Reason.UNVERIFIED })
        events.clear()
        val followUp = core.reconcile()
        assertFalse(followUp.complete)
        assertTrue("unchanged failures do not reannounce debt", debtKeys().isEmpty())
        assertEquals(3, events.count { it.type == EventType.RESTORE_FAILED })
        assertTrue(followUp.remaining.entries.all { it.attempts == 2 })
    }

    @Test fun accessLossAndUnknownReadbackEachNotifyWithoutDuplicates() {
        store.save(RestoreLedger(listOf(
            LedgerEntry(Feature.WIFI, null, "1", 0),
            LedgerEntry(Feature.MOTION_SENSORS, TOKEN, "NORMAL", 0),
        )))
        val runner = FakeRunner().apply {
            level = AccessLevel.APP
            replies("dumpsys sensorservice", "OEM mode unknown")
        }
        controller(runner).reconcile()
        assertEquals(setOf(Feature.WIFI to null, Feature.MOTION_SENSORS to TOKEN), debtKeys().toSet())
        assertEquals(2, debtKeys().size)
        assertTrue(runner.commands.contains("dumpsys sensorservice enable"))
    }

    @Test fun admittedFailureBeforeDeadlineStillRecordsDebtAndAttempts() {
        store.save(RestoreLedger(listOf(LedgerEntry(Feature.WIFI, null, "1", 0))))
        val clock = FakeClock(0)
        val runner = FakeRunner().apply {
            replies("settings get global wifi_on", "0")
            afterCommand = { clock.elapsed += 100 }
        }
        val core = controller(runner, clock)
        val result = core.exit { clock.elapsedRealtime() < SessionLifecycle.TEARDOWN_COMMAND_MS }
        assertFalse(result.complete)
        assertEquals(1, result.remaining.entries.single().attempts)
        assertEquals(1, events.count { it.type == EventType.RESTORE_FAILED })
        assertEquals(listOf(Feature.WIFI to null), debtKeys())
    }

    @Test fun expiredAdmissionPreservesLedgerButStillBumpsGeneration() {
        val entry = LedgerEntry(Feature.WIFI, null, "1", 0, attempts = 4, debt = true)
        store.save(RestoreLedger(listOf(entry)))
        val runner = FakeRunner()
        val core = controller(runner)
        val generation = core.currentGeneration
        val result = core.exit { false }
        assertFalse(result.complete)
        assertEquals(generation + 1, core.currentGeneration)
        assertEquals(listOf(entry), result.remaining.entries)
        assertEquals(listOf(entry), store.load().entries)
        assertTrue(runner.commands.isEmpty())
        assertTrue(events.isEmpty())
    }

    @Test fun failedDurableCleanupStillNotifiesRetainedIntentOncePerPass() {
        store.save(RestoreLedger(listOf(LedgerEntry(Feature.WIFI, null, "1", 0, attempts = 2))))
        store.failSave = true
        val runner = FakeRunner().apply { replies("settings get global wifi_on", "1", "1") }
        val core = controller(runner)
        repeat(2) {
            events.clear()
            assertFalse(core.reconcile().complete)
            assertEquals(listOf(Feature.WIFI to null), debtKeys())
        }
    }

    @Test fun forceVerifyIsUnverifiedForUnknownAndNullOnlyForVerifiedReadback() {
        val runner = FakeRunner().apply {
            replies("dumpsys deviceidle", "mForceIdle=false")
            replies("cmd deviceidle get deep", "OEM", "IDLE")
        }
        val core = controller(runner)
        repeat(2) { core.enterCore(DozeConfig(36, AccessLevel.SHELL, grants, restrictSensors = false), core.currentGeneration) { true } }
        assertEquals(listOf(Reason.UNVERIFIED, null), events.filter { it.type == EventType.VERIFY }.map { it.reason })
    }

    @Test fun sensorVerifyIsUnverifiedForWrongOwnerAndNullOnlyForVerifiedReadback() {
        val runner = FakeRunner().apply {
            level = AccessLevel.APP // Controller recovery capabilities intentionally still allow APP+DUMP.
            replies("dumpsys sensorservice", "Mode : NORMAL", "Mode : RESTRICTED : com.other.owner", "Mode : RESTRICTED : $TOKEN")
        }
        val core = controller(runner)
        repeat(2) { core.enterCore(DozeConfig(36, AccessLevel.APP, grants, allowToken = TOKEN), core.currentGeneration) { true } }
        assertEquals(listOf(Reason.UNVERIFIED, null), events.filter { it.type == EventType.VERIFY }.map { it.reason })
    }

    @Test fun otherThreadEventsDuringSelfTestKeepRealSessionIdentity() {
        val identity = JournalIdentity()
        identity.beginSession(100)
        identity.beginSelfTest(Feature.MOTION_SENSORS, 200)
        val testId = identity.forEvent(null)
        assertTrue(testId < 0)
        val featureless = AtomicLong()
        val sameFeature = AtomicLong()
        val thread = Thread {
            featureless.set(identity.forEvent(null))
            sameFeature.set(identity.forEvent(Feature.MOTION_SENSORS))
        }
        thread.start()
        thread.join(2_000)
        assertFalse("identity lookup must finish", thread.isAlive)
        assertEquals(100L, featureless.get())
        assertEquals(100L, sameFeature.get())
        assertEquals(100L, identity.forEvent(Feature.WIFI))
        assertEquals(testId, identity.forEvent(Feature.MOTION_SENSORS))
        identity.endSelfTest()
        assertEquals(100L, identity.forEvent(null))
    }

    @Test fun appWithDumpSelfTestIsUnavailableWithoutCommandsOrSubscriptions() {
        val runner = FakeRunner().apply {
            level = AccessLevel.APP
            replies("dumpsys sensorservice", "Mode : NORMAL", "Mode : RESTRICTED : $TOKEN", "Mode : NORMAL")
        }
        val sinks = EventSinks()
        val core = DozeController(runner, CommandCatalog, CapabilityResolver, store, FakeClock(), sinks, 36, grants)
        var subscriptions = 0
        val test = SelfTest(core, CapabilityResolver, { subscriptions++; sinks.addSink(it) }, sinks::removeSink, { false }, {})
        assertEquals(FeatureStatus.Available, CapabilityResolver.status(Feature.MOTION_SENSORS, AccessLevel.APP, 36, grants))
        val result = test.run(SelfTestKind.SENSORS, DozeConfig(36, AccessLevel.APP, grants, allowToken = TOKEN))
        assertEquals(SelfTestOutcome.UNAVAILABLE, result.outcome)
        assertTrue(runner.commands.isEmpty())
        assertEquals(0, subscriptions)
    }

    @Test fun teardownStartsBudgetOnWorkerAndQueuesDeadlineFreeWakeProtectedRestoreBeforeRetirement() {
        val source = File("src/main/java/com/akylas/enforcedoze/ForceDozeService.java").readText()
        val teardown = source.substringAfter("public void onDestroy()").substringBefore("public int onStartCommand(")
        val detach = teardown.indexOf("runtime.detachService(() -> {")
        val deadline = teardown.indexOf("long deadline =")
        assertTrue("incomplete teardown must queue follow-up restoration", teardown.contains("if (!complete.get()) queueTeardownRestore();"))
        assertTrue("budget starts inside the worker teardown, never on main", detach >= 0 && deadline > detach)
        assertTrue("incomplete exit drives follow-up", teardown.contains("complete.set(result.getComplete())"))
        assertTrue("exit admission shares the command deadline", teardown.contains(
            "exit(Build.VERSION.SDK_INT, runtime.grants(),\n                            () -> runtime.getClock().elapsedRealtime() < deadline)"))
        assertTrue("queue failure cannot bypass teardown cleanup", Regex(
            "try \\{\\s*if \\(!complete.get\\(\\)\\) queueTeardownRestore\\(\\);\\s*} finally \\{\\s*runtime.getSession\\(\\).recordExit\\(\\);",
        ).containsMatchIn(teardown))
        assertTrue("exception forces follow-up", teardown.substringAfter("catch (Exception error)").contains("complete.set(false)"))
        val enqueue = teardown.indexOf("if (!complete.get()) queueTeardownRestore();")
        assertTrue("enqueue precedes idle retirement", enqueue >= 0 && enqueue < teardown.indexOf("runtime.quitIfDetached()"))
        assertTrue(teardown.contains("stopped.await(SessionLifecycle.TEARDOWN_WAIT_MS"))
        assertTrue(teardown.contains("TEARDOWN_TIMEOUT"))
        val followUp = source.substringAfter("private void queueTeardownRestore()").substringBefore("@Override")
        assertTrue(followUp.contains("PowerManager.PARTIAL_WAKE_LOCK"))
        assertTrue(followUp.contains("wakeLock.acquire(30_000L)"))
        assertTrue("use the same worker queue", followUp.contains("worker.post(() ->"))
        assertTrue(followUp.contains("runtime.reconcileAndCheck()"))
        assertFalse("follow-up has no teardown deadline", followUp.contains("withDeadline"))
        assertFalse("restore-only", followUp.contains("enterDoze"))
        assertTrue(followUp.substringAfter("finally").contains("releaseWakeLock(wakeLock)"))
        assertTrue("timed wakelock release race cannot skip worker retirement", Regex(
            "try \\{\\s*releaseWakeLock\\(wakeLock\\);\\s*} catch \\(RuntimeException ignored\\) \\{[^}]*}\\s*runtime.quitIfDetached\\(\\);",
        ).containsMatchIn(followUp))
        val runtime = File("src/main/java/com/akylas/enforcedoze/service/DozeRuntime.kt").readText()
        assertTrue(runtime.contains("finally { commandDeadline = null }"))
        assertTrue(runtime.contains("handler?.hasMessages(0) == true"))
    }

    private fun packageState(target: String, suspended: Boolean) =
        "Package [$target] (abc):\n  User 0: suspended=$suspended"

    private companion object { const val TOKEN = "com.akylas.enforcedoze" }
}
