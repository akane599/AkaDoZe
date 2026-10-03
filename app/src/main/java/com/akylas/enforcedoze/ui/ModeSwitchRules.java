package com.akylas.enforcedoze.ui;

import com.akylas.enforcedoze.access.AccessLevel;
import com.akylas.enforcedoze.access.Reason;

/** Pure decisions for the Settings execution-mode switch (no android.*), so they can be JVM-tested. */
public final class ModeSwitchRules {
    private ModeSwitchRules() {}

    public enum ShizukuWait { NONE, KEEP, GRANTED, REVERT_DENIED, REVERT_NOT_RUNNING }

    /**
     * Decides a pending switch to Shizuku from the Shizuku transport itself, never from the published
     * access level: that can still be a stale ROOT state from the previous mode.
     *
     * @param promptSettled the Activity came back to the foreground after the prompt was requested
     *                      (or was recreated meanwhile), so no permission prompt is in flight anymore.
     */
    public static ShizukuWait shizukuWait(boolean pending, AccessLevel transportLevel, Reason transportReason,
                                          boolean promptSettled) {
        if (!pending) return ShizukuWait.NONE;
        if (transportLevel != AccessLevel.NONE) return ShizukuWait.GRANTED;
        if (transportReason == Reason.SHIZUKU_NOT_RUNNING) return ShizukuWait.REVERT_NOT_RUNNING;
        return promptSettled ? ShizukuWait.REVERT_DENIED : ShizukuWait.KEEP;
    }

    /** The level that proves the selected transport works: Shizuku's own state in Shizuku mode. */
    public static AccessLevel selectedLevel(String selectedMode, AccessLevel publishedLevel, AccessLevel shizukuLevel) {
        return "shizuku".equals(selectedMode) ? shizukuLevel : publishedLevel;
    }

    /** A pending switch completes once, and only after the selected transport is usable. */
    public static boolean switchReady(String pending, String selectedMode, AccessLevel publishedLevel,
                                      AccessLevel shizukuLevel) {
        if (pending == null || !pending.equals(selectedMode)) return false;
        AccessLevel level = selectedLevel(selectedMode, publishedLevel, shizukuLevel);
        return "shizuku".equals(selectedMode)
                ? level == AccessLevel.SHELL || level == AccessLevel.ROOT
                : level == AccessLevel.ROOT;
    }
}
