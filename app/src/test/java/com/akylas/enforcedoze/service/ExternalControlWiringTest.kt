package com.akylas.enforcedoze.service

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Pin Android-only orchestration; actual suspend/FGS/broadcast delivery needs a device. */
class ExternalControlWiringTest {
    private fun service() = File("src/main/java/com/akylas/enforcedoze/ForceDozeService.java").readText()
    private fun receiver() = File("src/main/java/com/akylas/enforcedoze/ExternalControlReceiver.java").readText()

    @Test fun settingWriteAndVerifiedOutcomeOnlyRunThroughParsedValueAdmission() {
        val source = receiver()
        val change = source.substringAfter("private void changeSetting(").substringBefore("private void basicControl(")
        assertTrue(change.contains("Admission.setting(current,"))
        assertTrue(change.contains("complete(Permission.DENIED, ExternalCallOutcome.DENIED, reason)"))
        assertTrue(change.contains("this::writeSetting"))
        assertFalse(change.contains("prefs.edit()"))
        val write = source.substringAfter("private void writeSetting(").substringBefore("private void editWhitelist(")
        assertTrue(write.contains("editor.putBoolean"))
        assertTrue(write.contains("editor.putInt"))
        assertTrue(write.indexOf("editor.commit()") in 0 until write.indexOf("ExternalCallOutcome.VERIFIED"))
    }

    @Test fun preAdmissionDenialsCannotConstructRuntime() {
        val entry = receiver().substringAfter("public final void onReceive(")
            .substringBefore("private static String stringExtra(")
        val factory = entry.indexOf("MyApplication.getDozeRuntime(app)")
        val gates = entry.indexOf("decide(prefs, null, null, null)")
        assertTrue("closed gates must run before the runtime factory", gates >= 0 && factory > gates)
        assertFalse("pre-admission denials must not journal through a runtime",
            entry.substringBefore("MyApplication.getDozeRuntime(app)").contains("journal(runtime,"))
    }

    @Test fun admittedWorkRetainsDeadlineCompletionAndReadmission() {
        val source = receiver()
        val entry = source.substringAfter("public final void onReceive(").substringBefore("private Input decode(")
        assertTrue(entry.contains("Admission.run(decide(prefs, null, null, null), () -> decode(intent),"))
        assertTrue(entry.contains("input -> decide(prefs, input.key, input.value, input.pkg)"))
        assertTrue(entry.contains("input -> execute(app, prefs, caller, input)"))
        assertFalse("all runtime construction belongs to admitted work", entry.contains("getDozeRuntime"))
        val work = source.substringAfter("private void execute(").substringBefore("private static String stringExtra(")
        assertTrue(work.indexOf("MyApplication.getDozeRuntime(app)") in 0 until work.indexOf("goAsync()"))
        assertTrue(work.contains("DEADLINES.schedule("))
        assertTrue(work.contains("BUDGET_MS, TimeUnit.MILLISECONDS"))
        assertTrue(work.contains("WORK.execute(call)"))
        assertTrue(source.contains("BUDGET_MS = 9_000"))
        val call = source.substringAfter("private final class Call")
        assertTrue(call.contains("System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BUDGET_MS - 250)"))
        assertTrue(call.contains("SystemClock.elapsedRealtime() + BUDGET_MS - 250"))
        assertTrue(call.contains("live() && decide(prefs, key, value, pkg).getAllowed()"))
        assertTrue(call.contains("Decision current = decide(prefs, key, value, pkg)"))
        val complete = call.substringAfter("void complete(").substringBefore("@Override")
        assertTrue(complete.contains("finished.compareAndSet(false, true)"))
        assertTrue(complete.contains("timeout.cancel(false)"))
        assertTrue(complete.contains("WORK.remove(this)"))
        assertTrue(complete.contains("finally { pending.finish(); }"))
        assertTrue(call.contains("access.controlWithDeadline(command, deadlineNanos,"))
        assertTrue(call.contains("access.controlWithDeadline(read, deadlineNanos, this::admitted)"))
        assertTrue("caller must come only from platform identity", entry.contains("getSentFromPackage()"))
    }

    @Test fun disabledNotificationUsesPrivateInternalEnableReceiver() {
        val notification = File("src/main/java/com/akylas/enforcedoze/Utils.java").readText()
            .substringAfter("public static void showDisabledNotification(")
            .substringBefore("public static void hideDisabledNotification(")
        val target = Regex("new Intent\\(context, (\\w+)\\.class\\)").find(notification)?.groupValues?.get(1)
        assertNotEquals("own notification must not pass through exported automation", "EnableForceDozeService", target)
        assertEquals("notification must explicitly target the internal enable receiver", "InternalEnableReceiver", target)
        assertTrue("notification tap must deliver the broadcast PendingIntent directly",
            notification.contains("PendingIntent.getBroadcast(") && notification.contains(".setContentIntent(pendingIntent)"))
        assertTrue("notification capability must stay immutable", notification.contains("PendingIntent.FLAG_IMMUTABLE"))
        assertFalse("notification must not reuse the external automation action", notification.contains("ENABLE_FORCEDOZE"))

        val source = File("src/main/java/com/akylas/enforcedoze/$target.java").readText()
        assertTrue("internal receiver must directly extend BroadcastReceiver", source.contains("extends BroadcastReceiver"))
        assertFalse("internal receiver must not inherit external admission or journaling", source.contains("ExternalControlReceiver"))
        assertFalse("own notification must not be gated as external control", source.contains("ALLOW_EXTERNAL"))
        assertFalse("own notification must not journal an external call", source.contains("EXTERNAL_CALL"))
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val declaration = Regex("<receiver\\b[^>]*android:name=\"com\\.akylas\\.enforcedoze\\.$target\"[^>]*>")
            .find(manifest)?.value
        assertNotNull("internal receiver must be declared", declaration)
        assertTrue("internal receiver must be non-exported", declaration!!.contains("android:exported=\"false\""))
    }

    @Test fun internalNotificationEnableStartsThenPersistsBothFlagsThenSchedules() {
        val source = File("src/main/java/com/akylas/enforcedoze/InternalEnableReceiver.java").readText()
            .substringAfter("public void onReceive(")
        val start = source.indexOf("if (!Utils.startForceDozeService(context)) {")
        val persist = source.indexOf(".edit().putBoolean(\"serviceEnabled\", true)")
        val schedule = source.indexOf("Utils.scheduleNextCustomDozePeriodBoundary(context);")
        assertTrue("direct notification enable must start before persisting and scheduling",
            start >= 0 && persist > start && schedule > persist)
        val failure = source.substring(start, persist)
        assertTrue("a denied start must return before any preferences are changed", failure.contains("return;"))
        assertFalse("a denied start must not persist state or intent", failure.contains("putBoolean("))
        val write = source.substring(persist, schedule)
        assertTrue("effective state and user intent must be saved in one edit",
            write.contains(".putBoolean(Prefs.SERVICE_USER_ENABLED, true).apply();"))
        assertEquals("enable must use a single preference edit", 1, Regex("\\.edit\\(\\)").findAll(source).count())
        assertFalse("notification FGS start must not be deferred off the user action", source.contains("goAsync("))
        assertFalse("explicit enable must not be gated by the schedule", source.contains("applyForceDozeSchedule("))
    }

    @Test fun explicitEnableRetainsStartThenPersistOrderingAndOriginalOutcomes() {
        val on = receiver().substringAfter("private void enableService(").substringBefore("private void disableService(")
        val start = on.indexOf("if (!Utils.startForceDozeService(app)) {")
        val persist = on.indexOf("else if (!prefs.edit().putBoolean(Prefs.SERVICE_ENABLED, true)")
        assertTrue("admitted enable must start unconditionally before persisting enabled state",
            start >= 0 && persist > start)
        val basic = receiver().substringAfter("private void basicControl(").substringBefore("private void enableService(")
        assertTrue("admission must still precede enable dispatch", basic.indexOf("if (!admitted())") in 0 until basic.indexOf("enableService();"))
        val failure = on.substring(start, persist)
        assertTrue("a denied start must retain FAILED / FOREGROUND_START_DENIED",
            failure.contains("complete(Permission.ALLOWED, ExternalCallOutcome.FAILED, ExecutionReason.FOREGROUND_START_DENIED);"))
        assertFalse("a denied start must not persist enabled state or intent", failure.contains("putBoolean("))
        val write = on.substring(persist).substringBefore("} else {")
        assertTrue("effective state and user intent must commit together",
            write.contains(".putBoolean(Prefs.SERVICE_USER_ENABLED, true).commit()"))
        assertTrue("a failed write must retain FAILED / PREFERENCE_WRITE_FAILED",
            write.contains("complete(Permission.ALLOWED, ExternalCallOutcome.FAILED, ExecutionReason.PREFERENCE_WRITE_FAILED);"))
        val success = on.substringAfter("} else {")
        assertTrue("successful enable must retain REQUESTED / SERVICE_START_REQUESTED",
            success.contains("complete(Permission.ALLOWED, ExternalCallOutcome.REQUESTED, ExecutionReason.SERVICE_START_REQUESTED);"))
        assertFalse("enable must never report a stop", on.contains("ExecutionReason.SERVICE_STOP_REQUESTED"))
        assertTrue("next boundary must arm only after the successful write",
            success.indexOf("Utils.scheduleNextCustomDozePeriodBoundary(app);") in 0 until success.indexOf("complete("))
    }

    @Test fun pendingEnterCannotBeCancelledOrItsUserDelayShortenedByReapply() {
        val start = service().substringAfter("public int onStartCommand(").substringBefore("public void reloadSettings()")
        assertTrue("pending admission must precede any reapply work", start.contains("enterDueElapsed > now"))
        assertTrue("pending enter must be journaled as a no-op", start.contains("EXTERNAL_REAPPLY_ENTER_PENDING"))
        assertFalse("reapply must never cancel the scheduled enter", start.contains("cancelEnter();"))
        assertFalse("reapply must never replace the user's enter deadline", start.contains("enterDueElapsed ="))
        assertFalse("receiver deadline is admission-only, not a multi-command deadline", start.contains("withDeadline("))
    }

    @Test fun externalReapplyMustPassTheSessionWatchdogBeforeEntering() {
        val start = service().substringAfter("public int onStartCommand(").substringBefore("private void reapplyEnter(")
        val gate = start.indexOf("runtime.getWatchdog().onExternalReapply(")
        val enter = start.indexOf("reapplyEnter(generation, epoch);")
        assertTrue("external requests must reserve the shared spacing and session budget before entering", gate >= 0 && gate < enter)
        assertFalse("explicit basic-control consent does not require automatic enforcement", start.contains("Prefs.KEEP_DOZE_ENFORCED"))
        assertTrue("policy sees both fresh deep/light state and latched maintenance", start.contains("DozeStateReading reading = runtime.readState();") && start.contains("onExternalReapply(reading, maintenance, Build.VERSION.SDK_INT)"))
        val stateRead = start.indexOf("DozeStateReading reading = runtime.readState();")
        val consent = start.indexOf("Prefs.ALLOW_EXTERNAL_BASIC_CONTROL", stateRead)
        assertTrue("consent, deadline and generation are checked after the blocking state read", stateRead >= 0 && stateRead < consent && consent < gate)
        assertTrue(start.substring(stateRead, consent).contains("now = runtime.getClock().elapsedRealtime();"))
        val skipped = start.substringAfter("if (decision instanceof Decision.SKIP)").substringBefore("reapplyEnter(generation, epoch);")
        assertTrue("a typed policy rejection is journaled and returns before enter", skipped.contains("journalReapplySkipped(runtime, ((Decision.SKIP) decision).getReason())") && skipped.contains("return;"))
        val enterCore = service().substringAfter("private EnterResult enterCoreForMode(").substringBefore("private boolean enterWasCancelled(")
        assertTrue("only force entries start watchdog spacing", enterCore.contains("if (mode == SessionMode.FORCE) runtime.getWatchdog().recordEnter();"))
        assertTrue("every force enter starts watchdog spacing before mutation", enterCore.indexOf("runtime.getWatchdog().recordEnter();") in 0 until enterCore.indexOf("enterCore(core, generation,"))
        assertTrue("core adapter forwards the original admission", service().contains("return runtime.getController().enterCore(config, generation, admission);"))
        val call = receiver().substringAfter("private void reapplyDoze(").substringBefore("private void writeSetting(")
        assertTrue("broadcast remains REQUESTED, never claims a completed reforce", call.contains("Outcome.REQUESTED, ExecutionReason.REAPPLY_REQUESTED"))
    }

    @Test fun externalAdmissionRejectsBeforeStateReadsAndIsRecheckedAfterwards() {
        val reapply = service().substringAfter("if (reapply) {").substringBefore("private void reapplyEnter(")
        val read = reapply.indexOf("DozeStateReading reading = runtime.readState();")
        assertTrue("reapply must read fresh state for admitted requests", read >= 0)
        val before = reapply.substringBefore("DozeStateReading reading = runtime.readState();")
        val after = reapply.substringAfter("DozeStateReading reading = runtime.readState();")
            .substringBefore("Decision decision =")
        for ((phase, block) in listOf("before reads" to before, "after reads" to after)) {
            assertTrue("generation must be checked $phase", block.contains("generation != runtime.getController().getCurrentGeneration()"))
            assertTrue("exit epoch must be checked $phase", block.contains("epoch != exitEpoch.get()"))
            assertTrue("deadline must be checked $phase", block.contains("now >= deadline"))
            assertTrue("session admission must be checked $phase", block.contains("!forceAdmitted()"))
            assertTrue("basic-control consent must be checked $phase", block.contains("!getDefaultSharedPreferences(this).getBoolean(")
                && block.contains("Prefs.ALLOW_EXTERNAL_BASIC_CONTROL, Prefs.DEFAULT_ALLOW_EXTERNAL_BASIC_CONTROL"))
            val rejection = block.substringAfter("Prefs.DEFAULT_ALLOW_EXTERNAL_BASIC_CONTROL)) {")
            assertEquals("admission rejection is journaled exactly once $phase", 1,
                Regex("journalReapplySkipped\\(runtime, ReapplySkip.EXTERNAL_REAPPLY_NOT_ADMITTED\\)").findAll(rejection).count())
            assertTrue("admission rejection returns $phase", rejection.substringBefore("}").contains("return;"))
        }
        assertTrue("blocking reads require a fresh deadline check", after.contains("now = runtime.getClock().elapsedRealtime();"))
    }

    @Test fun externalSpacingAndBudgetPrecheckReturnsBeforeAnyStateRead() {
        val start = service().substringAfter("if (reapply) {").substringBefore("private void reapplyEnter(")
        val mode = start.indexOf("SessionAccess.reapplySkip(sessionMode())")
        val precheck = start.indexOf("runtime.getWatchdog().precheckExternalReapply()")
        assertTrue("sensor-only returns typed non-admission before watchdog budget checks", mode >= 0 && mode < precheck)
        val read = start.indexOf("runtime.readState()")
        assertTrue("spacing and budget must be checked before shell reads", precheck >= 0 && precheck < read)
        val rejection = start.substring(precheck, read)
        assertTrue("pre-check rejection must journal its typed reason through the existing limiter",
            rejection.contains("if (precheck != null)") && rejection.contains("journalReapplySkipped(runtime, precheck)"))
        assertTrue("a rejected pre-check must return without reading", rejection.contains("return;"))
        assertTrue("post-read reservation remains authoritative", start.indexOf("onExternalReapply(") > read)
    }

    @Test fun foregroundReapplySkipsPromotionAndOtherPromotionDenialsAreCaught() {
        val start = service().substringAfter("public int onStartCommand(").substringBefore("public void reloadSettings()")
        val guarded = start.indexOf("if (!reapply || !foreground)")
        val promotion = start.indexOf("showPersistentNotification()")
        assertTrue("already-foreground reapply must skip promotion", guarded >= 0 && guarded < promotion)
        assertTrue("API 31+ denial must not escape onStartCommand", start.contains("catch (IllegalStateException"))
        assertTrue("promotion denial must be journaled", start.contains("FOREGROUND_START_DENIED"))
        assertEquals("foreground state must reflect successful promotions", 3,
            Regex("foreground = true;").findAll(service()).count())
        assertTrue(service().contains("foreground = false;"))
    }

    @Test fun reapplyHoldsBoundedWakeLockThroughCompletionAndSchedulesOnlyOneRetry() {
        val source = service()
        val reapply = source.substringAfter("private void reapplyEnter(").substringBefore("public void reloadSettings()")
        assertTrue("reapply uses the same bounded enter lock as scheduled enters", reapply.contains("acquireEnterWakeLock()"))
        assertTrue("completion must release its own lock", reapply.contains("releaseWakeLock(wakeLock)"))
        assertTrue("duplicate callbacks cannot create duplicate retries", reapply.contains("compareAndSet(false, true)"))
        assertTrue("cancelled/unverified enter must use normal delay scheduling", reapply.contains("if (retryNeeded") && reapply.contains("scheduleEnter();"))
        assertTrue("retry retains epoch and generation admission", reapply.contains("epoch == exitEpoch.get()") && reapply.contains("getCurrentGeneration()"))
        assertTrue("lock retains the existing ten-minute ceiling", source.contains("tempWakeLock.acquire(10 * 60 * 1000L)"))
        val record = source.substringAfter("private void recordVerifiedEnter()").substringBefore("public void exitDoze")
        assertFalse("core verification cannot drop the lock before deferred groups finish", record.contains("releaseWakeLock()"))
    }

    @Test fun cancelledAndUnverifiedResultsReachAOneShotNormalRetry() {
        val source = service()
        val result = source.substringAfter("private static boolean needsEnterRetry(").substringBefore("private void enterDoze(boolean sensors)")
        assertTrue("cancelled core/group results require retry", result.contains("EnterStatus.CANCELLED"))
        assertTrue("unverified core/group steps require retry", result.contains("StepStatus.UNVERIFIED"))
        val enter = source.substringAfter("private void enterDoze(boolean sensors, long generation,").substringBefore("private void recordVerifiedEnter()")
        assertTrue("both core and deferred groups contribute to the result", enter.contains("coreRetryNeeded || needsEnterRetry(groups)"))
        assertTrue("deferred rejection releases the bounded lock", enter.contains("else completion.complete(true);"))
        val retry = source.substringAfter("private void reapplyEnter(").substringBefore("public void reloadSettings()")
        assertEquals("one completion can schedule at most one retry", 1, Regex("scheduleEnter\\(\\);").findAll(retry).count())
        assertFalse("normal retry never recursively re-applies", retry.contains("reapplyEnter("))
    }

    @Test fun admittedTimeoutsAndUnknownReadbacksAreUnverifiedAndJournalIsRateLimited() {
        val source = receiver()
        assertTrue("timer after policy admission cannot assert definite failure", source.contains("Outcome.UNVERIFIED, ExecutionReason.TIMED_OUT"))
        assertFalse("unknown readback cannot assert definite failure", source.contains("Outcome.FAILED, ExecutionReason.UNVERIFIED"))
        assertTrue("lane results share the tested outcome mapping", source.contains("Outcome.fromCommand(result)"))
        val limiter = source.substringAfter("private static JournalSink admitJournal(").substringBefore("private void journal(")
        assertTrue("all outcomes, including service skips, share the process limiter",
            limiter.contains("Admission.journal(JOURNAL_LIMIT, action, factory,"))
        assertTrue("suppressed counts are coalesced without caller/target extras", limiter.contains("suppressed="))
        val seam = source.substringAfter("public static <T> T journal(").substringBefore("private static final class Input")
        assertTrue("suppression returns before resolving the journal factory",
            seam.indexOf("if (!admission.getAdmitted()) return null;") in 0 until seam.indexOf("factory.get()"))
        val journal = source.substringAfter("private void journal(").substringBefore("private final class Call")
        assertTrue("receiver outcomes use shared journal-only admission",
            journal.contains("admitJournal(() -> MyApplication.getJournal(app), action)"))
        assertFalse("denial journal cannot discover access", journal.contains("getDozeRuntime"))
        assertFalse("untrusted targets/values never enter journal details", journal.contains("input.") || journal.contains("pkg"))
        assertTrue(journal.contains("permission.name().toLowerCase(java.util.Locale.ROOT)"))
        assertTrue(journal.contains("action.name() + \" caller=\" + caller"))
        assertTrue(journal.contains("outcome.name() + \" reason=\" + reason.name()"))
        val skip = source.substringAfter("static void journalReapplySkipped(").substringBefore("private void journal(")
        assertTrue("service rejections are rate-limited under REAPPLY_DOZE",
            skip.contains("admitJournal(runtime::getJournal, Action.REAPPLY_DOZE)"))
        assertTrue("SKIPPED details come from a typed enum", skip.contains("EventType.SKIPPED, reason.getDetail()"))
    }

    @Test fun bootReceiverRejectsUnrelatedAndNullActionsBeforePreferenceOrServiceWork() {
        val source = File("src/main/java/com/akylas/enforcedoze/BootCompleteReceiver.java").readText()
        val onReceive = source.substringAfter("public void onReceive(")
        val guard = onReceive.indexOf("Intent.ACTION_BOOT_COMPLETED.equals(action)")
        val prefs = onReceive.indexOf("PreferenceManager.getDefaultSharedPreferences")
        assertTrue("boot action allowlist must run before any work", guard >= 0 && guard < prefs)
        assertTrue(onReceive.contains("Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)"))
        assertTrue(onReceive.contains("Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)"))
        assertTrue(onReceive.contains("intent == null ? null : intent.getAction()"))
        assertTrue(onReceive.substringBefore("boolean isServiceEnabled").contains("return;"))
    }
}
