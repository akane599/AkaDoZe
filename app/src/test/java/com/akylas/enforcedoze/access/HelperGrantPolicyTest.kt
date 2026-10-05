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
    fun readLogsCannotBeGrantedByAnyTriggerOnAnySupportedApi() {
        for (api in 23..36) for (trigger in HelperGrantPolicy.Trigger.entries) {
            val commands = HelperGrantPolicy.commands(helpers(api), emptySet(), trigger)
            assertFalse(commands.containsKey("READ_LOGS"))
            assertTrue(commands.values.none { "READ_LOGS" in it })
        }
    }
}
