package com.akylas.enforcedoze.service

import com.akylas.enforcedoze.access.*
import com.akylas.enforcedoze.doze.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class SensorSessionTest {
    private fun service() = File("src/main/java/com/akylas/enforcedoze/ForceDozeService.java").readText()

    @Test fun serviceAdmitsTypedSensorModeAndNeverSelectsGroupsOrRecordsForcedEnter() {
        val source = service()
        val admission = source.substringAfter("private boolean admitted()").substringBefore("private void cancelEnter")
        assertTrue("APP+DUMP must use typed session admission", admission.contains("sessionMode() != SessionMode.RESTORE_ONLY"))
        val core = source.substringAfter("private void enterDoze(boolean sensors, long generation").substringBefore("DeferredFeatureSelection selection")
        assertTrue("sensor-only completion must return before statistics and deferred groups", core.contains("mode == SessionMode.SENSOR_ONLY"))
        assertTrue("controller must receive the selected mode", core.contains("true, null, mode)"))
    }

    @Test fun everyAccessChangeInvalidatesBeforeWorkerRecoveryAndClearsForwardSelection() {
        val changed = service().substringAfter("private void onAccessChanged").substringBefore("private void scheduleRootProbeRetry")
        assertTrue("all access/mode changes must invalidate synchronously", changed.indexOf("invalidateForwardAccess(access)") in 0 until changed.indexOf("postWork("))
        assertTrue("cancel old selection before recovery", changed.indexOf("cancelEnter()") in 0 until changed.indexOf("runtime.recoverAccess()"))
        assertTrue(changed.contains("selectedGroups = null"))
        assertTrue(changed.contains("maintenance = false"))
        assertFalse("mode return must not create another session or reset its deadline/budget", changed.contains("resetSession()") || changed.contains("scheduleEnter()") || changed.contains("beginSession("))
    }

    @Test fun sensorModeCannotDispatchMaintenanceOrAutomaticWatchdog() {
        val idle = service().substringAfter("private void idleChanged()").substringBefore("private void forceOnly")
        assertTrue("natural observation remains, but force-only forwarding stops", idle.indexOf("sessionMode() != SessionMode.FORCE") in 0 until idle.indexOf("SessionLifecycle.maintenanceState"))
    }

    @Test fun downgradeRestoresSensorsFirstRetainsForceDebtThenResumesSameSession() {
        val token = "com.akylas.enforcedoze"
        val store = InMemoryLedgerStore()
        store.save(RestoreLedger(listOf(
            LedgerEntry(Feature.FORCE_DOZE, null, "0", 0),
            LedgerEntry(Feature.MOTION_SENSORS, token, "NORMAL", 0),
        )))
        val runner = FakeRunner().apply {
            level = AccessLevel.APP
            replies("dumpsys sensorservice", "Mode : NORMAL", "Mode : NORMAL", "Mode : RESTRICTED : $token")
        }
        val events = mutableListOf<DozeEvent>()
        val clock = FakeClock()
        val core = DozeController(runner, CommandCatalog, CapabilityResolver, store, clock,
            DozeEventSink { events += it }, 36, Grants(true, false))
        val lifecycle = SessionLifecycle().apply { active = true }
        val watchdog = WatchdogPolicy(clock)
        val state = AccessState(AccessLevel.APP, null, Grants(true, false), null)
        val readiness = AccessReadiness()
        core.bumpGeneration()
        assertTrue(readiness.recover(state, { state }) {
            assertFalse(readiness.ready(state))
            core.reconcile(36, state.grants)
        })
        val debt = store.load().entries.single()
        assertEquals(Feature.FORCE_DOZE, debt.feature)
        assertTrue(debt.debt)
        val result = core.enterCore(DozeConfig(36, state.level, state.grants, mode = SessionMode.SENSOR_ONLY), core.currentGeneration) {
            lifecycle.active && readiness.ready(state)
        }
        assertEquals(EnterStatus.COMPLETED, result.status)
        assertEquals(listOf(Feature.MOTION_SENSORS), result.steps.map { it.feature })
        assertEquals(debt, store.load().entries.first { it.feature == Feature.FORCE_DOZE })
        assertTrue(lifecycle.active)
        assertFalse(lifecycle.hasEnter)
        assertNull(watchdog.precheckExternalReapply())
        assertFalse(events.any { it.type == EventType.VERIFY && it.feature == Feature.FORCE_DOZE })
        assertEquals(listOf("dumpsys sensorservice enable", "dumpsys sensorservice restrict $token"), runner.mutations())
    }

    @Test fun sensorGrantLossDuringBlockedReadOrMutationCancelsForwardAndRetainsIntent() {
        for (interruptOnMutation in listOf(false, true)) {
            val store = InMemoryLedgerStore()
            val runner = FakeRunner().apply { level = AccessLevel.APP; replies("dumpsys sensorservice", "Mode : NORMAL") }
            val core = DozeController(runner, CommandCatalog, CapabilityResolver, store, FakeClock(), DozeEventSink {}, 36, Grants(true, false))
            var admitted = true
            runner.afterCommand = { command ->
                if (FakeRunner.isMutation(command) == interruptOnMutation) {
                    admitted = false
                    core.bumpGeneration()
                }
            }
            val result = core.enterCore(DozeConfig(36, AccessLevel.APP, Grants(true, false), mode = SessionMode.SENSOR_ONLY), core.currentGeneration) { admitted }
            assertEquals(EnterStatus.CANCELLED, result.status)
            assertEquals(if (interruptOnMutation) 1 else 0, store.load().entries.size)
            assertEquals(if (interruptOnMutation) 1 else 0, runner.mutations().size)
            assertFalse(runner.commands.any { "force-idle" in it })
        }
    }

    @Test fun safetyExemptionRequiresHealthyAdmittedSensorOwnershipAndNeverHidesForceDebt() {
        val token = "com.akylas.enforcedoze"
        val entry = LedgerEntry(Feature.MOTION_SENSORS, token, "NORMAL", 0)
        val healthy = RestoreLedger(listOf(entry, LedgerEntry(Feature.FORCE_DOZE, null, "0", 0, debt = true)))
        for (mode in listOf(SessionMode.SENSOR_ONLY, SessionMode.FORCE)) {
            assertTrue(SessionAccess.keepsSafetyIntent(Action.RESTORE_SENSORS, mode, true, false, healthy, token))
            assertFalse(SessionAccess.keepsSafetyIntent(Action.RESTORE_SENSORS, mode, false, false, healthy, token))
            assertFalse(SessionAccess.keepsSafetyIntent(Action.RESTORE_SENSORS, mode, true, true, healthy, token))
            assertFalse(SessionAccess.keepsSafetyIntent(Action.RESTORE_SENSORS, mode, true, false, RestoreLedger(), token))
            assertFalse(SessionAccess.keepsSafetyIntent(Action.RESTORE_SENSORS, mode, true, false, RestoreLedger(listOf(entry.copy(debt = true))), token))
            assertFalse(SessionAccess.keepsSafetyIntent(Action.RESTORE_SENSORS, mode, true, false, RestoreLedger(listOf(entry.copy(originalValue = null))), token))
            assertFalse(SessionAccess.keepsSafetyIntent(Action.RESTORE_SENSORS, mode, true, false, healthy, "another.token"))
            assertFalse(SessionAccess.keepsSafetyIntent(Action.RAISE_DEBT, mode, true, false, healthy, token))
        }
        assertFalse(SessionAccess.keepsSafetyIntent(Action.UNFORCE, SessionMode.SENSOR_ONLY, true, false, healthy, token))
        assertTrue(SessionAccess.keepsSafetyIntent(Action.UNFORCE, SessionMode.FORCE, true, false, healthy, token))
        assertFalse(SessionAccess.keepsSafetyIntent(Action.RESTORE_SENSORS, SessionMode.RESTORE_ONLY, true, false, healthy, token))
    }

    @Test fun sensorAndRestoreModesNeverReserveWatchdogBudgetOrExternalRetries() {
        val clock = FakeClock(0)
        val watchdog = WatchdogPolicy(clock)
        val reading = com.akylas.enforcedoze.doze.parse.DozeStateParser.parse("mState=ACTIVE")
        for (mode in listOf(SessionMode.SENSOR_ONLY, SessionMode.RESTORE_ONLY)) {
            repeat(10) {
                assertEquals(Decision.IGNORE, watchdog.onIdleChanged(reading, false, false,
                    SessionAccess.canRunFeature(mode, Feature.FORCE_DOZE)))
                assertEquals(ReapplySkip.EXTERNAL_REAPPLY_NOT_ADMITTED, SessionAccess.reapplySkip(mode))
                assertNull("rejected sensor work must not reserve budget or spacing", watchdog.precheckExternalReapply())
            }
        }
        assertNull(SessionAccess.reapplySkip(SessionMode.FORCE))
        repeat(WatchdogPolicy.MAX_REFORCES) {
            assertEquals(Decision.REFORCE, watchdog.onIdleChanged(reading, false, false, true))
            clock.elapsed += WatchdogPolicy.MIN_INTERVAL_MS
        }
        assertEquals(ReapplySkip.EXTERNAL_REAPPLY_BUDGET, watchdog.precheckExternalReapply())
        watchdog.cancelDeferred()
        assertEquals("mode callbacks preserve existing force budget", ReapplySkip.EXTERNAL_REAPPLY_BUDGET, watchdog.precheckExternalReapply())
    }

    @Test fun upgradeReconcilesBeforeForceEnterAndEpochChangeDuringRecoveryCannotAdmit() {
        val token = "com.akylas.enforcedoze"
        val store = InMemoryLedgerStore()
        store.save(RestoreLedger(listOf(LedgerEntry(Feature.MOTION_SENSORS, token, "NORMAL", 0))))
        val runner = FakeRunner().apply {
            replies("dumpsys sensorservice", "Mode : NORMAL", "Mode : NORMAL", "Mode : RESTRICTED : $token")
            replies("dumpsys deviceidle", "mForceIdle=false")
            replies("cmd deviceidle get deep", "IDLE")
        }
        val core = DozeController(runner, CommandCatalog, CapabilityResolver, store, FakeClock(), DozeEventSink {}, 36, Grants(true, false))
        val gate = AccessReadiness()
        val app = AccessState(AccessLevel.APP, null, Grants(true, false), null)
        assertTrue(gate.recover(app, { app }) { })
        val shell = app.copy(level = AccessLevel.SHELL)
        gate.invalidate()
        core.bumpGeneration()
        assertFalse(gate.recover(shell, { shell }) { gate.invalidate() })
        assertFalse(gate.ready(shell))
        assertTrue(gate.recover(shell, { shell }) { core.reconcile(36, shell.grants) })
        assertEquals(EnterStatus.COMPLETED, core.enterCore(DozeConfig(36, shell.level, shell.grants), core.currentGeneration) { gate.ready(shell) }.status)
        assertTrue(runner.commands.indexOf("dumpsys sensorservice enable") < runner.commands.indexOf("cmd deviceidle force-idle deep"))
    }

    @Test fun waitingForUnlockRetainsOnlyExistingRestrictionNotForwardAdmission() {
        assertFalse(SessionAccess.screenAdmitted(true, true, false))
        assertTrue(SessionAccess.screenAdmitted(true, true, true))
        assertFalse(SessionAccess.screenAdmitted(true, false, true))
        assertTrue(SessionAccess.screenAdmitted(false, false, false))
        for (mode in listOf(SessionMode.SENSOR_ONLY, SessionMode.RESTORE_ONLY)) {
            assertNotNull(SessionAccess.reapplySkip(mode))
        }
    }

    @Test fun dumpLossKeepsUnrestorableSensorIntentAsDurableDebt() {
        val store = InMemoryLedgerStore()
        val original = LedgerEntry(Feature.MOTION_SENSORS, "com.akylas.enforcedoze", "NORMAL", 0)
        store.save(RestoreLedger(listOf(original)))
        val runner = FakeRunner().apply { level = AccessLevel.APP }
        val core = DozeController(runner, CommandCatalog, CapabilityResolver, store, FakeClock(), DozeEventSink {}, 36, Grants(false, false))
        assertFalse(core.exit(36, Grants(false, false)).complete)
        val debt = store.restart().load().entries.single()
        assertEquals(original.originalValue, debt.originalValue)
        assertTrue(debt.debt)
        assertTrue(runner.commands.isEmpty())
        assertEquals(SessionMode.RESTORE_ONLY, SessionAccess.mode(AccessLevel.APP, Grants(false, false), true, true))
        assertEquals(SessionMode.RESTORE_ONLY, SessionAccess.mode(AccessLevel.APP, Grants(true, false), false, true))
    }
}
