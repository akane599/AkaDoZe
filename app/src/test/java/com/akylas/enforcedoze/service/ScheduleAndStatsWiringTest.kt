package com.akylas.enforcedoze.service

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Android permission/Handler adapters are compile-only; pin delegation to the tested pure policies. */
class ScheduleAndStatsWiringTest {
    @Test fun exactAlarmPermissionIsDeclaredWithoutPlayRestrictedAlternative() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("<uses-permission android:name=\"android.permission.SCHEDULE_EXACT_ALARM\" />"))
        assertFalse(manifest.contains("android.permission.USE_EXACT_ALARM"))
    }

    @Test fun scheduleAdapterUsesMinutePolicyAndChecksExactAlarmPermissionBeforeScheduling() {
        val utils = File("src/main/java/com/akylas/enforcedoze/Utils.java").readText()
        assertTrue(utils.contains("SchedulePolicy.isInside(getCustomDozePeriods(context), getCurrentMinuteOfDay())"))
        assertTrue(utils.contains("SchedulePolicy.nextBoundary(getCustomDozePeriods(context)"))
        val alarm = utils.substringAfter("public static void scheduleNextCustomDozePeriodBoundary(")
            .substringBefore("public static void cancelCustomDozePeriodAlarm(")
        val permission = alarm.indexOf("!alarmManager.canScheduleExactAlarms()")
        assertTrue(permission >= 0 && permission < alarm.indexOf("alarmManager.setExactAndAllowWhileIdle"))
        assertTrue(alarm.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.S"))
        assertTrue(alarm.substringAfter("!alarmManager.canScheduleExactAlarms()").substringBefore("try {")
            .contains("alarmManager.setAndAllowWhileIdle"))
        assertTrue(File("src/main/java/com/akylas/enforcedoze/CustomDozePeriodReceiver.java").readText()
            .contains("Utils.applyForceDozeSchedule(context)"))
    }

    @Test fun explicitMasterOffPersistsIntentAndCancelsBoundaryAlarm() {
        val activity = File("src/main/java/com/akylas/enforcedoze/MainActivity.java").readText()
        val toggle = activity.substringAfter("public void onCheckedChanged(")
            .substringBefore("public void showDozeTunablesActivity(")
        val off = toggle.substringAfter("editor = settings.edit();")
        assertTrue("master off must persist user intent separately from scheduled stops",
            off.contains("putBoolean(Prefs.SERVICE_USER_ENABLED, false)"))
        assertTrue("master off must cancel its pending boundary alarm",
            off.contains("Utils.cancelCustomDozePeriodAlarm(this)"))
    }

    @Test fun scheduledStopsPreserveUserIntentAndDisabledSchedulesCannotRearm() {
        val utils = File("src/main/java/com/akylas/enforcedoze/Utils.java").readText()
        val apply = utils.substringAfter("public static boolean applyForceDozeSchedule(")
            .substringBefore("public static void scheduleNextCustomDozePeriodBoundary(")
        assertTrue(apply.contains("Prefs.SERVICE_USER_ENABLED,"))
        assertTrue(apply.contains("Prefs.DEFAULT_SERVICE_USER_ENABLED"))
        assertTrue("boundary decisions must use the tested user-intent policy",
            apply.contains("SchedulePolicy.shouldRunService(userEnabled,"))
        assertTrue(apply.contains("if (shouldRunService)"))
        assertTrue(apply.contains("updateSettingBool(context, \"serviceEnabled\", false)"))
        assertFalse("scheduled stops cannot change user intent", apply.contains("putBoolean("))
        val alarm = utils.substringAfter("public static void scheduleNextCustomDozePeriodBoundary(")
            .substringBefore("public static void cancelCustomDozePeriodAlarm(")
        val intent = alarm.indexOf("Prefs.SERVICE_USER_ENABLED,")
        assertTrue("boot/settings rearming must honor explicit off before creating an alarm",
            intent >= 0 && intent < alarm.indexOf("getMillisUntilNextCustomDozePeriodBoundary(context)"))
        assertTrue(alarm.substringBefore("long delay").contains("return;"))
        assertTrue(alarm.indexOf("cancelCustomDozePeriodAlarm(context)") in 0 until intent)
    }

    private fun assertExplicitOnStartsImmediately(on: String, context: String, enabledKey: String) {
        val start = on.indexOf("Utils.startForceDozeService($context)")
        assertTrue("explicit on must start directly even outside a custom period", start >= 0)
        assertFalse("explicit on must not apply period membership", on.contains("applyForceDozeSchedule("))
        assertFalse("explicit on must not gate the start by period membership", on.contains("isInsideCustomDozePeriod("))
        val enabled = on.indexOf("putBoolean($enabledKey, true)")
        val intent = on.indexOf("putBoolean(Prefs.SERVICE_USER_ENABLED, true)")
        val boundary = on.indexOf("Utils.scheduleNextCustomDozePeriodBoundary($context)")
        assertTrue("effective enabled state must be persisted after a successful direct start", start < enabled)
        assertTrue("explicit on must persist user intent after a successful direct start", start < intent)
        assertTrue("explicit on must arm the next boundary after persisting enabled state and intent",
            boundary > enabled && boundary > intent)
        assertFalse("explicit on cannot stop the service outside a custom period", on.contains("stopForceDozeService("))
    }

    @Test fun explicitMasterOnStartsImmediatelyAndResumesAtTheNextBoundary() {
        val on = File("src/main/java/com/akylas/enforcedoze/MainActivity.java").readText()
            .substringAfter("public void onCheckedChanged(").substringBefore("editor = settings.edit();")
        assertExplicitOnStartsImmediately(on, "this", "\"serviceEnabled\"")
        val failure = on.substringAfter("if (!Utils.startForceDozeService(this)) {").substringBefore("}")
        assertTrue("master start denial must restore the toggle and return", failure.contains("updateToggleState();") && failure.contains("return;"))
        assertTrue("master success retains the effective enabled state", on.contains("serviceEnabled = true;"))
        assertTrue("master success retains status rendering", on.contains("renderServiceStatus();"))
    }

    @Test fun explicitTileOnStartsImmediatelyAndResumesAtTheNextBoundary() {
        val tile = File("src/main/java/com/akylas/enforcedoze/ForceDozeTileService.java").readText()
            .substringAfter("public void onClick()").substringBefore("public void sendBroadcastToApp(")
        val on = tile.substringAfter("} else {")
        assertExplicitOnStartsImmediately(on, "this", "\"serviceEnabled\"")
        assertTrue("tile enabled state and update stay guarded by a successful start",
            on.contains("if (Utils.startForceDozeService(this)) {"))
        assertTrue(on.indexOf("Utils.updateTileState(this)") > on.indexOf("putBoolean(\"serviceEnabled\", true)"))
        val off = tile.substringBefore("} else {")
        assertTrue(off.contains("putBoolean(Prefs.SERVICE_USER_ENABLED, false)"))
        assertTrue(off.contains("Utils.cancelCustomDozePeriodAlarm(this)"))
    }

    @Test fun explicitExternalOnStartsImmediatelyAndResumesAtTheNextBoundary() {
        val on = File("src/main/java/com/akylas/enforcedoze/ExternalControlReceiver.java").readText()
            .substringAfter("case ENABLE_SERVICE:").substringBefore("case DISABLE_SERVICE:")
        assertExplicitOnStartsImmediately(on, "app", "Prefs.SERVICE_ENABLED")
    }

    @Test fun externalServiceControlsPersistUserIntentWithoutBypassingAdmissionOrWriteFailures() {
        val receiver = File("src/main/java/com/akylas/enforcedoze/ExternalControlReceiver.java").readText()
        val on = receiver.substringAfter("case ENABLE_SERVICE:").substringBefore("case DISABLE_SERVICE:")
        val off = receiver.substringAfter("case DISABLE_SERVICE:").substringBefore("case REAPPLY_DOZE:")
        for ((block, enabled) in listOf(on to true, off to false)) {
            val intent = block.indexOf("putBoolean(Prefs.SERVICE_USER_ENABLED, $enabled).commit()")
            assertTrue("both controls must be admitted before writing intent",
                intent >= 0 && block.indexOf("if (!admitted())") in 0 until intent)
            assertTrue("preference failures remain reported", block.contains("ExecutionReason.PREFERENCE_WRITE_FAILED"))
        }
        assertTrue(off.contains("Utils.cancelCustomDozePeriodAlarm(app)"))
        assertTrue(off.indexOf(".commit()") < off.indexOf("Utils.stopForceDozeService(app)"))
    }

    @Test fun serviceKeepsRawChargingBatteryCapsStatsAndRequiresPairedExit() {
        val service = File("src/main/java/com/akylas/enforcedoze/ForceDozeService.java").readText()
        assertTrue(service.contains("lastDozeEnterBatteryLife = Utils.getBatteryLevel(this)"))
        assertTrue(service.contains("lastDozeExitBatteryLife = Utils.getBatteryLevel(this)"))
        assertTrue(service.contains("if (runtime.getSession().recordExit()) {"))
        assertTrue(service.contains("LegacyDozeStats.newest(dozeUsageData)"))
        val activity = File("src/main/java/com/akylas/enforcedoze/DozeBatteryStatsActivity.java").readText()
        assertTrue(activity.contains("LegacyDozeStats.intervals(dozeUsageStats)"))
        assertFalse("opening stats must not trim the persistent set", activity.contains("putStringSet(\"dozeUsageDataAdvanced\""))
    }
}
