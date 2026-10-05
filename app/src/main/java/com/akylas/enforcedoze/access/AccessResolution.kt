package com.akylas.enforcedoze.access

/** Process-owned discovery facts, separate from Android binder/probe adapters. */
class AccessResolution {
    private var binderSeen = false
    private var discoveryDeadline: Long? = null
    private var rootCompleted = false
    private var rootAvailable = false
    private var rootTimedOut = false
    private var rootAttempts = 0
    private enum class RootDiscoveryClose { OPEN, DETACHED, DETACHED_TIMEOUT, SERVICE }
    private var rootDiscoveryClose = RootDiscoveryClose.OPEN
    private var rootProbeInFlight = false

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
        rootProbeInFlight = true
    }

    @Synchronized fun rootProbeFinished(available: Boolean, timedOut: Boolean) {
        rootProbeInFlight = false
        rootAvailable = available
        rootCompleted = rootCompleted || !timedOut || rootAttempts >= 4
        rootTimedOut = timedOut && !rootCompleted
        // A detached close may precede its probe result; classify that timeout before settling it.
        if (rootDiscoveryClose != RootDiscoveryClose.OPEN) {
            finishRootDiscovery(detached = rootDiscoveryClose != RootDiscoveryClose.SERVICE)
        }
    }

    @Synchronized fun canRetryRoot(): Boolean = rootTimedOut && !rootCompleted && rootAttempts < 4

    /** A detached receiver settles on its first timeout; a service-owned close is terminal. */
    @Synchronized fun finishRootDiscovery(detached: Boolean = true) {
        if (!detached) rootDiscoveryClose = RootDiscoveryClose.SERVICE
        else if (rootDiscoveryClose == RootDiscoveryClose.OPEN) rootDiscoveryClose = RootDiscoveryClose.DETACHED
        if (rootTimedOut) {
            if (rootDiscoveryClose == RootDiscoveryClose.DETACHED) rootDiscoveryClose = RootDiscoveryClose.DETACHED_TIMEOUT
            rootCompleted = true
            rootTimedOut = false
        }
    }

    /** Only discovery cut short by a detached timeout gives an attached service a fresh 1+3 budget. */
    @Synchronized fun startServiceRootDiscovery(): Boolean {
        if (rootAvailable || rootDiscoveryClose != RootDiscoveryClose.DETACHED_TIMEOUT) return false
        rootDiscoveryClose = RootDiscoveryClose.OPEN
        rootCompleted = false
        rootTimedOut = false
        rootAttempts = if (rootProbeInFlight) 1 else 0
        return true
    }

    @Synchronized fun root(grants: Grants, appUid: Int): AccessState = AccessState(
        if (rootAvailable) AccessLevel.ROOT else AccessLevel.APP,
        if (rootAvailable) null else Reason.NO_ACCESS, grants, if (rootAvailable) 0 else appUid,
        resolved = rootCompleted, rootProbeTimedOut = rootTimedOut,
    )
}
