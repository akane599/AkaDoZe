package com.akylas.enforcedoze.doze

import com.akylas.enforcedoze.access.CapabilityResolver
import com.akylas.enforcedoze.access.CommandCatalog
import com.akylas.enforcedoze.access.CommandRunner
import com.akylas.enforcedoze.access.CommandResult
import com.akylas.enforcedoze.access.AccessLevel
import com.akylas.enforcedoze.access.Feature
import com.akylas.enforcedoze.access.Grants
import com.akylas.enforcedoze.access.Reason
import org.junit.Assert.*
import org.junit.Test

/** Golden observable traces captured before the restore-engine extraction. */
class RestoreCharacterizationTest {
    private val wifi = entry(Feature.WIFI, "1")
    private val data = entry(Feature.MOBILE_DATA, "1")
    private val airplane = entry(Feature.AIRPLANE, "0")
    private val saver = entry(Feature.BATTERY_SAVER, "0")
    private val force = entry(Feature.FORCE_DOZE, "0")
    private val sensors = entry(Feature.MOTION_SENSORS, "NORMAL")
    private val unreadOriginal = entry(Feature.BIOMETRICS, null)
    private val mixed = listOf(wifi, data, airplane, saver, force, sensors, unreadOriginal)

    @Test fun mixedRestorePinsOrderSavesEventsAndSettledVerdicts() {
        val f = Fixture(mixed)
        f.runner.replies("dumpsys sensorservice", "Mode : NORMAL")
        f.runner.replies("dumpsys deviceidle", "mForceIdle=false")
        f.runner.replies("settings get global low_power", "0")
        f.runner.replies(AIRPLANE_READ, "disabled")
        f.runner.replies(DATA_READ, "0", "1")
        f.runner.replies(WIFI_READ, "0", "0", "0", "0")

        val result = f.controller.exit()

        assertEquals(listOf(
            "dumpsys sensorservice enable", "dumpsys sensorservice",
            "cmd deviceidle unforce", "dumpsys deviceidle",
            "cmd power set-mode 0", "settings get global low_power",
            "cmd connectivity airplane-mode disable", AIRPLANE_READ,
            "svc data enable", DATA_READ, DATA_READ,
            "cmd wifi set-wifi-enabled enabled", WIFI_READ, WIFI_READ, WIFI_READ, WIFI_READ,
        ), f.runner.commands)
        assertEquals(listOf(
            RestoreLedger(mixed - sensors), RestoreLedger(mixed - sensors - force),
            RestoreLedger(listOf(wifi, data, airplane, unreadOriginal)),
            RestoreLedger(listOf(wifi, data, airplane)), RestoreLedger(listOf(wifi, data)),
            RestoreLedger(listOf(wifi)), RestoreLedger(listOf(wifi.copy(attempts = 1))),
        ), f.saves)
        assertEquals(listOf(
            event(EventType.VERIFY, Feature.MOTION_SENSORS, sensor = SensorMode.NORMAL),
            event(EventType.SENSORS_RESTORED, Feature.MOTION_SENSORS, sensor = SensorMode.NORMAL),
            event(EventType.VERIFY, Feature.FORCE_DOZE), event(EventType.VERIFY, Feature.BATTERY_SAVER),
            event(EventType.SKIPPED, Feature.BIOMETRICS, Reason.UNVERIFIED),
            event(EventType.VERIFY, Feature.AIRPLANE), event(EventType.VERIFY, Feature.MOBILE_DATA),
            event(EventType.VERIFY, Feature.WIFI, Reason.UNVERIFIED),
            event(EventType.RESTORE_FAILED, Feature.WIFI, Reason.UNVERIFIED),
            event(EventType.RECOVERY_DEBT, Feature.WIFI, Reason.UNVERIFIED),
        ), f.events)
        assertEquals(ExitResult(listOf(sensors, force, saver, airplane, data),
            RestoreLedger(listOf(wifi.copy(attempts = 1)))), result)
        assertEquals(listOf(150L, 150L, 150L, 150L), f.waits)
        assertEquals(1_600L, f.clock.elapsed)
        assertEquals(listOf(100L, 100L, 100L, 100L),
            f.readTimeouts.filter { it.second == 100L }.map { it.second })
    }

    @Test fun maintenancePinsReverseRestoreForwardApplyAndNoLedgerWrites() {
        val f = Fixture(mixed)
        f.runner.replies(AIRPLANE_READ, "disabled", "enabled")
        f.runner.replies(DATA_READ, "0", "1", "0")
        f.runner.replies(WIFI_READ, "1", "0")

        assertEquals(EnterResult(EnterStatus.COMPLETED, listOf(
            step(Feature.AIRPLANE), step(Feature.MOBILE_DATA), step(Feature.WIFI),
        )), f.controller.maintenance(true, 0) { true })
        assertEquals(EnterResult(EnterStatus.COMPLETED, listOf(
            step(Feature.WIFI), step(Feature.MOBILE_DATA), step(Feature.AIRPLANE),
        )), f.controller.maintenance(false, 0) { true })
        assertEquals(listOf(
            "cmd connectivity airplane-mode disable", AIRPLANE_READ,
            "svc data enable", DATA_READ, DATA_READ,
            "cmd wifi set-wifi-enabled enabled", WIFI_READ,
            "cmd wifi set-wifi-enabled disabled", WIFI_READ,
            "svc data disable", DATA_READ,
            "cmd connectivity airplane-mode enable", AIRPLANE_READ,
        ), f.runner.commands)
        assertEquals(listOf(
            event(EventType.VERIFY, Feature.AIRPLANE), event(EventType.VERIFY, Feature.MOBILE_DATA),
            event(EventType.VERIFY, Feature.WIFI), event(EventType.VERIFY, Feature.WIFI),
            event(EventType.VERIFY, Feature.MOBILE_DATA), event(EventType.VERIFY, Feature.AIRPLANE),
        ), f.events)
        assertTrue(f.saves.isEmpty())
        assertEquals(RestoreLedger(mixed), f.ledger)
        assertEquals(listOf(150L), f.waits)
    }

    @Test fun admissionRevokedAfterMutationPinsPartialExitAndMaintenanceResults() {
        for (maintenance in listOf(false, true)) {
            val f = Fixture(listOf(wifi, data, airplane))
            var admitted = true
            f.runner.replies(AIRPLANE_READ, "disabled")
            f.runner.afterCommand = { if (it == "svc data enable") admitted = false }
            if (maintenance) {
                assertEquals(EnterResult(EnterStatus.CANCELLED, listOf(step(Feature.AIRPLANE))),
                    f.controller.maintenance(true, 0) { admitted })
                assertTrue(f.saves.isEmpty())
                assertEquals(RestoreLedger(listOf(wifi, data, airplane)), f.ledger)
            } else {
                assertEquals(ExitResult(listOf(airplane), RestoreLedger(listOf(wifi, data))),
                    f.controller.exit(admission = { admitted }))
                assertEquals(listOf(RestoreLedger(listOf(wifi, data))), f.saves)
            }
            assertEquals(listOf("cmd connectivity airplane-mode disable", AIRPLANE_READ, "svc data enable"),
                f.runner.commands)
            assertEquals(listOf(event(EventType.VERIFY, Feature.AIRPLANE)), f.events)
            assertTrue(f.waits.isEmpty())
        }
    }

    @Test fun accessLostBetweenNotificationRestoreCommandsPinsDebtWithoutVerification() {
        val notification = LedgerEntry(Feature.NOTIFICATION_BLOCK, "com.example.app", "1,0,1", 0, apiLevel = 36)
        val f = Fixture(listOf(notification))
        val firstCommand = "pm grant com.example.app android.permission.POST_NOTIFICATIONS"
        f.runner.replies("dumpsys package com.example.app", "")
        f.runner.afterCommand = { if (it == firstCommand) f.runner.level = AccessLevel.APP }

        val result = f.controller.exit()

        assertEquals(listOf(firstCommand), f.runner.commands)
        val retained = RestoreLedger(listOf(notification.copy(attempts = 1, debt = true)))
        assertEquals(listOf(retained), f.saves)
        assertEquals(retained, f.ledger)
        assertEquals(RestoreLedgerCodec.encode(retained), f.rawLedger)
        assertEquals(listOf(
            DozeEvent(EventType.RESTORE_FAILED, "NOTIFICATION_BLOCK", feature = Feature.NOTIFICATION_BLOCK,
                target = "com.example.app", reason = Reason.NO_ACCESS),
            DozeEvent(EventType.RECOVERY_DEBT, "NOTIFICATION_BLOCK", feature = Feature.NOTIFICATION_BLOCK,
                target = "com.example.app", reason = Reason.NO_ACCESS),
        ), f.events)
        assertEquals(ExitResult(emptyList(), retained, emptyList()), result)
        assertFalse(result.complete)
        assertTrue(f.waits.isEmpty())
    }

    @Test fun maintenanceRadiosHaveOnlySingleCommandRestoresAcrossSupportedApis() {
        // A multi-command radio would make executeMaintenanceEntry's mid-entry lost path reachable;
        // that path would then need its own access-loss test.
        val radios = listOf(Feature.WIFI, Feature.MOBILE_DATA, Feature.BLUETOOTH, Feature.AIRPLANE, Feature.LOCATION)
        var supportedRestores = 0
        for (feature in radios) {
            for (apiLevel in 23..36) {
                for (original in listOf("0", "1")) {
                    val commands = CommandCatalog.restore(feature, apiLevel, original) ?: continue
                    assertEquals("$feature API $apiLevel original=$original", 1, commands.size)
                    supportedRestores++
                }
            }
        }
        assertEquals(126, supportedRestores)
    }

    @Test fun undecodableLedgerPinsLoadFailureWithoutCommandsOrOverwrite() {
        for (maintenance in listOf(false, true)) {
            val f = Fixture(emptyList(), unreadable = true)
            if (maintenance) {
                assertEquals(EnterResult(EnterStatus.CANCELLED, emptyList()),
                    f.controller.maintenance(true, 0) { true })
            } else {
                assertEquals(ExitResult(emptyList(), RestoreLedger(), listOf(ExitError.LEDGER_LOAD_FAILED)),
                    f.controller.exit())
            }
            assertEquals(listOf(DozeEvent(EventType.ERROR, "ERROR", reason = Reason.UNVERIFIED)), f.events)
            assertTrue(f.runner.commands.isEmpty())
            assertTrue(f.saves.isEmpty())
            assertEquals("undecodable ledger", f.rawLedger)
        }
    }

    private class Fixture(entries: List<LedgerEntry>, private val unreadable: Boolean = false) {
        val runner = FakeRunner()
        val clock = FakeClock()
        val events = mutableListOf<DozeEvent>()
        val saves = mutableListOf<RestoreLedger>()
        val waits = mutableListOf<Long>()
        val readTimeouts = mutableListOf<Pair<String, Long>>()
        var ledger = RestoreLedger(entries)
        var rawLedger = if (unreadable) "undecodable ledger" else RestoreLedgerCodec.encode(ledger)
        val store = object : LedgerStore {
            override fun load(): RestoreLedger {
                if (unreadable) {
                    check(RestoreLedgerCodec.decode(rawLedger).corruptLines.isEmpty())
                }
                return ledger
            }
            override fun save(ledger: RestoreLedger) {
                saves.add(ledger)
                this@Fixture.ledger = ledger
                rawLedger = RestoreLedgerCodec.encode(ledger)
            }
        }
        val controller = DozeController(
            object : CommandRunner {
                override val level: AccessLevel get() = runner.level
                override fun run(command: String, timeoutMs: Long): CommandResult {
                    readTimeouts.add(command to timeoutMs)
                    return runner.run(command, timeoutMs)
                }
            }, CommandCatalog, CapabilityResolver, store, clock,
            DozeEventSink { events.add(it) }, 36, Grants(true, true),
            sleeper = { waits.add(it); clock.elapsed += it },
        )
    }

    private fun entry(feature: Feature, original: String?) = LedgerEntry(feature, null, original, 0, apiLevel = 36)
    private fun step(feature: Feature) = StepResult(feature, null, StepStatus.VERIFIED)
    private fun event(type: EventType, feature: Feature, reason: Reason? = null, sensor: SensorMode? = null) =
        DozeEvent(type, feature.name, feature = feature, reason = reason, sensor = sensor)

    private companion object {
        const val AIRPLANE_READ = "cmd connectivity airplane-mode"
        const val DATA_READ = "settings get global mobile_data"
        const val WIFI_READ = "settings get global wifi_on"
    }
}
