package com.akylas.enforcedoze.service

import com.akylas.enforcedoze.access.*
import com.akylas.enforcedoze.doze.*
import com.akylas.enforcedoze.doze.parse.FocusedAppParser
import com.akylas.enforcedoze.doze.parse.FocusedApps
import org.junit.Assert.*
import org.junit.Test

class FocusedAppSelectionTest {
    private val own = "com.akylas.enforcedoze"
    private val foreground = "com.example.reader"
    private val background = "com.example.background"
    private val notificationOnly = "com.example.notifications"
    private val apps = setOf(own, foreground, background)
    private val notifications = setOf(own, foreground, background, notificationOnly)

    @Test fun unknownFocusSkipsBothPackageGroupsWithTypedJournalReasonButKeepsOtherGroups() {
        val events = mutableListOf<DozeEvent>()
        val selected = FeatureSelection.packages(apps, notifications, own, true,
            FocusedApps.Unknown(Reason.UNVERIFIED), DozeEventSink { events += it })
        assertTrue("unknown focus must not suspend any package", selected.appsToSuspend.isEmpty())
        assertTrue("unknown focus must not block any notification", selected.packagesToBlockNotifications.isEmpty())
        assertEquals(setOf(Feature.APP_SUSPEND, Feature.NOTIFICATION_BLOCK), events.map { it.feature }.toSet())
        assertTrue(events.all { it.type == EventType.SKIPPED && it.reason == Reason.UNVERIFIED })
        assertEquals(setOf(Feature.WIFI, Feature.SENSOR_PRIVACY_ALL), FeatureSelection.features(
            setOf(Prefs.TURN_OFF_WIFI, Prefs.TURN_OFF_ALL_SENSORS), false, false, false, false))
    }

    @Test fun unknownReadReachesEnterGroupsWithoutPackageCommandsWhileWifiStillApplies() {
        val runner = FakeRunner()
        val store = InMemoryLedgerStore()
        val events = mutableListOf<DozeEvent>()
        val sink = DozeEventSink { events += it }
        val grants = Grants(true, true)
        val controller = DozeController(runner, CommandCatalog, CapabilityResolver, store, FakeClock(), sink, 36, grants)
        val focused = FocusedAppParser.parse(FakeRunner.result("mCurrentFocus=null", timeout = true))
        val selected = FeatureSelection.packages(apps, notifications, own, true, focused, sink)
        runner.replies("cmd deviceidle get deep", "IDLE")
        runner.replies("settings get global wifi_on", "1", "0")
        val result = controller.enterGroups(DozeConfig(36, AccessLevel.SHELL, grants,
            features = setOf(Feature.WIFI), appsToSuspend = selected.appsToSuspend,
            packagesToBlockNotifications = selected.packagesToBlockNotifications), controller.currentGeneration) { true }
        assertEquals(StepStatus.VERIFIED, result.steps.single().status)
        assertEquals(Feature.WIFI, result.steps.single().feature)
        assertTrue(runner.commands.contains("cmd wifi set-wifi-enabled disabled"))
        assertFalse("unknown focus must not cause suspend/revoke/readback for any package",
            runner.commands.any { it.startsWith("pm ") || it.startsWith("dumpsys package ") })
        assertEquals(listOf(Feature.WIFI), store.load().entries.map { it.feature })
        assertEquals(2, events.count { it.type == EventType.SKIPPED && it.reason == Reason.UNVERIFIED })
    }

    @Test fun unknownFocusWithoutConfiguredPackageGroupsDoesNotClaimAnythingWasSkipped() {
        val events = mutableListOf<DozeEvent>()
        val selected = FeatureSelection.packages(setOf(own), emptySet(), own, true,
            FocusedApps.Unknown(Reason.UNVERIFIED), DozeEventSink { events += it })
        assertEquals(PackageSelection(emptySet(), emptySet()), selected)
        assertTrue(events.isEmpty())
    }

    @Test fun knownFocusPreservesExistingPackageFiltering() {
        val events = mutableListOf<DozeEvent>()
        val selected = FeatureSelection.packages(apps, notifications, own, true,
            FocusedApps.Known(setOf(foreground)), DozeEventSink { events += it })
        assertEquals(setOf(background), selected.appsToSuspend)
        assertEquals(setOf(foreground, notificationOnly), selected.packagesToBlockNotifications)
        assertTrue(events.isEmpty())
    }

    @Test fun explicitNoFocusAndDisabledWhitelistPreserveExistingBehavior() {
        for ((whitelist, reading) in listOf(
            true to FocusedApps.Known(emptySet()),
            false to FocusedApps.Unknown(Reason.UNVERIFIED),
        )) {
            val events = mutableListOf<DozeEvent>()
            val selected = FeatureSelection.packages(apps, notifications, own, whitelist,
                reading, DozeEventSink { events += it })
            assertEquals(setOf(foreground, background), selected.appsToSuspend)
            assertEquals(setOf(notificationOnly), selected.packagesToBlockNotifications)
            assertTrue(events.isEmpty())
        }
    }
}
