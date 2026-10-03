package com.akylas.enforcedoze.doze

import com.akylas.enforcedoze.access.*
import com.akylas.enforcedoze.service.DeferredFeatureSelection
import com.akylas.enforcedoze.service.FeatureSelection
import org.junit.Assert.*
import org.junit.Test

class DozeWakeAndSelectionTest {
    private val runner = FakeRunner()
    private val store = InMemoryLedgerStore()
    private val events = mutableListOf<DozeEvent>()
    private val grants = Grants(true, true)
    private val controller = subject(store)
    private val config = DozeConfig(36, AccessLevel.SHELL, grants, batterySaver = true,
        features = setOf(Feature.WIFI))

    private fun subject(ledger: LedgerStore) = DozeController(runner, CommandCatalog, CapabilityResolver,
        ledger, FakeClock(), DozeEventSink { events.add(it) }, 36, grants)
    private fun biometricLedger() = RestoreLedger(listOf(LedgerEntry(Feature.BIOMETRICS, null, "1", 0, apiLevel = 36)))
    private fun core() {
        runner.replies("settings get global low_power", "0", "1", "0")
        runner.replies("dumpsys sensorservice", "Mode : NORMAL", "Mode : RESTRICTED : com.akylas.enforcedoze", "Mode : NORMAL")
        runner.replies("dumpsys deviceidle", "mForceIdle=false", "mForceIdle=false")
        runner.replies("cmd deviceidle get deep", "IDLE")
        assertEquals(EnterStatus.COMPLETED, controller.enterCore(config, controller.currentGeneration) { true }.status)
    }

    @Test fun neverFiringSelectionDoesNotPreventBatterySensorsAndForce() {
        core()
        val selection = DeferredFeatureSelection(controller.currentGeneration, { controller.currentGeneration }, { true }) {
            fail("a never-firing selector cannot apply groups")
        }
        assertEquals(listOf("cmd power set-mode 1", "dumpsys sensorservice restrict com.akylas.enforcedoze",
            "cmd deviceidle force-idle deep"), runner.mutations())
        assertEquals(setOf(Feature.BATTERY_SAVER, Feature.MOTION_SENSORS, Feature.FORCE_DOZE), store.load().entries.map { it.feature }.toSet())
        selection.cancel()
    }

    @Test fun lateMusicCallbackAfterExitDoesNotReadOrMutate() {
        core()
        val selection = DeferredFeatureSelection(controller.currentGeneration, { controller.currentGeneration }, { true }) {
            controller.enterGroups(config, controller.currentGeneration) { true }
        }
        assertTrue(controller.exit().complete)
        val commands = runner.commands.toList()
        assertFalse(selection.complete(false))
        assertEquals(commands, runner.commands)
    }

    @Test fun timeoutKeepsNetworkAndAppliesOtherGroupsOnlyOnce() {
        val selected = mutableListOf<Boolean?>()
        val selection = DeferredFeatureSelection(0, { 0 }, { true }) { selected.add(it) }
        assertTrue(selection.complete(null))
        assertFalse(selection.complete(false))
        assertEquals(listOf<Boolean?>(null), selected)
        val features = FeatureSelection.features(setOf(Prefs.TURN_OFF_WIFI, Prefs.TURN_OFF_DATA,
            Prefs.TURN_OFF_BLUETOOTH, Prefs.TURN_ON_AIRPLANE, Prefs.TURN_OFF_LOCATION,
            Prefs.TURN_OFF_BIOMETRICS), false, false, true, false)
        assertEquals(setOf(Feature.BIOMETRICS), features)
    }

    @Test fun deferredGroupsRequireFreshDeepIdleWithoutReforcing() {
        runner.replies("cmd deviceidle get deep", "IDLE_MAINTENANCE", "IDLE")
        runner.replies("settings get global wifi_on", "1", "0")
        assertEquals(StepStatus.SKIPPED, controller.enterGroups(config, 0) { true }.steps.single().status)
        assertEquals(StepStatus.VERIFIED, controller.enterGroups(config, 0) { true }.steps.single().status)
        assertEquals(listOf("cmd wifi set-wifi-enabled disabled"), runner.mutations())
    }

    @Test fun biometricWakeChecksGenerationAndAdmissionBeforeMutation() {
        store.save(biometricLedger())
        controller.restoreBiometrics(1) { true }
        controller.restoreBiometrics(0) { false }
        assertTrue(runner.commands.isEmpty())
        assertEquals(biometricLedger(), store.load())
    }

    @Test fun biometricWakeRetainsEntryOnUnknownReadbackOrSaveFailure() {
        store.save(biometricLedger())
        runner.replies("settings get secure biometric_keyguard_enabled", "OEM", "1")
        assertTrue(controller.restoreBiometrics(0) { true }.restored.isEmpty())
        assertEquals(1, store.load().entries.size)
        store.failSave = true
        val result = controller.restoreBiometrics(0) { true }
        assertEquals(listOf(ExitError.LEDGER_SAVE_FAILED), result.errors)
        assertEquals(1, result.remaining.entries.size)
    }

    @Test fun biometricWakeLoadFailureDoesNotEscapeWorker() {
        val broken = object : LedgerStore {
            override fun load(): RestoreLedger = throw IllegalStateException("bad preference")
            override fun save(ledger: RestoreLedger) = fail("must not save")
        }
        assertEquals(listOf(ExitError.LEDGER_LOAD_FAILED), subject(broken).restoreBiometrics(0) { true }.errors)
        assertTrue(runner.commands.isEmpty())
    }

    @Test fun wifiAirplaneValueTwoRemainsUnverifiedNotGenericBooleanOn() {
        assertNull(FeatureReadback.value(Feature.WIFI, 36, listOf("2"), null))
        assertNull(FeatureReadback.value(Feature.WIFI, 36, listOf("OEM"), null))
    }
}
