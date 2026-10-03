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
import org.junit.Assert.*
import org.junit.Test

class SystemResetTest {
    private val runner = FakeRunner()
    private val store = InMemoryLedgerStore()
    private val controller = DozeController(runner, CommandCatalog, CapabilityResolver, store,
        FakeClock(), DozeEventSink {}, 36, Grants(true, true))

    @Test fun ledgerRestoreAndReadbackFinishBeforeResetOrRevocations() {
        store.save(RestoreLedger(listOf(LedgerEntry(Feature.FORCE_DOZE, null, "0", 0, apiLevel = 36))))
        runner.replies("dumpsys deviceidle", "mForceIdle=false")
        var restored = false
        runner.beforeMutation = { command ->
            if (command != "cmd deviceidle unforce") {
                assertTrue("restore returned before any reset/revoke command", restored)
                assertTrue("restore readback and durable cleanup precede revocations", store.load().entries.isEmpty())
            }
        }
        val result = SystemReset.run(runner, 36, PACKAGE) {
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
        val result = SystemReset.run(runner, 36, PACKAGE) { ResetRestoreOutcome.REMAINING_DEBT }
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
        val result = SystemReset.run(runner, 23, PACKAGE) { throw IllegalStateException("unreadable ledger") }
        assertEquals(ResetRestoreOutcome.REMAINING_DEBT, result.restoreOutcome)
        assertEquals("dumpsys deviceidle disable", runner.commands.first())
        assertEquals("dumpsys deviceidle enable", runner.commands[1])
        assertFalse(result.complete)
    }

    @Test fun unreadableLedgerRemainsDebtAndIsNeverOverwritten() {
        var saves = 0
        val unreadable = object : com.akylas.enforcedoze.doze.LedgerStore {
            override fun load(): RestoreLedger = throw IllegalStateException("unreadable ledger")
            override fun save(ledger: RestoreLedger) { saves++ }
        }
        val core = DozeController(runner, CommandCatalog, CapabilityResolver, unreadable,
            FakeClock(), DozeEventSink {}, 36, Grants(true, true))
        val result = SystemReset.run(runner, 36, PACKAGE) {
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
            val result = SystemReset.run(runner, 36, PACKAGE) {
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

    @Test fun invalidPackageNeverReachesTheShell() {
        try {
            SystemReset.run(runner, 36, "com.example;id") { ResetRestoreOutcome.COMPLETE }
            fail("reject invalid package")
        } catch (_: IllegalArgumentException) {
            assertTrue(runner.commands.isEmpty())
        }
    }

    companion object { private const val PACKAGE = "com.akylas.enforcedoze" }
}
