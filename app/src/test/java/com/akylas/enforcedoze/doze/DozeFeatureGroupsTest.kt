package com.akylas.enforcedoze.doze

import com.akylas.enforcedoze.access.*
import org.junit.Assert.*
import org.junit.Test

class DozeFeatureGroupsTest {
    private val store = InMemoryLedgerStore()
    private val runner = FakeRunner()
    private val events = mutableListOf<DozeEvent>()
    private val grants = Grants(true, true)
    private val controller = DozeController(runner, CommandCatalog, CapabilityResolver, store, FakeClock(),
        DozeEventSink { events.add(it) }, 36, grants)
    private val config = DozeConfig(36, AccessLevel.SHELL, grants, restrictSensors = false)

    private fun force(api: Int = 36, deep: String = "IDLE") {
        if (api >= 24) {
            runner.replies("dumpsys deviceidle", "mForceIdle=false", "mForceIdle=false")
            runner.replies("cmd deviceidle get deep", deep)
        } else {
            // M has no `get deep`: the applied deep state comes from the plain dump.
            runner.replies("dumpsys deviceidle", "mForceIdle=false", "mForceIdle=true\nmState=$deep", "mForceIdle=false")
        }
    }
    private fun enter(config: DozeConfig = this.config) = controller.enter(config, controller.currentGeneration) { true }

    @Test fun exitWithoutLedgerCannotMutateUserAirplaneRotationBrightnessOrPackages() {
        assertTrue(controller.exit().complete)
        assertTrue(runner.commands.isEmpty())
    }

    @Test fun allRadioGroupsRestoreCapturedOriginalsNotCurrentPreferences() {
        force()
        val readings = mapOf(Feature.WIFI to "settings get global wifi_on",
            Feature.MOBILE_DATA to "settings get global mobile_data", Feature.BLUETOOTH to "settings get global bluetooth_on",
            Feature.LOCATION to "cmd location is-location-enabled", Feature.BIOMETRICS to "settings get secure biometric_keyguard_enabled")
        for (command in readings.values) runner.replies(command, "1", "0", "1")
        runner.replies("cmd connectivity airplane-mode", "disabled", "enabled", "disabled")
        runner.replies("settings get global low_power", "0", "1", "0")
        runner.beforeMutation = { assertTrue("intent must be durable before $it", store.load().entries.isNotEmpty()) }
        assertTrue(enter(config.copy(batterySaver = true, features = readings.keys + Feature.AIRPLANE)).steps.all { it.status == StepStatus.VERIFIED })
        assertTrue(runner.commands.indexOf("cmd power set-mode 1") < runner.commands.indexOf("cmd deviceidle force-idle deep"))
        // No entry config is available to exit, just persisted originals.
        assertTrue(controller.exit().complete)
        assertTrue(runner.commands.containsAll(listOf("cmd connectivity airplane-mode disable", "cmd location set-location-enabled true",
            "cmd bluetooth_manager enable", "svc data enable", "cmd wifi set-wifi-enabled enabled")))
    }

    @Test fun groupsWaitForFirstVerifiedIdleAndLaterReforceAppliesThem() {
        force(deep = "OEM")
        runner.replies("cmd deviceidle get deep", "IDLE")
        runner.replies("settings get global wifi_on", "1", "0")
        val requested = config.copy(features = setOf(Feature.WIFI))
        assertEquals(StepStatus.SKIPPED, enter(requested).steps.last().status)
        assertFalse(runner.commands.contains("cmd wifi set-wifi-enabled disabled"))
        assertEquals(StepStatus.VERIFIED, enter(requested).steps.last().status)
        assertEquals(1, runner.commands.count { it == "cmd wifi set-wifi-enabled disabled" })
    }

    @Test fun maintenanceRestoresAndReappliesLedgerRadiosWithoutReplacingOriginal() {
        force()
        runner.replies("settings get global wifi_on", "1", "0", "1", "0", "1")
        enter(config.copy(features = setOf(Feature.WIFI)))
        val original = store.durable
        assertTrue(controller.maintenance(true, controller.currentGeneration) { true }.steps.all { it.status == StepStatus.VERIFIED })
        assertEquals(original, store.durable)
        assertTrue(controller.maintenance(false, controller.currentGeneration) { true }.steps.all { it.status == StepStatus.VERIFIED })
        assertEquals("1", store.load().entries.single { it.feature == Feature.WIFI }.originalValue)
        assertTrue(controller.exit().complete)
        assertEquals(2, runner.commands.count { it == "cmd wifi set-wifi-enabled enabled" })
    }

    @Test fun exitGenerationCancelsQueuedMaintenanceReapply() {
        force()
        runner.replies("settings get global wifi_on", "1", "0", "1")
        enter(config.copy(features = setOf(Feature.WIFI)))
        val generation = controller.currentGeneration
        controller.bumpGeneration()
        assertEquals(EnterStatus.CANCELLED, controller.maintenance(false, generation) { true }.status)
        assertEquals(1, runner.commands.count { it == "cmd wifi set-wifi-enabled disabled" })
        assertTrue(controller.exit().complete)
    }

    @Test fun generationChangeBetweenMaintenanceCommandsStopsRemainingRadios() {
        force()
        runner.replies("settings get global wifi_on", "1", "0")
        runner.replies("settings get global bluetooth_on", "1", "0")
        enter(config.copy(features = setOf(Feature.WIFI, Feature.BLUETOOTH)))
        runner.afterCommand = { if (it == "cmd bluetooth_manager enable") controller.bumpGeneration() }
        assertEquals(EnterStatus.CANCELLED, controller.maintenance(true, controller.currentGeneration) { true }.status)
        assertFalse(runner.commands.contains("cmd wifi set-wifi-enabled enabled"))
        assertEquals(3, store.load().entries.size) // force plus both unchanged radio originals
    }

    @Test fun accessLossDuringMaintenanceRetainsDebtAndStopsMutations() {
        force()
        runner.replies("settings get global wifi_on", "1", "0")
        enter(config.copy(features = setOf(Feature.WIFI)))
        runner.level = AccessLevel.APP
        val result = controller.maintenance(true, controller.currentGeneration) { true }
        assertEquals(Reason.NO_ACCESS, result.steps.single().reason)
        assertEquals("1", store.load().entries.single { it.feature == Feature.WIFI }.originalValue)
        assertFalse(runner.commands.contains("cmd wifi set-wifi-enabled enabled"))
    }

    @Test fun alreadySuspendedPackageIsNotOwnedAndChangedBlocklistCannotUnsuspendIt() {
        force()
        runner.replies("dumpsys package $OWNED", pkg(OWNED, false), pkg(OWNED, true), pkg(OWNED, false))
        runner.replies("dumpsys package $FOREIGN", pkg(FOREIGN, true), pkg(FOREIGN, true))
        enter(config.copy(appsToSuspend = setOf(OWNED, FOREIGN)))
        assertEquals(listOf(OWNED), store.load().entries.filter { it.feature == Feature.APP_SUSPEND }.map { it.target })
        assertTrue(controller.exit().complete)
        assertTrue(runner.commands.contains("pm unsuspend $OWNED"))
        assertFalse(runner.commands.contains("pm unsuspend $FOREIGN"))
    }

    @Test fun notificationsRestoreOriginalGrantAndBothFlagsAfterRestart() {
        force()
        runner.replies("dumpsys package $OWNED", pkg(OWNED, false, false, false, true),
            pkg(OWNED, false, false, true, true), pkg(OWNED, false, false, false, true))
        enter(config.copy(packagesToBlockNotifications = setOf(OWNED)))
        assertEquals("0,0,1", store.load().entries.single { it.feature == Feature.NOTIFICATION_BLOCK }.originalValue)
        val restarted = DozeController(runner, CommandCatalog, CapabilityResolver, store.restart(), FakeClock(), DozeEventSink {}, 36, grants)
        assertTrue(restarted.reconcile().complete)
        assertTrue(runner.commands.contains("pm revoke $OWNED android.permission.POST_NOTIFICATIONS"))
        assertTrue(runner.commands.contains("pm clear-permission-flags $OWNED android.permission.POST_NOTIFICATIONS user-set"))
        assertTrue(runner.commands.contains("pm set-permission-flags $OWNED android.permission.POST_NOTIFICATIONS user-fixed"))
    }

    @Test fun legacyNotificationsRequireRootAndResolvedTransactionWithoutGuessing() {
        force(32)
        val rootConfig = config.copy(apiLevel = 32, level = AccessLevel.ROOT, packagesToBlockNotifications = setOf(OWNED))
        assertEquals(Reason.REQUIRES_ROOT, enter(rootConfig).steps.last().reason)
        runner.level = AccessLevel.ROOT
        runner.replies("cmd deviceidle get deep", "IDLE")
        assertEquals(Reason.UNVERIFIED, enter(rootConfig).steps.last().reason)
        assertFalse(runner.commands.any { it.startsWith("service call notification") })
    }

    @Test fun resolvedLegacyTransactionRunsThroughSameRootRunnerAndRestoresCapturedUid() {
        runner.level = AccessLevel.ROOT
        force(32)
        runner.replies("dumpsys notification", "PackagePreferences: $OWNED (10123) importance=UNSPECIFIED",
            "PackagePreferences: $OWNED (10123) importance=NONE", "PackagePreferences: $OWNED (10123) importance=UNSPECIFIED")
        val block = "service call notification 7 s16 $OWNED i32 10123 i32 0"
        val restore = "service call notification 7 s16 $OWNED i32 10123 i32 1"
        runner.replies(block, "Parcel(00000000)")
        runner.replies(restore, "Parcel(00000000)")
        assertEquals(StepStatus.VERIFIED, enter(config.copy(apiLevel = 32, level = AccessLevel.ROOT,
            packagesToBlockNotifications = setOf(OWNED), legacyNotificationTransaction = 7)).steps.last().status)
        assertEquals("1,10123,7", store.load().entries.single { it.feature == Feature.NOTIFICATION_BLOCK }.originalValue)
        assertTrue(controller.exit().complete)
        assertTrue(runner.commands.contains(restore))
    }

    @Test fun unparseableLegacyImportanceCannotAuthorizeMutation() {
        runner.level = AccessLevel.ROOT
        force(32)
        runner.replies("dumpsys notification", "PackagePreferences: $OWNED (10123) importance=LOW")
        assertEquals(StepStatus.UNVERIFIED, enter(config.copy(apiLevel = 32, level = AccessLevel.ROOT,
            packagesToBlockNotifications = setOf(OWNED), legacyNotificationTransaction = 7)).steps.last().status)
        assertFalse(runner.commands.any { it.startsWith("service call notification") })
    }

    @Test fun legacyRootFeaturesAreSkippedWithRequiresRootUnderShell() {
        force(23)
        val result = enter(config.copy(apiLevel = 23, features = setOf(Feature.SETPROP_DOZE, Feature.SENSOR_PRIVACY_ALL), appsToSuspend = setOf(OWNED)))
        for (feature in setOf(Feature.SETPROP_DOZE, Feature.SENSOR_PRIVACY_ALL, Feature.PM_DISABLE)) {
            assertEquals(Reason.REQUIRES_ROOT, result.steps.single { it.feature == feature }.reason)
        }
        assertFalse(runner.commands.any { it.startsWith("setprop") || it.startsWith("service call") || it.startsWith("pm disable") })
    }

    @Test fun rootShizukuStyleRunnerRestoresPropertyAndApi23PackageEnabledStateExactly() {
        runner.level = AccessLevel.ROOT
        force(23)
        runner.replies("getprop persist.sys.doze_powersave", "false", "true", "false")
        runner.replies("setprop persist.sys.doze_powersave true", "")
        runner.replies("setprop persist.sys.doze_powersave false", "")
        runner.replies("dumpsys package $OWNED", pkg(OWNED, false, enabled = 0), pkg(OWNED, false, enabled = 2), pkg(OWNED, false, enabled = 0))
        enter(config.copy(apiLevel = 23, level = AccessLevel.ROOT, features = setOf(Feature.SETPROP_DOZE), appsToSuspend = setOf(OWNED)))
        assertEquals("0", store.load().entries.single { it.feature == Feature.PM_DISABLE }.originalValue)
        assertTrue(controller.exit().complete)
        assertTrue(runner.commands.contains("pm default-state $OWNED"))
        assertTrue(runner.commands.contains("setprop persist.sys.doze_powersave false"))
    }

    @Test fun sensorPrivacyUsesRootRunnerAndOnlyExplicitAllSensorReadback() {
        runner.level = AccessLevel.ROOT
        force()
        runner.replies("dumpsys sensor_privacy", "All sensor privacy enabled: false",
            "All sensor privacy enabled: true", "All sensor privacy enabled: false")
        runner.answer("service call sensor_privacy 9 i32 1") {
            assertEquals("0", store.load().entries.single { it.feature == Feature.SENSOR_PRIVACY_ALL }.originalValue)
            FakeRunner.result("")
        }
        runner.replies("service call sensor_privacy 9 i32 0", "")
        assertEquals(StepStatus.VERIFIED, enter(config.copy(level = AccessLevel.ROOT, features = setOf(Feature.SENSOR_PRIVACY_ALL))).steps.last().status)
        assertTrue(controller.exit().complete)
        assertTrue(runner.commands.contains("service call sensor_privacy 9 i32 0"))
    }

    @Test fun sensorPrivacyUnknownOriginalStaysUnverifiedWithoutMutation() {
        runner.level = AccessLevel.ROOT
        force()
        runner.replies("dumpsys sensor_privacy", "OEM per-sensor states")
        assertEquals(StepStatus.UNVERIFIED, enter(config.copy(level = AccessLevel.ROOT, features = setOf(Feature.SENSOR_PRIVACY_ALL))).steps.last().status)
        assertFalse(runner.commands.any { it.startsWith("service call sensor_privacy") })
    }

    @Test fun pre30AirplaneNeverUsesProtectedBroadcastOrSettingsOnlyMutation() {
        runner.level = AccessLevel.ROOT
        force(29)
        assertEquals(Reason.API_TOO_OLD, enter(config.copy(apiLevel = 29, level = AccessLevel.ROOT, features = setOf(Feature.AIRPLANE))).steps.last().reason)
        assertFalse(runner.commands.any { "AIRPLANE_MODE" in it || "airplane_mode_on" in it })
    }

    @Test fun screenOnWithWaitForUnlockRestoresOnlyLedgerBiometricsAndExitDoesNotRepeatIt() {
        val biometric = LedgerEntry(Feature.BIOMETRICS, null, "1", 0, apiLevel = 36)
        val wifi = LedgerEntry(Feature.WIFI, null, "1", 0, apiLevel = 36)
        val app = LedgerEntry(Feature.APP_SUSPEND, OWNED, "0", 0, apiLevel = 36)
        store.save(RestoreLedger(listOf(wifi, app, biometric)))
        runner.replies("settings get secure biometric_keyguard_enabled", "1")
        controller.restoreBiometrics(controller.currentGeneration) { true }
        assertEquals("keyguard must receive the ledger original before unlock", listOf(
            "settings put secure biometric_keyguard_enabled 1",
        ), runner.mutations())
        assertEquals(listOf(wifi, app), store.load().entries)
        runner.replies("settings get global wifi_on", "1")
        runner.replies("dumpsys package $OWNED", pkg(OWNED, false))
        assertTrue(controller.exit().complete)
        assertEquals(1, runner.commands.count { it == "settings put secure biometric_keyguard_enabled 1" })
    }

    @Test fun maintenanceRestoreLoadFailureCancelsWithoutCommands() = maintenanceLoadFailure(true)
    @Test fun maintenanceReapplyLoadFailureCancelsWithoutCommands() = maintenanceLoadFailure(false)

    private fun maintenanceLoadFailure(restore: Boolean) {
        val broken = object : LedgerStore {
            override fun load(): RestoreLedger = throw IllegalStateException("type-corrupt preference")
            override fun save(ledger: RestoreLedger) = fail("must not save after failed load")
        }
        val subject = DozeController(runner, CommandCatalog, CapabilityResolver, broken, FakeClock(),
            DozeEventSink { events.add(it) }, 36, grants)
        val result = try { subject.maintenance(restore, subject.currentGeneration) { true } }
            catch (error: Exception) { fail("ledger load must cancel maintenance, not crash: $error"); return }
        assertEquals(EnterStatus.CANCELLED, result.status)
        assertTrue(runner.commands.isEmpty())
        assertTrue(events.any { it.type == EventType.ERROR })
    }

    @Test fun exitRestoresAirplaneBeforeWifiAndRemovesBothEntries() = airplaneWifiRestore(false)
    @Test fun maintenanceRestoresAirplaneBeforeWifiAndRetainsOriginals() = airplaneWifiRestore(true)

    private fun airplaneWifiRestore(maintenance: Boolean) {
        val originals = RestoreLedger(listOf(
            LedgerEntry(Feature.WIFI, null, "1", 0, apiLevel = 36),
            LedgerEntry(Feature.AIRPLANE, null, "0", 0, apiLevel = 36),
        ))
        store.save(originals)
        var airplane = true
        runner.afterCommand = { if (it == "cmd connectivity airplane-mode disable") airplane = false }
        runner.answer("settings get global wifi_on") { FakeRunner.result(if (airplane) "2" else "1") }
        runner.replies("cmd connectivity airplane-mode", "disabled")
        if (maintenance) {
            assertTrue(controller.maintenance(true, controller.currentGeneration) { true }.steps.all { it.status == StepStatus.VERIFIED })
            assertEquals(originals, store.load())
        } else {
            val result = controller.exit()
            assertTrue("both restored entries must be removed", result.complete)
            assertEquals(2, result.restored.size)
        }
        assertEquals(listOf("cmd connectivity airplane-mode disable", "cmd wifi set-wifi-enabled enabled"), runner.mutations())
    }

    companion object {
        private const val OWNED = "com.example.owned"
        private const val FOREIGN = "com.example.foreign"
        private fun pkg(name: String, suspended: Boolean, granted: Boolean = true, userSet: Boolean = false,
                        userFixed: Boolean = false, enabled: Int = 1) = """
            Packages:
              Package [$name] (abc):
                User 0: installed=true suspended=$suspended enabled=$enabled
                  runtime permissions:
                    android.permission.POST_NOTIFICATIONS: granted=$granted, flags=[${listOfNotNull(if (userSet) "USER_SET" else null, if (userFixed) "USER_FIXED" else null).joinToString("|")}]
        """.trimIndent()
    }
}
