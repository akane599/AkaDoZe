package com.akylas.enforcedoze.service

import com.akylas.enforcedoze.access.Feature
import com.akylas.enforcedoze.access.Prefs

/** Existing radio exemptions are selection-only. Restoration never calls this helper. */
object FeatureSelection {
    @JvmStatic
    fun features(enabled: Set<String>, hotspot: Boolean, ignoreHotspot: Boolean,
                 playingMusic: Boolean, wifiOn: Boolean): Set<Feature> = buildSet {
        if (Prefs.TURN_OFF_ALL_SENSORS in enabled) add(Feature.SENSOR_PRIVACY_ALL)
        if (Prefs.TURN_OFF_BIOMETRICS in enabled) add(Feature.BIOMETRICS)
        if (!playingMusic) {
            if (Prefs.TURN_OFF_BLUETOOTH in enabled) add(Feature.BLUETOOTH)
            if (Prefs.TURN_OFF_LOCATION in enabled) add(Feature.LOCATION)
            if (ignoreHotspot || !hotspot) {
                if (Prefs.TURN_ON_AIRPLANE in enabled) add(Feature.AIRPLANE)
                if (Prefs.TURN_OFF_WIFI in enabled) add(Feature.WIFI)
            }
        }
        if (Prefs.TURN_OFF_DATA in enabled && (ignoreHotspot || !hotspot) && (!playingMusic || wifiOn)) {
            add(Feature.MOBILE_DATA)
        }
    }
}
