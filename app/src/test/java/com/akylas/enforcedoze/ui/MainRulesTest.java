package com.akylas.enforcedoze.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.akylas.enforcedoze.access.Reason;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * MainActivity's onCreate and onPrepareOptionsMenu decisions, on the plain JVM. Each rule is pinned against
 * the branch it replaced in MainActivity (akadoze-2.0 @ 2724123) over every input combination.
 */
public class MainRulesTest {
    private static final boolean[] BOOLS = {false, true};

    @Test
    public void whenRunsTheActionOnlyForATrueCondition() {
        List<String> ran = new ArrayList<>();
        MainRules.when(false, () -> ran.add("no"));
        MainRules.when(true, () -> ran.add("yes"));
        assertEquals(Collections.singletonList("yes"), ran);
    }

    @Test
    public void aFreshLaunchHandlesItsIntentAndARecreationDoesNot() {
        // onCreate: if (savedInstanceState == null) handleIntent(getIntent());
        List<String> ran = new ArrayList<>();
        Object restored = new Object();
        Object fresh = null;
        MainRules.when(restored == null, () -> ran.add("recreated"));
        MainRules.when(fresh == null, () -> ran.add("fresh"));
        assertEquals(Collections.singletonList("fresh"), ran);
    }

    /** The onCreate branch it replaced: notifications first, else phone state. */
    private static String originalPermissionRequest(boolean notificationsGranted, boolean phoneStateGranted) {
        if (!notificationsGranted) {
            return "notifications";
        } else if (!phoneStateGranted) {
            return "phoneState";
        }
        return null;
    }

    @Test
    public void onePermissionPromptPerLaunchNotificationsFirst() {
        for (boolean notifications : BOOLS) {
            for (boolean phoneState : BOOLS) {
                List<String> asked = new ArrayList<>();
                MainRules.requestFirstMissingPermission(notifications, phoneState,
                        () -> asked.add("notifications"), () -> asked.add("phoneState"));
                String expected = originalPermissionRequest(notifications, phoneState);
                String label = "notifications=" + notifications + " phoneState=" + phoneState;
                assertEquals(label, expected == null ? Collections.emptyList() : Collections.singletonList(expected),
                        asked);
            }
        }
        List<String> asked = new ArrayList<>();
        MainRules.requestFirstMissingPermission(false, false, () -> asked.add("notifications"),
                () -> asked.add("phoneState"));
        assertEquals("Phone state waits for the notification result", Arrays.asList("notifications"), asked);
    }

    @Test
    public void shizukuPermissionIsAskedOnlyInShizukuModeWhenOnlyItIsMissing() {
        for (boolean shizukuMode : BOOLS) {
            for (Reason reason : Reason.values()) {
                boolean original = shizukuMode && reason == Reason.SHIZUKU_PERMISSION_MISSING;
                assertEquals(shizukuMode + " " + reason, original, MainRules.asksShizukuPermission(shizukuMode, reason));
            }
            assertFalse("No Shizuku state reason asks nothing", MainRules.asksShizukuPermission(shizukuMode, null));
        }
        assertTrue(MainRules.asksShizukuPermission(true, Reason.SHIZUKU_PERMISSION_MISSING));
        assertFalse(MainRules.asksShizukuPermission(false, Reason.SHIZUKU_PERMISSION_MISSING));
        assertFalse(MainRules.asksShizukuPermission(true, Reason.SHIZUKU_NOT_RUNNING));
    }

    @Test
    public void lockscreenTimeoutNoticeShowsForATooHighTimeoutUnlessIgnored() {
        for (boolean tooHigh : BOOLS) {
            for (boolean ignored : BOOLS) {
                // onCreate: if (isLockscreenTimeoutValueTooHigh) { if (!ignoreLockscreenTimeout) { snackbar } }
                boolean original = false;
                if (tooHigh) {
                    if (!ignored) original = true;
                }
                assertEquals(tooHigh + " " + ignored, original, MainRules.showsLockscreenTimeoutNotice(tooHigh, ignored));
            }
        }
        assertTrue(MainRules.showsLockscreenTimeoutNotice(true, false));
        assertFalse(MainRules.showsLockscreenTimeoutNotice(true, true));
        assertFalse(MainRules.showsLockscreenTimeoutNotice(false, false));
    }

    @Test
    public void unsupportedDozeItemHidesWhereOemDozeWorksOrNWithoutRoot() {
        for (boolean oem : BOOLS) {
            for (boolean runningOnN : BOOLS) {
                for (boolean su : BOOLS) {
                    // onPrepareOptionsMenu: if (isDozeEnabledByOEM || (isDeviceRunningOnN() && !isSuAvailable)) hide
                    boolean original = oem || (runningOnN && !su);
                    assertEquals(oem + " " + runningOnN + " " + su, original,
                            MainRules.hidesUnsupportedDozeItem(oem, runningOnN, su));
                }
            }
        }
        assertTrue("OEM-enabled Doze hides it even with root", MainRules.hidesUnsupportedDozeItem(true, true, true));
        assertTrue("N+ without root hides it", MainRules.hidesUnsupportedDozeItem(false, true, false));
        assertFalse("N+ with root shows it", MainRules.hidesUnsupportedDozeItem(false, true, true));
        assertFalse("Pre-N without OEM Doze shows it", MainRules.hidesUnsupportedDozeItem(false, false, false));
    }
}
