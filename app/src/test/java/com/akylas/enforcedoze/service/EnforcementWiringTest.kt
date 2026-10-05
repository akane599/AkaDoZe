package com.akylas.enforcedoze.service

import com.akylas.enforcedoze.access.*
import com.akylas.enforcedoze.doze.*
import com.akylas.enforcedoze.doze.parse.DozeStateParser
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class EnforcementWiringTest {
    private fun service() = File("src/main/java/com/akylas/enforcedoze/ForceDozeService.java").readText()

    @Test fun serviceStartSelfWhitelistRemainsReachableForUnresolvedAndAppAccess() {
        val source = service()
        val start = source.substringAfter("public int onStartCommand(").substringBefore("private void reapplyEnter(")
        assertTrue("ordinary service start must post the whitelist check", start.contains("addSelfToDozeWhitelist();"))
        val whitelist = source.substringAfter("public void addSelfToDozeWhitelist() {")
            .substringBefore("private DozeConfig config(")
        assertFalse("unresolved and APP access must not lose self-whitelisting to session admission",
            whitelist.contains("sessionMode()") || whitelist.contains("SessionMode.FORCE"))
        assertTrue(whitelist.contains("pm.isIgnoringBatteryOptimizations(packageName)"))
        assertTrue(whitelist.contains("executeCommandWithRoot(\"dumpsys deviceidle whitelist +com.akylas.enforcedoze\")"))
        assertTrue(whitelist.contains("RequestIgnoreBatteryActivity.class"))
    }

    @Test fun forceOnlyUsesDurableGenerationCheckedControllerWithoutDeferredSelection() {
        val runner = FakeRunner()
        val store = InMemoryLedgerStore()
        val clock = FakeClock()
        val controller = DozeController(runner, CommandCatalog, CapabilityResolver, store, clock,
            DozeEventSink {}, 36, Grants(true, true))
        runner.replies("dumpsys deviceidle", "mForceIdle=false")
        runner.replies("cmd deviceidle get deep", "IDLE")
        runner.beforeMutation = { assertEquals(Feature.FORCE_DOZE, store.load().entries.single().feature) }
        val force = DozeConfig(36, AccessLevel.SHELL, Grants(true, true), false, "com.akylas.enforcedoze", false)
        val epoch = controller.currentGeneration
        assertEquals(EnterStatus.COMPLETED, controller.enterCore(force, epoch) { true }.status)
        assertEquals(listOf("cmd deviceidle force-idle deep"), runner.mutations())
        controller.bumpGeneration()
        assertEquals(EnterStatus.CANCELLED, controller.enterCore(force, epoch) { true }.status)
        assertEquals(EnterStatus.CANCELLED, controller.enterCore(force, controller.currentGeneration) { false }.status)
        assertEquals(1, runner.mutations().size)
        val wiring = service().substringAfter("private void forceOnly(long generation)")
            .substringBefore("private void resumeEnforcement")
        assertTrue(wiring.contains("generation != runtime.getController().getCurrentGeneration() || !forceAdmitted()"))
        assertTrue(wiring.contains("enterCore(force, generation, this::forceAdmitted)"))
        assertTrue(wiring.contains("EventType.REFORCE"))
        assertFalse(wiring.contains("enterDoze("))
        assertFalse(wiring.contains("DeferredFeatureSelection"))
    }

    @Test fun watchdogRateLimitIsPerSessionAndDeferredCallbackIsGenerationChecked() {
        val clock = FakeClock(0)
        val watchdog = WatchdogPolicy(clock)
        val state = DozeStateParser.parse("mState=ACTIVE")
        assertEquals(Decision.REFORCE, watchdog.onIdleChanged(state, false, true, true))
        assertEquals(Decision.DEFER(60_000), watchdog.onIdleChanged(state, false, true, true))
        assertEquals(Decision.IGNORE, watchdog.onIdleChanged(state, false, false, true))
        for (n in 1..4) {
            clock.elapsed = n * 60_000L
            assertEquals(Decision.REFORCE, watchdog.onIdleChanged(state, false, false, true))
        }
        clock.elapsed += 60_000
        assertEquals(Decision.IGNORE, watchdog.onIdleChanged(state, false, false, true))
        watchdog.resetSession()
        assertEquals(Decision.REFORCE, watchdog.onIdleChanged(state, false, false, true))
        val runtime = File("src/main/java/com/akylas/enforcedoze/service/DozeRuntime.kt").readText()
        val invalidation = runtime.substringAfter("fun bumpGeneration()").substringBefore("fun deferWatchdog")
        assertTrue(invalidation.contains("removeCallbacks"))
        assertTrue("generation cancellation must forget the policy's deferred retry",
            invalidation.contains("watchdog.cancelDeferred()"))
        assertFalse(invalidation.contains("resetSession"))
        val wiring = service().substringAfter("private void idleChanged()").substringBefore("private void forceOnly")
        assertTrue(wiring.contains("Prefs.KEEP_DOZE_ENFORCED"))
        assertTrue(wiring.contains("generation == runtime.getController().getCurrentGeneration()"))
        assertTrue(wiring.contains("forceOnly(generation)"))
    }

    @Test fun lostAccessDebtIncludesShellAndRootEntriesButNotAppRestorableFeatures() {
        fun debt(feature: Feature, api: Int = 36) = AccessRecovery.hasShellDebt(
            RestoreLedger(listOf(LedgerEntry(feature, null, "1", 0, apiLevel = api))), 36)
        assertFalse(AccessRecovery.hasShellDebt(RestoreLedger(), 36))
        assertTrue(debt(Feature.FORCE_DOZE))
        assertTrue(debt(Feature.WIFI))
        assertTrue(debt(Feature.SENSOR_PRIVACY_ALL))
        assertTrue(debt(Feature.AIRPLANE, 29))
        assertFalse(debt(Feature.MOTION_SENSORS))
        assertFalse(debt(Feature.BIOMETRICS))
        val source = service()
        val access = source.substringAfter("private void onAccessChanged").substringBefore("public void onDestroy")
        assertTrue(access.contains("runtime.announceAccess()"))
        val runtime = File("src/main/java/com/akylas/enforcedoze/service/DozeRuntime.kt").readText()
        val announce = runtime.substringAfter("fun announceAccess()").substringBefore("fun recordAccessDebt()")
        assertTrue(announce.contains("AccessRecovery.announce(state, journal, Runnable { recordAccessDebt() })"))
        assertTrue(access.contains("if (Utils.isScreenOn(this))"))
        assertTrue(access.contains("handleScreenOn(this, 0, 0)"))
        assertTrue(access.contains("resumeEnforcement()"))
        val resume = source.substringAfter("private void resumeEnforcement()").substringBefore("class ReloadSettingsReceiver")
        assertTrue(resume.contains("generation == runtime.getController().getCurrentGeneration() && admitted()"))
        assertTrue(resume.contains("enterDoze(disableMotionSensors)"))
        assertTrue(resume.contains("enterDueElapsed - runtime.getClock().elapsedRealtime()"))
    }

    @Test fun presentationSinksAreMultiSubscriberRemovableAndFailureIsolated() {
        val sinks = EventSinks()
        val events = mutableListOf<DozeEvent>()
        val listener = DozeEventSink { events += it }
        sinks.addSink(DozeEventSink { throw IllegalStateException("UI gone") })
        sinks.addSink(listener)
        sinks.addSink(listener)
        val event = DozeEvent(EventType.ACCESS_CHANGED, "APP NO_ACCESS")
        sinks.emit(event)
        assertEquals(listOf(event), events)
        sinks.removeSink(listener)
        sinks.emit(event)
        assertEquals(1, events.size)
        assertTrue(service().contains("public static void addSink(Context context, DozeEventSink sink)"))
    }
}
