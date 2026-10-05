package com.akylas.enforcedoze.service

import com.akylas.enforcedoze.access.AccessLevel
import com.akylas.enforcedoze.access.Feature
import com.akylas.enforcedoze.access.Grants
import org.junit.Assert.*
import org.junit.Test

class SessionAccessTest {
    @Test fun resolvedGrantAndSensorPreferenceMatrixKeepsResetPrivilegedOnly() {
        for (level in AccessLevel.entries) {
            assertEquals(level >= AccessLevel.SHELL, SessionAccess.canRunSessions(level))
            for (resolved in listOf(false, true)) {
                for (dump in listOf(false, true)) {
                    for (sensors in listOf(false, true)) {
                        val expected = when {
                            !resolved -> SessionMode.RESTORE_ONLY
                            level >= AccessLevel.SHELL -> SessionMode.FORCE
                            level == AccessLevel.APP && dump && sensors -> SessionMode.SENSOR_ONLY
                            else -> SessionMode.RESTORE_ONLY
                        }
                        assertEquals("$level resolved=$resolved dump=$dump sensors=$sensors", expected,
                            SessionAccess.mode(level, Grants(dump, true), sensors, resolved))
                    }
                }
            }
        }
    }

    @Test fun perFeatureAuthorityNeverBroadensSensorOnlyToOtherFeatures() {
        for (feature in Feature.entries) {
            assertFalse(SessionAccess.canRunFeature(SessionMode.RESTORE_ONLY, feature))
            assertEquals(feature == Feature.MOTION_SENSORS,
                SessionAccess.canRunFeature(SessionMode.SENSOR_ONLY, feature))
            assertTrue(SessionAccess.canRunFeature(SessionMode.FORCE, feature))
        }
    }
}
