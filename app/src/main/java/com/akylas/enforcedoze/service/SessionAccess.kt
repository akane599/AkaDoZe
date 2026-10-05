package com.akylas.enforcedoze.service

import com.akylas.enforcedoze.access.AccessLevel
import com.akylas.enforcedoze.access.Feature
import com.akylas.enforcedoze.access.Grants
import com.akylas.enforcedoze.doze.Action
import com.akylas.enforcedoze.doze.ReapplySkip
import com.akylas.enforcedoze.doze.RestoreLedger

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

    /** Waiting for unlock retains existing ownership, never admits another forward mutation. */
    @JvmStatic
    fun screenAdmitted(screenOn: Boolean, waitForUnlock: Boolean, retainingIntent: Boolean): Boolean =
        !screenOn || retainingIntent && waitForUnlock

    /** Reject before state reads or watchdog reservation; reapply never means sensor reapply. */
    @JvmStatic
    fun reapplySkip(mode: SessionMode): ReapplySkip? =
        if (mode == SessionMode.FORCE) null else ReapplySkip.EXTERNAL_REAPPLY_NOT_ADMITTED

    /** SafetyNet may exempt only healthy intent owned by the currently admitted session. */
    @JvmStatic
    fun keepsSafetyIntent(
        action: Action,
        mode: SessionMode,
        admitted: Boolean,
        recoveryNeeded: Boolean,
        ledger: RestoreLedger,
        token: String,
    ): Boolean {
        if (!admitted || recoveryNeeded || mode == SessionMode.RESTORE_ONLY) return false
        return when (action) {
            Action.UNFORCE -> mode == SessionMode.FORCE
            Action.RESTORE_SENSORS -> ledger.entries.any {
                it.feature == Feature.MOTION_SENSORS && it.target == token &&
                    it.originalValue == "NORMAL" && !it.debt
            }
            Action.RAISE_DEBT -> false
        }
    }

    /** Mode bounds requested features; the resolver still enforces current API/access/grants. */
    @JvmStatic
    fun canRunFeature(mode: SessionMode, feature: Feature): Boolean = when (mode) {
        SessionMode.RESTORE_ONLY -> false
        SessionMode.SENSOR_ONLY -> feature == Feature.MOTION_SENSORS
        SessionMode.FORCE -> true
    }
}
