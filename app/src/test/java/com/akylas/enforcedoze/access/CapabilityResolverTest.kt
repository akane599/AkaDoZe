package com.akylas.enforcedoze.access

import org.junit.Assert.assertEquals
import org.junit.Test

class CapabilityResolverTest {
    @Test
    fun everyFeatureLevelApiAndGrantCombinationMatchesMatrix() {
        val dumpFeatures = setOf(Feature.MOTION_SENSORS, Feature.DOZE_STATE_READ)
        val wssFeatures = setOf(Feature.TUNABLES, Feature.BIOMETRICS)
        val rootFeatures = setOf(Feature.SENSOR_PRIVACY_ALL, Feature.SETPROP_DOZE, Feature.PM_DISABLE)
        for (feature in Feature.entries) for (level in AccessLevel.entries) {
            for (api in listOf(23, 24, 29, 30, 33, 36)) for (dump in listOf(false, true)) {
                for (wss in listOf(false, true)) {
                    val privileged = level == AccessLevel.SHELL || level == AccessLevel.ROOT
                    val reason = when {
                        feature == Feature.APP_SUSPEND && api == 23 -> Reason.API_TOO_OLD
                        level == AccessLevel.NONE -> Reason.NO_ACCESS
                        (feature in rootFeatures || feature == Feature.AIRPLANE && api < 30 ||
                            feature == Feature.NOTIFICATION_BLOCK && api < 33) && level != AccessLevel.ROOT -> Reason.REQUIRES_ROOT
                        feature in dumpFeatures && !privileged && !dump -> Reason.NEEDS_DUMP
                        feature in wssFeatures && !privileged && !wss -> Reason.NEEDS_WRITE_SECURE_SETTINGS
                        feature !in dumpFeatures && feature !in wssFeatures && !privileged -> Reason.NO_ACCESS
                        else -> null
                    }
                    val expected = reason?.let { FeatureStatus.Unavailable(it) } ?: FeatureStatus.Available
                    assertEquals("$feature/$level/api=$api/dump=$dump/wss=$wss", expected,
                        CapabilityResolver.status(feature, level, api, Grants(dump, wss)))
                }
            }
        }
    }

    @Test
    fun grantsDoNotPromoteAnAppUidAndPrivilegedLevelsImplyGrants() {
        val granted = Grants(true, true)
        assertEquals(FeatureStatus.Unavailable(Reason.NO_ACCESS),
            CapabilityResolver.status(Feature.FORCE_DOZE, AccessLevel.APP, 36, granted))
        assertEquals(FeatureStatus.Available,
            CapabilityResolver.status(Feature.MOTION_SENSORS, AccessLevel.APP, 36, granted))
        assertEquals(FeatureStatus.Available,
            CapabilityResolver.status(Feature.BIOMETRICS, AccessLevel.APP, 36, granted))
        for (feature in listOf(Feature.MOTION_SENSORS, Feature.DOZE_STATE_READ, Feature.TUNABLES, Feature.BIOMETRICS)) {
            assertEquals(FeatureStatus.Available,
                CapabilityResolver.status(feature, AccessLevel.SHELL, 36, Grants(false, false)))
        }
    }

    @Test
    fun whitelistHasApi23FallbackButSuspendDoesNot() {
        assertEquals(FeatureStatus.Available,
            CapabilityResolver.status(Feature.WHITELIST_EDIT, AccessLevel.SHELL, 23, Grants(false, false)))
        assertEquals(FeatureStatus.Unavailable(Reason.API_TOO_OLD),
            CapabilityResolver.status(Feature.APP_SUSPEND, AccessLevel.ROOT, 23, Grants(true, true)))
    }
}
