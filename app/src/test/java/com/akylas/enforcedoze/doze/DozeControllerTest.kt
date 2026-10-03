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
import java.util.ArrayDeque

internal class FakeClock(var elapsed: Long = 1_000) : Clock {
    override fun elapsedRealtime(): Long = elapsed
    override fun wallTime(): Long = 1_700_000_000_000L + elapsed
}

internal class InMemoryLedgerStore(var durable: String = "") : LedgerStore {
    var failSave = false
    override fun load(): RestoreLedger = RestoreLedgerCodec.decode(durable).ledger
    override fun save(ledger: RestoreLedger) {
        if (failSave) throw IllegalStateException("disk unavailable")
        durable = RestoreLedgerCodec.encode(ledger)
    }
    fun restart(): InMemoryLedgerStore = InMemoryLedgerStore(durable)
}

internal class FakeRunner : CommandRunner {
    override var level: AccessLevel = AccessLevel.SHELL
    val commands = mutableListOf<String>()
    private val replies = mutableMapOf<String, ArrayDeque<() -> CommandResult>>()
    var afterCommand: (String) -> Unit = {}
    var beforeMutation: (String) -> Unit = {}

    fun replies(command: String, vararg output: String) {
        output.forEach { answer(command) { result(it) } }
    }
    fun answer(command: String, reply: () -> CommandResult) {
        replies.getOrPut(command) { ArrayDeque() }.add(reply)
    }
    override fun run(command: String, timeoutMs: Long): CommandResult {
        commands.add(command)
        if (isMutation(command)) beforeMutation(command)
        val reply = replies[command]?.pollFirst()
        val result = if (reply != null) reply() else {
            if (!isMutation(command)) throw AssertionError("Unscripted read: $command")
            result("")
        }
        afterCommand(command)
        return result
    }
    fun mutations(): List<String> = commands.filter(::isMutation)
    companion object {
        fun result(output: String, exit: Int = 0, timeout: Boolean = false) =
            CommandResult(exit, output.lines(), emptyList(), 1, timeout)
        fun isMutation(command: String): Boolean = command.startsWith("pm ") ||
            command.startsWith("settings put ") || command.startsWith("svc ") ||
            listOf(" restrict ", " enable", " disable", "force-idle", " unforce", " set-")
                .any { it in command }
    }
}

class DozeControllerTest {
    private val runner = FakeRunner()
    private val store = InMemoryLedgerStore()
    private val clock = FakeClock()
    private val events = mutableListOf<DozeEvent>()
    private val grants = Grants(true, true)
    private val controller = newController(store)
    private val config = DozeConfig(36, AccessLevel.SHELL, grants)

    private fun newController(ledger: InMemoryLedgerStore) = DozeController(
        runner, CommandCatalog, CapabilityResolver, ledger, clock, DozeEventSink { events.add(it) }, 36, grants,
    )
    private fun enter(config: DozeConfig = this.config): EnterResult = controller.enter(config, controller.currentGeneration) { true }
    private fun entry(feature: Feature, original: String?, target: String? = null) = LedgerEntry(feature, target, original, 0)
    private fun forceCycle() {
        runner.replies("dumpsys deviceidle", "mForceIdle=false", "mForceIdle=false")
        runner.replies("cmd deviceidle get deep", "IDLE")
    }
    private fun sensorCycle() = runner.replies(SENSORS, "Mode : NORMAL", "Mode : RESTRICTED : $TOKEN", "Mode : NORMAL")

    @Test fun happyEnterAndExitDurablyRecordAndRestoreSensorsFirst() {
        sensorCycle()
        forceCycle()
        runner.replies("settings get global low_power", "0", "1", "0")
        runner.replies("settings get global wifi_on", "1", "0", "1")
        runner.beforeMutation = { command ->
            val feature = when {
                "sensorservice" in command -> Feature.MOTION_SENSORS
                "deviceidle" in command -> Feature.FORCE_DOZE
                "power" in command -> Feature.BATTERY_SAVER
                else -> Feature.WIFI
            }
            assertTrue("durable intent before $command", store.load().entries.any { it.feature == feature })
        }
        val result = enter(config.copy(batterySaver = true, features = setOf(Feature.WIFI)))
        assertTrue(result.steps.all { it.status == StepStatus.VERIFIED })
        assertEquals(listOf(RESTRICT, "cmd power set-mode 1", FORCE, "cmd wifi set-wifi-enabled disabled"), runner.mutations())
        assertTrue(controller.exit().complete)
        assertEquals(listOf(ENABLE, UNFORCE, "cmd power set-mode 0", "cmd wifi set-wifi-enabled enabled"), runner.mutations().takeLast(4))
        assertTrue(store.load().entries.isEmpty())
        assertTrue(events.any { it.type == EventType.SENSORS_RESTRICTED })
        assertTrue(events.any { it.type == EventType.SENSORS_RESTORED })
    }

    @Test fun screenOnBetweenStepsCancelsFurtherMutationsAndExitRestoresAppliedOnly() {
        runner.replies(SENSORS, "Mode : NORMAL", "Mode : RESTRICTED : $TOKEN", "Mode : NORMAL")
        runner.afterCommand = { if (it == RESTRICT) controller.bumpGeneration() }
        assertEquals(EnterStatus.CANCELLED, enter(config.copy(batterySaver = true)).status)
        assertEquals(listOf(RESTRICT), runner.mutations())
        runner.afterCommand = {}
        // Cancellation before verification leaves the first remaining reading restricted: one restore retry.
        assertTrue(controller.exit().complete)
        assertEquals(listOf(RESTRICT, ENABLE, ENABLE), runner.mutations())
        assertFalse(runner.commands.contains(FORCE))
    }

    @Test fun generationChangeDuringOriginalReadDoesNotRecordOrMutate() {
        runner.replies(SENSORS, "Mode : NORMAL")
        runner.afterCommand = { controller.bumpGeneration() }
        assertEquals(EnterStatus.CANCELLED, enter().status)
        assertTrue(store.load().entries.isEmpty())
        assertTrue(runner.mutations().isEmpty())
    }

    @Test fun admissionIsCheckedBetweenCommandsOfNotificationMutation() {
        var admitted = true
        // Observe already-forced IDLE to focus the notification command group without owning force.
        runner.replies("dumpsys deviceidle", "mForceIdle=true")
        runner.replies("cmd deviceidle get deep", "IDLE")
        runner.replies("dumpsys package $PKG", packageState(false, true, false, false))
        runner.afterCommand = { if (it.startsWith("pm revoke")) admitted = false }
        val result = controller.enter(config.copy(restrictSensors = false, packagesToBlockNotifications = setOf(PKG)), 0) { admitted }
        assertEquals(EnterStatus.CANCELLED, result.status)
        assertEquals(listOf("pm revoke $PKG android.permission.POST_NOTIFICATIONS"), runner.mutations())
        assertEquals("1,0,0", store.load().entries.single { it.feature == Feature.NOTIFICATION_BLOCK }.originalValue)
    }

    @Test fun ambiguousTimeoutStillHasOriginalAndIsRestored() {
        runner.replies(SENSORS, "Mode : NORMAL", "OEM", "Mode : NORMAL")
        runner.answer(RESTRICT) { FakeRunner.result("", exit = -1, timeout = true) }
        runner.level = AccessLevel.APP
        val result = enter(config.copy(level = AccessLevel.APP))
        assertEquals(StepStatus.UNVERIFIED, result.steps.first().status)
        assertEquals("NORMAL", store.load().entries.single().originalValue)
        assertTrue(controller.exit().complete)
        assertEquals(listOf(RESTRICT, ENABLE), runner.mutations())
    }

    @Test fun binderDeathRestoresSensorsAtAppDumpAndRetainsUnforceDebt() {
        sensorCycle()
        forceCycle()
        enter()
        runner.level = AccessLevel.APP
        val result = controller.exit()
        assertEquals(listOf(Feature.FORCE_DOZE), result.remaining.entries.map { it.feature })
        assertEquals(1, result.remaining.entries.single().attempts)
        assertTrue(result.remaining.entries.single().debt)
        assertEquals(ENABLE, runner.mutations().last())
        assertFalse(runner.commands.contains(UNFORCE))
        assertTrue(events.any { it.type == EventType.RECOVERY_DEBT && it.feature == Feature.FORCE_DOZE })
        runner.level = AccessLevel.SHELL
        assertTrue(controller.reconcile().complete)
    }

    @Test fun restartReconcilesDurableLedgerWithoutEntryConfigOrPreferences() {
        sensorCycle()
        forceCycle()
        runner.replies("cmd connectivity airplane-mode", "disabled", "enabled", "disabled")
        enter(config.copy(features = setOf(Feature.AIRPLANE)))
        val restarted = store.restart()
        assertTrue(newController(restarted).reconcile().complete)
        assertEquals("cmd connectivity airplane-mode disable", runner.mutations().last())
        assertTrue(restarted.load().entries.isEmpty())
    }

    @Test fun changedFeaturePreferencesDoNotOverwriteOriginalOrControlExit() {
        runner.replies("dumpsys deviceidle", "mForceIdle=false", "mForceIdle=false")
        runner.replies("cmd deviceidle get deep", "IDLE", "IDLE")
        runner.replies("cmd connectivity airplane-mode", "disabled", "enabled", "disabled")
        enter(config.copy(restrictSensors = false, features = setOf(Feature.AIRPLANE)))
        enter(config.copy(restrictSensors = false, features = emptySet()))
        assertEquals("0", store.load().entries.single { it.feature == Feature.AIRPLANE }.originalValue)
        assertTrue(controller.exit().complete)
        assertEquals("cmd connectivity airplane-mode disable", runner.mutations().last())
    }

    @Test fun failedWifiRestoreDoesNotPreventRemainingEntries() {
        store.save(RestoreLedger(listOf(entry(Feature.WIFI, "1"), entry(Feature.BLUETOOTH, "1"))))
        runner.replies("settings get global wifi_on", "0")
        runner.replies("settings get global bluetooth_on", "1")
        runner.answer("cmd wifi set-wifi-enabled enabled") { throw IllegalStateException("binder") }
        val result = controller.exit()
        assertEquals(listOf(Feature.WIFI), result.remaining.entries.map { it.feature })
        assertEquals(1, result.remaining.entries.single().attempts)
        assertFalse(result.remaining.entries.single().debt)
        assertTrue(runner.commands.contains("cmd bluetooth_manager enable"))
    }

    @Test fun unknownOriginalIsNotRecordedMutatedOrGuessedOnExit() {
        runner.level = AccessLevel.APP
        runner.replies(SENSORS, "OEM output")
        enter(config.copy(level = AccessLevel.APP))
        assertTrue(store.load().entries.isEmpty())
        assertTrue(runner.mutations().isEmpty())
        assertTrue(controller.exit().complete)
        assertTrue(runner.mutations().isEmpty())
    }

    @Test fun missingModeAfterApplyIsUnverifiedAndNeverRetried() {
        runner.level = AccessLevel.APP
        runner.replies(SENSORS, "Mode : NORMAL", "No Mode line", "No Mode line")
        val result = enter(config.copy(level = AccessLevel.APP))
        assertEquals(1, runner.commands.count { it == RESTRICT })
        assertEquals(StepStatus.UNVERIFIED, result.steps.first().status)
        assertFalse(controller.exit().complete)
        assertEquals(1, runner.commands.count { it == ENABLE })
        assertFalse(events.any { it.type == EventType.SENSORS_RESTORED })
    }

    @Test fun wrongSensorAllowTokenIsNotSuccessful() {
        runner.level = AccessLevel.APP
        runner.replies(SENSORS, "Mode : NORMAL", "Mode : RESTRICTED : com.other.app")
        assertEquals(StepStatus.UNVERIFIED, enter(config.copy(level = AccessLevel.APP)).steps.first().status)
        assertFalse(events.any { it.type == EventType.SENSORS_RESTRICTED })
    }

    @Test fun forceRetriesOnceForKnownStateButNotForUnknownState() {
        runner.replies("dumpsys deviceidle", "mForceIdle=false")
        runner.replies("cmd deviceidle get deep", "ACTIVE", "IDLE_MAINTENANCE", "OEM")
        assertEquals(StepStatus.VERIFIED, enter(config.copy(restrictSensors = false)).steps.single().status)
        assertEquals(2, runner.commands.count { it == FORCE })
        assertEquals(StepStatus.UNVERIFIED, enter(config.copy(restrictSensors = false)).steps.single().status)
        assertEquals(3, runner.commands.count { it == FORCE })
        assertEquals(1, store.load().entries.size)
    }

    @Test fun forceRetryAlsoChecksGeneration() {
        runner.replies("dumpsys deviceidle", "mForceIdle=false")
        runner.replies("cmd deviceidle get deep", "ACTIVE")
        runner.afterCommand = { if (it == "cmd deviceidle get deep") controller.bumpGeneration() }
        assertEquals(EnterStatus.CANCELLED, enter(config.copy(restrictSensors = false)).status)
        assertEquals(1, runner.commands.count { it == FORCE })
    }

    @Test fun nonzeroMutationExitCanStillBeVerifiedButPartialReadCannot() {
        runner.level = AccessLevel.APP
        runner.replies(SENSORS, "Mode : NORMAL", "Mode : RESTRICTED : $TOKEN")
        runner.answer(RESTRICT) { FakeRunner.result("", exit = 1) }
        assertEquals(StepStatus.VERIFIED, enter(config.copy(level = AccessLevel.APP)).steps.first().status)
        runner.answer(SENSORS) { FakeRunner.result("Mode : NORMAL", timeout = true) }
        assertFalse(controller.exit().complete)
    }

    @Test fun failedDurableSavePreventsMutation() {
        runner.replies(SENSORS, "Mode : NORMAL")
        store.failSave = true
        assertThrows(IllegalStateException::class.java) { enter() }
        assertTrue(runner.mutations().isEmpty())
    }

    @Test fun invalidPackagesNeverReachShell() {
        runner.level = AccessLevel.APP
        val result = enter(config.copy(level = AccessLevel.APP, allowToken = "x; reboot", appsToSuspend = setOf("bad;name")))
        assertTrue(result.steps.all { it.status == StepStatus.SKIPPED })
        assertTrue(runner.commands.isEmpty())
        assertTrue(result.steps.any { it.reason == Reason.UNVERIFIED })
    }

    @Test fun notificationRestoresExactGrantAndFlagsAndSuspensionUsesLedger() {
        runner.replies("dumpsys deviceidle", "mForceIdle=false", "mForceIdle=false")
        runner.replies("cmd deviceidle get deep", "IDLE")
        runner.replies("dumpsys package $PKG",
            packageState(false, true, false, true), packageState(true, true, false, true),
            packageState(true, true, false, true), packageState(true, false, true, true),
            packageState(false, false, true, true), packageState(false, true, false, true))
        val result = enter(config.copy(restrictSensors = false, appsToSuspend = setOf(PKG), packagesToBlockNotifications = setOf(PKG)))
        assertTrue(result.steps.all { it.status == StepStatus.VERIFIED })
        assertTrue(controller.exit().complete)
        assertEquals(listOf("pm grant $PKG android.permission.POST_NOTIFICATIONS",
            "pm clear-permission-flags $PKG android.permission.POST_NOTIFICATIONS user-set",
            "pm set-permission-flags $PKG android.permission.POST_NOTIFICATIONS user-fixed"), runner.mutations().takeLast(3))
        assertTrue(runner.commands.contains("pm unsuspend $PKG"))
    }

    @Test fun noMutationOfAlreadyEnabledFeaturesOrOtherOwnersSensorRestriction() {
        runner.replies(SENSORS, "Mode : RESTRICTED : com.other.owner")
        runner.replies("dumpsys deviceidle", "mForceIdle=true")
        runner.replies("cmd deviceidle get deep", "IDLE")
        runner.replies("settings get global low_power", "1", "1")
        enter(config.copy(batterySaver = true))
        assertTrue(runner.mutations().isEmpty())
        assertTrue(store.load().entries.isEmpty())
    }

    @Test fun generationBumpDuringDurableSaveCancelsBeforeMutationButPreservesRecoveryIntent() {
        runner.replies(SENSORS, "Mode : NORMAL", "Mode : NORMAL")
        lateinit var core: DozeController
        val cancellingStore = object : LedgerStore {
            override fun load() = store.load()
            override fun save(ledger: RestoreLedger) {
                store.save(ledger)
                core.bumpGeneration()
            }
        }
        core = DozeController(runner, CommandCatalog, CapabilityResolver, cancellingStore, clock,
            DozeEventSink {}, 36, grants)
        assertEquals(EnterStatus.CANCELLED, core.enter(config, 0) { true }.status)
        assertTrue(runner.mutations().isEmpty())
        assertEquals("NORMAL", store.load().entries.single().originalValue)
        assertTrue(core.exit().complete)
        assertEquals(listOf(ENABLE), runner.mutations())
    }

    @Test fun failureToPersistCleanupStillAttemptsEveryEntryAndRetainsDurableIntent() {
        store.save(RestoreLedger(listOf(entry(Feature.WIFI, "1"), entry(Feature.BLUETOOTH, "1"))))
        store.failSave = true
        runner.replies("settings get global wifi_on", "1")
        runner.replies("settings get global bluetooth_on", "1")
        val result = controller.exit()
        assertEquals(2, result.remaining.entries.size)
        assertEquals(listOf(ExitError.LEDGER_SAVE_FAILED, ExitError.LEDGER_SAVE_FAILED), result.errors)
        assertEquals(2, runner.mutations().size)
        assertEquals(2, events.count { it.type == EventType.ERROR })
        assertEquals(2, store.restart().load().entries.size)
    }

    @Test fun legacyApiRestoresExactLocationModeAndUsesLegacyForceCommands() {
        runner.replies("dumpsys deviceidle", "mForceIdle=false", "mForceIdle=false")
        runner.replies("dumpsys deviceidle get deep", "IDLE")
        runner.replies("settings get secure location_mode", "2", "0", "2")
        runner.replies("settings get global low_power", "0", "1", "0")
        enter(config.copy(apiLevel = 23, restrictSensors = false, batterySaver = true, features = setOf(Feature.LOCATION)))
        assertTrue(controller.exit().complete)
        assertEquals(listOf("settings put global low_power 1", "dumpsys deviceidle force-idle",
            "settings put secure location_mode 0", "dumpsys deviceidle unforce",
            "settings put global low_power 0", "settings put secure location_mode 2"), runner.mutations())
    }

    companion object {
        private const val TOKEN = "com.akylas.enforcedoze"
        private const val PKG = "com.example.app"
        private const val SENSORS = "dumpsys sensorservice"
        private const val RESTRICT = "dumpsys sensorservice restrict $TOKEN"
        private const val ENABLE = "dumpsys sensorservice enable"
        private const val FORCE = "cmd deviceidle force-idle deep"
        private const val UNFORCE = "cmd deviceidle unforce"
        private fun packageState(suspended: Boolean, granted: Boolean, userSet: Boolean, userFixed: Boolean): String = """
            Packages:
              Package [$PKG] (abc):
                User 0: installed=true suspended=$suspended hidden=false
                  runtime permissions:
                    android.permission.POST_NOTIFICATIONS: granted=$granted, flags=[${listOfNotNull(if (userSet) "USER_SET" else null, if (userFixed) "USER_FIXED" else null).joinToString("|")}]
                User 10: installed=true suspended=true
                  runtime permissions:
                    android.permission.POST_NOTIFICATIONS: granted=false, flags=[USER_SET|USER_FIXED]
        """.trimIndent()
    }
}
