package com.akylas.enforcedoze.ui;

/** Pure decisions for the Settings screen (no android.*), so they can be JVM-tested. */
public final class SettingsRules {
    /** From five minutes on, the entry delay is long enough to cost battery life. */
    public static final int LONG_DOZE_DELAY_SECONDS = 5 * 60;

    private SettingsRules() { throw new AssertionError(); }

    /** Whether picking this Doze entry delay (in seconds) shows the long-delay warning. */
    public static boolean warnsLongDozeDelay(int delaySeconds) {
        return delaySeconds >= LONG_DOZE_DELAY_SECONDS;
    }
}
