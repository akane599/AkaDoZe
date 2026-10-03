package com.akylas.enforcedoze.service

import com.akylas.enforcedoze.access.AccessLevel
import com.akylas.enforcedoze.access.AccessManager
import com.akylas.enforcedoze.access.AccessState

/** Injectable access subscription shared by the runtime and JVM restore-window tests. */
interface RecoveryAccess {
    val state: AccessState
    fun addListener(listener: AccessManager.Listener)
    fun removeListener(listener: AccessManager.Listener)
}

/** Main-thread-owned receiver window; its passive subscription survives worker retirement. */
class RestoreOnlyRequest(
    private val access: RecoveryAccess,
    private val now: () -> Long,
    private val post: (() -> Unit) -> Unit,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val restore: (Long, () -> Unit) -> Unit,
    private val completed: () -> Unit,
    private val retry: () -> Unit,
    private val noAccess: () -> Unit,
    private val allowContinuation: Boolean = true,
) {
    private var finished = false
    private var waiting = false
    private var announcedNoAccess = false
    private var restoring = false
    private var cancelTimeout: (() -> Unit)? = null
    private var deadline = 0L
    private val listener = AccessManager.Listener { post { changed() } }

    fun start(deadline: Long = now() + 9_000L) {
        this.deadline = deadline
        cancelTimeout = schedule(deadline) { finish() }
        access.addListener(listener)
    }

    private fun changed() {
        val state = access.state
        if (!state.resolved) return
        if (waiting) {
            if (state.level >= AccessLevel.SHELL) {
                waiting = false
                access.removeListener(listener)
                retry()
            } else announceNoAccess()
        } else if (!finished && !restoring) {
            if (now() >= deadline) {
                finish()
                return
            }
            restoring = true
            // The active restore announces and records no-access debt through the normal runtime path.
            if (state.level < AccessLevel.SHELL) announcedNoAccess = true
            restore(deadline) {
                post {
                    restoring = false
                    if (access.state.resolved) finish()
                }
            }
        }
    }

    private fun announceNoAccess() {
        if (announcedNoAccess) return
        announcedNoAccess = true
        noAccess()
    }

    private fun finish() {
        if (finished) return
        finished = true
        // Settled APP also needs a later recovery: it may have finished before the nine-second timer.
        waiting = allowContinuation && (!access.state.resolved || access.state.level < AccessLevel.SHELL)
        if (!waiting) access.removeListener(listener)
        cancelTimeout?.invoke()
        completed()
        if (waiting) changed()
    }
}
