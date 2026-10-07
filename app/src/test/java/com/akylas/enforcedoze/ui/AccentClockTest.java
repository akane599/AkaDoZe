package com.akylas.enforcedoze.ui;

import static com.akylas.enforcedoze.ui.AccentClock.Phase.DAY;
import static com.akylas.enforcedoze.ui.AccentClock.Phase.EVENING;
import static com.akylas.enforcedoze.ui.AccentClock.Phase.MORNING;
import static com.akylas.enforcedoze.ui.AccentClock.Phase.NIGHT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class AccentClockTest {
    @Test
    public void everyHourMatchesExpectedPhase() {
        AccentClock.Phase[] expected = {
                NIGHT, NIGHT, NIGHT, NIGHT, NIGHT,
                MORNING, MORNING, MORNING, MORNING, MORNING,
                DAY, DAY, DAY, DAY, DAY, DAY, DAY,
                EVENING, EVENING, EVENING, EVENING, EVENING,
                NIGHT, NIGHT
        };
        assertEquals("Every hour has an expected phase", 24, expected.length);
        for (int hour = 0; hour < expected.length; hour++) {
            assertEquals("hour=" + hour, expected[hour], AccentClock.forHour(hour));
        }
    }

    @Test
    public void phaseBoundariesAndMidnightAreExplicit() {
        assertEquals(NIGHT, AccentClock.forHour(4));
        assertEquals(MORNING, AccentClock.forHour(5));
        assertEquals(MORNING, AccentClock.forHour(9));
        assertEquals(DAY, AccentClock.forHour(10));
        assertEquals(DAY, AccentClock.forHour(16));
        assertEquals(EVENING, AccentClock.forHour(17));
        assertEquals(EVENING, AccentClock.forHour(21));
        assertEquals(NIGHT, AccentClock.forHour(22));
        assertEquals(NIGHT, AccentClock.forHour(23));
        assertEquals(NIGHT, AccentClock.forHour(0));
    }

    @Test
    public void rejectsHoursBelowZero() {
        assertThrows(IllegalArgumentException.class, () -> AccentClock.forHour(-1));
        assertThrows(IllegalArgumentException.class, () -> AccentClock.forHour(Integer.MIN_VALUE));
    }

    @Test
    public void rejectsHoursAboveTwentyThree() {
        assertThrows(IllegalArgumentException.class, () -> AccentClock.forHour(24));
        assertThrows(IllegalArgumentException.class, () -> AccentClock.forHour(Integer.MAX_VALUE));
    }
}
