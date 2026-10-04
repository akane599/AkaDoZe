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

class SystemResetTest {
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
        var callbacks = 0
        var notices = 0
        tracker.setListener { notices++ }
        val callback = SystemResetCallback { result ->
            callbacks++
            // Null prefs: a failed job must return before even accessing the preference store.
            tracker.deliver(result, ResetReport.clearPreferences(null, result))
            tracker.notifyListener()
        }
        try {
            val result = SystemReset.runJob(job)
            callback.onComplete(result)
        } catch (_: Exception) {
            // The old worker's finally retires the worker, but never delivers a result.
        }
        assertEquals("a throwing reset job must still deliver one result", 1, callbacks)
        assertEquals("the current screen is notified", 1, notices)
        assertEquals(ResetReport.Tracker.Phase.REPORTED, tracker.phase())
        assertTrue("the exception is identified separately from ordinary partial outcomes", tracker.result().failed)
        assertFalse("an exception is never complete", tracker.result().complete)
        assertFalse("preferences were not cleared", tracker.prefsCleared())
        assertTrue("a failed job schedules no deferred revoke", tracker.result().deferred.isEmpty())
        assertNull("OK dismisses a failed job, without scheduling a restart", tracker.confirm())
        assertEquals(ResetReport.Tracker.Phase.IDLE, tracker.phase())
        assertTrue("after dismissing the failure the user can reset again", tracker.begin())
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
