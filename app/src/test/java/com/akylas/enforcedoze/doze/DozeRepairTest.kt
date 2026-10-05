package com.akylas.enforcedoze.doze

import com.akylas.enforcedoze.access.AccessLevel
import com.akylas.enforcedoze.access.CapabilityResolver
import com.akylas.enforcedoze.access.CommandCatalog
import com.akylas.enforcedoze.access.Feature
import com.akylas.enforcedoze.access.Grants
import com.akylas.enforcedoze.doze.parse.SensorModeReading
import org.junit.Assert.*
import org.junit.Test

class DozeRepairTest {
    private val runner = FakeRunner()
    private val store = InMemoryLedgerStore()
    private val events = mutableListOf<DozeEvent>()
    private val grants = Grants(true, true)
    private val config = DozeConfig(34, AccessLevel.SHELL, grants)
    private fun controller(
        ledger: LedgerStore = store,
        sink: DozeEventSink = DozeEventSink { events.add(it) },
        api: Int = 34,
    ) = DozeController(runner, CommandCatalog, CapabilityResolver, ledger, FakeClock(), sink, api, grants)

    @Test fun timedOutSensorOriginalDoesNotPoisonNextSession() {
        val core = controller()
        runner.answer(SENSORS) { FakeRunner.result("", timeout = true) }
        runner.replies("dumpsys deviceidle", "mForceIdle=true", "mForceIdle=true")
        runner.replies("cmd deviceidle get deep", "IDLE", "IDLE")
        val first = core.enter(config, core.currentGeneration) { true }
        assertEquals(StepStatus.UNVERIFIED, first.steps.first().status)
        assertTrue("unread original must not be persisted", store.load().entries.isEmpty())
        assertTrue(core.exit().complete)
        runner.replies(SENSORS, "Mode : NORMAL", "Mode : RESTRICTED : $TOKEN", "Mode : NORMAL")
        core.enter(config, core.currentGeneration) { true }
        assertTrue(runner.commands.contains("dumpsys sensorservice restrict $TOKEN"))
        assertTrue(core.exit().complete)
    }

    @Test fun timedOutForceOriginalDoesNotPoisonNextSession() {
        val core = controller()
        runner.answer("dumpsys deviceidle") { FakeRunner.result("", timeout = true) }
        val forceOnly = config.copy(restrictSensors = false)
        assertEquals(StepStatus.UNVERIFIED, core.enter(forceOnly, 0) { true }.steps.single().status)
        assertTrue("unread original must not be persisted", store.load().entries.isEmpty())
        assertTrue(core.exit().complete)
        runner.replies("dumpsys deviceidle", "mForceIdle=false", "mForceIdle=false")
        runner.replies("cmd deviceidle get deep", "IDLE")
        core.enter(forceOnly, core.currentGeneration) { true }
        assertTrue(runner.commands.contains("cmd deviceidle force-idle deep"))
        assertTrue(core.exit().complete)
    }

    @Test fun unknownOriginalDoesNotEvenAttemptDurableSave() {
        store.failSave = true
        runner.replies("dumpsys deviceidle", "OEM")
        val result = controller().enter(config.copy(restrictSensors = false), 0) { true }
        assertEquals(StepStatus.UNVERIFIED, result.steps.single().status)
        assertTrue(runner.mutations().isEmpty())
        assertTrue(events.any { it.type == EventType.VERIFY && it.feature == Feature.FORCE_DOZE })
    }

    @Test fun reconcileDropsLegacyNullOriginalsWithoutCommandsEvenWithoutAccess() {
        store.durable = "1|MOTION_SENSORS|$TOKEN|~|0|2|true\n1|FORCE_DOZE|~|~|0|0|false"
        runner.level = AccessLevel.NONE
        assertTrue(controller().reconcile().complete)
        assertTrue(store.load().entries.isEmpty())
        assertTrue(runner.commands.isEmpty())
        assertEquals(2, events.count { it.type == EventType.SKIPPED })
    }

    @Test fun throwingSinkCannotAbortEnterOrOrderedExit() {
        runner.replies(SENSORS, "Mode : NORMAL", "Mode : RESTRICTED : $TOKEN", "Mode : NORMAL")
        runner.replies("dumpsys deviceidle", "mForceIdle=false", "mForceIdle=false")
        runner.replies("cmd deviceidle get deep", "IDLE")
        runner.replies("settings get global wifi_on", "1", "0", "1")
        val core = controller(sink = DozeEventSink { throw IllegalStateException("journal full") })
        core.enter(config.copy(features = setOf(Feature.WIFI)), 0) { true }
        assertTrue(core.exit().complete)
        assertEquals(listOf("dumpsys sensorservice enable", "cmd deviceidle unforce",
            "cmd wifi set-wifi-enabled enabled"), runner.mutations().takeLast(3))
    }

    @Test fun throwingSinkAndSaveStillAttemptEveryExitEntry() {
        store.save(RestoreLedger(listOf(
            LedgerEntry(Feature.MOTION_SENSORS, TOKEN, "NORMAL", 0),
            LedgerEntry(Feature.FORCE_DOZE, null, "0", 0),
            LedgerEntry(Feature.WIFI, null, "1", 0),
        )))
        store.failSave = true
        runner.replies(SENSORS, "Mode : NORMAL")
        runner.replies("dumpsys deviceidle", "mForceIdle=false")
        runner.replies("settings get global wifi_on", "1")
        val result = controller(sink = DozeEventSink { throw IllegalStateException("journal full") }).exit()
        assertFalse(result.complete)
        assertEquals(3, result.remaining.entries.size)
        assertEquals(List(3) { ExitError.LEDGER_SAVE_FAILED }, result.errors)
        assertEquals(listOf("dumpsys sensorservice enable", "cmd deviceidle unforce",
            "cmd wifi set-wifi-enabled enabled"), runner.mutations())
    }

    @Test fun exitLoadFailureReturnsIncompleteEmptyResultInsteadOfThrowing() {
        val unreadable = object : LedgerStore {
            override fun load(): RestoreLedger = throw java.io.IOException("unreadable")
            override fun save(ledger: RestoreLedger) = fail("must not overwrite an unreadable store")
        }
        val result = controller(ledger = unreadable).exit()
        assertFalse(result.complete)
        assertEquals(listOf(ExitError.LEDGER_LOAD_FAILED), result.errors)
        assertTrue(result.restored.isEmpty())
        assertTrue(result.remaining.entries.isEmpty())
        assertTrue(runner.commands.isEmpty())
        assertTrue(events.any { it.type == EventType.ERROR })
    }

    @Test fun recordedLocationApiSurvivesRestartWithDifferentControllerApi() {
        runner.replies("dumpsys deviceidle", "mForceIdle=true")
        runner.replies("cmd deviceidle get deep", "IDLE")
        runner.replies("cmd location is-location-enabled", "true", "false", "true")
        controller().enter(config.copy(restrictSensors = false, features = setOf(Feature.LOCATION)), 0) { true }
        assertEquals(34, store.load().entries.single().apiLevel)
        val result = controller(ledger = store.restart(), api = 29).reconcile()
        assertTrue("use the recorded API for location restoration", runner.commands.contains("cmd location set-location-enabled true"))
        assertFalse(runner.commands.contains("settings put secure location_mode 1"))
        assertTrue(result.complete)
    }

    @Test fun legacyEntryWithoutApiUsesControllerApi() {
        store.durable = "1|LOCATION|~|2|0|0|false"
        runner.replies("settings get secure location_mode", "2")
        assertTrue(controller(api = 29).reconcile().complete)
        assertEquals(listOf("settings put secure location_mode 2"), runner.mutations())
    }

    @Test fun constructorRequiresExplicitPlatformFacts() {
        assertTrue("no silent API or grants defaults", DozeController::class.java.constructors.all {
            it.parameterCount >= 8 && it.parameterTypes[6] == Int::class.javaPrimitiveType &&
                it.parameterTypes[7] == Grants::class.java && (it.isSynthetic || it.parameterCount <= 12)
        })
        assertTrue("Java callers retain the explicit platform-facts overload",
            DozeController::class.java.constructors.any { it.parameterCount == 8 })
    }

    @Test fun safetyNetDoesNotUndoForeignSensorOwnerOrUnownedForce() {
        assertTrue(SafetyNet.check(SensorModeReading(SensorMode.RESTRICTED, "com.other.owner"),
            true, AccessLevel.SHELL, TOKEN, false).isEmpty())
    }

    @Test fun safetyNetRequiresEachOwnershipSignalIndependentlyAtEveryLevel() {
        val foreign = SensorModeReading(SensorMode.RESTRICTED, "com.other.owner")
        val owned = SensorModeReading(SensorMode.RESTRICTED, TOKEN)
        for (level in AccessLevel.entries) {
            assertTrue(SafetyNet.check(foreign, true, level, TOKEN, false).isEmpty())
            assertTrue(SafetyNet.check(SensorModeReading(SensorMode.RESTRICTED, null),
                false, level, TOKEN, false).isEmpty())
            assertEquals(listOf(if (level == AccessLevel.NONE) Action.RAISE_DEBT else Action.RESTORE_SENSORS),
                SafetyNet.check(owned, true, level, TOKEN, false))
            assertEquals(listOf(if (level >= AccessLevel.SHELL) Action.UNFORCE else Action.RAISE_DEBT),
                SafetyNet.check(foreign, true, level, TOKEN, true))
        }
    }

    @Test fun sinkFailureWhileReportingRestoreFailureDoesNotAbortLaterEntries() {
        store.save(RestoreLedger(listOf(
            LedgerEntry(Feature.MOTION_SENSORS, TOKEN, "NORMAL", 0),
            LedgerEntry(Feature.FORCE_DOZE, null, "0", 0),
            LedgerEntry(Feature.WIFI, null, "1", 0),
        )))
        runner.answer("dumpsys sensorservice enable") { throw IllegalStateException("binder") }
        runner.replies(SENSORS, "OEM")
        runner.replies("dumpsys deviceidle", "mForceIdle=false")
        runner.replies("settings get global wifi_on", "1")
        val result = controller(sink = DozeEventSink { throw IllegalStateException("journal full") }).exit()
        assertEquals(listOf(Feature.MOTION_SENSORS), result.remaining.entries.map { it.feature })
        assertEquals(listOf(Feature.FORCE_DOZE, Feature.WIFI), result.restored.map { it.feature })
        assertTrue(runner.commands.contains("cmd deviceidle unforce"))
        assertTrue(runner.commands.contains("cmd wifi set-wifi-enabled enabled"))
    }

    @Test fun mixedLedgerApisSelectEachEntriesCommandsAndReadback() {
        store.save(RestoreLedger(listOf(
            LedgerEntry(Feature.LOCATION, null, "1", 0, apiLevel = 34),
            LedgerEntry(Feature.BATTERY_SAVER, null, "0", 0, apiLevel = 23),
        )))
        runner.replies("settings get global low_power", "0")
        runner.replies("cmd location is-location-enabled", "true")
        assertTrue(controller(api = 29).exit().complete)
        assertEquals(listOf("settings put global low_power 0", "cmd location set-location-enabled true"), runner.mutations())
    }

    @Test fun legacyNullCleanupSaveFailureIsReportedWithoutBlockingRealRestore() {
        store.save(RestoreLedger(listOf(
            LedgerEntry(Feature.MOTION_SENSORS, TOKEN, null, 0),
            LedgerEntry(Feature.FORCE_DOZE, null, "0", 0),
        )))
        val failing = object : LedgerStore {
            override fun load() = store.load()
            override fun save(ledger: RestoreLedger) { throw java.io.IOException("disk unavailable") }
        }
        runner.replies("dumpsys deviceidle", "mForceIdle=false")
        val result = controller(ledger = failing).reconcile()
        assertFalse(result.complete)
        assertEquals(List(2) { ExitError.LEDGER_SAVE_FAILED }, result.errors)
        assertEquals(listOf("cmd deviceidle unforce"), runner.mutations())
        assertEquals(2, result.remaining.entries.size)
    }

    @Test fun alreadySuspendedPackageIsNotAdoptedOrUnsuspended() {
        runner.replies("dumpsys deviceidle", "mForceIdle=true")
        runner.replies("cmd deviceidle get deep", "IDLE")
        val suspended = "  Package [com.example.app] (abc):\n    User 0: installed=true suspended=true"
        runner.replies("dumpsys package com.example.app", suspended, suspended)
        val core = controller()
        val result = core.enter(config.copy(restrictSensors = false, appsToSuspend = setOf("com.example.app")), 0) { true }
        assertEquals(StepStatus.VERIFIED, result.steps.last().status)
        assertTrue(store.load().entries.isEmpty())
        assertTrue(core.exit().complete)
        assertTrue(runner.mutations().isEmpty())
    }

    companion object {
        private const val TOKEN = "com.akylas.enforcedoze"
        private const val SENSORS = "dumpsys sensorservice"
    }
}
