package com.akylas.enforcedoze.service

import com.akylas.enforcedoze.doze.DeepState
import com.akylas.enforcedoze.doze.LightState

/** Pure decisions extracted from the service, shared by worker and receiver. */
class SessionLifecycle {
    @Volatile var active = false
    private var entered = false

    fun activate(expectedEpoch: Long, currentEpoch: () -> Long, screenOn: () -> Boolean): Boolean {
        active = true
        if (expectedEpoch != currentEpoch() || screenOn()) active = false
        return active
    }

    fun recordEnter(verifiedIdle: Boolean): Boolean {
        if (!verifiedIdle || entered) return false
        entered = true
        return true
    }

    fun recordExit(): Boolean {
        val hadEnter = entered
        entered = false
        return hadEnter
    }

    companion object {
        const val TEARDOWN_WAIT_MS = 4_000L
        const val TEARDOWN_COMMAND_MS = 3_500L

        /** Null means no known transition; OEM/absent state cannot authorize reapplication. */
        @JvmStatic
        fun maintenanceState(deep: DeepState?, light: LightState?): Boolean? = when {
            deep == DeepState.IDLE_MAINTENANCE || light == LightState.IDLE_MAINTENANCE -> true
            deep == DeepState.IDLE -> false
            deep != null && deep != DeepState.UNKNOWN && light == LightState.IDLE -> false
            else -> null
        }

        @JvmStatic
        fun shouldExit(powerConnected: Boolean, screenOn: Boolean, sessionActive: Boolean,
                       disableWhenCharging: Boolean): Boolean = powerConnected && disableWhenCharging && sessionActive && !screenOn
    }
}
