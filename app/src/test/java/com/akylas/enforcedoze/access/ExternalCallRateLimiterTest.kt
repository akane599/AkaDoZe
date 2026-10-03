package com.akylas.enforcedoze.access

import com.akylas.enforcedoze.access.ExternalControlPolicy.Action
import org.junit.Assert.*
import org.junit.Test

class ExternalCallRateLimiterTest {
    private class Clock(var now: Long = 0) : ExternalCallRateLimiter.Clock {
        override fun elapsedRealtime() = now
    }

    @Test fun rollingWindowDoesNotResetAtAMinuteBoundary() {
        val clock = Clock(59_000)
        val limiter = ExternalCallRateLimiter(clock, 2, 60_000)
        assertTrue(limiter.record(Action.ENABLE_SERVICE).admitted)
        clock.now = 60_000
        assertTrue(limiter.record(Action.ENABLE_SERVICE).admitted)
        assertFalse("wall-minute boundaries do not reset a rolling window", limiter.record(Action.ENABLE_SERVICE).admitted)
        clock.now = 118_999
        assertFalse(limiter.record(Action.ENABLE_SERVICE).admitted)
        clock.now = 119_000
        val next = limiter.record(Action.ENABLE_SERVICE)
        assertTrue("the oldest event expires at exactly one window", next.admitted)
        assertEquals(2L, next.suppressed)
        assertFalse("the newer event still occupies its rolling slot", limiter.record(Action.ENABLE_SERVICE).admitted)
    }

    @Test fun floodIsBoundedAndItsCountIsCoalescedExactlyOnceOnNextAdmission() {
        val clock = Clock()
        val limiter = ExternalCallRateLimiter(clock)
        repeat(10) {
            val event = limiter.record(Action.REAPPLY_DOZE)
            assertTrue(event.admitted)
            assertEquals(0L, event.suppressed)
        }
        repeat(20_000) { assertFalse("flood events never become journal rows", limiter.record(Action.REAPPLY_DOZE).admitted) }
        clock.now = 59_999
        assertFalse(limiter.record(Action.REAPPLY_DOZE).admitted)
        clock.now = 60_000
        val summary = limiter.record(Action.REAPPLY_DOZE)
        assertTrue(summary.admitted)
        assertEquals("all suppressed events appear in one summary", 20_001L, summary.suppressed)
        assertEquals("a summary is not repeated", 0L, limiter.record(Action.REAPPLY_DOZE).suppressed)
    }

    @Test fun actionWindowsAndSuppressedCountsAreIndependent() {
        val clock = Clock()
        val limiter = ExternalCallRateLimiter(clock, 1)
        assertTrue(limiter.record(Action.CHANGE_SETTING).admitted)
        assertFalse(limiter.record(Action.CHANGE_SETTING).admitted)
        clock.now = 1_000
        assertTrue("a denied settings flood cannot suppress a disable request", limiter.record(Action.DISABLE_SERVICE).admitted)
        assertFalse(limiter.record(Action.DISABLE_SERVICE).admitted)
        assertFalse(limiter.record(Action.CHANGE_SETTING).admitted)
        clock.now = 60_000
        assertEquals(2L, limiter.record(Action.CHANGE_SETTING).suppressed)
        assertFalse("disable has its own later window", limiter.record(Action.DISABLE_SERVICE).admitted)
        clock.now = 61_000
        assertEquals(2L, limiter.record(Action.DISABLE_SERVICE).suppressed)
    }
}
