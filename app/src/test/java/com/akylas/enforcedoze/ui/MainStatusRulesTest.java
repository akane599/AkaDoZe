package com.akylas.enforcedoze.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.akylas.enforcedoze.R;
import com.akylas.enforcedoze.access.AccessLevel;
import com.akylas.enforcedoze.access.AccessState;
import com.akylas.enforcedoze.access.Grants;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** MainActivity's status line and its handling of a published access state, on the plain JVM. */
public class MainStatusRulesTest {

    private static AccessState state(AccessLevel level, boolean dump, boolean writeSecureSettings) {
        return new AccessState(level, null, new Grants(dump, writeSecureSettings), null);
    }

    @Test
    public void everyMainStatusHasItsOwnStatusLine() {
        Map<AccessUi.ServiceStatus, Integer> expected = new EnumMap<>(AccessUi.ServiceStatus.class);
        expected.put(AccessUi.ServiceStatus.FORCING, R.string.service_active);
        expected.put(AccessUi.ServiceStatus.SENSORS_ONLY, R.string.service_sensors_only);
        expected.put(AccessUi.ServiceStatus.PASSIVE, R.string.service_needs_session_access);
        expected.put(AccessUi.ServiceStatus.CHECKING, R.string.service_checking);
        expected.put(AccessUi.ServiceStatus.UNAVAILABLE, R.string.service_disabled);
        expected.put(AccessUi.ServiceStatus.RESOLVING, R.string.access_status_checking);
        expected.put(AccessUi.ServiceStatus.INACTIVE, R.string.service_inactive);
        assertEquals("every status is pinned", AccessUi.ServiceStatus.values().length, expected.size());
        for (AccessUi.ServiceStatus status : AccessUi.ServiceStatus.values()) {
            assertEquals(status.name(), (int) expected.get(status), AccessUi.mainStatusText(status));
        }
    }

    @Test
    public void accessUpdateMatchesTheMainScreenRulesOverTheWholeMatrix() {
        for (AccessLevel level : AccessLevel.values()) {
            for (boolean shizukuMode : new boolean[] {false, true}) {
                for (boolean dump : new boolean[] {false, true}) {
                    for (boolean wss : new boolean[] {false, true}) {
                        for (boolean requested : new boolean[] {false, true}) {
                            checkUpdate(level, shizukuMode, dump, wss, requested);
                        }
                    }
                }
            }
        }
    }

    private static void checkUpdate(AccessLevel level, boolean shizukuMode, boolean dump, boolean wss, boolean requested) {
        String label = level + " shizukuMode=" + shizukuMode + " dump=" + dump + " wss=" + wss + " requested=" + requested;
        AccessUi.AccessUpdate update = new AccessUi.AccessUpdate(state(level, dump, wss), shizukuMode, requested);
        boolean su = level == AccessLevel.ROOT;
        boolean shizuku = shizukuMode && (level == AccessLevel.SHELL || level == AccessLevel.ROOT);
        assertEquals(label, su, update.su);
        assertEquals(label, shizuku, update.shizuku);
        assertEquals(label, dump, update.dump);
        assertEquals(label, wss, update.writeSecureSettings);
        assertEquals(label, su || shizuku || dump, update.usable);
        assertEquals(label, su || shizuku, update.helpersRequested);
        assertEquals(label, (su || shizuku) && !requested, ran(update));
        assertEquals(label, update.usable ? "setup" : "status", rendered(update));
    }

    private static boolean ran(AccessUi.AccessUpdate update) {
        List<String> calls = new ArrayList<>();
        update.requestHelpers(() -> calls.add("grant"));
        return !calls.isEmpty();
    }

    private static String rendered(AccessUi.AccessUpdate update) {
        List<String> calls = new ArrayList<>();
        update.render(() -> calls.add("setup"), () -> calls.add("status"));
        assertEquals(1, calls.size());
        return calls.get(0);
    }

    @Test
    public void helpersAreRequestedOncePerPrivilegedStretchAndAgainAfterAccessDrops() {
        boolean requested = false;
        List<Boolean> grants = new ArrayList<>();
        AccessState[] sequence = {
                state(AccessLevel.ROOT, false, false),
                state(AccessLevel.ROOT, true, true),
                state(AccessLevel.APP, true, false),
                state(AccessLevel.ROOT, true, true),
        };
        for (AccessState access : sequence) {
            AccessUi.AccessUpdate update = new AccessUi.AccessUpdate(access, false, requested);
            requested = update.helpersRequested;
            grants.add(ran(update));
        }
        assertEquals(Arrays.asList(true, false, false, true), grants);
        assertFalse("dropping to APP resets the flag", new AccessUi.AccessUpdate(sequence[2], false, true).helpersRequested);
    }

    @Test
    public void rootModeShellLevelIsNeitherSuNorShizukuButDumpStillMakesItUsable() {
        AccessUi.AccessUpdate update = new AccessUi.AccessUpdate(state(AccessLevel.SHELL, true, false), false, false);
        assertFalse(update.su);
        assertFalse(update.shizuku);
        assertTrue(update.usable);
        assertFalse(ran(update));
        assertFalse(AccessUi.shizukuAvailable(AccessLevel.APP, true));
        assertTrue(AccessUi.shizukuAvailable(AccessLevel.ROOT, true));
    }
}
