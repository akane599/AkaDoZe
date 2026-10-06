package com.akylas.enforcedoze.monitor

import org.junit.Assert.assertEquals
import org.junit.Test

class EventCodesTest {
    @Test fun typedDetailEmittersKeepTheirOriginalEnumNames() {
        com.akylas.enforcedoze.doze.ReapplySkip.entries.forEach { assertEquals(it.name, it.detail) }
        com.akylas.enforcedoze.doze.ExitError.entries.forEach { assertEquals(it.name, it.detail) }
        com.akylas.enforcedoze.doze.Action.entries.forEach { assertEquals(it.name, it.detail) }
    }

    @Test fun everyPersistedCodeKeepsItsOriginalLiteral() {
        val expected = mapOf(
            "ACCESS_LOST" to EventCodes.ACCESS_LOST,
            "ADMISSION" to EventCodes.ADMISSION,
            "ENTER_FAILED" to EventCodes.ENTER_FAILED,
            "EXTERNAL_REAPPLY_FAILED" to EventCodes.EXTERNAL_REAPPLY_FAILED,
            "FEATURE_SELECTION_FAILED" to EventCodes.FEATURE_SELECTION_FAILED,
            "FOCUSED_APP_UNVERIFIED" to EventCodes.FOCUSED_APP_UNVERIFIED,
            "FOREGROUND_START_DENIED" to EventCodes.FOREGROUND_START_DENIED,
            "HISTORY_IMPORT_FAILED" to EventCodes.HISTORY_IMPORT_FAILED,
            "HISTORY_READ_FAILED" to EventCodes.HISTORY_READ_FAILED,
            "IDLE_CHANGED" to EventCodes.IDLE_CHANGED,
            "LEDGER_DAMAGED" to EventCodes.LEDGER_DAMAGED,
            "LEDGER_LOAD_FAILED" to EventCodes.LEDGER_LOAD_FAILED,
            "LEDGER_SAVE_FAILED" to EventCodes.LEDGER_SAVE_FAILED,
            "LEDGER_RECOVERY_COMMIT_FAILED" to EventCodes.LEDGER_RECOVERY_COMMIT_FAILED,
            "MAINT_START" to EventCodes.MAINT_START,
            "MAINT_END" to EventCodes.MAINT_END,
            "MUSIC_SELECTION_FAILED" to EventCodes.MUSIC_SELECTION_FAILED,
            "MUSIC_SELECTION_TIMEOUT" to EventCodes.MUSIC_SELECTION_TIMEOUT,
            "MUSIC_SELECTION_UNAVAILABLE" to EventCodes.MUSIC_SELECTION_UNAVAILABLE,
            "REFORCE" to EventCodes.REFORCE,
            "REFORCE_FAILED" to EventCodes.REFORCE_FAILED,
            "RESET_FAILED" to EventCodes.RESET_FAILED,
            "RESTORE_WINDOW_STARVED" to EventCodes.RESTORE_WINDOW_STARVED,
            "SAFETY_COMMAND_FAILED" to EventCodes.SAFETY_COMMAND_FAILED,
            "SAFETY_READ_UNAVAILABLE" to EventCodes.SAFETY_READ_UNAVAILABLE,
            "TEARDOWN_FAILED" to EventCodes.TEARDOWN_FAILED,
            "TEARDOWN_TIMEOUT" to EventCodes.TEARDOWN_TIMEOUT,
            "EXTERNAL_REAPPLY_ENTER_PENDING" to EventCodes.EXTERNAL_REAPPLY_ENTER_PENDING,
            "EXTERNAL_REAPPLY_NOT_ADMITTED" to EventCodes.EXTERNAL_REAPPLY_NOT_ADMITTED,
            "EXTERNAL_REAPPLY_MAINTENANCE" to EventCodes.EXTERNAL_REAPPLY_MAINTENANCE,
            "EXTERNAL_REAPPLY_STATE_UNKNOWN" to EventCodes.EXTERNAL_REAPPLY_STATE_UNKNOWN,
            "EXTERNAL_REAPPLY_SPACING" to EventCodes.EXTERNAL_REAPPLY_SPACING,
            "EXTERNAL_REAPPLY_BUDGET" to EventCodes.EXTERNAL_REAPPLY_BUDGET,
            "RESTORE_SENSORS" to EventCodes.RESTORE_SENSORS,
            "UNFORCE" to EventCodes.UNFORCE,
            "RAISE_DEBT" to EventCodes.RAISE_DEBT,
            "LEDGER_CORRUPT_LINES" to EventCodes.LEDGER_CORRUPT_LINES,
            "LEDGER_CORRUPT_LINE" to EventCodes.LEDGER_CORRUPT_LINE,
            "LEDGER_CORRUPT_RECOVERED_LINES" to EventCodes.LEDGER_CORRUPT_RECOVERED_LINES,
            "CONTROL_RUN_FAILED" to EventCodes.CONTROL_RUN_FAILED,
            "RESTORE_LEDGER_SAVE_FAILED" to EventCodes.RESTORE_LEDGER_SAVE_FAILED,
        )
        assertEquals(expected.keys, EventCodes::class.java.fields.map { it.name }.filter { it != "INSTANCE" }.toSet())
        expected.forEach { (literal, value) -> assertEquals(literal, value) }
    }
}
