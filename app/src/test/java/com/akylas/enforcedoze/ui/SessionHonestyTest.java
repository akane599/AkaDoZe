package com.akylas.enforcedoze.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.akylas.enforcedoze.access.AccessLevel;
import com.akylas.enforcedoze.access.AccessState;
import com.akylas.enforcedoze.access.CapabilityResolver;
import com.akylas.enforcedoze.access.Feature;
import com.akylas.enforcedoze.access.FeatureStatus;
import com.akylas.enforcedoze.access.Grants;
import com.akylas.enforcedoze.access.Reason;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/** SQ-40: session availability needs SHELL/ROOT (F-B) and debt notices re-arm on a clean ledger (N4). */
public class SessionHonestyTest {
    private static final int API = 34;

    private static AccessState state(AccessLevel level, boolean dump, boolean wss) {
        return new AccessState(level, null, new Grants(dump, wss), null);
    }

    // --- F-B: the sessions-available rule ---

    @Test
    public void sessionsNeedShellOrRoot() {
        assertFalse(AccessUi.sessionsAvailable(state(AccessLevel.APP, true, false)));
        assertFalse(AccessUi.sessionsAvailable(state(AccessLevel.APP, false, true)));
        assertFalse(AccessUi.sessionsAvailable(state(AccessLevel.NONE, false, false)));
        assertTrue(AccessUi.sessionsAvailable(state(AccessLevel.SHELL, false, false)));
        assertTrue(AccessUi.sessionsAvailable(state(AccessLevel.ROOT, false, false)));
    }

    @Test
    public void appLevelGrantsDoNotOfferSessionFeaturesTheResolverAllows() {
        AccessState dump = state(AccessLevel.APP, true, false);
        AccessState wss = state(AccessLevel.APP, false, true);
        // The resolver keeps these available below SHELL for the restore path...
        assertEquals(FeatureStatus.Available.INSTANCE,
                CapabilityResolver.status(Feature.MOTION_SENSORS, AccessLevel.APP, API, dump.getGrants()));
        assertEquals(FeatureStatus.Available.INSTANCE,
                CapabilityResolver.status(Feature.BIOMETRICS, AccessLevel.APP, API, wss.getGrants()));
        // ...but no session runs there, so the UI must not offer them as working.
        assertTrue(AccessUi.sessionBlocked(Feature.MOTION_SENSORS, dump, null));
        assertTrue(AccessUi.sessionBlocked(Feature.BIOMETRICS, wss, null));
        assertTrue(AccessUi.sessionBlocked(Feature.WIFI, dump, Reason.NO_ACCESS));
        assertTrue(AccessUi.sessionBlocked(Feature.FORCE_DOZE, dump, Reason.SHIZUKU_NOT_RUNNING));
    }

    @Test
    public void shellAndRootDoNotSessionBlock() {
        for (AccessLevel level : new AccessLevel[] {AccessLevel.SHELL, AccessLevel.ROOT}) {
            AccessState privileged = state(level, true, true);
            for (Feature feature : Feature.values()) {
                assertFalse(level + " " + feature, AccessUi.sessionBlocked(feature, privileged, null));
            }
        }
    }

    @Test
    public void nonSessionScreensKeepResolverAvailabilityAtAppLevel() {
        AccessState app = state(AccessLevel.APP, true, true);
        assertFalse(AccessUi.sessionBlocked(Feature.TUNABLES, app, null));
        assertFalse(AccessUi.sessionBlocked(Feature.WHITELIST_EDIT, app, Reason.NO_ACCESS));
        assertFalse(AccessUi.sessionBlocked(Feature.DOZE_STATE_READ, app, null));
    }

    @Test
    public void aFeaturesOwnPlatformLimitStaysTheReason() {
        AccessState app = state(AccessLevel.APP, true, true);
        assertFalse(AccessUi.sessionBlocked(Feature.SENSOR_PRIVACY_ALL, app, Reason.REQUIRES_ROOT));
        assertFalse(AccessUi.sessionBlocked(Feature.APP_SUSPEND, app, Reason.API_TOO_OLD));
    }

    // --- N4: debt notice keys ---

    private static final class Memory implements DebtRules.NoticeGate.Store {
        Set<String> keys = new HashSet<>();

        @Override
        public Set<String> load() {
            return keys;
        }

        @Override
        public void save(Set<String> next) {
            keys = new HashSet<>(next);
        }
    }

    private static final class CountingPoster implements DebtRules.NoticeGate.Poster {
        int posts;

        @Override
        public boolean post() {
            posts++;
            return true;
        }
    }

    @Test
    public void runtimeDebtKeysClearWhenNoDebtIsObservedAndNotifyAgain() {
        DebtRules.NoticeGate gate = new DebtRules.NoticeGate(new Memory());
        CountingPoster poster = new CountingPoster();
        String[] runtime = {"TEARDOWN_TIMEOUT", "LEDGER_DAMAGED", "SAFETY_READ_UNAVAILABLE"};
        for (String key : runtime) assertTrue(key, gate.offer(DebtRules.key(key, null), false, poster));
        // The user swiped the notices away: a recurrence is deduped while the debt stands.
        for (String key : runtime) assertFalse(key, gate.offer(DebtRules.key(key, null), false, poster));
        gate.ledgerChecked(true);
        for (String key : runtime) assertFalse(key, gate.offer(DebtRules.key(key, null), false, poster));

        gate.ledgerChecked(false);
        for (String key : runtime) assertTrue(key, gate.offer(DebtRules.key(key, null), false, poster));
        assertEquals(6, poster.posts);
    }

    @Test
    public void perEntryDebtIsDedupedPerEntryAndRearmsAfterTheDebtClears() {
        DebtRules.NoticeGate gate = new DebtRules.NoticeGate(new Memory());
        CountingPoster poster = new CountingPoster();
        String wifi = DebtRules.key(Feature.WIFI.name(), null);
        String foo = DebtRules.key(Feature.APP_SUSPEND.name(), "com.example.foo");
        String bar = DebtRules.key(Feature.APP_SUSPEND.name(), "com.example.bar");
        assertTrue(gate.offer(wifi, false, poster));
        assertTrue(gate.offer(foo, false, poster));
        assertTrue("another entry of the same feature is its own item", gate.offer(bar, false, poster));
        assertFalse(gate.offer(foo, false, poster));
        assertFalse(gate.offer(wifi, false, poster));

        gate.ledgerChecked(false);
        assertTrue(gate.offer(wifi, false, poster));
        assertTrue(gate.offer(foo, false, poster));
        assertTrue(gate.offer(bar, false, poster));
    }

    @Test
    public void eventRaisedDebtIsNotClearedByACleanLedger() {
        Memory store = new Memory();
        DebtRules.NoticeGate gate = new DebtRules.NoticeGate(store);
        CountingPoster poster = new CountingPoster();
        // ACCESS_LOST clears when access returns; SafetyNet readback debts clear on their verified restore.
        for (String key : new String[] {"ACCESS_LOST", "RAISE_DEBT", "RESTORE_SENSORS", "UNFORCE"}) {
            assertTrue(gate.offer(key, false, poster));
        }
        gate.ledgerChecked(false);
        assertEquals(4, store.keys.size());
        assertFalse(gate.offer("ACCESS_LOST", false, poster));
    }
}
