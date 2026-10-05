package com.akylas.enforcedoze.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.akylas.enforcedoze.access.AccessLevel;
import com.akylas.enforcedoze.access.Feature;
import com.akylas.enforcedoze.access.Reason;
import com.akylas.enforcedoze.doze.EventType;
import com.akylas.enforcedoze.doze.LedgerEntry;
import com.akylas.enforcedoze.monitor.Coverage;
import com.akylas.enforcedoze.monitor.JournalEvent;
import com.akylas.enforcedoze.monitor.Source;
import com.akylas.enforcedoze.R;
import com.akylas.enforcedoze.service.SelfTestCommand;
import com.akylas.enforcedoze.service.SessionMode;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** SQ-35 repairs of SQ-17/SQ-18 UI logic, through the pure helpers the screens use. */
public class AccessRepairTest {

    // --- SQ-118 (1C): a usable downgrade is not "paused" ---

    @Test
    public void losingShizukuOrRootDuringASensorOnlyCapableSessionIsADowngradeNotAPause() {
        assertEquals(R.string.notice_sensors_only_title, NoticeSink.accessLossTitle(SessionMode.SENSOR_ONLY));
        assertEquals(R.string.notice_sensors_only_shizuku_text, NoticeSink.accessLossText(SessionMode.SENSOR_ONLY, true));
        assertEquals(R.string.notice_sensors_only_root_text, NoticeSink.accessLossText(SessionMode.SENSOR_ONLY, false));
    }

    @Test
    public void losingEverySessionFeatureIsStillAPause() {
        assertEquals(R.string.notice_paused_title, NoticeSink.accessLossTitle(SessionMode.RESTORE_ONLY));
        assertEquals(R.string.notice_paused_shizuku_text, NoticeSink.accessLossText(SessionMode.RESTORE_ONLY, true));
        assertEquals(R.string.notice_paused_root_text, NoticeSink.accessLossText(SessionMode.RESTORE_ONLY, false));
    }

    @Test
    public void accessLossNoticeWeighsTheLiveGrantAndTheSensorSetting() {
        assertEquals(SessionMode.SENSOR_ONLY, NoticeSink.modeAfterLoss(AccessLevel.APP, true, true));
        assertEquals(SessionMode.RESTORE_ONLY, NoticeSink.modeAfterLoss(AccessLevel.APP, false, true));
        assertEquals(SessionMode.RESTORE_ONLY, NoticeSink.modeAfterLoss(AccessLevel.APP, true, false));
        assertEquals(SessionMode.RESTORE_ONLY, NoticeSink.modeAfterLoss(AccessLevel.NONE, true, true));
    }

    // --- FIX-1 / NIT-1: the Shizuku wait decision ---

    @Test
    public void shizukuWaitRevertsOnceThePromptIsSettledAndPermissionIsStillMissing() {
        assertEquals(ModeSwitchRules.ShizukuWait.REVERT_DENIED, ModeSwitchRules.shizukuWait(
                true, AccessLevel.NONE, Reason.SHIZUKU_PERMISSION_MISSING, true));
    }

    @Test
    public void shizukuWaitKeepsWaitingWhileThePromptCanStillAnswer() {
        assertEquals(ModeSwitchRules.ShizukuWait.KEEP, ModeSwitchRules.shizukuWait(
                true, AccessLevel.NONE, Reason.SHIZUKU_PERMISSION_MISSING, false));
    }

    @Test
    public void shizukuWaitRevertsWhenShizukuStopsMidWait() {
        assertEquals(ModeSwitchRules.ShizukuWait.REVERT_NOT_RUNNING, ModeSwitchRules.shizukuWait(
                true, AccessLevel.NONE, Reason.SHIZUKU_NOT_RUNNING, false));
    }

    @Test
    public void shizukuWaitCompletesFromTheTransportAndIgnoresNoPendingSwitch() {
        assertEquals(ModeSwitchRules.ShizukuWait.GRANTED, ModeSwitchRules.shizukuWait(
                true, AccessLevel.SHELL, null, true));
        assertEquals(ModeSwitchRules.ShizukuWait.NONE, ModeSwitchRules.shizukuWait(
                false, AccessLevel.NONE, Reason.SHIZUKU_PERMISSION_MISSING, true));
    }

    // --- FIX-2: granted only from the Shizuku transport ---

    @Test
    public void stalePublishedRootNeverCompletesASwitchToShizuku() {
        // Published state still says ROOT (previous mode), Shizuku itself reports PERMISSION_MISSING (NONE).
        assertEquals(AccessLevel.NONE, ModeSwitchRules.selectedLevel("shizuku", AccessLevel.ROOT, AccessLevel.NONE));
        assertFalse(ModeSwitchRules.switchReady("shizuku", "shizuku", AccessLevel.ROOT, AccessLevel.NONE));
        assertTrue(ModeSwitchRules.switchReady("shizuku", "shizuku", AccessLevel.ROOT, AccessLevel.SHELL));
        assertTrue(ModeSwitchRules.switchReady("root", "root", AccessLevel.ROOT, AccessLevel.NONE));
        assertFalse(ModeSwitchRules.switchReady("root", "root", AccessLevel.APP, AccessLevel.ROOT));
    }

    // --- FIX-3: debt predicate ---

    @Test
    public void entriesOfAnExitStillInFlightAreNotDebt() {
        List<LedgerEntry> inFlight = Arrays.asList(
                new LedgerEntry(Feature.MOTION_SENSORS, "token", "NORMAL", 10L),
                new LedgerEntry(Feature.FORCE_DOZE, null, "false", 11L));
        // sessionActive already cleared on main, exit not yet walked: never attempted, so not debt.
        assertFalse(DebtRules.isDebt(inFlight, false, false));
    }

    @Test
    public void failedOrFlaggedEntriesAndDamageAreDebtOutsideASession() {
        LedgerEntry attempted = new LedgerEntry(Feature.FORCE_DOZE, null, "false", 11L, 1, false);
        LedgerEntry flagged = new LedgerEntry(Feature.MOTION_SENSORS, "token", "NORMAL", 10L, 0, true);
        assertTrue(DebtRules.isDebt(Collections.singletonList(attempted), false, false));
        assertTrue(DebtRules.isDebt(Collections.singletonList(flagged), false, false));
        assertTrue(DebtRules.isDebt(Collections.emptyList(), true, false));
        assertFalse(DebtRules.isDebt(Collections.singletonList(flagged), true, true));
    }

    // --- FIX-4: notice dedupe ---

    private static final class MemoryStore implements DebtRules.NoticeGate.Store {
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

    @Test
    public void identicalRecoveryDebtPostsOnceAndAChangedSetPostsAgain() {
        DebtRules.NoticeGate gate = new DebtRules.NoticeGate(new MemoryStore());
        int[] posts = {0};
        DebtRules.NoticeGate.Poster poster = () -> {
            posts[0]++;
            return true;
        };
        String sensors = DebtRules.key("MOTION_SENSORS", "token");
        for (int i = 0; i < 5; i++) gate.offer(sensors, false, poster);
        assertEquals(1, posts[0]);

        gate.offer(DebtRules.key("FORCE_DOZE", null), false, poster);
        assertEquals(2, posts[0]);
        gate.offer(sensors, false, poster);
        assertEquals(2, posts[0]);

        // Cleared (restored) and lost again later: a new notice.
        gate.clear(sensors);
        gate.offer(sensors, false, poster);
        assertEquals(3, posts[0]);
    }

    @Test
    public void debtShownInAppIsNotPostedAndABlockedPostRetries() {
        DebtRules.NoticeGate gate = new DebtRules.NoticeGate(new MemoryStore());
        int[] posts = {0};
        gate.offer("ACCESS_LOST", true, () -> {
            posts[0]++;
            return true;
        });
        gate.offer("ACCESS_LOST", false, () -> {
            posts[0]++;
            return true;
        });
        assertEquals(0, posts[0]);

        assertFalse(gate.offer("RAISE_DEBT", false, () -> false));
        assertTrue(gate.offer("RAISE_DEBT", false, () -> true));
        gate.clearAll();
        assertTrue(gate.offer("ACCESS_LOST", false, () -> true));
    }

    // --- SQ-36 F6: coverage percentages never round up ---

    @Test
    public void coverageFloorsStatesAndShowsASliverOfUnknown() {
        assertEquals(99, MonitorFormat.wholePercent(Coverage.DEEP_IDLE, 99.9));
        assertEquals(99, MonitorFormat.wholePercent(Coverage.DEEP_IDLE, 99.5));
        assertEquals(0, MonitorFormat.wholePercent(Coverage.LIGHT_IDLE, 0.6));
        assertEquals(MonitorFormat.BELOW_ONE_PERCENT, MonitorFormat.wholePercent(Coverage.UNKNOWN, 0.4));
        assertEquals(0, MonitorFormat.wholePercent(Coverage.UNKNOWN, 0.0));
        assertEquals(2, MonitorFormat.wholePercent(Coverage.UNKNOWN, 1.2));
    }

    // --- SQ-36 F4: raw output is capped per command ---

    @Test
    public void rawOutputShowsOnlyTheHeadOfEachCommand() {
        List<String> stdout = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) stdout.add("line " + i);
        SelfTestCommand command = new SelfTestCommand("dumpsys sensorservice", 0, stdout,
                Collections.singletonList("err"), false);
        List<String> shown = MonitorFormat.cappedOutput(command, MonitorFormat.RAW_LINES_PER_COMMAND);
        assertEquals(200, shown.size());
        assertEquals("line 0", shown.get(0));

        SelfTestCommand small = new SelfTestCommand("id -u", 0, Collections.singletonList("0"),
                Collections.singletonList("warn"), false);
        assertEquals(Arrays.asList("0", "! warn"), MonitorFormat.cappedOutput(small, 200));
    }

    // --- SQ-36 N1: summary only after an applied step ---

    private static JournalEvent event(long at, EventType type, String detail) {
        return new JournalEvent(1, at, at, 7, Source.APP, type, null, null, null, null, null, detail);
    }

    @Test
    public void sessionWithOnlySkippedStepsIsNotEnforced() {
        List<JournalEvent> skipped = Arrays.asList(
                event(1, EventType.SCREEN_OFF, null),
                event(2, EventType.ENTER_STEP, "MOTION_SENSORS"),
                event(3, EventType.SKIPPED, "MOTION_SENSORS: SHIZUKU_PERMISSION_MISSING"),
                event(4, EventType.ENTER_STEP, "FORCE_DOZE"),
                event(5, EventType.SKIPPED, "FORCE_DOZE: SHIZUKU_PERMISSION_MISSING"),
                event(6, EventType.VERIFY, "FORCE_DOZE"), // exit-time restore of an older entry
                event(7, EventType.SCREEN_ON, null));
        assertFalse(MonitorData.hasAppliedStep(skipped));

        List<JournalEvent> applied = Arrays.asList(
                event(1, EventType.SCREEN_OFF, null),
                event(2, EventType.ENTER_STEP, "FORCE_DOZE"),
                event(3, EventType.VERIFY, "FORCE_DOZE"));
        assertTrue(MonitorData.hasAppliedStep(applied));
    }
}
