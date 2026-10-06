package com.akylas.enforcedoze.service

import com.akylas.enforcedoze.access.AccessLevel
import com.akylas.enforcedoze.access.AccessState
import java.util.concurrent.atomic.AtomicLong

/** Worker-owned recovery barrier; caller-thread invalidation fences admission on the worker. */
class AccessReadiness {
    private data class ReadinessState(val level: AccessLevel, val resolved: Boolean, val mode: SessionMode)

    private val epoch = AtomicLong()
    private var recoveredEpoch = -1L
    private var recoveredState: ReadinessState? = null

    companion object {
        // Compare access capability, not incidental probe/grant metadata. Sensor preference changes
        // invalidate the epoch separately; DUMP still changes the available APP session mode.
        private fun readinessState(state: AccessState) = ReadinessState(
            state.level, state.resolved,
            SessionAccess.mode(state.level, state.grants, sensorsEnabled = true, resolved = state.resolved),
        )

        /** Shared by caller-thread service invalidation and worker recovery. */
        @JvmStatic
        fun sameCapability(state: AccessState, previous: AccessState?): Boolean =
            previous != null && readinessState(state) == readinessState(previous)
    }

    fun invalidate() { epoch.incrementAndGet() }

    fun ready(state: AccessState): Boolean = state.resolved &&
        recoveredEpoch == epoch.get() && recoveredState == readinessState(state)

    fun recover(state: AccessState, current: () -> AccessState, restore: () -> Unit): Boolean {
        if (!state.resolved) return false
        if (ready(state)) return true
        val token = epoch.get()
        val recoveryState = readinessState(state)
        restore()
        if (recoveryState == readinessState(current())) {
            recoveredState = recoveryState
            recoveredEpoch = token
        }
        return ready(current())
    }
}

/** Finite retries per owner (service lifetime or bounded restore request), not per callback. */
class RootProbeRetry {
    private var attempts = 0

    fun nextDelay(timedOut: Boolean, needed: Boolean): Long? {
        if (!timedOut || !needed || attempts >= 3) return null
        return 1_000L shl attempts++
    }
}
