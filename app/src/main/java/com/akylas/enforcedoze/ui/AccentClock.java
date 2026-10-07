package com.akylas.enforcedoze.ui;

/** Pure hour-to-accent decisions, independent of Android and theme resources. */
public final class AccentClock {
    private AccentClock() { throw new AssertionError(); }

    public enum Phase { MORNING, DAY, EVENING, NIGHT }

    private static final Phase[] PHASE_BY_HOUR = {
            Phase.NIGHT, Phase.NIGHT, Phase.NIGHT, Phase.NIGHT, Phase.NIGHT, // 0..4
            Phase.MORNING, Phase.MORNING, Phase.MORNING, Phase.MORNING, Phase.MORNING, // 5..9
            Phase.DAY, Phase.DAY, Phase.DAY, Phase.DAY, Phase.DAY, Phase.DAY, Phase.DAY, // 10..16
            Phase.EVENING, Phase.EVENING, Phase.EVENING, Phase.EVENING, Phase.EVENING, // 17..21
            Phase.NIGHT, Phase.NIGHT // 22..23
    };

    /** Returns the accent phase for a 24-hour clock hour, rejecting values outside 0..23. */
    public static Phase forHour(int hourOfDay) {
        if (hourOfDay < 0 || hourOfDay > 23) {
            throw new IllegalArgumentException("Hour must be between 0 and 23: " + hourOfDay);
        }
        return PHASE_BY_HOUR[hourOfDay];
    }
}
