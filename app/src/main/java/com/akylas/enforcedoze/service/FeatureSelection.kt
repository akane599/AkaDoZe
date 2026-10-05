package com.akylas.enforcedoze.service

import com.akylas.enforcedoze.monitor.EventCodes

import com.akylas.enforcedoze.access.Feature
import com.akylas.enforcedoze.access.Prefs
import com.akylas.enforcedoze.doze.DozeEvent
import com.akylas.enforcedoze.doze.DozeEventSink
import com.akylas.enforcedoze.doze.EventType
import com.akylas.enforcedoze.doze.parse.FocusedApps

data class PackageSelection(
    val appsToSuspend: Set<String>,
    val packagesToBlockNotifications: Set<String>,
)

/** Enter-time exemptions are selection-only. Restoration never calls this helper. */
object FeatureSelection {
    @JvmStatic
    fun packages(
        apps: Set<String>,
        notifications: Set<String>,
        ownPackage: String,
        whitelistCurrentApp: Boolean,
        focused: FocusedApps,
        sink: DozeEventSink,
    ): PackageSelection {
        if (whitelistCurrentApp && focused is FocusedApps.Unknown) {
            for ((feature, targets) in listOf(Feature.APP_SUSPEND to apps, Feature.NOTIFICATION_BLOCK to notifications)) {
                if ((targets - ownPackage).isNotEmpty()) {
                    sink.emit(DozeEvent(EventType.SKIPPED, EventCodes.FOCUSED_APP_UNVERIFIED, feature = feature, reason = focused.reason))
                }
            }
            return PackageSelection(emptySet(), emptySet())
        }
        val exemptions = if (whitelistCurrentApp && focused is FocusedApps.Known) focused.packages else emptySet()
        val selectedApps = apps - exemptions - ownPackage
        return PackageSelection(selectedApps, notifications - selectedApps - ownPackage)
    }

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
