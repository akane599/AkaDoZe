package com.akylas.enforcedoze.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.akylas.enforcedoze.access.AccessLevel;
import com.akylas.enforcedoze.access.AccessState;
import com.akylas.enforcedoze.access.CommandResult;
import com.akylas.enforcedoze.access.Grants;
import com.akylas.enforcedoze.access.Prefs;
import com.akylas.enforcedoze.access.Reason;
import com.akylas.enforcedoze.access.WhitelistParseReason;
import com.akylas.enforcedoze.access.WhitelistParseResult;
import com.akylas.enforcedoze.access.WhitelistParser;
import com.akylas.enforcedoze.doze.CorruptLedgerLine;
import com.akylas.enforcedoze.service.ResetCommandId;
import com.akylas.enforcedoze.service.ResetCommandOutcome;
import com.akylas.enforcedoze.service.ResetCommandResult;
import com.akylas.enforcedoze.service.ResetRestoreOutcome;
import com.akylas.enforcedoze.service.SystemResetResult;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** SQ-51: the UI decisions behind the 2.0 review's honesty fixes, through the pure helpers the screens use. */
public class HonestUiTest {

    private static AccessState state(AccessLevel level, boolean dump) {
        return new AccessState(level, null, new Grants(dump, false), null);
    }

    // --- 1. Main screen: DUMP only is not enforcing ---

    @Test
    public void dumpOnlyServiceIsOnButNotReportedAsEnforcing() {
        assertEquals(AccessUi.ServiceStatus.NEEDS_SESSION_ACCESS,
                AccessUi.serviceStatus(true, state(AccessLevel.APP, true)));
        assertEquals(AccessUi.ServiceStatus.NEEDS_SESSION_ACCESS,
                AccessUi.serviceStatus(true, state(AccessLevel.NONE, false)));
    }

    @Test
    public void shizukuOrRootServiceIsActiveAndOffIsInactive() {
        assertEquals(AccessUi.ServiceStatus.ACTIVE, AccessUi.serviceStatus(true, state(AccessLevel.SHELL, false)));
        assertEquals(AccessUi.ServiceStatus.ACTIVE, AccessUi.serviceStatus(true, state(AccessLevel.ROOT, true)));
        assertEquals(AccessUi.ServiceStatus.INACTIVE, AccessUi.serviceStatus(false, state(AccessLevel.APP, true)));
        // Access not published yet: no claim either way beyond the switch itself.
        assertEquals(AccessUi.ServiceStatus.ACTIVE, AccessUi.serviceStatus(true, null));
    }

    // --- 2. Whitelist: typed reasons, partial reads still list their rows ---

    private static CommandResult result(int exit, boolean timedOut, String... lines) {
        return new CommandResult(exit, Arrays.asList(lines), Collections.emptyList(), 0, timedOut);
    }

    @Test
    public void partialWhitelistReadListsParsableRowsWithItsUnparsedCount() {
        WhitelistParseResult parsed = WhitelistParser.parse(result(0, false,
                "user,com.example.app,10123", "garbage line", "system,com.android.phone,1001"));
        assertEquals(WhitelistParseReason.PARTIALLY_PARSED, parsed.getParseReason());
        assertTrue(WhitelistUi.showsList(parsed.getParseReason()));
        assertEquals(WhitelistUi.Problem.PARTIAL, WhitelistUi.readProblem(parsed.getParseReason()));
        assertEquals(Arrays.asList("com.example.app", "com.android.phone"), parsed.getPackages());
        assertEquals(1, parsed.getUnparsedLineCount());
    }

    @Test
    public void failedAndTimedOutReadsHaveTheirOwnReasonAndNoList() {
        WhitelistParseReason failed = WhitelistParser.parse(result(1, false, "Error: denied")).getParseReason();
        WhitelistParseReason timedOut = WhitelistParser.parse(result(-1, true)).getParseReason();
        assertEquals(WhitelistUi.Problem.READ_FAILED, WhitelistUi.readProblem(failed));
        assertEquals(WhitelistUi.Problem.READ_TIMED_OUT, WhitelistUi.readProblem(timedOut));
        assertFalse(WhitelistUi.showsList(failed));
        assertFalse(WhitelistUi.showsList(timedOut));
    }

    @Test
    public void completeAndEmptyReadsAreNotProblems() {
        assertNull(WhitelistUi.readProblem(null));
        assertNull(WhitelistUi.readProblem(WhitelistParseReason.EMPTY));
        assertTrue(WhitelistUi.showsList(WhitelistParseReason.EMPTY));
    }

    @Test
    public void editProblemsNameAccessThenReadbackThenNoEffect() {
        assertNull(WhitelistUi.editProblem(true, null, null));
        assertEquals(WhitelistUi.Problem.ACCESS, WhitelistUi.editProblem(false, Reason.NEEDS_DUMP, null));
        assertEquals(WhitelistUi.Problem.PARTIAL,
                WhitelistUi.editProblem(false, Reason.UNVERIFIED, WhitelistParseReason.PARTIALLY_PARSED));
        assertEquals(WhitelistUi.Problem.READ_TIMED_OUT,
                WhitelistUi.editProblem(false, Reason.UNVERIFIED, WhitelistParseReason.TIMED_OUT));
        assertEquals(WhitelistUi.Problem.NOT_APPLIED, WhitelistUi.editProblem(false, Reason.UNVERIFIED, null));
        assertEquals(WhitelistUi.Problem.NOT_APPLIED,
                WhitelistUi.editProblem(false, Reason.UNVERIFIED, WhitelistParseReason.EMPTY));
    }

    // --- 2b. SQ-77: whitelist results never touch a finishing or destroyed screen ---

    @Test
    public void whitelistResultsTouchOnlyALiveScreen() {
        assertTrue(WhitelistUi.mayTouchUi(false, false));
        assertFalse(WhitelistUi.mayTouchUi(true, false));
        assertFalse(WhitelistUi.mayTouchUi(false, true));
        assertFalse(WhitelistUi.mayTouchUi(true, true));
    }

    private static final String WHITELIST_GUARD = "if (!WhitelistUi.mayTouchUi(isFinishing(), isDestroyed())) return;";

    private static String whitelistActivitySource() throws IOException {
        return new String(Files.readAllBytes(
                Paths.get("src/main/java/com/akylas/enforcedoze/WhitelistAppsActivity.java")), StandardCharsets.UTF_8);
    }

    @Test
    public void everyWhitelistCompletionCallbackChecksTheScreenFirst() throws IOException {
        String source = whitelistActivitySource();
        int completions = source.split("new Completion<", -1).length - 1;
        assertTrue("expected the read and edit completions", completions >= 2);
        int callbacks = 0;
        for (String marker : new String[] {"public void onSuccess(", "public void onError("}) {
            for (int at = source.indexOf(marker); at >= 0; at = source.indexOf(marker, at + 1)) {
                callbacks++;
                String body = source.substring(source.indexOf('{', at) + 1).trim();
                assertTrue("callback at offset " + at + " must start with the screen guard, got: "
                        + body.substring(0, Math.min(80, body.length())), body.startsWith(WHITELIST_GUARD));
            }
        }
        assertEquals("one onSuccess and one onError per completion", completions * 2, callbacks);
    }

    @Test
    public void whitelistScreenDismissesItsProgressDialogOnDestroy() throws IOException {
        String source = whitelistActivitySource();
        int at = source.indexOf("protected void onDestroy()");
        assertTrue("WhitelistAppsActivity must override onDestroy", at >= 0);
        String body = source.substring(at, source.indexOf("\n    }", at));
        assertTrue(body.contains("dismissProgress();"));
    }

    // --- 3. Settings reset: truthful result, prefs cleared without dropping restore intent ---

    private static SystemResetResult reset(ResetRestoreOutcome restore, ResetCommandOutcome... outcomes) {
        List<ResetCommandResult> commands = new ArrayList<>();
        ResetCommandId[] ids = ResetCommandId.values();
        for (int i = 0; i < ids.length; i++) {
            commands.add(new ResetCommandResult(ids[i], i < outcomes.length ? outcomes[i] : ResetCommandOutcome.OK));
        }
        return new SystemResetResult(restore, commands);
    }

    @Test
    public void resetIsCompleteOnlyWhenRestoredEveryStepConfirmedAndSettingsCleared() {
        assertTrue(ResetReport.complete(reset(ResetRestoreOutcome.COMPLETE), true));
        assertFalse(ResetReport.complete(reset(ResetRestoreOutcome.COMPLETE), false));
        assertFalse(ResetReport.complete(reset(ResetRestoreOutcome.REMAINING_DEBT), true));
        assertFalse(ResetReport.complete(reset(ResetRestoreOutcome.COMPLETE,
                ResetCommandOutcome.OK, ResetCommandOutcome.UNVERIFIED), true));
    }

    @Test
    public void unconfirmedStepsAreListedInOrderWithTheirOutcome() {
        List<ResetCommandResult> problems = ResetReport.unconfirmed(reset(ResetRestoreOutcome.COMPLETE,
                ResetCommandOutcome.FAILED, ResetCommandOutcome.OK, ResetCommandOutcome.TIMEOUT,
                ResetCommandOutcome.UNVERIFIED));
        assertEquals(3, problems.size());
        assertEquals(ResetCommandId.DISABLE_DEVICE_IDLE, problems.get(0).getId());
        assertEquals(ResetCommandOutcome.FAILED, problems.get(0).getOutcome());
        assertEquals(ResetCommandId.REVOKE_DUMP, problems.get(1).getId());
        assertEquals(ResetCommandOutcome.TIMEOUT, problems.get(1).getOutcome());
        assertEquals(ResetCommandId.REVOKE_READ_LOGS, problems.get(2).getId());
        assertEquals(ResetCommandOutcome.UNVERIFIED, problems.get(2).getOutcome());
    }

    @Test
    public void remainingDebtKeepsTheRestoreIntentThroughThePreferenceClear() {
        Map<String, Object> stored = new HashMap<>();
        stored.put(Prefs.EXECUTION_MODE, Prefs.MODE_SHIZUKU);
        stored.put(Prefs.RESTRICT_SENSORS_ALLOW_TOKEN, "token");
        stored.put(Prefs.SERVICE_ENABLED, true);
        stored.put(Prefs.TURN_OFF_WIFI, true);

        Map<String, Object> kept = ResetReport.retained(stored,
                ResetReport.keysToKeep(reset(ResetRestoreOutcome.REMAINING_DEBT)));
        assertEquals(Prefs.MODE_SHIZUKU, kept.get(Prefs.EXECUTION_MODE));
        assertEquals("token", kept.get(Prefs.RESTRICT_SENSORS_ALLOW_TOKEN));
        assertFalse(kept.containsKey(Prefs.SERVICE_ENABLED));
        assertFalse(kept.containsKey(Prefs.TURN_OFF_WIFI));
        assertTrue(ResetReport.keysToKeep(reset(ResetRestoreOutcome.REMAINING_DEBT)).contains(Prefs.RESTORE_LEDGER));
    }

    @Test
    public void completedRestoreClearsEverything() {
        Map<String, Object> stored = new HashMap<>();
        stored.put(Prefs.EXECUTION_MODE, Prefs.MODE_ROOT);
        assertTrue(ResetReport.keysToKeep(reset(ResetRestoreOutcome.COMPLETE)).isEmpty());
        assertTrue(ResetReport.retained(stored, ResetReport.keysToKeep(reset(ResetRestoreOutcome.COMPLETE,
                ResetCommandOutcome.FAILED))).isEmpty());
    }

    // --- 4. Re-picking the active execution mode is not a switch ---

    @Test
    public void rePickingTheActiveModeArmsNoSwitch() {
        assertFalse(ModeSwitchRules.isSwitch(Prefs.MODE_ROOT, Prefs.MODE_ROOT));
        assertFalse(ModeSwitchRules.isSwitch(Prefs.MODE_SHIZUKU, Prefs.MODE_SHIZUKU));
        assertTrue(ModeSwitchRules.isSwitch(Prefs.MODE_ROOT, Prefs.MODE_SHIZUKU));
        assertTrue(ModeSwitchRules.isSwitch(Prefs.MODE_SHIZUKU, Prefs.MODE_ROOT));
    }

    // --- 7. Only damaged records that can't be recovered are offered for dismissal ---

    @Test
    public void dismissibleDamageExcludesRecoverableRecordsAndNamesFeaturesWhenKnown() {
        List<CorruptLedgerLine> lines = Arrays.asList(
                new CorruptLedgerLine(1, "1|WIFI|bad"),
                new CorruptLedgerLine(2, "1|FORCE_DOZE|bad"),
                new CorruptLedgerLine(3, "1|MOTION_SENSORS|bad"),
                new CorruptLedgerLine(4, "garbage"),
                new CorruptLedgerLine(5, "1|WIFI|other"));
        assertEquals(Arrays.asList("WIFI", null), DebtRules.dismissibleDamage(lines));
    }

    @Test
    public void recoverableOnlyDamageOffersNoDismiss() {
        assertTrue(DebtRules.dismissibleDamage(Arrays.asList(
                new CorruptLedgerLine(1, "1|FORCE_DOZE|bad"),
                new CorruptLedgerLine(2, "1|MOTION_SENSORS|bad"))).isEmpty());
        assertTrue(DebtRules.dismissibleDamage(Collections.emptyList()).isEmpty());
    }
}
