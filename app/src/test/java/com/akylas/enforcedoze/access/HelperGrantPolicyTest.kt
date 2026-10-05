package com.akylas.enforcedoze.access

import org.junit.Assert.*
import org.junit.Test

class HelperGrantPolicyTest {
    private fun helpers(api: Int = 36) = GrantCommands.forApp(api, "com.example.app", "com.example.app.NotificationService")

    @Test
    fun automaticFirstRunThenRevocationThenReopenDoesNotRegrant() {
        val commands = helpers()
        val first = HelperGrantPolicy.commands(commands, emptySet(), HelperGrantPolicy.Trigger.AUTOMATIC)
        assertEquals(commands, first)
        // The first grant record survives activity/process recreation. System revocation changes
        // effective access, not this record; reopening must not change exact-alarm consent again.
        val persisted = first.keys.toSet()
        assertTrue("SCHEDULE_EXACT_ALARM" in persisted)
        assertTrue(HelperGrantPolicy.commands(helpers(), persisted, HelperGrantPolicy.Trigger.AUTOMATIC).isEmpty())
        assertEquals(commands, HelperGrantPolicy.commands(commands, persisted, HelperGrantPolicy.Trigger.EXPLICIT))
    }

    @Test
    fun deniedPhoneAndOptimizedBatteryStayRevokedAfterStickyServiceRestart() {
        val firstProcess = HelperGrantPolicy.commands(helpers(), emptySet(), HelperGrantPolicy.Trigger.AUTOMATIC)
        val diskRecord = firstProcess.keys.toSet()
        // Android kills the uid after the user denies Phone. A fresh process reloads the record,
        // independently of the denied runtime grant and the battery optimization setting.
        val restartedProcess = HelperGrantPolicy.commands(helpers(), diskRecord.toSet(), HelperGrantPolicy.Trigger.AUTOMATIC)
        assertFalse(restartedProcess.containsKey("READ_PHONE_STATE"))
        assertFalse(restartedProcess.containsKey("SELF_WHITELIST"))
        assertTrue(restartedProcess.isEmpty())
    }

    @Test
    fun automaticOnlyAppliesUnrecordedKeysAndPreservesOrder() {
        val commands = helpers()
        val applied = setOf("DUMP", "SCHEDULE_EXACT_ALARM", "NOTIFICATION_LISTENER", "READ_LOGS")
        val selected = HelperGrantPolicy.commands(commands, applied, HelperGrantPolicy.Trigger.AUTOMATIC)
        assertEquals(listOf("WRITE_SECURE_SETTINGS", "READ_PHONE_STATE", "GET_USAGE_STATS", "SELF_WHITELIST"), selected.keys.toList())
        assertEquals(commands.filterKeys { it !in applied }, selected)
        assertEquals(7, commands.size)
        assertEquals(4, applied.size)
    }

    @Test
    fun explicitGrantsAllHelpersRegardlessOfRecordedFailures() {
        val commands = helpers()
        for (record in listOf(emptySet(), setOf("SCHEDULE_EXACT_ALARM"), commands.keys)) {
            assertEquals(commands, HelperGrantPolicy.commands(commands, record, HelperGrantPolicy.Trigger.EXPLICIT))
        }
    }

    @Test
    fun resetClearsRecordAndApiUpgradeOnlyAddsNewHelper() {
        val oldRecord = helpers(23).keys
        val upgraded = HelperGrantPolicy.commands(helpers(), oldRecord, HelperGrantPolicy.Trigger.AUTOMATIC)
        assertEquals(mapOf("SCHEDULE_EXACT_ALARM" to "appops set com.example.app SCHEDULE_EXACT_ALARM allow"), upgraded)
        assertEquals(helpers(), HelperGrantPolicy.commands(helpers(), emptySet(), HelperGrantPolicy.Trigger.AUTOMATIC))
    }

    @Test
    fun failedPersistenceRunsOnlyDurablyRecordedPrefixWithoutThrowing() {
        val commands = helpers()
        for (failedCommit in 0 until commands.size) {
            var diskRecord = emptySet<String>()
            val executed = mutableListOf<String>()
            var commits = 0
            val results = HelperGrantPolicy.runAttempts(commands, emptySet(), persist = { record ->
                if (commits++ == failedCommit) false else { diskRecord = record; true }
            }, execute = { command ->
                val item = commands.entries.single { it.value == command }.key
                assertTrue("every executed helper is durably recorded first", item in diskRecord)
                executed.add(item)
                CommandResult(0, emptyList(), emptyList(), 0, false)
            })
            val expected = commands.keys.take(failedCommit)
            assertEquals("failed and later commands must not run", expected, executed)
            assertEquals("return earlier results without a crash", expected, results.keys.toList())
            assertEquals(expected.toSet(), diskRecord)
            assertEquals("stop at the first failed commit", failedCommit + 1, commits)
        }
    }

    @Test
    fun successfulPersistenceRecordsTransportFailureAndPreventsAutomaticRetryAfterRestart() {
        val commands = helpers()
        var diskRecord = emptySet<String>()
        val results = HelperGrantPolicy.runAttempts(commands, emptySet(), persist = { record ->
            diskRecord = record
            true
        }, execute = { CommandResult(1, emptyList(), emptyList(), 0, false) })
        assertEquals(commands.keys, results.keys)
        assertTrue(results.values.none { it.ok })
        assertTrue(HelperGrantPolicy.commands(helpers(), diskRecord.toSet(), HelperGrantPolicy.Trigger.AUTOMATIC).isEmpty())
        assertEquals(commands, HelperGrantPolicy.commands(helpers(), diskRecord, HelperGrantPolicy.Trigger.EXPLICIT))
    }

    @Test
    fun explicitPhoneOnlyRetriesPhoneWithoutRevivingRevokedAlarmOrWhitelist() {
        val commands = helpers().filterKeys { it == "READ_PHONE_STATE" }
        val original = helpers().keys.toSet()
        val selected = HelperGrantPolicy.commands(commands, original, HelperGrantPolicy.Trigger.EXPLICIT)
        var diskRecord = original
        val executed = mutableListOf<String>()
        val results = HelperGrantPolicy.runAttempts(selected, original, persist = { record ->
            diskRecord = record
            true
        }, execute = { command ->
            executed.add(command)
            CommandResult(0, emptyList(), emptyList(), 0, false)
        })
        assertEquals(listOf("READ_PHONE_STATE"), results.keys.toList())
        assertEquals(listOf("pm grant com.example.app android.permission.READ_PHONE_STATE"), executed)
        assertEquals("unrelated records survive the one-helper action", original, diskRecord)
    }

    @Test
    fun readLogsCannotBeGrantedByAnyTriggerOnAnySupportedApi() {
        for (api in 23..36) for (trigger in HelperGrantPolicy.Trigger.entries) {
            val commands = HelperGrantPolicy.commands(helpers(api), emptySet(), trigger)
            assertFalse(commands.containsKey("READ_LOGS"))
            assertTrue(commands.values.none { "READ_LOGS" in it })
        }
    }
}
