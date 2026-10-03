package com.akylas.enforcedoze.access

import com.akylas.enforcedoze.access.ExternalControlPolicy.Action
import com.akylas.enforcedoze.access.ExternalControlPolicy.DenialReason
import com.akylas.enforcedoze.access.ExternalControlPolicy.SettingValue
import org.junit.Assert.*
import org.junit.Test

class ExternalControlPolicyTest {
    private fun setting(key: String?, value: String?) = ExternalControlPolicy.evaluate(
        Action.CHANGE_SETTING, allowPrivileged = true, settingName = key, settingValue = value,
    )

    @Test
    fun defaultsAllowOnlyBasicActionsAndGatesRemainIndependent() {
        for (action in Action.entries) {
            val default = ExternalControlPolicy.evaluate(action)
            assertEquals(action.name, !action.privileged, default.allowed)
            if (action.privileged) assertEquals(DenialReason.PRIVILEGED_CONTROL_DISABLED, default.reason)
        }
        for (action in listOf(Action.ENABLE_SERVICE, Action.DISABLE_SERVICE, Action.REAPPLY_DOZE)) {
            assertEquals(DenialReason.BASIC_CONTROL_DISABLED,
                ExternalControlPolicy.evaluate(action, allowBasic = false, allowPrivileged = true).reason)
        }
        assertTrue(ExternalControlPolicy.evaluate(Action.ADD_WHITELIST,
            allowBasic = false, allowPrivileged = true, packageName = "com.example.app").allowed)
        assertEquals(DenialReason.PRIVILEGED_CONTROL_DISABLED, ExternalControlPolicy.evaluate(
            Action.CHANGE_SETTING, settingName = "dozeEnterDelay", settingValue = "oops",
        ).reason)
    }

    @Test
    fun trustLedgerAccessAndServiceKeysAreNeverExternallyWritable() {
        for (key in listOf(
            Prefs.ALLOW_EXTERNAL_BASIC_CONTROL, Prefs.ALLOW_EXTERNAL_PRIVILEGED_CONTROL,
            Prefs.RESTORE_LEDGER, Prefs.EXECUTION_MODE, Prefs.RESTRICT_SENSORS_ALLOW_TOKEN,
            Prefs.SERVICE_ENABLED, "isSuAvailable", "isShizukuAvailable", "disableLogcat", "waitForUnlock",
        )) {
            assertEquals(key, DenialReason.PROTECTED_SETTING, setting(key, "true").reason)
            assertNull(key, ExternalControlPolicy.settingType(key))
        }
        for (key in listOf("sensorWhitelistPackage", "dozeAppBlockList", "notificationBlockList", "DUMP")) {
            assertEquals(key, DenialReason.UNKNOWN_SETTING, setting(key, "true").reason)
        }
    }

    @Test
    fun booleansAcceptOnlyExactTrueOrFalseIgnoringCase() {
        assertEquals(SettingValue.BooleanValue(true), setting("disableWhenCharging", "TrUe").value)
        assertEquals(SettingValue.BooleanValue(false), setting("turnOffWiFiInDoze", "FALSE").value)
        for (bad in listOf("", "1", "0", "yes", "null", " true", "false ", "true\n", "true; id", "$(id)")) {
            assertEquals(bad, DenialReason.INVALID_BOOLEAN, setting("disableMotionSensors", bad).reason)
        }
        assertEquals(DenialReason.MISSING_VALUE, setting("disableMotionSensors", null).reason)
    }

    @Test
    fun delayIsAnIntegerInThePreferenceXmlRange() {
        assertEquals(SettingValue.IntegerValue(0), setting("dozeEnterDelay", "0").value)
        assertEquals(SettingValue.IntegerValue(1800), setting("dozeEnterDelay", "1800").value)
        assertEquals(SettingValue.IntegerValue(31), setting("dozeEnterDelay", "31").value)
        for (bad in listOf("-1", "1801", "2147483647")) {
            assertEquals(bad, DenialReason.OUT_OF_RANGE, setting("dozeEnterDelay", bad).reason)
        }
        for (bad in listOf("2147483648", "-2147483649", "1.5", "1e3", " 30", "30 ", "30; id", "$(id)", "")) {
            assertEquals(bad, DenialReason.INVALID_INTEGER, setting("dozeEnterDelay", bad).reason)
        }
        assertEquals(DenialReason.MISSING_VALUE, setting("dozeEnterDelay", null).reason)
    }

    @Test
    fun correctedKeysAreTypedAndStaleUnknownKeysAreRejected() {
        for (key in listOf("autoRotateAndBrightnessFix", "disableMotionSensors", "turnOffGPSInDoze",
            "turnOffBluetoothInDoze", Prefs.KEEP_DOZE_ENFORCED, Prefs.SCREEN_ON_SUMMARY)) {
            assertEquals(key, ExternalControlPolicy.SettingType.BOOLEAN, ExternalControlPolicy.settingType(key))
            assertTrue(key, setting(key, "true").allowed)
        }
        for (key in listOf("useAutoRotateAndBrightnessFix", "enableSensors", "useNonRootSensorWorkaround",
            "notASetting", "dozeEnterDelay; id", "executionMode\n")) {
            assertEquals(key, DenialReason.UNKNOWN_SETTING, setting(key, "true").reason)
        }
        assertEquals(DenialReason.UNKNOWN_SETTING, setting(null, "true").reason)
        assertEquals(ExternalControlPolicy.SettingType.INTEGER, ExternalControlPolicy.settingType("dozeEnterDelay"))
    }

    @Test
    fun packageNamesAreValidatedBeforeWhitelistCommandsCanBeBuilt() {
        for (action in listOf(Action.ADD_WHITELIST, Action.REMOVE_WHITELIST)) {
            for (pkg in listOf("com.example.app", "com.example_app.Client2")) {
                assertTrue(pkg, ExternalControlPolicy.evaluate(action,
                    allowPrivileged = true, packageName = pkg).allowed)
            }
            for (pkg in listOf(null, "", "android", "com..app", "com.example.app; id", "com.app\n", "$(id)",
                "com.example.app --user 0", "com.app/Activity")) {
                assertEquals(pkg, DenialReason.INVALID_PACKAGE, ExternalControlPolicy.evaluate(action,
                    allowPrivileged = true, packageName = pkg).reason)
            }
        }
    }

    @Test
    fun whitelistSuccessRequiresStructuredReadbackNotExitCodeOrSubstring() {
        val target = "com.example.app"
        val rows = listOf("system,android,1000", "system-excidle,$target,10001", "user,com.example.app.other,10002")
        assertEquals(false, ExternalControlPolicy.whitelistMembership(rows, target))
        assertEquals(true, ExternalControlPolicy.whitelistMembership(rows + "user,$target,10001", target))
        assertEquals(true, ExternalControlPolicy.whitelistMembership(listOf("system,$target,10001"), target))
        for (bad in listOf(emptyList(), listOf("Permission Denial"), listOf("user,$target"),
            listOf("user,$target,notAnId"), rows + "OEM unknown", listOf("user,$target,-1"))) {
            assertNull(bad.toString(), ExternalControlPolicy.whitelistMembership(bad, target))
        }
    }
}
