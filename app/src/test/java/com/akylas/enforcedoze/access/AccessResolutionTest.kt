package com.akylas.enforcedoze.access

import org.junit.Assert.*
import org.junit.Test

class AccessResolutionTest {
    private val grants = Grants(false, false)

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

    @Test fun attachmentCountsAnInheritedProbeInTheFreshBudget() {
        val resolution = AccessResolution()
        resolution.rootProbeStarted()
        resolution.finishRootDiscovery()
        assertTrue(resolution.startServiceRootDiscovery())
        resolution.rootProbeFinished(false, true)
        assertTrue("an inherited timeout allows the service to retry", resolution.canRetryRoot())
        repeat(3) {
            resolution.rootProbeStarted()
            resolution.rootProbeFinished(false, true)
        }
        assertTrue("inherited probe plus three retries settles", resolution.root(grants, 10001).resolved)
        assertFalse(resolution.canRetryRoot())
    }

    @Test fun attachmentPreservesKnownRootAndShizukuDiscoveryFacts() {
        val resolution = AccessResolution()
        resolution.startDiscovery(0)
        val absent = ShizukuState(AccessLevel.NONE, Reason.SHIZUKU_NOT_RUNNING, null)
        resolution.rootProbeStarted()
        resolution.rootProbeFinished(true, false)
        resolution.finishRootDiscovery()
        val before = resolution.root(grants, 10001)
        assertTrue(resolution.startServiceRootDiscovery())
        resolution.rootProbeStarted()
        assertEquals("a known root grant is not artificially withdrawn", before, resolution.root(grants, 10001))
        assertFalse(resolution.shizuku(absent, grants, 10001, 9_999).resolved)
        assertTrue("the original Shizuku deadline is unchanged", resolution.shizuku(absent, grants, 10001, 10_000).resolved)
        resolution.sawBinder()
        resolution.finishRootDiscovery()
        resolution.startServiceRootDiscovery()
        assertTrue("binder-seen remains process-owned", resolution.shizuku(absent, grants, 10001, 1).resolved)
    }

    @Test fun freshServiceProbeCanRecoverRootAfterDetachedTimeout() {
        val resolution = AccessResolution()
        resolution.rootProbeStarted()
        resolution.rootProbeFinished(false, true)
        resolution.finishRootDiscovery()
        resolution.startServiceRootDiscovery()
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
        resolution.finishRootDiscovery()
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
