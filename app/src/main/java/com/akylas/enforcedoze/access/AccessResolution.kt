package com.akylas.enforcedoze.access

/** Process-owned discovery facts, separate from Android binder/probe adapters. */
class AccessResolution {
    private var binderSeen = false
    private var discoveryDeadline: Long? = null
    private var rootCompleted = false
    private var rootAvailable = false
    private var rootTimedOut = false
    private var rootAttempts = 0
    private var rootDiscoveryClosed = false

    @Synchronized fun startDiscovery(now: Long) {
        if (discoveryDeadline == null) discoveryDeadline = now + 10_000L
    }

    @Synchronized fun sawBinder() { binderSeen = true }

    @Synchronized fun shizuku(value: ShizukuState, grants: Grants, appUid: Int, now: Long): AccessState {
        if (value.reason != Reason.SHIZUKU_NOT_RUNNING) binderSeen = true
        return if (value.level != AccessLevel.NONE) AccessState(value.level, value.reason, grants, value.uid)
        else AccessState(AccessLevel.APP, value.reason, grants, appUid,
            resolved = binderSeen || discoveryDeadline?.let { now >= it } == true)
    }

    @Synchronized fun rootProbeStarted() {
        // A refresh is not a second cold start. Keep the published state until its result arrives.
        if (!rootCompleted) rootTimedOut = false
        rootAttempts++
    }

    @Synchronized fun rootProbeFinished(available: Boolean, timedOut: Boolean) {
        rootAvailable = available
        rootCompleted = rootCompleted || !timedOut || rootAttempts >= 4 || rootDiscoveryClosed
        rootTimedOut = timedOut && !rootCompleted
    }

    @Synchronized fun canRetryRoot(): Boolean = rootTimedOut && !rootCompleted && rootAttempts < 4

    /** A detached receiver does not repeatedly prompt for su; its first timeout settles discovery. */
    @Synchronized fun finishRootDiscovery() {
        rootDiscoveryClosed = true
        if (rootTimedOut) {
            rootCompleted = true
            rootTimedOut = false
        }
    }

    @Synchronized fun root(grants: Grants, appUid: Int): AccessState = AccessState(
        if (rootAvailable) AccessLevel.ROOT else AccessLevel.APP,
        if (rootAvailable) null else Reason.NO_ACCESS, grants, if (rootAvailable) 0 else appUid,
        resolved = rootCompleted, rootProbeTimedOut = rootTimedOut,
    )
}
