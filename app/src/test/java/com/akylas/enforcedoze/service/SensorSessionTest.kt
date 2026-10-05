package com.akylas.enforcedoze.service

import com.akylas.enforcedoze.access.*
import com.akylas.enforcedoze.doze.*
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
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

    interface AccessCallbackProbe {
        fun initialize(state: AccessState)
        fun change(state: AccessState)
        fun setSensors(enabled: Boolean)
        fun ready(): Boolean
        fun events(): List<String>
    }

    /** Execute the actual service adapter methods with synchronous worker/platform fakes. */
    private fun withAccessCallbackProbe(check: (AccessCallbackProbe) -> Unit) {
        val source = service()
        val methods = source.substringAfter("/** Caller-thread invalidation must interrupt")
            .substringAfter("*/").substringBefore("/** Worker-only, finite backoff")
        val directory = Files.createTempDirectory("service-access-adapter").toFile()
        try {
            val sourceFile = File(directory, "ServiceAccessAdapter.java")
            sourceFile.writeText("""
                import com.akylas.enforcedoze.access.AccessState;
                import com.akylas.enforcedoze.access.AccessLevel;
                import com.akylas.enforcedoze.service.*;
                import java.util.*;
                public class ServiceAccessAdapter implements SensorSessionTest.AccessCallbackProbe {
                    private AccessState forwardAccess;
                    private SessionMode forwardMode = SessionMode.RESTORE_ONLY;
                    private boolean forwardSensors, destroyed, waitForUnlock, maintenance, verifiedIdleSeen;
                    private boolean sensors = true;
                    private Object selectedGroups;
                    private AccessLevel previousAccess;
                    private final List<String> actions = new ArrayList<>();
                    private final Runtime runtime = new Runtime();
                    public void initialize(AccessState state) {
                        runtime.state = state;
                        invalidateForwardAccess(state);
                        runtime.recoverAccess();
                        actions.clear();
                    }
                    public void change(AccessState state) { runtime.state = state; onAccessChanged(state); }
                    public void setSensors(boolean enabled) { sensors = enabled; }
                    public boolean ready() { return runtime.accessReadyForEnter(); }
                    public List<String> events() { return new ArrayList<>(actions); }
                    private SessionMode sessionMode() {
                        return SessionAccess.mode(runtime.state.getLevel(), runtime.state.getGrants(),
                                sensors, runtime.state.getResolved());
                    }
                    private ServiceAccessAdapter getDefaultSharedPreferences(Object ignored) { return this; }
                    private boolean getBoolean(String key, boolean fallback) { return sensors; }
                    private void postWork(Runnable work) { actions.add("post"); work.run(); }
                    private void cancelEnter() { actions.add("cancel"); }
                    private void resumeEnforcement() { actions.add("resume"); }
                    private void scheduleRootProbeRetry() { actions.add("retry"); }
                    private void updateAccessFlags(AccessLevel level) {}
                    private void handleScreenOn(Object context, int a, int b) {}
                    private static class Utils {
                        static boolean isScreenOn(Object context) { return false; }
                    }
                    private class Runtime {
                        private AccessState state;
                        private final AccessReadiness readiness = new AccessReadiness();
                        Runtime getAccess() { return this; }
                        AccessState getState() { return state; }
                        void invalidateAccess() { actions.add("invalidate"); readiness.invalidate(); }
                        boolean accessReadyForEnter() { return readiness.ready(state); }
                        boolean recoverAccess() {
                            return readiness.recover(state, () -> state, () -> {
                                actions.add("reconcile");
                                return kotlin.Unit.INSTANCE;
                            });
                        }
                        void checkSafety() { actions.add("safety"); }
                        void announceAccess() {}
                        boolean getSessionActive() { return true; }
                        void deactivateSession() {}
                        void importHistory() {}
                    }
                    $methods
                }
            """.trimIndent())
            val classpath = listOf(AccessState::class.java, AccessReadiness::class.java,
                SensorSessionTest::class.java, kotlin.Unit::class.java).map {
                File(it.protectionDomain.codeSource.location.toURI()).path
            }.distinct().joinToString(File.pathSeparator)
            // Android's compile bootclasspath omits javax.tools; the JVM test runner uses a JDK.
            val compiler = Class.forName("javax.tools.ToolProvider").getMethod("getSystemJavaCompiler").invoke(null)
            assertNotNull("adapter regression requires the project's JDK", compiler)
            val run = Class.forName("javax.tools.Tool").getMethod("run", java.io.InputStream::class.java,
                java.io.OutputStream::class.java, java.io.OutputStream::class.java, Array<String>::class.java)
            assertEquals("actual service methods must compile in the adapter harness", 0,
                run.invoke(compiler, null, null, null,
                    arrayOf("-classpath", classpath, "-d", directory.path, sourceFile.path)))
            URLClassLoader(arrayOf(directory.toURI().toURL()), javaClass.classLoader).use { loader ->
                val probe = loader.loadClass("ServiceAccessAdapter").getDeclaredConstructor().newInstance()
                    as AccessCallbackProbe
                check(probe)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun serviceGrantOnlyCallbacksPreserveEpochAndDoNotCancelReconcileOrResume() {
        withAccessCallbackProbe { probe ->
            for (level in listOf(AccessLevel.APP, AccessLevel.SHELL, AccessLevel.ROOT)) {
                val initial = AccessState(level, null, Grants(false, false), 10001)
                probe.initialize(initial)
                val grants = if (level == AccessLevel.APP) Grants(false, true) else Grants(true, true)
                probe.change(initial.copy(grants = grants, reason = Reason.NO_ACCESS, uid = 2000,
                    rootProbeTimedOut = true))
                assertEquals("$level grant/metadata-only callback must keep the epoch and skip recovery",
                    listOf("post", "safety"), probe.events())
                assertTrue("$level existing recovery epoch remains ready", probe.ready())
            }
        }
    }

    @Test fun serviceModeLevelResolutionAndSensorChangesInvalidateBeforeRecovery() {
        withAccessCallbackProbe { probe ->
            val app = AccessState(AccessLevel.APP, null, Grants(false, false), 10001)
            val sensor = app.copy(grants = Grants(true, false))
            val shell = app.copy(level = AccessLevel.SHELL)
            for ((initial, changed) in listOf(app to sensor, sensor to app, shell to shell.copy(level = AccessLevel.ROOT))) {
                probe.initialize(initial)
                probe.change(changed)
                val expected = mutableListOf("invalidate", "post", "cancel", "reconcile")
                if (changed != app) expected += "resume"
                assertEquals("capability changes invalidate on the caller before cancel/reconcile", expected, probe.events())
                assertTrue(probe.ready())
            }
            probe.initialize(shell)
            probe.change(shell.copy(resolved = false))
            assertEquals(listOf("invalidate", "post", "cancel", "safety", "retry"), probe.events())
            assertFalse("unresolved access cannot recover or admit", probe.ready())
            probe.initialize(shell)
            probe.setSensors(false)
            probe.change(shell)
            assertEquals("sensor preference changes must still invalidate even in FORCE",
                listOf("invalidate", "post", "cancel", "reconcile", "resume"), probe.events())
        }
    }

    @Test fun idleObservationUsesTheSameCallerThreadInvalidationAsAccessCallbacks() {
        val idle = service().substringAfter("private void idleChanged()").substringBefore("DozeStateReading reading")
        assertTrue(idle.contains("if (invalidateForwardAccess(runtime.getAccess().getState()))"))
        assertTrue(idle.contains("onAccessChanged(runtime.getAccess().getState())"))
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
        val lifecycle = SessionLifecycle().apply { assertTrue(activate(0, { 0 }, { false })) }
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
