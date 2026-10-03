package com.akylas.enforcedoze.service

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Pin the Android service seam that cannot be instantiated by the plain JVM suite. */
class FocusedAppEnterWiringTest {
    private val source = File("src/main/java/com/akylas/enforcedoze/ForceDozeService.java").readText()

    @Test fun focusedReadPassesRealCommandResultWithoutLossyShellCallbackOrGrep() {
        val read = source.substringAfter("private FocusedApps getFocusedApps()")
            .substringBefore("public void executeCommandWithRoot")
        assertTrue("read must retain exitCode/timedOut on the actual control result",
            read.contains("CommandResult result = runtime.getControl().run(\"dumpsys window\", 8000)"))
        assertTrue(read.contains("return FocusedAppParser.parse(result)"))
        assertFalse(read.contains("executeCommandWithRoot"))
        assertFalse(read.contains("grep"))
        assertFalse(source.contains("OnGetFocusedApp"))
    }

    @Test fun enterConfigUsesFailClosedPackageSelectionWithoutRemovingOtherGroups() {
        val config = source.substringAfter("private DozeConfig config(boolean sensors, Boolean playingMusic)")
            .substringBefore("private Integer legacyNotificationTransaction()")
        assertTrue(config.contains("focused = getFocusedApps()"))
        assertTrue(config.contains("FeatureSelection.packages(dozeAppBlocklist, dozeNotificationBlocklist,"))
        assertTrue(config.contains("getPackageName(), whitelistCurrentApp, focused, runtime.getJournal()"))
        assertTrue(config.contains("features, packages.getAppsToSuspend(), packages.getPackagesToBlockNotifications()"))
    }
}
