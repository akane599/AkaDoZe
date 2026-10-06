package com.akylas.enforcedoze.service

import com.akylas.enforcedoze.access.*
import com.akylas.enforcedoze.doze.*
import org.junit.Assert.*
import org.junit.Test

class RuntimeGlueRulesTest {
    @Test fun readbackReturnsOnlySuccessfulStdoutAndUsesEightSecondTimeout() {
        var result = CommandResult(0, listOf("answer"), emptyList(), 0, false)
        var throws = false
        val runner = object : CommandRunner {
            override val level = AccessLevel.APP
            override fun run(command: String, timeoutMs: Long): CommandResult {
                assertEquals("read", command); assertEquals(8_000L, timeoutMs)
                if (throws) throw IllegalStateException("read error")
                return result
            }
        }
        assertEquals(listOf("answer"), readRuntimeCommand(runner, "read"))
        result = result.copy(exitCode = 1)
        assertEquals(emptyList<String>(), readRuntimeCommand(runner, "read"))
        throws = true
        assertEquals(emptyList<String>(), readRuntimeCommand(runner, "read"))
    }

    @Test fun debtLoadExceptionMeansDebtAndNoticeExceptionIsLoggedAndSwallowed() {
        val error = IllegalStateException("notice")
        val calls = mutableListOf<String>()
        updateRuntimeDebtNotice({ throw IllegalStateException("ledger") }, { fail("not read after load throws"); false },
            { debt -> assertTrue("ledger exception means debt", debt); calls += "notice"; throw error },
            { message, actual -> assertEquals("Debt notice update failed", message); assertSame(error, actual); calls += "log" })
        assertEquals(listOf("notice", "log"), calls)
    }

    @Test fun committedDebtRemainsInActiveSessionAndHealthyLedgerClearsNotice() {
        val entry = LedgerEntry(Feature.WIFI, "wifi", "on", 1, debt = true)
        for ((ledger, damaged, expected) in listOf(
            Triple(RestoreLedger(), false, false),
            Triple(RestoreLedger(), true, true),
            Triple(RestoreLedger(listOf(entry)), false, true),
            Triple(RestoreLedger(listOf(entry.copy(debt = false, attempts = 1))), false, true),
            Triple(RestoreLedger(listOf(entry.copy(debt = false))), false, false),
        )) {
            val session = SessionLifecycle(); session.activate(1, { 1 }, { false })
            assertTrue(session.active)
            var noticed: Boolean? = null
            updateRuntimeDebtNotice({ ledger }, { damaged }, { noticed = it }, { _, _ -> fail("no notice error") })
            assertEquals("runtime debt does not suppress an active session", expected, noticed)
        }
    }

    @Test fun damageShortCircuitsCorruptionReadOnlyForLoadFailure() {
        assertTrue(runtimeLedgerDamaged(true) { fail("short-circuit"); false })
        assertTrue(runtimeLedgerDamaged(false) { true })
        assertFalse(runtimeLedgerDamaged(false) { false })
    }
}
