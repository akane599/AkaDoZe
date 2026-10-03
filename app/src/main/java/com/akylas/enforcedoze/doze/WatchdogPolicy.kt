package com.akylas.enforcedoze.doze

import com.akylas.enforcedoze.doze.parse.DozeStateReading

sealed interface Decision {
    data object REFORCE : Decision
    data object IGNORE : Decision
    data class DEFER(val untilElapsed: Long) : Decision
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
        if (screenOn || !admission || reforces >= MAX_REFORCES ||
            snapshot.deep == null || snapshot.deep == DeepState.UNKNOWN ||
            snapshot.deep == DeepState.IDLE || snapshot.deep == DeepState.IDLE_MAINTENANCE
        ) return Decision.IGNORE
        val now = clock.elapsedRealtime()
        val due = lastReforce?.plus(MIN_INTERVAL_MS)
        if (due != null && now < due) {
            if (deferred) return Decision.IGNORE
            deferred = true
            return Decision.DEFER(due)
        }
        deferred = false
        lastReforce = now
        reforces++
        return Decision.REFORCE
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
