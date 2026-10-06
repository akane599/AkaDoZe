package com.akylas.enforcedoze.access

import org.junit.Assert.*
import org.junit.Test

class CommandCatalogTest {
    private val pkg = "com.example.app"

    @Test
    fun apiVariantsAndReadbacksMatchDocumentedCommands() {
        val variants = listOf(
            Triple(Feature.FORCE_DOZE, 23, "dumpsys deviceidle force-idle"),
            Triple(Feature.FORCE_DOZE, 24, "cmd deviceidle force-idle deep"),
            Triple(Feature.WIFI, 29, "svc wifi disable"),
            Triple(Feature.WIFI, 30, "cmd wifi set-wifi-enabled disabled"),
            Triple(Feature.BATTERY_SAVER, 28, "settings put global low_power 1"),
            Triple(Feature.BATTERY_SAVER, 29, "cmd power set-mode 1"),
            Triple(Feature.BLUETOOTH, 32, "svc bluetooth disable"),
            Triple(Feature.BLUETOOTH, 33, "cmd bluetooth_manager disable"),
            Triple(Feature.LOCATION, 29, "settings put secure location_mode 0"),
            Triple(Feature.LOCATION, 30, "cmd location set-location-enabled false"),
            Triple(Feature.AIRPLANE, 30, "cmd connectivity airplane-mode enable"),
            Triple(Feature.MOBILE_DATA, 36, "svc data disable"),
            Triple(Feature.BIOMETRICS, 36, "settings put secure biometric_keyguard_enabled 0"),
            Triple(Feature.MOTION_SENSORS, 36, "dumpsys sensorservice restrict $pkg"),
            Triple(Feature.APP_SUSPEND, 24, "pm suspend $pkg"),
            Triple(Feature.WHITELIST_EDIT, 23, "dumpsys deviceidle whitelist +$pkg"),
            Triple(Feature.WHITELIST_EDIT, 24, "cmd deviceidle whitelist +$pkg"),
        )
        for ((feature, api, command) in variants) assertEquals(listOf(command), CommandCatalog.apply(feature, api, pkg))
        assertNull(CommandCatalog.apply(Feature.AIRPLANE, 29))
        assertNull(CommandCatalog.apply(Feature.APP_SUSPEND, 23, pkg))
        assertNull(CommandCatalog.apply(Feature.NOTIFICATION_BLOCK, 32, pkg))
        assertEquals("cmd deviceidle get deep", CommandCatalog.readback(Feature.FORCE_DOZE, 36))
        assertEquals("dumpsys deviceidle", CommandCatalog.readback(Feature.FORCE_DOZE, 23))
        assertEquals("dumpsys deviceidle", CommandCatalog.readback(Feature.DOZE_STATE_READ, 36))
        assertEquals("dumpsys sensorservice", CommandCatalog.readback(Feature.MOTION_SENSORS, 36))
        for ((feature, command) in mapOf(
            Feature.WIFI to "settings get global wifi_on",
            Feature.MOBILE_DATA to "settings get global mobile_data",
            Feature.BLUETOOTH to "settings get global bluetooth_on",
            Feature.BATTERY_SAVER to "settings get global low_power",
            Feature.AIRPLANE to "cmd connectivity airplane-mode",
            Feature.LOCATION to "cmd location is-location-enabled",
            Feature.APP_SUSPEND to "dumpsys package $pkg",
            Feature.NOTIFICATION_BLOCK to "dumpsys package $pkg",
            Feature.WHITELIST_EDIT to "dumpsys deviceidle whitelist",
        )) assertEquals(command, CommandCatalog.readback(feature, 36, pkg))
        assertEquals("settings get secure location_mode", CommandCatalog.readback(Feature.LOCATION, 29))
        assertEquals("dumpsys deviceidle", CommandCatalog.originalValueRead(Feature.FORCE_DOZE, 36))
        assertEquals("settings get global device_idle_constants", CommandCatalog.originalValueRead(Feature.TUNABLES, 36))
        assertEquals("cmd device_config get device_idle inactive_to", CommandCatalog.originalDeviceConfigTunable(36, "inactive_to"))
        rejects { CommandCatalog.originalDeviceConfigTunable(36, "inactive_to;reboot") }
    }

    @Test
    fun restoreUsesCapturedValuesIncludingLocationModesAndNotificationFlags() {
        assertEquals(listOf("cmd wifi set-wifi-enabled disabled"), CommandCatalog.restore(Feature.WIFI, 36, "0"))
        assertEquals(listOf("cmd wifi set-wifi-enabled enabled"), CommandCatalog.restore(Feature.WIFI, 36, "1"))
        assertEquals(listOf("dumpsys deviceidle step"), CommandCatalog.restore(Feature.FORCE_DOZE, 23, "false"))
        assertEquals(listOf("cmd deviceidle unforce"), CommandCatalog.restore(Feature.FORCE_DOZE, 24, "false"))
        assertEquals(listOf("dumpsys sensorservice enable"), CommandCatalog.restore(Feature.MOTION_SENSORS, 36, "NORMAL"))
        assertNull(CommandCatalog.restore(Feature.MOTION_SENSORS, 36, "OTHER"))
        assertEquals(listOf("dumpsys sensorservice restrict $pkg"),
            CommandCatalog.restore(Feature.MOTION_SENSORS, 36, "RESTRICTED:$pkg"))
        assertEquals(listOf("settings put secure location_mode 2"), CommandCatalog.restore(Feature.LOCATION, 29, "2"))
        assertEquals(listOf("pm unsuspend $pkg"), CommandCatalog.restore(Feature.APP_SUSPEND, 24, "false", pkg))
        assertEquals(listOf("cmd deviceidle whitelist -$pkg"), CommandCatalog.restore(Feature.WHITELIST_EDIT, 36, "false", pkg))
        assertEquals(listOf(
            "pm revoke $pkg android.permission.POST_NOTIFICATIONS",
            "pm set-permission-flags $pkg android.permission.POST_NOTIFICATIONS user-set user-fixed",
        ), CommandCatalog.apply(Feature.NOTIFICATION_BLOCK, 33, pkg))
        assertEquals(listOf(
            "pm grant $pkg android.permission.POST_NOTIFICATIONS",
            "pm clear-permission-flags $pkg android.permission.POST_NOTIFICATIONS user-set user-fixed",
        ), CommandCatalog.setEnabled(Feature.NOTIFICATION_BLOCK, 33, false, pkg))
        assertEquals(listOf(
            "pm revoke $pkg android.permission.POST_NOTIFICATIONS",
            "pm clear-permission-flags $pkg android.permission.POST_NOTIFICATIONS user-set",
            "pm set-permission-flags $pkg android.permission.POST_NOTIFICATIONS user-fixed",
        ), CommandCatalog.restoreNotification(33, pkg, false, false, true))
        assertNull(CommandCatalog.restore(Feature.NOTIFICATION_BLOCK, 33, "true", pkg))
        assertEquals(listOf("settings delete global device_idle_constants"), CommandCatalog.restore(Feature.TUNABLES, 36, "null"))
        assertEquals(listOf("settings put global device_idle_constants inactive_to=1000"),
            CommandCatalog.apply(Feature.TUNABLES, 36, value = "inactive_to=1000"))
        rejects { CommandCatalog.restore(Feature.WIFI, 36, "UNKNOWN") }
        rejects { CommandCatalog.restore(Feature.LOCATION, 29, "2;reboot") }
        rejects { CommandCatalog.apply(Feature.TUNABLES, 36, value = "inactive_to=1;reboot") }
    }

    @Test
    fun api23SafetyUnforceUsesTheSameStepAsRestore() {
        assertEquals(listOf("dumpsys deviceidle step"), CommandCatalog.setEnabled(Feature.FORCE_DOZE, 23, false))
    }

    @Test
    fun injectionNeverReachesAnyCatalogOrHelperOutput() {
        for (bad in listOf("a.b;reboot", "\$(x)", "a.b x", "a.b\nreboot", "a.b|id", "a.日本", "a.b'", "a.b/evil")) {
            for (feature in Feature.entries) for (api in listOf(23, 24, 29, 30, 33, 36)) {
                rejects { CommandCatalog.apply(feature, api, bad) }
                rejects { CommandCatalog.setEnabled(feature, api, false, bad) }
                rejects { CommandCatalog.restore(feature, api, "false", bad) }
                rejects { CommandCatalog.readback(feature, api, bad) }
                rejects { CommandCatalog.originalValueRead(feature, api, bad) }
            }
            rejects { CommandCatalog.restoreNotification(36, bad, true, true, true) }
            rejects { GrantCommands.forApp(36, bad, "$pkg.NotificationService") }
            rejects { GrantCommands.forApp(36, pkg, bad) }
            rejects { CommandCatalog.restore(Feature.MOTION_SENSORS, 36, "RESTRICTED:$bad") }
        }
        for (feature in Feature.entries) for (api in listOf(23, 24, 29, 30, 33, 36)) {
            val outputs = (CommandCatalog.apply(feature, api, pkg) ?: emptyList()) +
                (CommandCatalog.setEnabled(feature, api, false, pkg) ?: emptyList()) +
                listOfNotNull(CommandCatalog.readback(feature, api, pkg), CommandCatalog.originalValueRead(feature, api, pkg))
            for (output in outputs) assertFalse(output, Regex("[;|&`$<>\\n\\r]").containsMatchIn(output))
        }
    }

    @Test
    fun helperGrantsUseCorrectPermissionsAndApiSpecificWhitelist() {
        val helpers = GrantCommands.forApp(36, pkg, "$pkg.NotificationService")
        assertEquals(7, helpers.size)
        assertEquals("pm grant $pkg android.permission.READ_PHONE_STATE", helpers["READ_PHONE_STATE"])
        assertFalse(helpers.containsKey("READ_LOGS"))
        assertTrue(helpers.values.none { "READ_LOGS" in it })
        assertEquals("appops set $pkg SCHEDULE_EXACT_ALARM allow", helpers["SCHEDULE_EXACT_ALARM"])
        assertEquals("appops set $pkg GET_USAGE_STATS allow", helpers["GET_USAGE_STATS"])
        assertEquals("cmd notification allow_listener $pkg/$pkg.NotificationService", helpers["NOTIFICATION_LISTENER"])
        assertEquals("cmd deviceidle whitelist +$pkg", helpers["SELF_WHITELIST"])
        val legacy = GrantCommands.forApp(23, pkg, "$pkg.NotificationService")
        assertFalse(legacy.containsKey("SCHEDULE_EXACT_ALARM"))
        assertEquals("dumpsys deviceidle whitelist +$pkg", legacy["SELF_WHITELIST"])
    }

    @Test
    fun legacyCommandsRequireResolvedMetadataAndPreserveEnabledStates() {
        assertEquals(listOf("service call notification 7 s16 $pkg i32 10123 i32 0"),
            CommandCatalog.legacyNotification(32, pkg, 10123, 7, false))
        assertNull(CommandCatalog.legacyNotification(32, pkg, 10123, 0, false))
        assertNull(CommandCatalog.legacyNotification(32, pkg, 110123, 7, false))
        assertNull(CommandCatalog.legacyNotification(33, pkg, 10123, 7, false))
        rejects { CommandCatalog.legacyNotification(32, "a.b;reboot", 10123, 7, false) }
        assertEquals(listOf("pm default-state $pkg"), CommandCatalog.restore(Feature.PM_DISABLE, 23, "0", pkg))
        assertEquals(listOf("pm disable-user $pkg"), CommandCatalog.restore(Feature.PM_DISABLE, 23, "3", pkg))
        assertNull(CommandCatalog.restore(Feature.PM_DISABLE, 23, "99", pkg))
        for ((api, transaction) in listOf(30 to 4, 31 to 8, 33 to 9)) {
            assertEquals(listOf("service call sensor_privacy $transaction i32 1"), CommandCatalog.apply(Feature.SENSOR_PRIVACY_ALL, api))
            assertEquals(listOf("service call sensor_privacy $transaction i32 0"), CommandCatalog.restore(Feature.SENSOR_PRIVACY_ALL, api, "0"))
        }
    }

    private fun rejects(block: () -> Any?) {
        try {
            block()
            fail("Invalid input accepted")
        } catch (_: IllegalArgumentException) {
            // Expected validation at the interpolation boundary.
        }
    }
}
