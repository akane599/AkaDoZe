package com.akylas.enforcedoze.service

import com.akylas.enforcedoze.access.AccessState
import java.util.concurrent.atomic.AtomicLong

/** Worker-owned recovery barrier; invalidation may interrupt a running enter on the main thread. */
class AccessReadiness {
    private val epoch = AtomicLong()
    private var recoveredEpoch = -1L
    private var recoveredState: AccessState? = null

    fun invalidate() { epoch.incrementAndGet() }

    fun ready(state: AccessState): Boolean = state.resolved &&
        recoveredEpoch == epoch.get() && recoveredState == state

    fun recover(state: AccessState, current: () -> AccessState, restore: () -> Unit): Boolean {
        if (!state.resolved) return false
        if (ready(state)) return true
        val token = epoch.get()
        restore()
        if (state == current()) {
            recoveredState = state
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

object BootRestorePolicy {
    @JvmStatic
    fun shouldRestore(serviceEnabled: Boolean, encoded: String, retained: String): Boolean =
        !serviceEnabled && (encoded.isNotEmpty() || retained.isNotEmpty())
}
