package com.akylas.enforcedoze.monitor

import com.akylas.enforcedoze.access.Feature
import com.akylas.enforcedoze.doze.DeepState
import com.akylas.enforcedoze.doze.EventType
import com.akylas.enforcedoze.doze.LightState
import com.akylas.enforcedoze.doze.SensorMode
import com.akylas.enforcedoze.doze.parse.HistoryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Append-only compatibility goldens: additions are allowed, renames/removals are not. */
class PersistedNamesTest {
    @Test
    fun ledgerFeatureNamesAreWireFormat() {
        assertNames(
            "Feature",
            listOf(
                "FORCE_DOZE", "DOZE_STATE_READ", "TUNABLES", "MOTION_SENSORS", "BATTERY_SAVER",
                "WIFI", "MOBILE_DATA", "BLUETOOTH", "AIRPLANE", "LOCATION", "BIOMETRICS", "APP_SUSPEND",
                "NOTIFICATION_BLOCK", "WHITELIST_EDIT", "FOCUSED_APP", "SENSOR_PRIVACY_ALL",
                "SETPROP_DOZE", "PM_DISABLE",
            ),
            Feature.entries,
        )
    }

    @Test
    fun journalSourceNamesAreWireFormat() {
        assertNames("Source", listOf("APP", "OS_HISTORY"), Source.entries)
    }

    @Test
    fun journalEventTypeNamesAreWireFormat() {
        assertNames(
            "EventType",
            listOf(
                "SCREEN_OFF", "SCREEN_ON", "ENTER_STEP", "VERIFY", "REFORCE", "IDLE_CHANGED", "MAINT_START", "MAINT_END",
                "SENSORS_RESTRICTED", "SENSORS_RESTORED", "SKIPPED", "RESTORE_FAILED", "RECOVERY_DEBT",
                "ACCESS_CHANGED", "EXTERNAL_CALL", "ERROR",
            ),
            EventType.entries,
        )
    }

    @Test
    fun journalDeepStateNamesAreWireFormat() {
        assertNames(
            "DeepState",
            listOf(
                "ACTIVE", "INACTIVE", "IDLE_PENDING", "SENSING", "LOCATING", "IDLE", "IDLE_MAINTENANCE",
                "QUICK_DOZE_DELAY", "UNKNOWN",
            ),
            DeepState.entries,
        )
    }

    @Test
    fun journalLightStateNamesAreWireFormat() {
        assertNames(
            "LightState",
            listOf("ACTIVE", "INACTIVE", "IDLE", "WAITING_FOR_NETWORK", "IDLE_MAINTENANCE", "OVERRIDE", "PRE_IDLE", "UNKNOWN"),
            LightState.entries,
        )
    }

    @Test
    fun journalSensorModeNamesAreWireFormat() {
        assertNames("SensorMode", listOf("NORMAL", "RESTRICTED", "OTHER", "UNVERIFIED"), SensorMode.entries)
    }

    @Test
    fun journalHistoryKindNamesAreWireFormat() {
        assertNames("HistoryKind", listOf("NORMAL", "LIGHT_IDLE", "LIGHT_MAINT", "DEEP_IDLE", "DEEP_MAINT"), HistoryKind.entries)
    }

    @Test
    fun journalEventCodeValuesAreWireFormat() {
        val golden = listOf(
            "ACCESS_LOST", "ADMISSION", "ENTER_FAILED", "EXTERNAL_REAPPLY_FAILED", "FEATURE_SELECTION_FAILED",
            "FOCUSED_APP_UNVERIFIED", "FOREGROUND_START_DENIED", "HISTORY_IMPORT_FAILED", "HISTORY_READ_FAILED",
            "IDLE_CHANGED", "LEDGER_DAMAGED", "LEDGER_LOAD_FAILED", "LEDGER_SAVE_FAILED", "LEDGER_RECOVERY_COMMIT_FAILED",
            "MAINT_START", "MAINT_END", "MUSIC_SELECTION_FAILED", "MUSIC_SELECTION_TIMEOUT", "MUSIC_SELECTION_UNAVAILABLE",
            "REFORCE", "REFORCE_FAILED", "RESET_FAILED", "RESTORE_WINDOW_STARVED", "SAFETY_COMMAND_FAILED",
            "SAFETY_READ_UNAVAILABLE", "TEARDOWN_FAILED", "TEARDOWN_TIMEOUT", "EXTERNAL_REAPPLY_ENTER_PENDING",
            "EXTERNAL_REAPPLY_NOT_ADMITTED", "EXTERNAL_REAPPLY_MAINTENANCE", "EXTERNAL_REAPPLY_STATE_UNKNOWN",
            "EXTERNAL_REAPPLY_SPACING", "EXTERNAL_REAPPLY_BUDGET", "RESTORE_SENSORS", "UNFORCE", "RAISE_DEBT",
            "LEDGER_CORRUPT_LINES", "LEDGER_CORRUPT_LINE", "LEDGER_CORRUPT_RECOVERED_LINES",
        )
        val actual = EventCodes::class.java.fields.filter { it.type == String::class.java }
            .associate { it.name to it.get(null) }
        golden.forEach { name ->
            assertEquals("EventCodes.$name is wire format; append new codes instead of renaming/removing", name, actual[name])
        }
    }

    private fun assertNames(type: String, golden: List<String>, actual: List<Enum<*>>) {
        val missing = golden.toSet() - actual.map { it.name }.toSet()
        assertTrue("$type names are wire format; append new names instead of renaming/removing: $missing", missing.isEmpty())
    }
}
