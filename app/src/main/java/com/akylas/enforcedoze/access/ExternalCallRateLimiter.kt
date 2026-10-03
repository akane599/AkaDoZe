package com.akylas.enforcedoze.access

import java.util.ArrayDeque

/** Process-wide, per-action rolling limit. Suppression never stores callers or untrusted values. */
class ExternalCallRateLimiter @JvmOverloads constructor(
    private val clock: Clock,
    private val maxEvents: Int = 10,
    private val windowMs: Long = 60_000,
) {
    fun interface Clock { fun elapsedRealtime(): Long }
    data class Admission(val admitted: Boolean, val suppressed: Long = 0)

    private class Window {
        val admitted = ArrayDeque<Long>()
        var suppressed = 0L
    }

    private val windows = mutableMapOf<ExternalControlPolicy.Action, Window>()

    init {
        require(maxEvents > 0)
        require(windowMs > 0)
    }

    /** One summary is returned alongside the next admitted event; no timer/idle journal writes. */
    @Synchronized
    fun record(action: ExternalControlPolicy.Action): Admission {
        val now = clock.elapsedRealtime()
        val window = windows.getOrPut(action) { Window() }
        while (window.admitted.isNotEmpty() && now - window.admitted.first >= windowMs) {
            window.admitted.removeFirst()
        }
        if (window.admitted.size >= maxEvents) {
            window.suppressed++
            return Admission(false)
        }
        window.admitted.addLast(now)
        val suppressed = window.suppressed
        window.suppressed = 0
        return Admission(true, suppressed)
    }
}
