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

    @Test fun grantReceiverIsPrivateAndOnlyRequeriesActualAccess() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val declaration = manifest.substringAfter("android:name=\"com.akylas.enforcedoze.ExactAlarmPermissionReceiver\"", "")
            .substringBefore("</receiver>")
        assertTrue("system grant receiver must not be exported", declaration.contains("android:exported=\"false\""))
        assertTrue(declaration.contains("android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"))
        val file = File("src/main/java/com/akylas/enforcedoze/ExactAlarmPermissionReceiver.java")
        assertTrue("grant receiver must exist", file.isFile)
        val receiver = file.readText()
        val actionGuard = receiver.indexOf("AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED.equals(action)")
        val requery = receiver.indexOf("Utils.requeryExactAlarmAccess(context)")
        assertTrue("ignore unrelated actions before any requery", actionGuard >= 0 && requery > actionGuard)
        assertTrue(receiver.substring(actionGuard, requery).contains("return;"))
        assertFalse("broadcast extras are not permission authority", receiver.contains("getExtras(") || receiver.contains("Extra("))
        assertNoServiceOrPreferenceMutation(receiver)
    }

    @Test fun processRestartAfterExactAlarmRevocationRequeriesOnceWithoutConstructingRuntime() {
        val application = File("src/main/java/com/akylas/enforcedoze/MyApplication.java").readText()
        val startup = application.substringAfter("public void onCreate() {", "")
            .substringBefore("public static Context getAppContext()")
        val requery = "Utils.requeryExactAlarmAccess(MyApplication.context);"
        assertTrue("a listener rebind after revoke must re-arm without opening Main", startup.contains(requery))
        assertEquals("startup must not arm the same boundary twice", 1, startup.windowed(requery.length).count { it == requery })
        assertTrue("application context must be ready before requery",
            startup.indexOf("MyApplication.context = getApplicationContext();") in 0 until startup.indexOf(requery))
        for (forbidden in listOf("getDozeRuntime(", "new DozeRuntime(", "AccessManager", "getJournal(",
            "scheduleNextCustomDozePeriodBoundary(", "isInsideCustomDozePeriod(", "Shizuku", "Shell.")) {
            assertFalse("process-start rearm must not call $forbidden", startup.contains(forbidden))
        }
        assertNoServiceOrPreferenceMutation(startup)
    }

    @Test fun lockedProcessStartSkipsRequeryBeforeCredentialProtectedPreferencesAreRead() {
        val startup = File("src/main/java/com/akylas/enforcedoze/MyApplication.java").readText()
            .substringAfter("public void onCreate() {", "").substringBefore("public static Context getAppContext()")
        val locked = startup.indexOf("Build.VERSION.SDK_INT >= Build.VERSION_CODES.N")
        val unlock = startup.indexOf("isUserUnlocked()")
        val requery = startup.indexOf("Utils.requeryExactAlarmAccess(")
        assertTrue("API 23 must not call the API 24 unlock check", locked >= 0 && unlock > locked)
        assertTrue("locked credential storage must be checked before requery", requery > unlock)
        assertTrue(startup.substring(locked, requery).contains("return;"))
        assertTrue("skip when locked, not when unlocked", startup.contains("!((UserManager) getSystemService(Context.USER_SERVICE)).isUserUnlocked()"))
    }

    @Test fun requeryReadsCapabilityAndPersistedIntentWithoutApplyingCurrentWindow() {
        val utils = File("src/main/java/com/akylas/enforcedoze/Utils.java").readText()
        val requery = utils.substringAfter("public static ExactAlarmAccessPolicy.Access requeryExactAlarmAccess(", "")
            .substringBefore("public static void scheduleNextCustomDozePeriodBoundary(")
        assertTrue("requery must guard the API 31-only capability read",
            requery.contains("Build.VERSION.SDK_INT < Build.VERSION_CODES.S") && requery.contains("alarmManager.canScheduleExactAlarms()"))
        assertTrue(requery.contains("ExactAlarmAccessPolicy.requery("))
        assertTrue(requery.contains("Prefs.SERVICE_USER_ENABLED,"))
        assertTrue(requery.contains("Prefs.DEFAULT_SERVICE_USER_ENABLED"))
        assertTrue(requery.contains("hasCustomDozePeriods(context)"))
        val guard = requery.indexOf("if (access.getShouldRearm())")
        assertTrue("rearm only when the pure policy admits persisted intent",
            guard >= 0 && requery.indexOf("scheduleNextCustomDozePeriodBoundary(context)") > guard)
        assertTrue("foreground return must be able to use the refreshed access snapshot", requery.contains("return access;"))
        assertFalse("grant must not catch up to the current period", requery.contains("isInsideCustomDozePeriod("))
        assertNoServiceOrPreferenceMutation(requery)
    }

    @Test fun duplicateGrantReplacesOneImmutableExplicitBoundaryAndRetainsRevocationFallback() {
        val utils = File("src/main/java/com/akylas/enforcedoze/Utils.java").readText()
        val schedule = utils.substringAfter("public static void scheduleNextCustomDozePeriodBoundary(")
            .substringBefore("public static void cancelCustomDozePeriodAlarm(")
        assertTrue("replace, do not accumulate, the boundary alarm",
            schedule.indexOf("cancelCustomDozePeriodAlarm(context)") in 0 until schedule.indexOf("getCustomDozePeriodPendingIntent(context)"))
        val target = utils.substringAfter("private static PendingIntent getCustomDozePeriodPendingIntent(")
            .substringBefore("private static Set<String> getCustomDozePeriods(")
        assertTrue(target.contains("new Intent(context, CustomDozePeriodReceiver.class)"))
        assertTrue(target.contains("intent.setAction(ACTION_CUSTOM_DOZE_PERIOD_BOUNDARY)"))
        assertTrue(target.contains("PendingIntent.getBroadcast(context, CUSTOM_DOZE_PERIOD_REQUEST_CODE, intent,"))
        assertTrue(target.contains("PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE"))
        assertTrue(utils.contains("CUSTOM_DOZE_PERIOD_REQUEST_CODE = 9012"))
        val cancel = utils.substringAfter("public static void cancelCustomDozePeriodAlarm(")
            .substringBefore("public static boolean hasCustomDozePeriods(")
        assertTrue(cancel.contains("alarmManager.cancel(getCustomDozePeriodPendingIntent(context))"))
        val fallback = schedule.substringAfter("catch (SecurityException e)", "")
        assertTrue("grant-then-revoke race must retain the inexact fallback", fallback.contains("alarmManager.setAndAllowWhileIdle"))
        assertNoServiceOrPreferenceMutation(schedule)
    }

    @Test fun bootRearmAndBoundaryForegroundStartDenialOutcomesRemainIntact() {
        assertTrue(File("src/main/java/com/akylas/enforcedoze/BootCompleteReceiver.java").readText()
            .contains("Utils.scheduleNextCustomDozePeriodBoundary(context)"))
        val start = File("src/main/java/com/akylas/enforcedoze/Utils.java").readText()
            .substringAfter("public static boolean startForceDozeService(")
            .substringBefore("public static void stopForceDozeService(")
        val failure = start.substringAfter("catch (IllegalStateException e)").substringBefore("// Hide disabled notification")
        assertTrue(start.contains("ContextCompat.startForegroundService(context, intent)"))
        assertTrue(failure.contains("EventType.ERROR, \"FOREGROUND_START_DENIED\""))
        assertTrue(failure.contains("return false;"))
    }

    @Test fun foregroundReturnRequeriesThroughTheSharedSeamWithoutRestartingOrPrompting() {
        val activity = File("src/main/java/com/akylas/enforcedoze/MainActivity.java").readText()
        val resume = activity.substringAfter("protected void onResume()", "").substringBefore("protected void onPause()")
        assertTrue("foreground return must requery actual access and re-arm via 2A's seam",
            resume.contains("Utils.requeryExactAlarmAccess(this);"))
        val settings = File("src/main/java/com/akylas/enforcedoze/SettingsActivity.java").readText()
        val settingsResume = settings.substringAfter("public void onResume()", "").substringBefore("public void onPause()")
        assertTrue(settingsResume.contains("Utils.requeryExactAlarmAccess(requireContext())"))
        for (source in listOf(resume, settingsResume)) {
            // Master-off and no-period gating stays in the seam's pure policy; resume must not bypass it.
            for (forbidden in listOf("scheduleNextCustomDozePeriodBoundary(", "applyForceDozeSchedule(",
                "startForceDozeService(", "ACTION_REQUEST_SCHEDULE_EXACT_ALARM", "requestExactAlarmAccess(")) {
                assertFalse("foreground return cannot call $forbidden", source.contains(forbidden))
            }
        }
    }

    private fun assertNoServiceOrPreferenceMutation(source: String) {
        for (forbidden in listOf("applyForceDozeSchedule(", "startForceDozeService(", "startForegroundService(",
            "startService(", "stopForceDozeService(", "updateSettingBool(", "putBoolean(", ".edit()")) {
            assertFalse("permission rearm cannot call $forbidden", source.contains(forbidden))
        }
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
            .substringAfter("private void enableService(").substringBefore("private void disableService(")
        assertExplicitOnStartsImmediately(on, "app", "Prefs.SERVICE_ENABLED")
    }

    @Test fun externalServiceControlsPersistUserIntentWithoutBypassingAdmissionOrWriteFailures() {
        val receiver = File("src/main/java/com/akylas/enforcedoze/ExternalControlReceiver.java").readText()
        val on = receiver.substringAfter("private void enableService(").substringBefore("private void disableService(")
        val off = receiver.substringAfter("private void disableService(").substringBefore("private void reapplyDoze(")
        val admission = receiver.substringAfter("private void basicControl(").substringBefore("private void enableService(")
        assertTrue("basic admission precedes all control dispatch", admission.indexOf("if (!admitted())") in 0 until admission.indexOf("switch (action)"))
        for ((block, enabled) in listOf(on to true, off to false)) {
            val intent = block.indexOf("putBoolean(Prefs.SERVICE_USER_ENABLED, $enabled).commit()")
            assertTrue("both controls must be admitted before writing intent",
                intent >= 0 && admission.contains(if (enabled) "enableService();" else "disableService();"))
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
