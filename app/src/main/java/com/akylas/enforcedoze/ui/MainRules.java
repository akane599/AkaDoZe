package com.akylas.enforcedoze.ui;

import com.akylas.enforcedoze.access.Reason;

/**
 * Pure decisions for the main screen (no android.*), so they can be JVM-tested. MainActivity can't be
 * hosted under Robolectric (its onCreate starts access discovery), so it only wires these rules.
 */
public final class MainRules {
    private MainRules() { throw new AssertionError(); }

    /** Runs {@code action} only when {@code condition} holds, keeping the Activity glue branch-free. */
    public static void when(boolean condition, Runnable action) {
        if (condition) action.run();
    }

    /** At most one prompt per launch: notifications first, phone state only once notifications are granted. */
    public static void requestFirstMissingPermission(boolean notificationsGranted, boolean phoneStateGranted,
                                                     Runnable requestNotifications, Runnable requestPhoneState) {
        if (!notificationsGranted) requestNotifications.run();
        else if (!phoneStateGranted) requestPhoneState.run();
    }

    /** Shizuku mode asks for Shizuku's permission when Shizuku reports only that permission missing. */
    public static boolean asksShizukuPermission(boolean shizukuMode, Reason shizukuReason) {
        return shizukuMode && shizukuReason == Reason.SHIZUKU_PERMISSION_MISSING;
    }

    /** The lock-screen timeout warning shows for a too-high timeout unless the user chose to ignore it. */
    public static boolean showsLockscreenTimeoutNotice(boolean timeoutTooHigh, boolean ignored) {
        return timeoutTooHigh && !ignored;
    }

    /**
     * The "Doze unsupported" menu item is hidden where Doze is already enabled by the OEM, and on Android N+
     * without root. It is never re-shown here: otherwise the inflated menu's own visibility stands.
     */
    public static boolean hidesUnsupportedDozeItem(boolean dozeEnabledByOem, boolean runningOnN, boolean suAvailable) {
        return dozeEnabledByOem || (runningOnN && !suAvailable);
    }
}
