package com.akylas.enforcedoze.service

import com.akylas.enforcedoze.access.AccessLevel
import com.akylas.enforcedoze.access.CapabilityResolver
import com.akylas.enforcedoze.access.CommandCatalog
import com.akylas.enforcedoze.access.Feature
import com.akylas.enforcedoze.access.Grants
import com.akylas.enforcedoze.doze.DozeController
import com.akylas.enforcedoze.doze.DozeEventSink
import com.akylas.enforcedoze.doze.FakeClock
import com.akylas.enforcedoze.doze.FakeRunner
import com.akylas.enforcedoze.doze.InMemoryLedgerStore
import com.akylas.enforcedoze.doze.LedgerEntry
import com.akylas.enforcedoze.doze.RestoreLedger
import com.akylas.enforcedoze.ui.ResetReport
import org.junit.Assert.*
import org.junit.Test

private class ResetPreferences(initial: Map<String, Any?>) : android.content.SharedPreferences {
    private val memory = initial.toMutableMap()
    private var durable = initial.toMap()
    var commitSucceeds = true
    var throwOnEdit = false
    fun afterProcessRestart() = ResetPreferences(durable)
    override fun getAll(): Map<String, *> = memory.toMap()
    override fun getBoolean(key: String, defValue: Boolean) = memory[key] as? Boolean ?: defValue
    override fun getString(key: String, defValue: String?) = memory[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = error("Unused")
    override fun getInt(key: String, defValue: Int) = error("Unused")
    override fun getLong(key: String, defValue: Long) = error("Unused")
    override fun getFloat(key: String, defValue: Float) = error("Unused")
    override fun contains(key: String) = memory.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    override fun edit(): android.content.SharedPreferences.Editor {
        if (throwOnEdit) throw IllegalStateException("preference edit failed")
        return object : android.content.SharedPreferences.Editor {
            private val updates = mutableMapOf<String, Any?>()
            private val removed = mutableSetOf<String>()
            private var clear = false
            override fun putString(key: String, value: String?) = apply { updates[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { updates[key] = value }
            override fun putInt(key: String, value: Int) = apply { updates[key] = value }
            override fun putLong(key: String, value: Long) = apply { updates[key] = value }
            override fun putFloat(key: String, value: Float) = apply { updates[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = error("Unused")
            override fun remove(key: String) = apply { removed += key }
            override fun clear() = apply { clear = true }
            override fun commit(): Boolean {
                if (clear) memory.clear()
                removed.forEach(memory::remove)
                memory.putAll(updates)
                if (commitSucceeds) durable = memory.toMap()
                return commitSucceeds
            }
            override fun apply() = error("Reset must commit before delivering its result")
        }
    }
}

class SystemResetTest {
    private fun outcome(
        exitComplete: Boolean = true,
        remaining: RestoreLedger = RestoreLedger(),
        loadFailed: Boolean = false,
        corrupt: List<com.akylas.enforcedoze.doze.CorruptLedgerLine> = emptyList(),
    ) = SystemReset.restoreOutcome(exitComplete, remaining, loadFailed, corrupt)

    @Test fun throwingJobReturnsFailureAndReportsExactExceptionOnce() {
        val exception = IllegalStateException("reset failed")
        val reported = mutableListOf<Throwable>()

        val result = SystemReset.runJob({ throw exception }, onError = reported::add)

        assertTrue(result.failed)
        assertEquals(listOf(exception), reported)
    }

    @Test fun throwingRestoreReturnsFailureAndReportsException() {
        val exception = IllegalStateException("restore failed")
        val reported = mutableListOf<Throwable>()

        val result = SystemReset.run(resetRunner(), 36, PACKAGE, restore = { throw exception },
            onError = reported::add)

        assertTrue(result.failed)
        assertEquals(listOf(exception), reported)
    }

    @Test fun successfulJobDoesNotReportError() {
        val reported = mutableListOf<Throwable>()
        val expected = SystemResetResult(ResetRestoreOutcome.COMPLETE, emptyList())

        val result = SystemReset.runJob({ expected }, onError = reported::add)

        assertSame(expected, result)
        assertTrue(reported.isEmpty())
    }

    @Test fun restoreOutcomeAllClearIsComplete() =
        assertEquals(ResetRestoreOutcome.COMPLETE, outcome())

    @Test fun restoreOutcomeIncompleteExitIsDebt() =
        assertEquals(ResetRestoreOutcome.REMAINING_DEBT, outcome(exitComplete = false))

    @Test fun restoreOutcomeRemainingEntriesIsDebt() =
        assertEquals(ResetRestoreOutcome.REMAINING_DEBT,
            outcome(remaining = RestoreLedger(listOf(LedgerEntry(Feature.WIFI, null, "1", 0)))))

    @Test fun restoreOutcomeLoadFailedIsDebt() =
        assertEquals(ResetRestoreOutcome.REMAINING_DEBT, outcome(loadFailed = true))

    @Test fun restoreOutcomeCorruptLinesIsDebt() =
        assertEquals(ResetRestoreOutcome.REMAINING_DEBT,
            outcome(corrupt = listOf(com.akylas.enforcedoze.doze.CorruptLedgerLine(1, "garbage"))))

    @Test fun attachedServiceTeardownRunsBeforeResetBody() {
        val worker = java.util.ArrayDeque<Runnable>()
        val queue = ServiceResetQueue { worker.add(it) }
        val order = mutableListOf<String>()
        queue.attachService()
        queue.resetSystemState(Runnable { order += "reset" })
        queue.detachService(Runnable { order += "teardown" })
        while (worker.isNotEmpty()) worker.removeFirst().run()
        assertEquals("teardown must run before the reset job body", listOf("teardown", "reset"), order)
    }

    @Test fun resetIsHeldWhileAttachedEvenWhenWorkerDrainsBeforeDetach() {
        val worker = java.util.ArrayDeque<Runnable>()
        val queue = ServiceResetQueue { worker.add(it) }
        var resetRan = false
        queue.attachService()
        queue.resetSystemState(Runnable { resetRan = true })
        assertTrue("reset is not posted before Android delivers onDestroy", worker.isEmpty())
        assertFalse(resetRan)
        queue.detachService(Runnable {})
        worker.removeFirst().run()
        assertFalse("teardown alone does not run reset", resetRan)
        worker.removeFirst().run()
        assertTrue(resetRan)
    }

    @Test fun resetWithoutServicePostsImmediatelyIncludingAfterDetach() {
        val worker = java.util.ArrayDeque<Runnable>()
        val queue = ServiceResetQueue { worker.add(it) }
        val order = mutableListOf<String>()
        queue.resetSystemState(Runnable { order += "no-service reset" })
        assertEquals(1, worker.size)
        worker.removeFirst().run()
        queue.attachService()
        queue.detachService(Runnable { order += "teardown" })
        queue.resetSystemState(Runnable { order += "detached reset" })
        assertEquals(2, worker.size)
        while (worker.isNotEmpty()) worker.removeFirst().run()
        assertEquals(listOf("no-service reset", "teardown", "detached reset"), order)
    }

    @Test fun detachReleasesEveryHeldResetOnceAndNextAttachmentHoldsAgain() {
        val worker = java.util.ArrayDeque<Runnable>()
        val queue = ServiceResetQueue { worker.add(it) }
        val order = mutableListOf<String>()
        queue.attachService()
        queue.resetSystemState(Runnable { order += "reset 1" })
        queue.resetSystemState(Runnable { order += "reset 2" })
        queue.detachService(Runnable { order += "teardown 1" })
        queue.attachService()
        queue.resetSystemState(Runnable { order += "reset 3" })
        while (worker.isNotEmpty()) worker.removeFirst().run()
        assertEquals(listOf("teardown 1", "reset 1", "reset 2"), order)
        queue.detachService(Runnable { order += "teardown 2" })
        while (worker.isNotEmpty()) worker.removeFirst().run()
        assertEquals(listOf("teardown 1", "reset 1", "reset 2", "teardown 2", "reset 3"), order)
    }

    @Test fun runtimeRoutesResetAndDetachThroughTheTestedQueueUnderItsMonitor() {
        val source = java.io.File("src/main/java/com/akylas/enforcedoze/service/DozeRuntime.kt").readText()
        assertTrue(source.contains("private val resets = ServiceResetQueue(this) { job -> worker().post(job) }"))
        assertTrue(source.contains("@Synchronized\n    fun attachService(): Handler {\n        selfTests.attach()\n        resets.attachService()"))
        assertTrue(source.contains("@Synchronized\n    fun detachService(teardown: Runnable)"))
        assertTrue(source.contains("resets.detachService(teardown)"))
        assertTrue(source.contains("@Synchronized\n    fun resetSystemState(callback: SystemResetCallback) {\n        bumpGeneration()\n        resets.resetSystemState(Runnable {"))
        assertTrue(source.contains("resets.finishReset(Runnable {"))
    }

    private val runner = resetRunner()

    private fun resetRunner(writeSettingsOutput: String = "WRITE_SETTINGS: default") = FakeRunner().apply {
        answer("pm revoke $PACKAGE android.permission.WRITE_SETTINGS") {
            FakeRunner.result("SecurityException: not a changeable permission type", exit = 1)
        }
        confirmingWriteSettingsReadback(this, writeSettingsOutput)
    }

    private fun confirmingWriteSettingsReadback(fake: FakeRunner, output: String = "WRITE_SETTINGS: default") {
        fake.replies("appops set $PACKAGE WRITE_SETTINGS default", "")
        fake.replies("appops get $PACKAGE WRITE_SETTINGS", output)
    }
    private val store = InMemoryLedgerStore()
    private val controller = DozeController(runner, CommandCatalog, CapabilityResolver, store,
        FakeClock(), DozeEventSink {}, 36, Grants(true, true))

    private fun confirmingDeviceIdleReadbacks(apiLevel: Int = 36) {
        if (apiLevel >= 24) {
            runner.replies("cmd deviceidle enabled deep", "0", "1")
            runner.replies("cmd deviceidle enabled light", "0", "1")
        } else runner.replies("dumpsys deviceidle enabled", "0", "1")
    }

    @Test fun writeSettingsUsesAppOpInsteadOfUnchangeablePermissionAndCanComplete() {
        for (apiLevel in listOf(23, 36)) {
            val fake = FakeRunner()
            if (apiLevel >= 24) {
                fake.replies("cmd deviceidle enabled deep", "0", "1")
                fake.replies("cmd deviceidle enabled light", "0", "1")
            } else fake.replies("dumpsys deviceidle enabled", "0", "1")
            fake.answer("pm revoke $PACKAGE android.permission.WRITE_SETTINGS") {
                FakeRunner.result("SecurityException: not a changeable permission type", exit = 1)
            }
            fake.replies("appops set $PACKAGE WRITE_SETTINGS default", "")
            fake.replies("appops get $PACKAGE WRITE_SETTINGS", "WRITE_SETTINGS: default")

            val result = SystemReset.run(fake, apiLevel, PACKAGE, permissionGranted = { false }) {
                ResetRestoreOutcome.COMPLETE
            }
            assertEquals("WRITE_SETTINGS must be reset through its app-op on API $apiLevel",
                ResetCommandOutcome.OK, result.commands.last().outcome)
            assertEquals(ResetCommandId.REVOKE_WRITE_SETTINGS, result.commands.last().id)
            assertEquals(listOf("appops set $PACKAGE WRITE_SETTINGS default", "appops get $PACKAGE WRITE_SETTINGS"),
                fake.commands.takeLast(2))
            assertFalse(fake.commands.contains("pm revoke $PACKAGE android.permission.WRITE_SETTINGS"))
            assertTrue("all readbacks confirmed, so reset can be complete on API $apiLevel", result.complete)
        }
    }

    @Test fun unparseableWriteSettingsAppOpReadbackIsUnverified() {
        val fake = resetRunner("OEM unknown")
        fake.replies("cmd deviceidle enabled deep", "0", "1")
        fake.replies("cmd deviceidle enabled light", "0", "1")
        val result = SystemReset.run(fake, 36, PACKAGE, permissionGranted = { false }) { ResetRestoreOutcome.COMPLETE }
        assertEquals("unknown app-op output is never confirmed", ResetCommandOutcome.UNVERIFIED,
            result.commands.last().outcome)
        assertFalse(result.complete)
    }

    @Test fun writeSettingsReadbackAcceptsAospPrunedAndHistoryForms() {
        // AOSP prunes an op set back to its default mode, so `appops get` usually prints "No operations.".
        for (output in listOf("No operations.", "No operations.\nDefault mode: default",
            "WRITE_SETTINGS: default; time=+1m2s ago", "WRITE_SETTINGS: default; time=+5s ago; rejectTime=+1h ago",
            "WRITE_SETTINGS: default (running)")) {
            assertEquals(output, true, SystemReset.writeSettingsIsDefault(output.split('\n')))
        }
        assertEquals(false, SystemReset.writeSettingsIsDefault(listOf("WRITE_SETTINGS: allow; time=+3s ago")))
    }

    @Test fun writeSettingsReadbackAcceptsOnlyOneExactOperationAndMode() {
        val outputs = listOf("", "default", "GET_USAGE_STATS: default", "No operations.\nDefault mode: allow",
            "Uid mode: WRITE_SETTINGS: default", "Uid mode: WRITE_SETTINGS: allow\nWRITE_SETTINGS: default",
            "WRITE_SETTINGS: default extra", "WRITE_SETTINGS: unknown", "write_settings: default",
            "WRITE_SETTINGS: default\nWRITE_SETTINGS: default", "WRITE_SETTINGS: default\nOEM warning")
        for (output in outputs) {
            val fake = resetRunner(output)
            fake.replies("cmd deviceidle enabled deep", "0", "1")
            fake.replies("cmd deviceidle enabled light", "0", "1")
            val result = SystemReset.run(fake, 36, PACKAGE, permissionGranted = { false }) { ResetRestoreOutcome.COMPLETE }
            assertEquals(output, ResetCommandOutcome.UNVERIFIED, result.commands.last().outcome)
            assertFalse(result.complete)
        }
        for (mode in listOf("allow", "ignore", "deny", "foreground")) {
            val fake = resetRunner("WRITE_SETTINGS: $mode")
            fake.replies("cmd deviceidle enabled deep", "0", "1")
            fake.replies("cmd deviceidle enabled light", "0", "1")
            val result = SystemReset.run(fake, 36, PACKAGE, permissionGranted = { false }) { ResetRestoreOutcome.COMPLETE }
            assertEquals(mode, ResetCommandOutcome.FAILED, result.commands.last().outcome)
            assertFalse(result.complete)
        }
        val fake = resetRunner("\n  WRITE_SETTINGS: default  \n")
        fake.replies("cmd deviceidle enabled deep", "0", "1")
        fake.replies("cmd deviceidle enabled light", "0", "1")
        val result = SystemReset.run(fake, 36, PACKAGE, permissionGranted = { false }) { ResetRestoreOutcome.COMPLETE }
        assertTrue("blank lines and surrounding whitespace do not change an exact mode", result.complete)
    }

    @Test fun writeSettingsReadbackTransportFailureAndTimeoutCannotConfirm() {
        val reads = listOf(
            FakeRunner.result("WRITE_SETTINGS: default", exit = 1) to ResetCommandOutcome.UNVERIFIED,
            FakeRunner.result("WRITE_SETTINGS: default", exit = -1) to ResetCommandOutcome.UNVERIFIED,
            FakeRunner.result("WRITE_SETTINGS: default", timeout = true) to ResetCommandOutcome.TIMEOUT,
            FakeRunner.result("WRITE_SETTINGS: default").copy(stderr = listOf("OEM warning")) to ResetCommandOutcome.UNVERIFIED,
        )
        for ((reply, expected) in reads) {
            val fake = FakeRunner()
            fake.replies("cmd deviceidle enabled deep", "0", "1")
            fake.replies("cmd deviceidle enabled light", "0", "1")
            fake.replies("appops set $PACKAGE WRITE_SETTINGS default", "")
            fake.answer("appops get $PACKAGE WRITE_SETTINGS") { reply }
            val result = SystemReset.run(fake, 36, PACKAGE, permissionGranted = { false }) { ResetRestoreOutcome.COMPLETE }
            assertEquals(expected, result.commands.last().outcome)
            assertFalse(result.complete)
        }
    }

    @Test fun writeSettingsMutationFailureDoesNotRunReadback() {
        val writes = listOf(
            FakeRunner.result("", exit = 1) to ResetCommandOutcome.FAILED,
            FakeRunner.result("", exit = -1) to ResetCommandOutcome.UNVERIFIED,
            FakeRunner.result("", timeout = true) to ResetCommandOutcome.TIMEOUT,
        )
        for ((reply, expected) in writes) {
            val fake = FakeRunner()
            fake.replies("cmd deviceidle enabled deep", "0", "1")
            fake.replies("cmd deviceidle enabled light", "0", "1")
            fake.answer("appops set $PACKAGE WRITE_SETTINGS default") { reply }
            val result = SystemReset.run(fake, 36, PACKAGE, permissionGranted = { false }) { ResetRestoreOutcome.COMPLETE }
            assertEquals(expected, result.commands.last().outcome)
            assertFalse(fake.commands.contains("appops get $PACKAGE WRITE_SETTINGS"))
            assertFalse(result.complete)
        }
    }

    @Test fun throwingResetJobDeliversFailureAndAllowsRetry() {
        assertFailedJobCanBeRetried { throw IllegalStateException("reset job failed before callback") }
    }

    @Test fun exceptionInsideSystemResetDeliversFailureAndAllowsRetry() {
        assertFailedJobCanBeRetried {
            SystemReset.run(runner, 36, "com.example;id") { ResetRestoreOutcome.COMPLETE }
        }
        assertTrue("no command ran after the failed validation", runner.commands.isEmpty())
    }

    @Test fun throwingLedgerReadDeliversFailureAndAllowsRetry() {
        var saves = 0
        val unreadable = object : com.akylas.enforcedoze.doze.LedgerStore {
            override fun load(): RestoreLedger = throw IllegalStateException("unreadable ledger")
            override fun save(ledger: RestoreLedger) { saves++ }
        }
        assertFailedJobCanBeRetried {
            SystemReset.run(runner, 36, PACKAGE) {
                unreadable.load()
                ResetRestoreOutcome.COMPLETE
            }
        }
        assertEquals("unreadable ledger is never overwritten", 0, saves)
        assertTrue("a throwing restore aborts reset mutations", runner.commands.isEmpty())
    }

    private fun assertFailedJobCanBeRetried(job: () -> SystemResetResult) {
        val tracker = ResetReport.Tracker()
        assertTrue(tracker.begin())
        val prefs = ResetPreferences(mapOf(
            com.akylas.enforcedoze.access.Prefs.SERVICE_ENABLED to true,
            com.akylas.enforcedoze.access.Prefs.SERVICE_USER_ENABLED to true,
            com.akylas.enforcedoze.access.Prefs.RESTORE_LEDGER to "damaged ledger",
            com.akylas.enforcedoze.access.Prefs.TURN_OFF_WIFI to true,
        ))
        val main = java.util.ArrayDeque<Runnable>()
        var notices = 0
        tracker.setListener { notices++ }
        val callback = ResetReport.callback(prefs, ResetPreferences(emptyMap()), tracker) { main.add(it) }
        callback.onComplete(SystemReset.runJob(job))
        assertEquals("a throwing reset job posts one result notification", 1, main.size)
        assertEquals("notification waits for the main dispatcher", 0, notices)
        main.removeFirst().run()
        assertEquals("the current screen is notified", 1, notices)
        assertEquals("the stopped service is off before any retry", false,
            prefs.all[com.akylas.enforcedoze.access.Prefs.SERVICE_ENABLED])
        assertEquals("master intent and all other settings survive", mapOf(
            com.akylas.enforcedoze.access.Prefs.SERVICE_ENABLED to false,
            com.akylas.enforcedoze.access.Prefs.SERVICE_USER_ENABLED to true,
            com.akylas.enforcedoze.access.Prefs.RESTORE_LEDGER to "damaged ledger",
            com.akylas.enforcedoze.access.Prefs.TURN_OFF_WIFI to true,
        ), prefs.afterProcessRestart().all)
        assertEquals(ResetReport.Tracker.Phase.REPORTED, tracker.phase())
        assertTrue("the exception is identified separately from ordinary partial outcomes", tracker.result().failed)
        assertFalse("an exception is never complete", tracker.result().complete)
        assertFalse("preferences were not cleared", tracker.prefsCleared())
        assertTrue("a failed job schedules no deferred revoke", tracker.result().deferred.isEmpty())
        assertNull("OK dismisses a failed job, without scheduling a restart", tracker.confirm())
        assertEquals(ResetReport.Tracker.Phase.IDLE, tracker.phase())
        assertTrue("after dismissing the failure the user can reset again", tracker.begin())
    }

    @Test fun failedCallbackKeepsExplicitMasterOffAndAlreadyStoppedServiceOff() {
        val keys = com.akylas.enforcedoze.access.Prefs
        val initial = mapOf(keys.SERVICE_ENABLED to false, keys.SERVICE_USER_ENABLED to false)
        val prefs = ResetPreferences(initial)
        val tracker = ResetReport.Tracker()
        assertTrue(tracker.begin())
        ResetReport.callback(prefs, ResetPreferences(emptyMap()), tracker, Runnable::run).onComplete(SystemReset.runJob({
            throw IllegalStateException("failed")
        }))
        assertEquals(initial, prefs.afterProcessRestart().all)
        assertFalse(tracker.prefsCleared())
    }

    @Test fun successfulRetryUsesTheSameCallbackAndClearsSettingsOnlyAfterItsResult() {
        val keys = com.akylas.enforcedoze.access.Prefs
        val prefs = ResetPreferences(mapOf(keys.SERVICE_ENABLED to true, keys.SERVICE_USER_ENABLED to true))
        val tracker = ResetReport.Tracker()
        val callback = ResetReport.callback(prefs, ResetPreferences(emptyMap()), tracker, Runnable::run)
        assertTrue(tracker.begin())
        callback.onComplete(SystemReset.runJob({ throw IllegalStateException("failed") }))
        assertEquals(true, prefs.all[keys.SERVICE_USER_ENABLED])
        assertNull(tracker.confirm())
        assertTrue(tracker.begin())
        assertEquals("retry does not destroy recoverable master intent", true, prefs.all[keys.SERVICE_USER_ENABLED])
        callback.onComplete(SystemResetResult(ResetRestoreOutcome.COMPLETE, emptyList()))
        assertTrue(tracker.prefsCleared())
        assertTrue(prefs.afterProcessRestart().all.isEmpty())
        assertEquals(emptyList<ResetCommandId>(), tracker.confirm())
    }

    @Test fun successfulResetClearsDeviceLocalHelperPrefsDurably() {
        val keys = com.akylas.enforcedoze.access.Prefs
        val prefs = ResetPreferences(mapOf(keys.SERVICE_ENABLED to true))
        val helpers = ResetPreferences(mapOf(keys.APPLIED_HELPERS to setOf("READ_PHONE_STATE", "SELF_WHITELIST")))
        val tracker = ResetReport.Tracker()
        assertTrue(tracker.begin())
        ResetReport.callback(prefs, helpers, tracker, Runnable::run)
            .onComplete(SystemResetResult(ResetRestoreOutcome.COMPLETE, emptyList()))
        assertTrue(tracker.prefsCleared())
        assertTrue(prefs.afterProcessRestart().all.isEmpty())
        assertTrue("successful reset clears the helper-grant file", helpers.afterProcessRestart().all.isEmpty())
    }

    @Test fun failedResetPreservesDeviceLocalHelperPrefs() {
        val keys = com.akylas.enforcedoze.access.Prefs
        val original = mapOf(keys.APPLIED_HELPERS to setOf("READ_PHONE_STATE", "SELF_WHITELIST"))
        val helpers = ResetPreferences(original)
        val tracker = ResetReport.Tracker()
        assertTrue(tracker.begin())
        ResetReport.callback(ResetPreferences(emptyMap()), helpers, tracker, Runnable::run)
            .onComplete(SystemReset.runJob({ throw IllegalStateException("reset failed") }))
        assertFalse(tracker.prefsCleared())
        assertEquals("failed reset keeps helper attempts", original, helpers.afterProcessRestart().all)
    }

    @Test fun failedDefaultPrefsCommitDoesNotClearHelperPrefs() {
        val keys = com.akylas.enforcedoze.access.Prefs
        val prefs = ResetPreferences(mapOf(keys.SERVICE_ENABLED to true)).apply { commitSucceeds = false }
        val original = mapOf(keys.APPLIED_HELPERS to setOf("READ_PHONE_STATE"))
        val helpers = ResetPreferences(original)
        assertFalse(ResetReport.clearPreferences(prefs, helpers, SystemResetResult(ResetRestoreOutcome.COMPLETE, emptyList())))
        assertEquals(original, helpers.afterProcessRestart().all)
    }

    @Test fun failedHelperPrefsCommitNeverReportsSuccessfulClear() {
        val keys = com.akylas.enforcedoze.access.Prefs
        val original = mapOf(keys.APPLIED_HELPERS to setOf("READ_PHONE_STATE"))
        val helpers = ResetPreferences(original).apply { commitSucceeds = false }
        assertFalse(ResetReport.clearPreferences(ResetPreferences(emptyMap()), helpers,
            SystemResetResult(ResetRestoreOutcome.COMPLETE, emptyList())))
        assertEquals(original, helpers.afterProcessRestart().all)
    }

    @Test fun preferenceExceptionStillDeliversResultWithoutClaimingClear() {
        val prefs = ResetPreferences(emptyMap()).apply { throwOnEdit = true }
        val tracker = ResetReport.Tracker()
        var notices = 0
        tracker.setListener { notices++ }
        assertTrue(tracker.begin())
        ResetReport.callback(prefs, ResetPreferences(emptyMap()), tracker, Runnable::run)
            .onComplete(SystemResetResult(ResetRestoreOutcome.COMPLETE, emptyList()))
        assertEquals(1, notices)
        assertFalse(tracker.prefsCleared())
        assertEquals(ResetReport.Tracker.Phase.REPORTED, tracker.phase())
    }

    @Test fun failedCommitKeepsStoppedMemoryStateButDoesNotClaimPreferencesCleared() {
        val keys = com.akylas.enforcedoze.access.Prefs
        val prefs = ResetPreferences(mapOf(keys.SERVICE_ENABLED to true, keys.SERVICE_USER_ENABLED to true))
            .apply { commitSucceeds = false }
        val tracker = ResetReport.Tracker()
        assertTrue(tracker.begin())
        ResetReport.callback(prefs, ResetPreferences(emptyMap()), tracker, Runnable::run).onComplete(SystemReset.runJob({
            throw IllegalStateException("failed")
        }))
        assertEquals(false, prefs.all[keys.SERVICE_ENABLED])
        assertEquals(true, prefs.all[keys.SERVICE_USER_ENABLED])
        assertFalse(tracker.prefsCleared())
        assertEquals("failed commit cannot promise durable stopped state", true,
            prefs.afterProcessRestart().all[keys.SERVICE_ENABLED])
    }

    @Test fun ledgerRestoreAndReadbackFinishBeforeResetOrRevocations() {
        confirmingDeviceIdleReadbacks()
        store.save(RestoreLedger(listOf(LedgerEntry(Feature.FORCE_DOZE, null, "0", 0, apiLevel = 36))))
        runner.replies("dumpsys deviceidle", "mForceIdle=false")
        var restored = false
        runner.beforeMutation = { command ->
            if (command != "cmd deviceidle unforce") {
                assertTrue("restore returned before any reset/revoke command", restored)
                assertTrue("restore readback and durable cleanup precede revocations", store.load().entries.isEmpty())
            }
        }
        val result = SystemReset.run(runner, 36, PACKAGE, permissionGranted = { false }) {
            val exit = controller.reconcile()
            restored = true
            if (exit.complete) ResetRestoreOutcome.COMPLETE else ResetRestoreOutcome.REMAINING_DEBT
        }
        assertEquals(ResetRestoreOutcome.COMPLETE, result.restoreOutcome)
        assertEquals("cmd deviceidle unforce", runner.commands.first())
        assertEquals("dumpsys deviceidle", runner.commands[1])
        assertEquals(7, result.commands.size)
        assertTrue(result.commands.all { it.outcome == ResetCommandOutcome.OK })
        assertTrue(result.complete)
    }

    @Test fun perCommandOutcomesAreTypedAndFailuresDoNotDiscardOtherResults() {
        runner.answer("dumpsys deviceidle disable all") { FakeRunner.result("", exit = 1) }
        runner.answer("dumpsys deviceidle enable all") { FakeRunner.result("", exit = 0, timeout = true) }
        runner.answer("pm revoke $PACKAGE android.permission.DUMP") { FakeRunner.result("", exit = -1) }
        runner.answer("pm revoke $PACKAGE android.permission.READ_LOGS") { throw IllegalStateException("transport gone") }
        val result = SystemReset.run(runner, 36, PACKAGE, permissionGranted = { false }) { ResetRestoreOutcome.REMAINING_DEBT }
        assertEquals(ResetRestoreOutcome.REMAINING_DEBT, result.restoreOutcome)
        assertEquals(listOf(ResetCommandId.DISABLE_DEVICE_IDLE, ResetCommandId.ENABLE_DEVICE_IDLE,
            ResetCommandId.REVOKE_DUMP, ResetCommandId.REVOKE_READ_LOGS, ResetCommandId.REVOKE_READ_PHONE_STATE,
            ResetCommandId.REVOKE_WRITE_SECURE_SETTINGS, ResetCommandId.REVOKE_WRITE_SETTINGS), result.commands.map { it.id })
        assertEquals(listOf(ResetCommandOutcome.FAILED, ResetCommandOutcome.TIMEOUT,
            ResetCommandOutcome.UNVERIFIED, ResetCommandOutcome.UNVERIFIED,
            ResetCommandOutcome.OK, ResetCommandOutcome.OK, ResetCommandOutcome.OK), result.commands.map { it.outcome })
        assertFalse(result.complete)
    }

    @Test fun failedRestoreIsDebtAndNeverClaimsCompletion() {
        confirmingDeviceIdleReadbacks(23)
        val result = SystemReset.run(runner, 23, PACKAGE, permissionGranted = { false }) { throw IllegalStateException("unreadable ledger") }
        assertEquals(ResetRestoreOutcome.REMAINING_DEBT, result.restoreOutcome)
        assertTrue("the throwing restore is a failed job, not just returned remaining debt", result.failed)
        assertTrue("no reset mutations follow a throwing restore", runner.commands.isEmpty())
        assertTrue(result.commands.isEmpty())
        assertFalse(result.complete)
    }

    @Test fun unreadableLedgerRemainsDebtAndIsNeverOverwritten() {
        confirmingDeviceIdleReadbacks()
        var saves = 0
        val unreadable = object : com.akylas.enforcedoze.doze.LedgerStore {
            override fun load(): RestoreLedger = throw IllegalStateException("unreadable ledger")
            override fun save(ledger: RestoreLedger) { saves++ }
        }
        val core = DozeController(runner, CommandCatalog, CapabilityResolver, unreadable,
            FakeClock(), DozeEventSink {}, 36, Grants(true, true))
        val result = SystemReset.run(runner, 36, PACKAGE, permissionGranted = { false }) {
            if (core.reconcile().complete) ResetRestoreOutcome.COMPLETE else ResetRestoreOutcome.REMAINING_DEBT
        }
        assertEquals(ResetRestoreOutcome.REMAINING_DEBT, result.restoreOutcome)
        assertEquals("unreadable restoration intent is preserved", 0, saves)
        assertFalse(result.complete)
    }

    @Test fun noSessionAccessReturnsPromptlyWithoutInvokingPrivilegedCommands() {
        store.save(RestoreLedger(listOf(LedgerEntry(Feature.FORCE_DOZE, null, "0", 0, apiLevel = 36))))
        for (level in listOf(AccessLevel.NONE, AccessLevel.APP)) {
            runner.level = level
            val result = SystemReset.run(runner, 36, PACKAGE, permissionGranted = { false }) {
                if (controller.reconcile(36, Grants(false, false)).complete) ResetRestoreOutcome.COMPLETE
                else ResetRestoreOutcome.REMAINING_DEBT
            }
            assertEquals(ResetRestoreOutcome.REMAINING_DEBT, result.restoreOutcome)
            assertEquals(1, store.load().entries.size)
            assertTrue(runner.commands.isEmpty())
            assertEquals(7, result.commands.size)
            assertTrue(result.commands.all { it.outcome == ResetCommandOutcome.UNVERIFIED })
            assertFalse(result.complete)
        }
    }

    @Test fun transportSuccessWithContradictingDeviceIdleReadbackIsFailed() {
        runner.replies("cmd deviceidle enabled deep", "1", "0")
        runner.replies("cmd deviceidle enabled light", "0", "1")
        val result = SystemReset.run(runner, 36, PACKAGE, permissionGranted = { false }) { ResetRestoreOutcome.COMPLETE }
        assertEquals("disable all must confirm both modes disabled", ResetCommandOutcome.FAILED,
            result.commands[0].outcome)
        assertEquals("enable all must confirm both modes enabled", ResetCommandOutcome.FAILED,
            result.commands[1].outcome)
        assertFalse(result.complete)
    }

    @Test fun transportSuccessWithUnknownDeviceIdleReadbackIsUnverified() {
        runner.replies("cmd deviceidle enabled deep", "OEM unknown", "")
        runner.replies("cmd deviceidle enabled light", "0", "1")
        val result = SystemReset.run(runner, 36, PACKAGE, permissionGranted = { false }) { ResetRestoreOutcome.COMPLETE }
        assertEquals(ResetCommandOutcome.UNVERIFIED, result.commands[0].outcome)
        assertEquals(ResetCommandOutcome.UNVERIFIED, result.commands[1].outcome)
        assertFalse(result.complete)
    }

    @Test fun permissionReadbackRunsAfterRevocationAndContradictionsAreFailed() {
        confirmingDeviceIdleReadbacks()
        val checked = mutableListOf<String>()
        val result = SystemReset.run(runner, 36, PACKAGE, permissionGranted = readback@{ permission ->
            if (runner.commands.isEmpty()) {
                // The one read before any command: is the self-killing permission held? No, so it runs in place.
                assertEquals(PHONE_STATE, permission)
                return@readback false
            }
            assertEquals("permission readback must follow its mutation", "pm revoke $PACKAGE $permission", runner.commands.last())
            checked.add(permission)
            true
        }) { ResetRestoreOutcome.COMPLETE }
        assertEquals(listOf("DUMP", "READ_LOGS", "READ_PHONE_STATE", "WRITE_SECURE_SETTINGS")
            .map { "android.permission.$it" }, checked)
        assertTrue(result.commands.take(2).all { it.outcome == ResetCommandOutcome.OK })
        assertTrue(result.commands.drop(2).dropLast(1).all { it.outcome == ResetCommandOutcome.FAILED })
        assertEquals("WRITE_SETTINGS uses app-op readback, not the permission predicate",
            ResetCommandOutcome.OK, result.commands.last().outcome)
        assertFalse(result.complete)
    }

    @Test fun unknownAndUnavailablePermissionReadbacksAreUnverified() {
        confirmingDeviceIdleReadbacks()
        val result = SystemReset.run(runner, 36, PACKAGE, permissionGranted = { permission ->
            when (permission) {
                "android.permission.DUMP" -> null
                "android.permission.READ_LOGS" -> throw IllegalStateException("readback unavailable")
                else -> false
            }
        }) { ResetRestoreOutcome.COMPLETE }
        assertEquals(listOf(ResetCommandOutcome.UNVERIFIED, ResetCommandOutcome.UNVERIFIED,
            ResetCommandOutcome.OK, ResetCommandOutcome.OK, ResetCommandOutcome.OK),
            result.commands.drop(2).map { it.outcome })
        assertFalse(result.complete)
    }

    @Test fun missingPermissionPredicateNeverClaimsVerifiedRevocations() {
        confirmingDeviceIdleReadbacks()
        val result = SystemReset.run(runner, 36, PACKAGE) { ResetRestoreOutcome.COMPLETE }
        assertTrue(result.commands.drop(2).dropLast(1).all { it.outcome == ResetCommandOutcome.UNVERIFIED })
        assertEquals(ResetCommandOutcome.OK, result.commands.last().outcome)
        assertFalse(result.complete)
    }

    @Test fun deviceIdleReadbackAcceptsOnlyOneExactBit() {
        for (unknown in listOf("", "true", "01", "1 extra", "1\n1", "1\nOEM warning")) {
            val fake = resetRunner()
            fake.replies("cmd deviceidle enabled deep", unknown, " 1 ")
            fake.replies("cmd deviceidle enabled light", "0", "\n1\n")
            val result = SystemReset.run(fake, 36, PACKAGE, permissionGranted = { false }) { ResetRestoreOutcome.COMPLETE }
            assertEquals(unknown, ResetCommandOutcome.UNVERIFIED, result.commands[0].outcome)
            assertEquals(ResetCommandOutcome.OK, result.commands[1].outcome)
        }
    }

    @Test fun deviceIdleReadbackTimeoutAndTransportFailureCannotClaimSuccess() {
        runner.answer("cmd deviceidle enabled deep") { FakeRunner.result("0", timeout = true) }
        runner.answer("cmd deviceidle enabled deep") { FakeRunner.result("1", exit = 1) }
        runner.replies("cmd deviceidle enabled light", "0", "1")
        val result = SystemReset.run(runner, 36, PACKAGE, permissionGranted = { false }) { ResetRestoreOutcome.COMPLETE }
        assertEquals(ResetCommandOutcome.TIMEOUT, result.commands[0].outcome)
        assertEquals(ResetCommandOutcome.UNVERIFIED, result.commands[1].outcome)
        assertFalse(result.complete)
    }

    /** Stands in for Android killing this app's uid: an Error, so no catch (Exception) in the reset absorbs it. */
    private class ProcessKilled(command: String) : Error(command)

    /** Own-app grants as the platform holds them: revoking a granted runtime permission kills the uid. */
    private fun platformGrants(vararg held: String): MutableMap<String, Boolean> {
        val granted = held.associateWith { true }.toMutableMap()
        runner.beforeMutation = { command ->
            if (command.startsWith("pm revoke $PACKAGE ")) {
                val permission = command.substringAfterLast(' ')
                if (granted[permission] == true && permission == PHONE_STATE) throw ProcessKilled(command)
                granted[permission] = false
            }
        }
        return granted
    }

    @Test fun heldRuntimePermissionRevokeNeverRunsBeforeTheResultIsDelivered() {
        confirmingDeviceIdleReadbacks()
        val granted = platformGrants(PHONE_STATE)
        val result = try {
            SystemReset.run(runner, 36, PACKAGE, permissionGranted = { granted[it] ?: false }) { ResetRestoreOutcome.COMPLETE }
        } catch (killed: ProcessKilled) {
            fail("the process was killed before the reset result reached its callback: ${killed.message}")
            return
        }
        assertFalse("the self-killing revoke waits for the user's confirm",
            runner.commands.any { it.endsWith(PHONE_STATE) })
        assertTrue("steps after it still run", runner.commands.containsAll(listOf(
            "pm revoke $PACKAGE android.permission.WRITE_SECURE_SETTINGS",
            "appops set $PACKAGE WRITE_SETTINGS default")))
        assertTrue(result.commands.none { it.id == ResetCommandId.REVOKE_READ_PHONE_STATE })
        assertEquals(listOf(ResetCommandId.REVOKE_READ_PHONE_STATE), result.deferred)
        assertTrue("restore complete and every executed step confirmed", result.complete)

        // Only after the user's confirm: now the platform may kill the process.
        val killed = try {
            SystemReset.runDeferred(runner, 36, PACKAGE, result.deferred)
            null
        } catch (killed: ProcessKilled) { killed }
        assertEquals("pm revoke $PACKAGE $PHONE_STATE", killed?.message)
    }

    @Test fun deferredStepIsNeverCountedAsConfirmed() {
        confirmingDeviceIdleReadbacks()
        platformGrants(PHONE_STATE)
        // Held before, and never revoked here: a readback would say granted.
        val result = SystemReset.run(runner, 36, PACKAGE, permissionGranted = { it == PHONE_STATE }) {
            ResetRestoreOutcome.COMPLETE
        }
        assertEquals(6, result.commands.size)
        assertTrue(result.commands.all { it.outcome == ResetCommandOutcome.OK })
        assertEquals(listOf(ResetCommandId.REVOKE_READ_PHONE_STATE), result.deferred)
        assertFalse("the deferred step did not run, so it cannot be readback-confirmed",
            runner.commands.any { it.endsWith(PHONE_STATE) })
        assertFalse("a step that has not run is not OK",
            result.commands.any { it.id in result.deferred && it.outcome == ResetCommandOutcome.OK })
    }

    @Test fun notGrantedOrUnknownPhoneStateDecidesWhetherItWaits() {
        confirmingDeviceIdleReadbacks()
        platformGrants()
        val notHeld = SystemReset.run(runner, 36, PACKAGE, permissionGranted = { false }) { ResetRestoreOutcome.COMPLETE }
        assertTrue("revoking a permission not held kills nothing, so it runs and is read back in place",
            notHeld.deferred.isEmpty() && notHeld.commands.any { it.id == ResetCommandId.REVOKE_READ_PHONE_STATE })
        confirmingDeviceIdleReadbacks()
        confirmingWriteSettingsReadback(runner)
        val unknown = SystemReset.run(runner, 36, PACKAGE) { ResetRestoreOutcome.COMPLETE }
        assertEquals("unknown grant state is treated as held", listOf(ResetCommandId.REVOKE_READ_PHONE_STATE), unknown.deferred)
        val throwing = resetRunner()
        throwing.replies("cmd deviceidle enabled deep", "0", "1")
        throwing.replies("cmd deviceidle enabled light", "0", "1")
        val failedCheck = SystemReset.run(throwing, 36, PACKAGE, permissionGranted = { throw IllegalStateException("gone") }) {
            ResetRestoreOutcome.COMPLETE
        }
        assertEquals(listOf(ResetCommandId.REVOKE_READ_PHONE_STATE), failedCheck.deferred)
    }

    @Test fun deferredStepsNeedSessionAccessAndRunOnlyWhatWasDeferred() {
        SystemReset.runDeferred(runner, 36, PACKAGE, listOf(ResetCommandId.REVOKE_DUMP))
        assertTrue("only self-killing revokes are ever deferred", runner.commands.isEmpty())
        runner.level = AccessLevel.APP
        SystemReset.runDeferred(runner, 36, PACKAGE, listOf(ResetCommandId.REVOKE_READ_PHONE_STATE))
        assertTrue(runner.commands.isEmpty())
        runner.level = AccessLevel.SHELL
        SystemReset.runDeferred(runner, 36, PACKAGE, listOf(ResetCommandId.REVOKE_READ_PHONE_STATE))
        assertEquals(listOf("pm revoke $PACKAGE $PHONE_STATE"), runner.commands)
    }

    @Test fun invalidPackageNeverReachesTheShell() {
        try {
            SystemReset.run(runner, 36, "com.example;id") { ResetRestoreOutcome.COMPLETE }
            fail("reject invalid package")
        } catch (_: IllegalArgumentException) {
            assertTrue(runner.commands.isEmpty())
        }
    }

    companion object {
        private const val PACKAGE = "com.akylas.enforcedoze"
        private const val PHONE_STATE = "android.permission.READ_PHONE_STATE"
    }
}
