package com.akylas.enforcedoze.access

import org.junit.Assert.*
import org.junit.Test

class AccessResolutionTest {
    private val grants = Grants(false, false)

    @Test fun knownRootSurvivesRefreshTimeoutAndDetachedCloseUntilDefinitiveLoss() {
        val resolution = AccessResolution()
        resolution.rootProbeStarted()
        resolution.rootProbeFinished(true, false)
        val known = resolution.root(grants, 10001)
        resolution.rootProbeStarted()
        resolution.finishRootDiscovery(detached = true) // SQ-119 S9: close while the refresh is in flight.
        resolution.rootProbeFinished(false, true)
        assertEquals("a refresh timeout is not evidence of root loss", known, resolution.root(grants, 10001))
        assertFalse("known root must never reopen", resolution.startServiceRootDiscovery())
        assertFalse(resolution.canRetryRoot())
        resolution.rootProbeStarted()
        resolution.rootProbeFinished(false, false)
        assertEquals("a definitive denial still drops root", AccessLevel.APP, resolution.root(grants, 10001).level)
        assertTrue(resolution.root(grants, 10001).resolved)
        assertFalse("definitive loss consumes the detached handoff", resolution.startServiceRootDiscovery())
    }

    @Test fun knownRootSurvivesRefreshTimeoutWithoutDetachedClose() {
        val resolution = AccessResolution()
        resolution.rootProbeStarted()
        resolution.rootProbeFinished(true, false)
        val known = resolution.root(grants, 10001)
        resolution.rootProbeStarted()
        resolution.rootProbeFinished(false, true)
        assertEquals("ordinary refresh timeout keeps published root", known, resolution.root(grants, 10001))
    }

    @Test fun attachedRootModeSwitchConsumesStrandedDetachedHandoffOnce() {
        val resolution = AccessResolution()
        resolution.rootProbeStarted()
        resolution.finishRootDiscovery(detached = true)
        resolution.rootProbeFinished(false, true) // SQ-119 S7: result arrives after switching away from ROOT.
        resolution.setServiceAttached(true) // Service attaches in Shizuku mode; no root reopen yet.
        assertTrue(resolution.root(grants, 10001).resolved)
        assertTrue("switching back to ROOT consumes the stranded handoff", resolution.startRootModeDiscovery())
        assertFalse("mode-switch handoff is consumed only once", resolution.startRootModeDiscovery())
        repeat(4) { attempt ->
            resolution.rootProbeStarted()
            resolution.rootProbeFinished(false, true)
            assertEquals("mode switch gets one initial probe plus three retries", attempt < 3, resolution.canRetryRoot())
        }
        assertTrue(resolution.root(grants, 10001).resolved)
        assertFalse("exhaustion cannot acquire a second budget", resolution.startRootModeDiscovery())
    }

    @Test fun detachedRootModeSwitchCannotConsumeServiceHandoff() {
        val resolution = AccessResolution()
        resolution.rootProbeStarted()
        resolution.finishRootDiscovery(detached = true)
        resolution.rootProbeFinished(false, true)
        resolution.setServiceAttached(true)
        resolution.setServiceAttached(false)
        assertFalse("detachment removes mode-switch service authority", resolution.startRootModeDiscovery())
        assertTrue("the next service still owns the unconsumed handoff", resolution.startServiceRootDiscovery())
    }

    @Test fun attachedRootModeSwitchDoesNotReopenKnownRootOrDefinitiveDenial() {
        for (available in listOf(false, true)) {
            val resolution = AccessResolution()
            resolution.setServiceAttached(true)
            resolution.rootProbeStarted()
            resolution.finishRootDiscovery(detached = true)
            resolution.rootProbeFinished(available, false)
            assertFalse("definitive answers do not reopen on a mode switch", resolution.startRootModeDiscovery())
        }
    }

    @Test fun attachmentDoesNotResetAnOpenRootBudget() {
        val resolution = AccessResolution()
        repeat(3) {
            resolution.rootProbeStarted()
            resolution.rootProbeFinished(false, true)
        }
        val before = resolution.root(grants, 10001)
        assertFalse("only closed discovery can reopen", resolution.startServiceRootDiscovery())
        assertEquals(before, resolution.root(grants, 10001))
        resolution.rootProbeStarted()
        resolution.rootProbeFinished(false, true)
        assertTrue(resolution.root(grants, 10001).resolved)
        assertFalse(resolution.canRetryRoot())
    }

    @Test fun attachmentReopensAfterAnInFlightDetachedProbeTimesOut() {
        val resolution = AccessResolution()
        resolution.rootProbeStarted()
        resolution.finishRootDiscovery(detached = true)
        resolution.rootProbeFinished(false, true)
        assertTrue("a detached in-flight timeout settles its window", resolution.root(grants, 10001).resolved)
        assertTrue("the late detached timeout gives the service a fresh budget", resolution.startServiceRootDiscovery())
        repeat(4) { attempt ->
            resolution.rootProbeStarted()
            resolution.rootProbeFinished(false, true)
            assertEquals("fresh initial probe plus three retries", attempt < 3, resolution.canRetryRoot())
        }
        assertTrue(resolution.root(grants, 10001).resolved)
    }

    @Test fun attachmentDoesNotReopenAfterAnInFlightDetachedProbeSucceeds() {
        val resolution = AccessResolution()
        resolution.rootProbeStarted()
        resolution.finishRootDiscovery(detached = true)
        resolution.rootProbeFinished(true, false)
        assertFalse("the late root grant needs no service probe", resolution.startServiceRootDiscovery())
        assertEquals(AccessLevel.ROOT, resolution.root(grants, 10001).level)
        assertTrue(resolution.root(grants, 10001).resolved)
    }

    @Test fun attachmentDoesNotReopenKnownRootAfterDetachedClose() {
        val resolution = AccessResolution()
        resolution.rootProbeStarted()
        resolution.rootProbeFinished(true, false)
        resolution.finishRootDiscovery(detached = true)
        assertFalse("known root needs no service probe", resolution.startServiceRootDiscovery())
        val state = resolution.root(grants, 10001)
        assertEquals(AccessLevel.ROOT, state.level)
        assertTrue(state.resolved)
    }

    @Test fun attachmentDoesNotReopenAnExhaustedRootBudget() {
        val resolution = AccessResolution()
        repeat(4) {
            resolution.rootProbeStarted()
            resolution.rootProbeFinished(false, true)
        }
        resolution.finishRootDiscovery(detached = true)
        assertFalse("four timeouts cannot acquire another service budget", resolution.startServiceRootDiscovery())
        assertFalse("no further root attempt is allowed", resolution.canRetryRoot())
        assertTrue(resolution.root(grants, 10001).resolved)
    }

    @Test fun attachmentPreservesKnownRootAndShizukuDiscoveryFacts() {
        val resolution = AccessResolution()
        resolution.startDiscovery(0)
        val absent = ShizukuState(AccessLevel.NONE, Reason.SHIZUKU_NOT_RUNNING, null)
        resolution.rootProbeStarted()
        resolution.rootProbeFinished(true, false)
        resolution.finishRootDiscovery(detached = true)
        val before = resolution.root(grants, 10001)
        var attachProbes = 0
        if (resolution.startServiceRootDiscovery()) {
            attachProbes++
            resolution.rootProbeStarted()
            resolution.rootProbeFinished(false, true)
        }
        assertEquals("a timed-out attach probe must not lose known root", before, resolution.root(grants, 10001))
        assertEquals("attachment never probes known root", 0, attachProbes)
        assertFalse(resolution.shizuku(absent, grants, 10001, 9_999).resolved)
        assertTrue("the original Shizuku deadline is unchanged", resolution.shizuku(absent, grants, 10001, 10_000).resolved)
        resolution.sawBinder()
        resolution.finishRootDiscovery(detached = true)
        assertFalse("known root still cannot reopen after binder discovery", resolution.startServiceRootDiscovery())
        assertTrue("binder-seen remains process-owned", resolution.shizuku(absent, grants, 10001, 1).resolved)
    }

    @Test fun attachmentDoesNotReopenServiceOwnedTimeoutClose() {
        val resolution = AccessResolution()
        resolution.rootProbeStarted()
        resolution.rootProbeFinished(false, true)
        resolution.finishRootDiscovery(detached = false)
        resolution.finishRootDiscovery(detached = true) // A later detached completion cannot undo service ownership.
        repeat(3) {
            assertFalse("service give-up never starts another su budget", resolution.startServiceRootDiscovery())
        }
        assertFalse(resolution.canRetryRoot())
        assertTrue(resolution.root(grants, 10001).resolved)
    }

    @Test fun attachmentDoesNotReopenAnInFlightServiceOwnedTimeoutClose() {
        val resolution = AccessResolution()
        resolution.rootProbeStarted()
        resolution.finishRootDiscovery(detached = false)
        resolution.rootProbeFinished(false, true)
        assertFalse("late service-owned timeout cannot reopen", resolution.startServiceRootDiscovery())
        assertFalse(resolution.canRetryRoot())
        assertTrue(resolution.root(grants, 10001).resolved)
    }

    @Test fun repeatedDetachedClosuresPreserveOneFreshServiceBudget() {
        val resolution = AccessResolution()
        resolution.rootProbeStarted()
        resolution.rootProbeFinished(false, true)
        repeat(3) { resolution.finishRootDiscovery(detached = true) }
        assertTrue("joining detached completions retain the timeout handoff", resolution.startServiceRootDiscovery())
        assertFalse("the timeout handoff is consumed once", resolution.startServiceRootDiscovery())
    }

    @Test fun attachmentDoesNotReopenACompletedRootDenial() {
        val resolution = AccessResolution()
        resolution.rootProbeStarted()
        resolution.rootProbeFinished(false, false)
        resolution.finishRootDiscovery(detached = true)
        assertFalse("a denial is not a detached timeout", resolution.startServiceRootDiscovery())
        assertFalse(resolution.canRetryRoot())
        assertTrue(resolution.root(grants, 10001).resolved)
    }

    @Test fun attachmentDoesNotReopenAfterALaterDenial() {
        val resolution = AccessResolution()
        resolution.rootProbeStarted()
        resolution.rootProbeFinished(false, true)
        resolution.finishRootDiscovery(detached = true)
        resolution.rootProbeStarted()
        resolution.rootProbeFinished(false, false)
        assertFalse("a later denial ends the detached timeout handoff", resolution.startServiceRootDiscovery())
        assertEquals(AccessLevel.APP, resolution.root(grants, 10001).level)
        assertTrue(resolution.root(grants, 10001).resolved)
    }

    @Test fun attachmentDoesNotReopenAfterALaterSuccess() {
        val resolution = AccessResolution()
        resolution.rootProbeStarted()
        resolution.rootProbeFinished(false, true)
        resolution.finishRootDiscovery(detached = true)
        resolution.rootProbeStarted()
        resolution.rootProbeFinished(true, false)
        assertFalse("a later grant ends the detached timeout handoff", resolution.startServiceRootDiscovery())
        assertEquals(AccessLevel.ROOT, resolution.root(grants, 10001).level)
        assertTrue(resolution.root(grants, 10001).resolved)
        resolution.rootProbeStarted()
        resolution.rootProbeFinished(false, true)
        assertFalse("a later refresh timeout cannot revive the consumed handoff", resolution.startServiceRootDiscovery())
    }

    @Test fun modeSwitchAfterStaleDetachedCloseGetsFullBudget() {
        val resolution = AccessResolution()
        resolution.finishRootDiscovery(detached = true)
        assertFalse("a close without a root probe has no timeout handoff", resolution.startServiceRootDiscovery())
        repeat(4) { attempt ->
            resolution.rootProbeStarted()
            resolution.rootProbeFinished(false, true)
            assertEquals("a fresh mode switch gets initial probe plus three retries", attempt < 3, resolution.canRetryRoot())
        }
        assertTrue(resolution.root(grants, 10001).resolved)
        assertFalse("the mode switch budget is not a detached timeout", resolution.startServiceRootDiscovery())
    }

    @Test fun freshServiceProbeCanRecoverRootAfterDetachedTimeout() {
        val resolution = AccessResolution()
        resolution.rootProbeStarted()
        resolution.rootProbeFinished(false, true)
        resolution.finishRootDiscovery(detached = true)
        assertTrue("the detached timeout hands off a fresh service budget", resolution.startServiceRootDiscovery())
        resolution.rootProbeStarted()
        resolution.rootProbeFinished(true, false)
        val state = resolution.root(grants, 10001)
        assertEquals(AccessLevel.ROOT, state.level)
        assertTrue(state.resolved)
        assertFalse(state.rootProbeTimedOut)
        assertFalse(resolution.canRetryRoot())
    }

    @Test fun serviceAttachReopensClosedRootDiscoveryWithFourFreshAttempts() {
        val resolution = AccessResolution()
        resolution.rootProbeStarted()
        resolution.rootProbeFinished(false, true)
        resolution.finishRootDiscovery(detached = true)
        assertTrue("the detached timeout settles its window", resolution.root(grants, 10001).resolved)
        assertFalse("detached receivers cannot retry", resolution.canRetryRoot())
        assertFalse(resolution.root(grants, 10001).rootProbeTimedOut)

        assertTrue(resolution.startServiceRootDiscovery())
        assertFalse("a new service gets unresolved discovery", resolution.root(grants, 10001).resolved)
        assertFalse("a fresh owner has not timed out yet", resolution.root(grants, 10001).rootProbeTimedOut)
        repeat(4) { attempt ->
            resolution.rootProbeStarted()
            resolution.rootProbeFinished(false, true)
            val state = resolution.root(grants, 10001)
            assertEquals("initial probe plus three retries", attempt < 3, resolution.canRetryRoot())
            assertEquals("timeouts belong to the fresh owner", attempt < 3, state.rootProbeTimedOut)
            assertEquals("the fourth timeout settles again", attempt == 3, state.resolved)
        }
        assertEquals(AccessLevel.APP, resolution.root(grants, 10001).level)
    }
}
