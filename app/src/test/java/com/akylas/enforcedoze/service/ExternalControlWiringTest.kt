package com.akylas.enforcedoze.service

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Pin Android-only orchestration; actual suspend/FGS/broadcast delivery needs a device. */
class ExternalControlWiringTest {
    private fun service() = File("src/main/java/com/akylas/enforcedoze/ForceDozeService.java").readText()
    private fun receiver() = File("src/main/java/com/akylas/enforcedoze/ExternalControlReceiver.java").readText()

    @Test fun pendingEnterCannotBeCancelledOrItsUserDelayShortenedByReapply() {
        val start = service().substringAfter("public int onStartCommand(").substringBefore("public void reloadSettings()")
        assertTrue("pending admission must precede any reapply work", start.contains("enterDueElapsed > now"))
        assertTrue("pending enter must be journaled as a no-op", start.contains("EXTERNAL_REAPPLY_ENTER_PENDING"))
        assertFalse("reapply must never cancel the scheduled enter", start.contains("cancelEnter();"))
        assertFalse("reapply must never replace the user's enter deadline", start.contains("enterDueElapsed ="))
        assertFalse("receiver deadline is admission-only, not a multi-command deadline", start.contains("withDeadline("))
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
        val journal = source.substringAfter("private void journal(").substringBefore("private final class Call")
        assertTrue("all outcomes, including denials, share the process limiter", journal.contains("JOURNAL_LIMIT.record(action)"))
        assertTrue("suppressed counts are coalesced without caller/target extras", journal.contains("suppressed="))
        assertTrue(journal.contains("if (!admission.getAdmitted()) return;"))
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
