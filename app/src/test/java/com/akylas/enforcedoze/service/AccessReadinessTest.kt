package com.akylas.enforcedoze.service

import com.akylas.enforcedoze.access.*
import com.akylas.enforcedoze.doze.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class AccessReadinessTest {
    @Test fun unresolvedRecoveryDoesNotAttemptOrAnnounceAndReadyRecoveryPrecedesEnter() {
        val store = InMemoryLedgerStore()
        val entry = LedgerEntry(Feature.FORCE_DOZE, null, "0", 0)
        store.save(RestoreLedger(listOf(entry)))
        val runner = FakeRunner().apply { level = AccessLevel.NONE }
        val events = mutableListOf<DozeEvent>()
        var resolved = false
        val core = DozeController(runner, CommandCatalog, CapabilityResolver, store, FakeClock(),
            DozeEventSink { events += it }, 36, Grants(false, false), accessResolved = { resolved })
        core.reconcile()
        assertEquals("unresolved access cannot consume a restore attempt or create debt", entry, store.load().entries.single())
        assertTrue(runner.commands.isEmpty())
        assertTrue(events.isEmpty())
        resolved = true
        runner.level = AccessLevel.SHELL
        runner.replies("dumpsys deviceidle", "mForceIdle=false", "mForceIdle=false")
        runner.replies("cmd deviceidle get deep", "IDLE")
        assertTrue(core.reconcile().complete)
        assertFalse(events.any { it.type == EventType.RECOVERY_DEBT })
        val result = core.enterCore(DozeConfig(36, AccessLevel.SHELL, Grants(false, false), restrictSensors = false),
            core.currentGeneration) { true }
        assertEquals(StepStatus.VERIFIED, result.steps.single().status)
        assertTrue(runner.commands.indexOf("cmd deviceidle unforce") < runner.commands.indexOf("cmd deviceidle force-idle deep"))
    }

    @Test fun resolvedInsufficientAccessStillRecordsDebt() {
        val store = InMemoryLedgerStore()
        store.save(RestoreLedger(listOf(LedgerEntry(Feature.SENSOR_PRIVACY_ALL, null, "0", 0))))
        val events = mutableListOf<DozeEvent>()
        val core = DozeController(FakeRunner(), CommandCatalog, CapabilityResolver, store, FakeClock(),
            DozeEventSink { events += it }, 36, Grants(false, false), accessResolved = { true })
        core.reconcile()
        assertTrue(store.load().entries.single().debt)
        assertEquals(1, store.load().entries.single().attempts)
        assertTrue(events.any { it.type == EventType.RECOVERY_DEBT && it.reason == Reason.REQUIRES_ROOT })
    }

    @Test fun bootPolicyIncludesRetainedDamageButNeverRequestsEmptyLedgerOrEnabledSession() {
        assertTrue(BootRestorePolicy.shouldRestore(false, "1|FORCE_DOZE|~|0|0|0|false|36", ""))
        assertTrue(BootRestorePolicy.shouldRestore(false, "", "1|FORCE_DOZE|damaged intent"))
        assertFalse(BootRestorePolicy.shouldRestore(false, "", ""))
        assertFalse(BootRestorePolicy.shouldRestore(true, "pending", "damaged"))
    }

    @Test fun recoveryGateSerializesRestoreBeforeAdmissionAndInvalidationWins() {
        val gate = AccessReadiness()
        var state = AccessState(AccessLevel.APP, Reason.NO_ACCESS, Grants(false, false), null, resolved = false)
        var restores = 0
        assertFalse(gate.recover(state, { state }) { restores++ })
        assertEquals(0, restores)
        assertFalse(gate.ready(state))
        state = state.copy(level = AccessLevel.ROOT, resolved = true)
        assertTrue(gate.recover(state, { state }) {
            assertFalse("enter remains blocked during recovery", gate.ready(state))
            restores++
        })
        assertTrue(gate.ready(state))
        assertTrue(gate.recover(state, { state }) { fail("duplicate access callback must not exit a healthy session") })
        assertEquals(1, restores)
        gate.invalidate()
        assertFalse(gate.recover(state, { state }) { gate.invalidate() })
        assertFalse(gate.ready(state))
        assertTrue(gate.recover(state, { state }) { restores++ })
    }

    @Test fun grantAndMetadataChangesPreserveReadinessWithinTheSameSessionMode() {
        for (level in listOf(AccessLevel.APP, AccessLevel.SHELL, AccessLevel.ROOT)) {
            val gate = AccessReadiness()
            val state = AccessState(level, null, Grants(false, false), 10001)
            assertTrue(gate.recover(state, { state }) {})
            val grants = if (level == AccessLevel.APP) Grants(false, true) else Grants(true, true)
            val changed = state.copy(grants = grants, uid = 2000, reason = Reason.NO_ACCESS, rootProbeTimedOut = true)
            assertTrue("$level metadata/grants do not change session readiness", gate.ready(changed))
            assertTrue(gate.recover(changed, { changed }) { fail("unchanged readiness must not reconcile again") })
        }
    }

    @Test fun metadataChangingDuringRestoreDoesNotStrandReadiness() {
        val gate = AccessReadiness()
        val initial = AccessState(AccessLevel.ROOT, null, Grants(false, false), 0)
        var current = initial
        assertTrue("irrelevant concurrent updates do not discard completed recovery", gate.recover(initial, { current }) {
            current = initial.copy(grants = Grants(true, true), uid = 10001, rootProbeTimedOut = true)
        })
        assertTrue(gate.ready(current))
    }

    @Test fun dumpChangesSensorOnlyReadinessButSecureSettingsDoesNot() {
        val gate = AccessReadiness()
        var state = AccessState(AccessLevel.APP, Reason.NO_ACCESS, Grants(false, false), 10001)
        var restores = 0
        assertTrue(gate.recover(state, { state }) { restores++ })
        state = state.copy(grants = Grants(true, false))
        assertFalse("DUMP admits SENSOR_ONLY and requires recovery first", gate.ready(state))
        assertTrue(gate.recover(state, { state }) { restores++ })
        state = state.copy(grants = Grants(true, true))
        assertTrue("WSS does not change SENSOR_ONLY readiness", gate.ready(state))
        assertTrue(gate.recover(state, { state }) { fail("WSS must not interrupt the sensor session") })
        state = state.copy(grants = Grants(false, true))
        assertFalse("DUMP loss returns to RESTORE_ONLY", gate.ready(state))
        assertTrue(gate.recover(state, { state }) { restores++ })
        assertEquals(3, restores)
    }

    @Test fun levelAndResolutionChangesDuringRecoveryStillBlockAdmission() {
        val initial = AccessState(AccessLevel.ROOT, null, Grants(false, false), 0)
        for (changed in listOf(initial.copy(level = AccessLevel.SHELL), initial.copy(resolved = false),
            initial.copy(level = AccessLevel.APP, grants = Grants(true, false)))) {
            val gate = AccessReadiness()
            var current = initial
            assertFalse(gate.recover(initial, { current }) { current = changed })
            assertFalse(gate.ready(changed))
        }
    }

    @Test fun staleNoAccessDebtIsRestoredBeforeNextEnterWithoutNewDebtNotice() {
        val store = InMemoryLedgerStore()
        store.save(RestoreLedger(listOf(LedgerEntry(Feature.FORCE_DOZE, null, "0", 0, attempts = 1, debt = true))))
        val runner = FakeRunner().apply {
            replies("dumpsys deviceidle", "mForceIdle=false", "mForceIdle=false")
            replies("cmd deviceidle get deep", "IDLE")
        }
        val events = mutableListOf<DozeEvent>()
        val core = DozeController(runner, CommandCatalog, CapabilityResolver, store, FakeClock(),
            DozeEventSink { events += it }, 36, Grants(false, false))
        val state = AccessState(AccessLevel.SHELL, null, Grants(false, false), 2000)
        val gate = AccessReadiness()
        assertTrue(gate.recover(state, { state }) { assertTrue(core.reconcile().complete) })
        assertFalse(events.any { it.type == EventType.RECOVERY_DEBT })
        assertEquals(StepStatus.VERIFIED, core.enterCore(
            DozeConfig(36, AccessLevel.SHELL, state.grants, restrictSensors = false), core.currentGeneration,
        ) { gate.ready(state) }.steps.single().status)
    }

    @Test fun rootTimeoutBackoffIsFiniteAndRequiresLiveDemand() {
        val retry = RootProbeRetry()
        assertNull(retry.nextDelay(false, true))
        assertNull(retry.nextDelay(true, false))
        assertEquals(1_000L, retry.nextDelay(true, true))
        assertEquals(2_000L, retry.nextDelay(true, true))
        assertEquals(4_000L, retry.nextDelay(true, true))
        repeat(20) { assertNull(retry.nextDelay(true, true)) }
    }

    @Test fun accessBecomingUnresolvedDuringRestoreKeepsOriginalAttemptAndDebt() {
        val store = InMemoryLedgerStore()
        val entry = LedgerEntry(Feature.FORCE_DOZE, null, "0", 0)
        store.save(RestoreLedger(listOf(entry)))
        var resolved = true
        val runner = FakeRunner().apply { afterCommand = { resolved = false } }
        val events = mutableListOf<DozeEvent>()
        val core = DozeController(runner, CommandCatalog, CapabilityResolver, store, FakeClock(),
            DozeEventSink { events += it }, 36, Grants(false, false), accessResolved = { resolved })
        assertFalse(core.reconcile().complete)
        assertEquals(entry, store.load().entries.single())
        assertFalse(events.any { it.type == EventType.RECOVERY_DEBT || it.type == EventType.RESTORE_FAILED })
        assertEquals(EnterStatus.CANCELLED, core.enterCore(
            DozeConfig(36, AccessLevel.SHELL, Grants(false, false)), core.currentGeneration,
        ) { true }.status)
        assertEquals(listOf("cmd deviceidle unforce"), runner.commands)
    }

    @Test fun adaptersWaitForReadinessAndBoundTheirLifetime() {
        val root = if (File("src/main").exists()) File("src/main/java/com/akylas/enforcedoze")
            else File("app/src/main/java/com/akylas/enforcedoze")
        val boot = File(root, "BootCompleteReceiver.java").readText()
        assertTrue(boot.indexOf("if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) return;") <
            boot.indexOf("PreferenceManager.getDefaultSharedPreferences(context)"))
        val service = File(root, "ForceDozeService.java").readText()
        val changed = service.substringAfter("private void onAccessChanged").substringBefore("private void scheduleRootProbeRetry")
        assertTrue(changed.indexOf("runtime.recoverAccess()") < changed.indexOf("resumeEnforcement()"))
        assertTrue(service.contains("&& runtime.accessReadyForEnter()"))
        assertTrue(service.contains("worker.postDelayed(pendingRootRetry, delay)"))
        assertTrue(service.contains("worker.removeCallbacks(pendingRootRetry)"))
        val runtime = File(root, "service/DozeRuntime.kt").readText()
        val request = runtime.substringAfter("fun requestRestoreOnly").substringBefore("fun checkSafety()")
        assertTrue(request.contains("worker.post"))
        assertTrue(request.contains("RestoreOnlyRequest(source"))
        assertTrue(request.contains("withDeadline(deadline"))
        assertTrue(request.contains("android.os.CountDownTimer("))
        assertTrue(request.contains("wakeLock.acquire(30_000L)"))
        assertTrue(request.contains("wakeLock.release()"))
        assertTrue(request.contains("clock.elapsedRealtime() + 9_000L"))
        assertTrue(changed.contains("runtime.announceAccess()"))
        assertTrue(service.contains("!runtime.getAccess().getState().getRootProbeTimedOut()"))
        assertTrue(File(root, "service/RestoreOnlyRequest.kt").readText().contains("access.removeListener(listener)"))
        assertTrue(runtime.contains("selfTests.attached || pendingRecoveries > 0"))
        assertFalse(request.contains("startForeground"))
        assertFalse(request.contains("sessionActive = true"))
    }

    @Test fun lateAppContinuationUsesWakefulWorkerOwnedReconciliation() {
        val root = if (File("src/main").exists()) File("src/main/java/com/akylas/enforcedoze")
            else File("app/src/main/java/com/akylas/enforcedoze")
        val runtime = File(root, "service/DozeRuntime.kt").readText()
        val recovery = runtime.substringAfter("// Late APP discovery").substringBefore(").also { continuation = it }")
        assertTrue("late APP recovery brackets a fresh worker with pendingRecoveries", recovery.contains("synchronized(this@DozeRuntime)") &&
            recovery.indexOf("pendingRecoveries++") < recovery.indexOf("worker().post"))
        assertFalse("must not post to the expired window's captured worker", recovery.contains("worker.post"))
        assertTrue("the APP restore holds the same restore wakelock", recovery.contains("\"forcedoze:restore\"") &&
            recovery.contains("appWakeLock.acquire(30_000L)") && recovery.contains("appWakeLock.release()"))
        assertTrue("ledger work uses the bounded normal reconciliation path", recovery.contains("if (hasPendingRestore())") &&
            recovery.contains("clock.elapsedRealtime() + 9_000L") &&
            recovery.contains("withDeadline(appDeadline, Runnable { reconcileAndCheck() })"))
        assertTrue("completion permits safe worker retirement", recovery.contains("pendingRecoveries--") &&
            recovery.contains("quitIfDetached()"))
        assertFalse("restore-only recovery never probes su", recovery.contains("refreshRoot"))
    }

    @Test fun disabledBootAndReplacementRequestRestoreWithoutStartingSession() {
        val root = if (File("src/main").exists()) File("src/main/java/com/akylas/enforcedoze")
            else File("app/src/main/java/com/akylas/enforcedoze")
        for (name in listOf("BootCompleteReceiver.java", "AutoRestartOnUpdate.java")) {
            val source = File(root, name).readText()
            val callbackStart = "BootRestore.restoreIfPending(context, () -> {"
            assertTrue("$name must delegate to the shared preflight", source.contains(callbackStart))
            val callback = source.substringAfter(callbackStart).substringBefore("});")
            assertTrue("$name must construct runtime only in the admitted callback", callback.contains("MyApplication.getDozeRuntime(context)"))
            assertEquals("$name must not construct runtime outside the callback", 1,
                Regex("""MyApplication\.getDozeRuntime\(context\)""").findAll(source).count())
            assertTrue("$name must retain broadcast for bounded restore-only work",
                callback.contains("requestRestoreOnly(pending::finish)") && callback.contains("goAsync()"))
        }
    }
}
