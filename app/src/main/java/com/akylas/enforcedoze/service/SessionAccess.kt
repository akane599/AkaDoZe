package com.akylas.enforcedoze.service

import com.akylas.enforcedoze.access.AccessLevel
import com.akylas.enforcedoze.access.Feature
import com.akylas.enforcedoze.access.Grants

/** Forward selection only; recovery retains its independent per-feature capabilities. */
enum class SessionMode { RESTORE_ONLY, SENSOR_ONLY, FORCE }

object SessionAccess {
    /** Level-only authority for privileged reset/revoke callers; not sensor session admission. */
    @JvmStatic
    fun canRunSessions(level: AccessLevel): Boolean = level >= AccessLevel.SHELL

    @JvmStatic
    fun mode(level: AccessLevel, grants: Grants, sensorsEnabled: Boolean, resolved: Boolean): SessionMode = when {
        !resolved -> SessionMode.RESTORE_ONLY
        canRunSessions(level) -> SessionMode.FORCE
        level == AccessLevel.APP && grants.dump && sensorsEnabled -> SessionMode.SENSOR_ONLY
        else -> SessionMode.RESTORE_ONLY
    }

    /** Mode bounds requested features; the resolver still enforces current API/access/grants. */
    @JvmStatic
    fun canRunFeature(mode: SessionMode, feature: Feature): Boolean = when (mode) {
        SessionMode.RESTORE_ONLY -> false
        SessionMode.SENSOR_ONLY -> feature == Feature.MOTION_SENSORS
        SessionMode.FORCE -> true
    }
}
