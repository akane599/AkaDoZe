package com.akylas.enforcedoze.service

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Android permission/Handler adapters are compile-only; pin delegation to the tested pure policies. */
class ScheduleAndStatsWiringTest {
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
