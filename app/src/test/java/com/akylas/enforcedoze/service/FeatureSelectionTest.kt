package com.akylas.enforcedoze.service

import com.akylas.enforcedoze.access.Feature
import com.akylas.enforcedoze.access.Prefs
import org.junit.Assert.*
import org.junit.Test

class FeatureSelectionTest {
    private val enabled = setOf(Prefs.TURN_OFF_WIFI, Prefs.TURN_OFF_DATA, Prefs.TURN_OFF_BLUETOOTH,
        Prefs.TURN_OFF_LOCATION, Prefs.TURN_ON_AIRPLANE, Prefs.TURN_OFF_BIOMETRICS, Prefs.TURN_OFF_ALL_SENSORS)

    @Test fun actualExistingKeysMapWithoutRenamingAndExemptionsAreSelectionOnly() {
        assertEquals("turnOffGPSInDoze", Prefs.TURN_OFF_LOCATION)
        assertEquals("dozeAppBlockList", Prefs.APP_BLOCKLIST)
        assertEquals("notificationBlockList", Prefs.NOTIFICATION_BLOCKLIST)
        assertEquals(setOf(Feature.WIFI, Feature.MOBILE_DATA, Feature.BLUETOOTH, Feature.LOCATION,
            Feature.AIRPLANE, Feature.BIOMETRICS, Feature.SENSOR_PRIVACY_ALL),
            FeatureSelection.features(enabled, false, false, false, false))
        assertEquals(setOf(Feature.BIOMETRICS, Feature.SENSOR_PRIVACY_ALL),
            FeatureSelection.features(enabled, false, false, true, false))
        assertEquals(setOf(Feature.MOBILE_DATA, Feature.BIOMETRICS, Feature.SENSOR_PRIVACY_ALL),
            FeatureSelection.features(enabled, false, false, true, true))
    }

    @Test fun hotspotPrefRetainsLegacyExemptionWithoutWritingAnyPreference() {
        assertEquals(setOf(Feature.BLUETOOTH, Feature.LOCATION, Feature.BIOMETRICS, Feature.SENSOR_PRIVACY_ALL),
            FeatureSelection.features(enabled, true, false, false, true))
        assertTrue(FeatureSelection.features(enabled, true, true, false, true).containsAll(setOf(Feature.WIFI, Feature.MOBILE_DATA, Feature.AIRPLANE)))
    }
}
