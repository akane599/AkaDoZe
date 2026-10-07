package com.akylas.enforcedoze.ui;

import com.akylas.enforcedoze.access.AccessLevel;
import com.akylas.enforcedoze.access.AccessState;
import com.akylas.enforcedoze.access.Feature;
import com.akylas.enforcedoze.access.Grants;
import com.akylas.enforcedoze.access.Reason;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * SQ-46: the Doze tunables entry (Main toolbar) and screen follow the resolver's TUNABLES verdict through
 * AccessUi.unavailableReason: null means the screen can write tunables at the current access.
 */
public class TunablesGateTest {
    private static final int API = 34;

    private static AccessState state(AccessLevel level, boolean dump, boolean wss) {
        return new AccessState(level, null, new Grants(dump, wss), null);
    }

    private static Reason reason(AccessState state, boolean shizukuMode) {
        return AccessUi.unavailableReason(Feature.TUNABLES, state, shizukuMode, API);
    }

    @Test
    public void shellAndRootWriteTunablesWithoutGrants() {
        assertNull(reason(state(AccessLevel.SHELL, false, false), true));
        assertNull(reason(state(AccessLevel.ROOT, false, false), false));
        assertNull(reason(state(AccessLevel.ROOT, false, false), true));
    }

    @Test
    public void appLevelNeedsTheAdbWriteSecureSettingsGrant() {
        assertEquals(Reason.NEEDS_WRITE_SECURE_SETTINGS, reason(state(AccessLevel.APP, false, false), false));
        assertEquals("DUMP alone doesn't write settings",
                Reason.NEEDS_WRITE_SECURE_SETTINGS, reason(state(AccessLevel.APP, true, false), false));
        assertNull(reason(state(AccessLevel.APP, false, true), false));
        assertNull(reason(state(AccessLevel.APP, true, true), true));
    }

    @Test
    public void noAccessAndOldAndroidAreUnavailableWhateverTheGrants() {
        assertEquals(Reason.NO_ACCESS, reason(state(AccessLevel.NONE, true, true), false));
        assertEquals(Reason.API_TOO_OLD, AccessUi.unavailableReason(Feature.TUNABLES,
                state(AccessLevel.ROOT, true, true), false, 22));
    }

    @Test
    public void shizukuModeNamesTheShizukuProblemTheUserCanFix() {
        AccessState notRunning = new AccessState(AccessLevel.APP, Reason.SHIZUKU_NOT_RUNNING, new Grants(true, false), null);
        AccessState notAuthorized = new AccessState(AccessLevel.APP, Reason.SHIZUKU_PERMISSION_MISSING, new Grants(false, false), null);
        assertEquals(Reason.SHIZUKU_NOT_RUNNING, reason(notRunning, true));
        assertEquals(Reason.SHIZUKU_PERMISSION_MISSING, reason(notAuthorized, true));
        assertEquals("root mode keeps the grant it needs", Reason.NEEDS_WRITE_SECURE_SETTINGS, reason(notRunning, false));
        AccessState granted = new AccessState(AccessLevel.APP, Reason.SHIZUKU_NOT_RUNNING, new Grants(false, true), null);
        assertNull("an adb WRITE_SECURE_SETTINGS grant works without Shizuku", reason(granted, true));
    }

    @Test
    public void tunablesAreNotASessionFeature() {
        // So the entry is offered exactly when the resolver allows it, with no session-access rule on top.
        assertFalse(AccessUi.isSessionFeature(Feature.TUNABLES));
        assertTrue(AccessUi.offered(Feature.TUNABLES, state(AccessLevel.APP, false, true), false, API));
        assertFalse(AccessUi.offered(Feature.TUNABLES, state(AccessLevel.APP, true, false), true, API));
    }
}
