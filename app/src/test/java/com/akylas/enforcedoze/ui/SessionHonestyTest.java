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
import com.akylas.enforcedoze.service.SelfTestKind;
import com.akylas.enforcedoze.service.SessionMode;

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
        // ...but only the sensor-only session runs there, so the UI offers motion sensors and nothing else.
        assertFalse(AccessUi.sessionBlocked(Feature.MOTION_SENSORS, dump, null));
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

    // --- SQ-118 (1C): sensor-only sessions at APP+DUMP ---

    private static AccessState unresolved(AccessLevel level, boolean dump) {
        return new AccessState(level, null, new Grants(dump, false), null, false);
    }

    @Test
    public void sessionModeWeighsSensorPreferenceGrantsAndResolution() {
        assertEquals(SessionMode.SENSOR_ONLY, AccessUi.sessionMode(state(AccessLevel.APP, true, false), true));
        assertEquals("sensors-off leaves nothing to run", SessionMode.RESTORE_ONLY,
                AccessUi.sessionMode(state(AccessLevel.APP, true, false), false));
        assertEquals("no DUMP, no sensor session", SessionMode.RESTORE_ONLY,
                AccessUi.sessionMode(state(AccessLevel.APP, false, true), true));
        assertEquals(SessionMode.RESTORE_ONLY, AccessUi.sessionMode(state(AccessLevel.NONE, true, true), true));
        assertEquals("still checking access", SessionMode.RESTORE_ONLY,
                AccessUi.sessionMode(unresolved(AccessLevel.APP, true), true));
        assertEquals(SessionMode.FORCE, AccessUi.sessionMode(state(AccessLevel.SHELL, false, false), false));
        assertEquals(SessionMode.FORCE, AccessUi.sessionMode(state(AccessLevel.ROOT, false, false), true));
    }

    @Test
    public void appWithDumpOffersMotionSensorsAndKeepsEveryOtherSessionReason() {
        AccessState dump = state(AccessLevel.APP, true, true);
        assertTrue(AccessUi.offered(Feature.MOTION_SENSORS, dump, false, API));
        assertTrue(AccessUi.offered(Feature.MOTION_SENSORS, dump, true, API));
        for (Feature feature : Feature.values()) {
            if (feature == Feature.MOTION_SENSORS || !AccessUi.isSessionFeature(feature)) continue;
            assertFalse(feature.name(), AccessUi.offered(feature, dump, false, API));
        }
        assertTrue(AccessUi.sessionBlocked(Feature.FORCE_DOZE, dump,
                AccessUi.unavailableReason(Feature.FORCE_DOZE, dump, false, API)));
        assertEquals("a platform limit stays the precise reason", Reason.REQUIRES_ROOT,
                AccessUi.unavailableReason(Feature.SENSOR_PRIVACY_ALL, dump, false, API));
    }

    @Test
    public void appWithoutDumpNamesTheDumpGrantForMotionSensors() {
        AccessState app = state(AccessLevel.APP, false, true);
        Reason reason = AccessUi.unavailableReason(Feature.MOTION_SENSORS, app, false, API);
        assertEquals(Reason.NEEDS_DUMP, reason);
        assertFalse("DUMP is what the user can grant, not Shizuku or root",
                AccessUi.sessionBlocked(Feature.MOTION_SENSORS, app, reason));
        assertFalse(AccessUi.offered(Feature.MOTION_SENSORS, app, false, API));
        AccessState none = state(AccessLevel.NONE, false, false);
        assertTrue(AccessUi.sessionBlocked(Feature.MOTION_SENSORS, none,
                AccessUi.unavailableReason(Feature.MOTION_SENSORS, none, false, API)));
    }

    @Test
    public void accessCardSaysWhatStillRunsBelowShell() {
        assertEquals(com.akylas.enforcedoze.R.string.access_problem_sensors_only,
                AccessUi.sessionProblem(state(AccessLevel.APP, true, false), true));
        assertEquals("DUMP granted but the sensor setting is off", com.akylas.enforcedoze.R.string.access_problem_sensors_off,
                AccessUi.sessionProblem(state(AccessLevel.APP, true, false), false));
        assertEquals(com.akylas.enforcedoze.R.string.access_problem_sessions,
                AccessUi.sessionProblem(state(AccessLevel.APP, false, false), true));
        assertEquals(com.akylas.enforcedoze.R.string.access_problem_sessions,
                AccessUi.sessionProblem(state(AccessLevel.NONE, false, false), true));
    }

    @Test
    public void selfTestsFollowTheirOwnFeatureNotOneSessionsBoolean() {
        assertEquals(Feature.FORCE_DOZE, AccessUi.selfTestFeature(SelfTestKind.DOZE));
        assertEquals(Feature.MOTION_SENSORS, AccessUi.selfTestFeature(SelfTestKind.SENSORS));
        AccessState dump = state(AccessLevel.APP, true, false);
        assertTrue(AccessUi.selfTestOffered(SelfTestKind.SENSORS, dump, false, API));
        assertFalse(AccessUi.selfTestOffered(SelfTestKind.DOZE, dump, false, API));
        assertFalse(AccessUi.selfTestOffered(SelfTestKind.SENSORS, state(AccessLevel.APP, false, false), false, API));
        AccessState shell = state(AccessLevel.SHELL, false, false);
        assertTrue(AccessUi.selfTestOffered(SelfTestKind.SENSORS, shell, true, API));
        assertTrue(AccessUi.selfTestOffered(SelfTestKind.DOZE, shell, true, API));
    }

    // --- SQ-126 (SQ-53 S3): cold-start discovery is "checking", not an access problem ---

    @Test
    public void unresolvedShellOrRootRunsNoSessionsYet() {
        // The same rule as sessionMode: nothing runs until discovery settles.
        assertFalse(AccessUi.sessionsAvailable(unresolved(AccessLevel.SHELL, true)));
        assertFalse(AccessUi.sessionsAvailable(unresolved(AccessLevel.ROOT, true)));
        assertFalse(AccessUi.selfTestOffered(SelfTestKind.DOZE, unresolved(AccessLevel.ROOT, true), false, API));
    }

    @Test
    public void discoveryShowsCheckingInsteadOfNeedsSessionAccess() {
        AccessState[] discovering = {unresolved(AccessLevel.APP, false), unresolved(AccessLevel.APP, true),
                unresolved(AccessLevel.NONE, false), unresolved(AccessLevel.ROOT, true)};
        for (AccessState state : discovering) {
            for (boolean shizukuMode : new boolean[] {false, true}) {
                for (Feature feature : Feature.values()) {
                    Reason reason = AccessUi.unavailableReason(feature, state, shizukuMode, API);
                    if (AccessUi.offered(feature, state, shizukuMode, API)) continue;
                    assertTrue(state + " " + feature + " reads checking, not " + reason,
                            AccessUi.checking(feature, state, reason));
                }
            }
        }
        AccessState app = unresolved(AccessLevel.APP, true);
        assertTrue("the session rule would say Needs Shizuku or root", AccessUi.checking(Feature.FORCE_DOZE, app,
                AccessUi.unavailableReason(Feature.FORCE_DOZE, app, false, API)));
    }

    @Test
    public void aVersionLimitIsFinalEvenWhileChecking() {
        AccessState app = unresolved(AccessLevel.APP, true);
        assertFalse(AccessUi.checking(Feature.APP_SUSPEND, app, Reason.API_TOO_OLD));
        assertFalse(AccessUi.checking(Feature.BIOMETRICS, app, Reason.NOT_EFFECTIVE_ON_THIS_VERSION));
    }

    @Test
    public void aDefinitiveNoneStillNamesTheSessionRule() {
        AccessState none = state(AccessLevel.NONE, false, false);
        Reason reason = AccessUi.unavailableReason(Feature.FORCE_DOZE, none, false, API);
        assertFalse(AccessUi.checking(Feature.FORCE_DOZE, none, reason));
        assertTrue(AccessUi.sessionBlocked(Feature.FORCE_DOZE, none, reason));
        assertEquals(AccessUi.Action.ADB_INSTRUCTIONS, AccessUi.primaryAction(none, false));
        assertFalse("an offered feature is never checking",
                AccessUi.checking(Feature.MOTION_SENSORS, unresolved(AccessLevel.APP, true), null));
    }

    // --- N4: debt notice keys ---

    private static final class Memory implements DebtRules.NoticeGate.Store {
        Set<String> keys = new HashSet<>();
        boolean posted;

        @Override public boolean posted() { return posted; }
        @Override public void setPosted(boolean value) { posted = value; }
        @Override public void cancel() {}

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
