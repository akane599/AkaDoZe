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
    private fun controller(runner: CommandRunner) = DozeController(
        runner, CommandCatalog, CapabilityResolver, store, FakeClock(),
        DozeEventSink { events.add(it) }, 36, grants,
    )
    private fun debtKeys() = events.filter { it.type == EventType.RECOVERY_DEBT }.map { it.feature to it.target }

    @Test fun timedOutExitRetainsEntriesAndReconcileNotifiesEachExactlyOnce() {
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
        events.clear()
        val followUp = core.reconcile()
        assertFalse(followUp.complete)
        assertEquals(entries.map { it.feature to it.target }.toSet(), debtKeys().toSet())
        assertEquals("one debt event per remaining entry per pass", 3, debtKeys().size)
        assertTrue(events.filter { it.type == EventType.RECOVERY_DEBT }.all { it.reason == Reason.UNVERIFIED })
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

    @Test fun failedDurableCleanupStillNotifiesRetainedIntentOnce() {
        store.save(RestoreLedger(listOf(LedgerEntry(Feature.WIFI, null, "1", 0))))
        store.failSave = true
        val runner = FakeRunner().apply { replies("settings get global wifi_on", "1") }
        assertFalse(controller(runner).reconcile().complete)
        assertEquals(listOf(Feature.WIFI to null), debtKeys())
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
        val runtime = File("src/main/java/com/akylas/enforcedoze/service/DozeRuntime.kt").readText()
        assertTrue(runtime.contains("finally { commandDeadline = null }"))
        assertTrue(runtime.contains("handler?.hasMessages(0) == true"))
    }

    private companion object { const val TOKEN = "com.akylas.enforcedoze" }
}
