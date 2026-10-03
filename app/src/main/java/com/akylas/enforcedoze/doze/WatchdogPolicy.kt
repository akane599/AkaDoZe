package com.akylas.enforcedoze.doze

import com.akylas.enforcedoze.doze.parse.DozeStateReading

sealed interface Decision {
    data object REFORCE : Decision
    data object IGNORE : Decision
    data class DEFER(val untilElapsed: Long) : Decision
    data class SKIP(val reason: ReapplySkip) : Decision
}

/** Typed journal details, not user-visible text or new event types. */
enum class ReapplySkip {
    EXTERNAL_REAPPLY_ENTER_PENDING,
    EXTERNAL_REAPPLY_NOT_ADMITTED,
    EXTERNAL_REAPPLY_MAINTENANCE,
    EXTERNAL_REAPPLY_STATE_UNKNOWN,
    EXTERNAL_REAPPLY_SPACING,
    EXTERNAL_REAPPLY_BUDGET,
}

/** Caller owns a single deferred callback and cancels it at every generation/session change. */
class WatchdogPolicy(private val clock: Clock) {
    private var lastReforce: Long? = null
    private var reforces = 0
    private var deferred = false

    /** admission already includes the user's charging policy; charging alone is not a veto. */
    @Suppress("UNUSED_PARAMETER")
    @Synchronized
    fun onIdleChanged(snapshot: DozeStateReading, screenOn: Boolean, charging: Boolean, admission: Boolean): Decision {
        if (screenOn || !admission || unknownDeep(snapshot) || maintenance(snapshot) ||
            snapshot.deep == DeepState.IDLE
        ) return Decision.IGNORE
        return when (reserveReforce()) {
            null -> Decision.REFORCE
            ReapplySkip.EXTERNAL_REAPPLY_SPACING -> {
                if (deferred) Decision.IGNORE else {
                    deferred = true
                    Decision.DEFER(requireNotNull(lastReforce) + MIN_INTERVAL_MS)
                }
            }
            else -> Decision.IGNORE
        }
    }

    /** Explicit basic-control consent does not depend on the automatic enforcement preference.
     * Unlike automatic motion enforcement, an explicit reapply may reforce IDLE, but never
     * schedules a deferred request or consumes budget when rejected.
     */
    @Synchronized
    fun onExternalReapply(snapshot: DozeStateReading, maintenanceInProgress: Boolean): Decision {
        if (maintenanceInProgress || maintenance(snapshot)) {
            return Decision.SKIP(ReapplySkip.EXTERNAL_REAPPLY_MAINTENANCE)
        }
        if (unknownDeep(snapshot)) return Decision.SKIP(ReapplySkip.EXTERNAL_REAPPLY_STATE_UNKNOWN)
        val reason = reserveReforce()
        return if (reason == null) Decision.REFORCE else Decision.SKIP(reason)
    }

    /** An ordinary enter starts spacing too, without spending the reforce budget. */
    @Synchronized
    fun recordEnter() {
        lastReforce = clock.elapsedRealtime()
    }

    private fun unknownDeep(snapshot: DozeStateReading): Boolean =
        snapshot.deep == null || snapshot.deep == DeepState.UNKNOWN

    private fun maintenance(snapshot: DozeStateReading): Boolean =
        snapshot.deep == DeepState.IDLE_MAINTENANCE || snapshot.light == LightState.IDLE_MAINTENANCE

    /** Both sources reserve from the same session budget before issuing any mutation. */
    private fun reserveReforce(): ReapplySkip? {
        if (reforces >= MAX_REFORCES) return ReapplySkip.EXTERNAL_REAPPLY_BUDGET
        val now = clock.elapsedRealtime()
        val due = lastReforce?.plus(MIN_INTERVAL_MS)
        if (due != null && now < due) return ReapplySkip.EXTERNAL_REAPPLY_SPACING
        deferred = false
        lastReforce = now
        reforces++
        return null
    }

    @Synchronized
    fun resetSession() {
        lastReforce = null
        reforces = 0
        deferred = false
    }

    companion object {
        const val MIN_INTERVAL_MS = 60_000L
        const val MAX_REFORCES = 5
    }
}
