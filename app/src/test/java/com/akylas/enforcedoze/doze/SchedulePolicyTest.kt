package com.akylas.enforcedoze.doze

import org.junit.Assert.*
import org.junit.Test

class SchedulePolicyTest {
    @Test fun overnightWrapIncludesStartAndExcludesEnd() {
        val periods = listOf("22:00-06:00")
        assertTrue(SchedulePolicy.isInside(periods, 22 * 60))
        assertTrue(SchedulePolicy.isInside(periods, 23 * 60 + 59))
        assertTrue(SchedulePolicy.isInside(periods, 0))
        assertTrue(SchedulePolicy.isInside(periods, 6 * 60 - 1))
        assertFalse(SchedulePolicy.isInside(periods, 6 * 60))
        assertFalse(SchedulePolicy.isInside(periods, 12 * 60))
        assertEquals(SchedulePolicy.Boundary(360, 1, 420), SchedulePolicy.nextBoundary(periods, 1380))
    }

    @Test fun equalEndpointsAndMalformedPeriodsDoNotCreateAnAllDaySession() {
        val periods = listOf("07:00-07:00", "nope", "24:00-01:00", "01:60-02:00", "-01:00-02:00")
        assertFalse(SchedulePolicy.isInside(periods, 420))
        assertNull(SchedulePolicy.nextBoundary(periods, 420))
        assertNull(SchedulePolicy.parse(null))
        assertTrue(SchedulePolicy.isInside(emptyList(), 420))
        assertNull(SchedulePolicy.nextBoundary(emptyList(), 420))
    }

    @Test fun daytimeBoundariesAndOverlapsUseTheNearestStrictlyFutureBoundary() {
        val periods = listOf("08:00-10:00", "09:00-11:00")
        assertFalse(SchedulePolicy.isInside(periods, 479))
        assertTrue(SchedulePolicy.isInside(periods, 480))
        assertTrue(SchedulePolicy.isInside(periods, 600))
        assertFalse(SchedulePolicy.isInside(periods, 660))
        assertEquals(SchedulePolicy.Boundary(540, 0, 60), SchedulePolicy.nextBoundary(periods, 480))
        assertEquals(SchedulePolicy.Boundary(480, 1, 1260), SchedulePolicy.nextBoundary(periods, 660))
    }

    @Test fun minuteMathHasNoDstHourAssumption() {
        // Spring/fall transitions are resolved by the Android Calendar adapter, not this pure core.
        assertEquals(120, SchedulePolicy.nextBoundary(listOf("01:00-03:00"), 60)?.minutesAway)
        assertEquals(120, SchedulePolicy.nextBoundary(listOf("23:00-01:00"), 1380)?.minutesAway)
        assertEquals(1440, SchedulePolicy.nextBoundary(listOf("01:00-01:01"), 61)?.let {
            // Closest boundary is tomorrow's 01:00, followed by 01:01.
            it.minutesAway + 1
        })
    }
}
