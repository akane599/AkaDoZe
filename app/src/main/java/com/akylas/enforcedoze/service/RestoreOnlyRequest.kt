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

/**
 * Main-thread-owned receiver window. It always drops its own subscription when it ends; a window that
 * could not restore at SHELL/ROOT hands the later restore to the runtime's single [continuation].
 * A null continuation marks the continuation's own follow-up window, which never arms another.
 * [dispatchRestore] queues worker work; [restore] runs synchronously only after worker-time admission.
 */
class RestoreOnlyRequest(
    private val access: RecoveryAccess,
    private val now: () -> Long,
    private val post: (() -> Unit) -> Unit,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val dispatchRestore: (() -> Unit) -> Unit,
    private val restore: (Long) -> Unit,
    private val completed: () -> Unit,
    private val continuation: RestoreContinuation?,
) {
    private var finished = false
    private var announcedNoAccess = false
    private var restoring = false
    private var restoredReady = false
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
        if (finished || restoring || !state.resolved) return
        val ready = state.level >= AccessLevel.SHELL
        val remaining = deadline - now()
        // A late ready gets the continuation's fresh window instead of a sliver of this one.
        if (remaining <= 0 || (ready && continuation != null && remaining < MIN_READY_BUDGET_MS)) {
            finish()
            return
        }
        restoring = true
        dispatchRestore {
            // Worker backlog can consume the budget after changed() admits this window.
            val admitted = deadline - now() >= MIN_READY_BUDGET_MS
            try {
                if (admitted) {
                    post {
                        if (ready) restoredReady = true
                        // The active restore announces no-access debt through the runtime path.
                        else announcedNoAccess = true
                    }
                    restore(deadline)
                }
            } finally {
                post {
                    restoring = false
                    if (!admitted || access.state.resolved) finish()
                }
            }
        }
    }

    private fun finish() {
        if (finished) return
        finished = true
        val state = access.state
        // Settled APP also needs a later recovery: it may have finished before the nine-second timer.
        val later = !state.resolved || state.level < AccessLevel.SHELL || !restoredReady
        access.removeListener(listener)
        cancelTimeout?.invoke()
        completed()
        if (later) continuation?.arm(announcedNoAccess)
    }

    private companion object {
        const val MIN_READY_BUDGET_MS = 2_000L
    }
}

/**
 * Main-thread-only, process-level wait for SHELL/ROOT shared by every window that ends without it:
 * one subscription however many windows arm it, one APP restore if discovery settles after a window,
 * then one follow-up window on ready and disarmed.
 */
class RestoreContinuation(
    private val access: RecoveryAccess,
    private val post: (() -> Unit) -> Unit,
    private val retry: () -> Unit,
    private val noAccess: () -> Unit,
    private val restoreApp: () -> Unit,
) {
    private var armed = false
    private var announced = false
    private var restoredApp = false
    private val listener = AccessManager.Listener { post { changed() } }

    /** Idempotent; a window whose own restore already announced no access owes no second notice. */
    fun arm(windowAnnounced: Boolean) {
        announced = if (armed) announced && windowAnnounced else windowAnnounced
        // An APP attempt already made by any window satisfies this arm's APP recovery.
        restoredApp = if (armed) restoredApp || windowAnnounced else windowAnnounced
        if (!armed) {
            armed = true
            access.addListener(listener)
        }
        changed()
    }

    private fun changed() {
        val state = access.state
        if (!armed || !state.resolved) return
        if (state.level >= AccessLevel.SHELL) {
            armed = false
            access.removeListener(listener)
            retry()
        } else {
            if (!announced) {
                announced = true
                noAccess()
            }
            if (!restoredApp) {
                restoredApp = true
                restoreApp()
            }
        }
    }
}
