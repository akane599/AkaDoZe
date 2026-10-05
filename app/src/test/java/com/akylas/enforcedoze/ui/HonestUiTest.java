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

import java.io.File;
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

    private static String source(String path) throws IOException {
        File file = new File(path).isFile() ? new File(path) : new File("app/" + path);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static String between(String text, String start, String end) {
        int from = text.indexOf(start);
        assertTrue("missing " + start, from >= 0);
        int to = text.indexOf(end, from);
        assertTrue("missing " + end, to >= 0);
        return text.substring(from, to);
    }

    @Test
    public void resetResultOutlivesTheSettingsScreenThatStartedIt() throws IOException {
        String reset = between(source("src/main/java/com/akylas/enforcedoze/SettingsActivity.java"),
                "public void resetForceDoze()", "private void dismissResetProgress()");
        assertFalse("an isAdded() gate drops the result (prefs already cleared) when the screen was recreated",
                reset.contains("isAdded()"));
        assertTrue("the result is kept process-wide for whichever Settings screen is showing",
                reset.contains("ResetReport.TRACKER.deliver("));
        assertFalse("the fragment's own handler is cleared in onDestroy, so it can't carry the result",
                reset.contains("mainHandler.post"));
        String settings = source("src/main/java/com/akylas/enforcedoze/SettingsActivity.java");
        String onStart = between(settings, "public void onStart()", "public void onResume()");
        assertTrue("a started screen listens and renders a reset it missed",
                onStart.contains("ResetReport.TRACKER.setListener(resetListener)") && onStart.contains("renderReset()"));
        String finish = between(settings, "private void finishReset()", "private void applyCapabilities(");
        assertTrue("deferred revokes run only after the user confirmed the report, then the rebirth",
                finish.indexOf("ResetReport.TRACKER.confirm()") < finish.indexOf(".finishReset(deferred")
                        && finish.contains("ProcessPhoenix.triggerRebirth("));
        String runtime = source("src/main/java/com/akylas/enforcedoze/service/DozeRuntime.kt");
        String resetJob = between(runtime, "fun resetSystemState(", "fun finishReset(");
        assertFalse("the reset job itself never runs the deferred steps", resetJob.contains("runDeferred"));
    }

    @Test
    public void finishResetPostsUnderTheSameRuntimeLockAsResetSystemState() throws IOException {
        String runtime = source("src/main/java/com/akylas/enforcedoze/service/DozeRuntime.kt");
        assertTrue("resetSystemState queues atomically with worker retirement",
                runtime.contains("@Synchronized\n    fun resetSystemState("));
        assertTrue("finishReset must hold the runtime lock from worker() through post()",
                runtime.contains("@Synchronized\n    fun finishReset("));
    }

    @Test
    public void failedJobNeverClearsPrefsOrClaimsCompletionEvenWithAnEmptySuccessfulRestore() {
        SystemResetResult failed = new SystemResetResult(ResetRestoreOutcome.COMPLETE,
                Collections.emptyList(), Collections.emptyList(), true);
        assertFalse(failed.getComplete());
        assertFalse(ResetReport.complete(failed, true));
        assertFalse("a failed job must not even access the preference store", ResetReport.clearPreferences(null, failed));
    }

    @Test
    public void failedResetJobIsWiredThroughTheBoundaryAndDismissedWithoutRestart() throws IOException {
        String runtime = source("src/main/java/com/akylas/enforcedoze/service/DozeRuntime.kt");
        String job = between(runtime, "val result = SystemReset.runJob(", "callback.onComplete(result)");
        assertTrue("the boundary includes work before SystemReset.run", job.contains("session.recordExit()"));
        assertTrue("both reset catch sites report to the runtime error sink",
                job.contains("onError = onError,\n") && job.contains("}, onError = onError)"));
        assertTrue("the boundary includes reset, reconciliation and the final ledger load",
                job.contains("SystemReset.run(control") && job.contains("controller.reconcile(") && job.contains("store.load()"));
        String report = source("src/main/java/com/akylas/enforcedoze/ui/ResetReport.java");
        String message = between(report, "public static String message(", "private static String step(");
        String failedReturn = "if (result.getFailed()) return context.getString(R.string.reset_failed_not_run);";
        assertTrue("a failed report says the reset did not finish and returns before any restart text",
                message.indexOf(failedReturn) >= 0
                        && message.indexOf(failedReturn) < message.indexOf("R.string.reset_restart_text"));
        assertTrue("a failed job must not show the debt line, the unconfirmed steps or the prefs-failed line",
                message.indexOf(failedReturn) < message.indexOf("R.string.reset_debt_remaining")
                        && message.indexOf(failedReturn) < message.indexOf("R.string.reset_steps_unconfirmed")
                        && message.indexOf(failedReturn) < message.indexOf("R.string.reset_prefs_failed"));
        assertTrue("a non-failed debt result still shows the debt line",
                message.contains("if (result.getRestoreOutcome() == ResetRestoreOutcome.REMAINING_DEBT) {\n            text.append(context.getString(R.string.reset_debt_remaining));"));
        assertFalse("the old late failed return is gone",
                message.contains("if (result.getFailed()) return text.toString();"));
        assertTrue("the new string lives in values/strings.xml",
                source("src/main/res/values/strings.xml").contains("name=\"reset_failed_not_run\""));
        String finish = between(source("src/main/java/com/akylas/enforcedoze/SettingsActivity.java"),
                "private void finishReset()", "private void applyCapabilities(");
        assertTrue("a failed confirmation renders IDLE instead of starting a rebirth",
                finish.contains("if (deferred == null) {\n                renderReset();\n                return;\n            }"));
    }

    @Test
    public void recreatedScreenStillGetsTheReportAndFinishesOnce() {
        ResetReport.Tracker tracker = new ResetReport.Tracker();
        List<String> rendered = new ArrayList<>();
        ResetReport.Tracker.Listener first = () -> rendered.add("first");
        ResetReport.Tracker.Listener second = () -> rendered.add("second");

        assertTrue(tracker.begin());
        assertFalse("one reset per process", tracker.begin());
        tracker.setListener(first);
        // Rotation: the old screen stops after the new one registered; that must not unregister the new one.
        tracker.setListener(second);
        tracker.removeListener(first);
        SystemResetResult result = new SystemResetResult(ResetRestoreOutcome.COMPLETE, Collections.emptyList(),
                Collections.singletonList(ResetCommandId.REVOKE_READ_PHONE_STATE));
        tracker.deliver(result, true);
        tracker.notifyListener();
        assertEquals(Collections.singletonList("second"), rendered);
        assertEquals(ResetReport.Tracker.Phase.REPORTED, tracker.phase());
        assertEquals(result, tracker.result());
        assertTrue(tracker.prefsCleared());

        // No screen showing when it lands: the next one to start renders it from the phase.
        tracker.removeListener(second);
        tracker.notifyListener();
        assertEquals(1, rendered.size());
        assertEquals(ResetReport.Tracker.Phase.REPORTED, tracker.phase());

        assertEquals(Collections.singletonList(ResetCommandId.REVOKE_READ_PHONE_STATE), tracker.confirm());
        assertEquals(ResetReport.Tracker.Phase.FINISHING, tracker.phase());
        assertNull("a second OK (or a re-shown report) cannot run the deferred steps twice", tracker.confirm());
    }

    @Test
    public void deferredStepDoesNotCountAgainstCompleteButIsNotConfirmed() {
        SystemResetResult result = new SystemResetResult(ResetRestoreOutcome.COMPLETE,
                Collections.singletonList(new ResetCommandResult(ResetCommandId.REVOKE_DUMP, ResetCommandOutcome.OK)),
                Collections.singletonList(ResetCommandId.REVOKE_READ_PHONE_STATE));
        assertTrue("complete: restore complete and every executed step OK", ResetReport.complete(result, true));
        assertTrue("the deferred step is not listed as a confirmed step", ResetReport.unconfirmed(result).isEmpty()
                && result.getCommands().size() == 1);
    }

    @Test
    public void titleIsCompleteOnlyWhenNoDeferredStepIsPending() {
        List<ResetCommandResult> ok = Collections.singletonList(
                new ResetCommandResult(ResetCommandId.REVOKE_DUMP, ResetCommandOutcome.OK));
        SystemResetResult pending = new SystemResetResult(ResetRestoreOutcome.COMPLETE, ok,
                Collections.singletonList(ResetCommandId.REVOKE_READ_PHONE_STATE));
        assertEquals(com.akylas.enforcedoze.R.string.reset_almost_done_title, ResetReport.title(pending, true));
        SystemResetResult done = new SystemResetResult(ResetRestoreOutcome.COMPLETE, ok, Collections.emptyList());
        assertEquals(com.akylas.enforcedoze.R.string.reset_complete_dialog_title, ResetReport.title(done, true));
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
