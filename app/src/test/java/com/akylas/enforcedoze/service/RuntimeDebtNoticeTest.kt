package com.akylas.enforcedoze.service

import com.akylas.enforcedoze.access.*
import com.akylas.enforcedoze.doze.*
import com.akylas.enforcedoze.monitor.EventCodes
import com.akylas.enforcedoze.ui.DebtRules
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class RuntimeDebtNoticeTest {
    private val notices = DebtNoticeStore()
    private var gate = DebtRules.NoticeGate(notices)
    private val ledger = InMemoryLedgerStore()
    private val runner = FakeRunner().apply { level = AccessLevel.NONE }
    private val events = mutableListOf<DozeEvent>()
    private val core = DozeController(
        runner, CommandCatalog, CapabilityResolver, ledger, FakeClock(),
        DozeEventSink(::emit), 36, Grants(true, true),
    )

    // The existing NoticeSink event mapping: individual VERIFY rows re-arm keys, not cancellation.
    private fun emit(event: DozeEvent) {
        events += event
        when (event.type) {
            EventType.RECOVERY_DEBT -> gate.offer(DebtRules.key(event.detail, event.target), false, notices::post)
            EventType.VERIFY -> if (event.reason == null) gate.clear(DebtRules.key(event.detail, event.target))
            EventType.SENSORS_RESTORED -> gate.clear(DebtRules.key(event.detail, event.target))
            EventType.ACCESS_CHANGED -> if (event.detail == "SHELL") gate.clear(EventCodes.ACCESS_LOST)
            else -> Unit
        }
    }

    private fun loseAccess() {
        ledger.save(RestoreLedger(listOf(
            LedgerEntry(Feature.FORCE_DOZE, null, "0", 0),
            LedgerEntry(Feature.BATTERY_SAVER, null, "0", 1),
            LedgerEntry(Feature.WIFI, null, "1", 2),
        )))
        emit(DozeEvent(EventType.RECOVERY_DEBT, EventCodes.ACCESS_LOST))
        assertFalse(core.reconcile().complete)
        assertEquals(3, events.count { it.type == EventType.RESTORE_FAILED })
        checkLedger()
        assertEquals(0, notices.cancels)
    }

    private fun restoreAccess(wifi: String = "1") {
        runner.level = AccessLevel.SHELL
        emit(DozeEvent(EventType.ACCESS_CHANGED, "SHELL"))
        runner.replies("dumpsys deviceidle", "mForceIdle=false")
        runner.replies("settings get global low_power", "0")
        runner.replies("settings get global wifi_on", wifi)
    }

    private fun checkLedger(damaged: Boolean = false) {
        // Session suppression is deliberately false: debt also exists during an active session.
        gate.ledgerChecked(DebtRules.isDebt(ledger.load().entries, damaged, false))
    }

    @Test fun accessLostThreeFailedRestoresThenThreeVerifiesCancelExactlyOnceWithoutUi() {
        loseAccess()
        restoreAccess()
        assertTrue(core.reconcile().complete)
        assertEquals(listOf(Feature.FORCE_DOZE, Feature.BATTERY_SAVER, Feature.WIFI),
            events.filter { it.type == EventType.VERIFY && it.reason == null }.map { it.feature })
        assertTrue(ledger.load().entries.isEmpty())
        assertEquals("VERIFY alone must not cancel before durable ledger cleanup", 0, notices.cancels)
        checkLedger()
        assertEquals("runtime clean-ledger observation cancels the posted notice", 1, notices.cancels)
        repeat(3) { checkLedger() }
        gate = DebtRules.NoticeGate(notices)
        checkLedger()
        assertEquals("screen cycles and a new process gate do not cancel again", 1, notices.cancels)
    }

    @Test fun oneRemainingDebtKeepsNoticeUntilLastEntryIsVerified() {
        loseAccess()
        restoreAccess(wifi = "0")
        assertFalse(core.reconcile().complete)
        assertEquals(Feature.WIFI, ledger.load().entries.single().feature)
        checkLedger()
        assertEquals("partial restore must retain the notice", 0, notices.cancels)
        val posts = notices.posts
        runner.replies("settings get global wifi_on", "0")
        core.reconcile()
        checkLedger()
        assertEquals("unchanged debt is not re-posted on every pass", posts, notices.posts)
        runner.replies("settings get global wifi_on", "1")
        assertTrue(core.reconcile().complete)
        checkLedger()
        assertEquals(1, notices.cancels)
    }

    @Test fun damagedOrUnreadableLedgerAndEventRaisedDebtKeepNotice() {
        gate.offer(EventCodes.LEDGER_DAMAGED, false, notices::post)
        checkLedger(damaged = true)
        gate.ledgerChecked(true) // Runtime also fails closed when load throws.
        assertEquals(0, notices.cancels)
        gate.offer("RESTORE_SENSORS", false, notices::post)
        checkLedger()
        assertEquals("clean ledger cannot disprove a failed SafetyNet readback", 0, notices.cancels)
        gate.clear("RESTORE_SENSORS")
        checkLedger()
        assertEquals(1, notices.cancels)
    }

    @Test fun successfulReadbackWithFailedLedgerCleanupDoesNotCancel() {
        loseAccess()
        restoreAccess()
        ledger.failSave = true
        assertFalse(core.reconcile().complete)
        assertEquals(3, events.count { it.type == EventType.VERIFY && it.reason == null })
        checkLedger()
        assertEquals("failed durable cleanup retains debt despite successful readbacks", 0, notices.cancels)
    }

    @Test fun uiRearmingCannotAuthorizeCancellationFromSessionSuppressedDebt() {
        gate.offer(Feature.WIFI.name, false, notices::post)
        gate.rearmFromLedger(false)
        assertEquals("UI session suppression must not cancel the runtime notice", 0, notices.cancels)
        val notice = File("src/main/java/com/akylas/enforcedoze/ui/NoticeSink.java").readText()
        val uiCheck = notice.substringAfter("public static void ledgerChecked(")
            .substringBefore("public static void restoresChecked(")
        assertTrue(uiCheck.contains("sink.debtGate.rearmFromLedger(debt)"))
        assertFalse(uiCheck.contains("sink.debtGate.ledgerChecked(debt)"))
    }

    @Test fun laterDebtPostsAgainButSuppressedOrBlockedPostDoesNotNeedCancel() {
        checkLedger()
        assertEquals(1, notices.cancels)
        gate.offer(Feature.WIFI.name, true, notices::post)
        checkLedger()
        gate.offer(Feature.WIFI.name, false) { false }
        checkLedger()
        assertEquals(1, notices.cancels)
        assertTrue(gate.offer(Feature.WIFI.name, false, notices::post))
        checkLedger()
        assertEquals(2, notices.cancels)
    }

    @Test fun runtimeChecksCommittedLedgerUnderControllerLockAfterSafetyIncludingEarlyReturns() {
        val source = File("src/main/java/com/akylas/enforcedoze/service/DozeRuntime.kt").readText()
        val safety = source.substringAfter("fun checkSafety() = synchronized(controller)")
            .substringBefore("private fun checkSafetyLocked()")
        assertTrue(safety.contains("finally { checkDebtNotice() }"))
        assertTrue(safety.contains("updateRuntimeDebtNotice(store::load"))
        assertTrue(safety.contains("runtimeLedgerDamaged(store.loadFailed) { store.corruptLines.isNotEmpty() }"))
        assertTrue(safety.contains("NoticeSink.restoresChecked(app, debt)"))
        val debtRule = source.substringAfter("internal fun updateRuntimeDebtNotice(")
        assertTrue(debtRule.contains("val ledger = load()"))
        assertTrue(debtRule.contains("DebtRules.isDebt(ledger.entries, damaged(), false)"))
        assertTrue("load failure cannot be treated as an empty ledger", debtRule.contains("catch (_: Exception) { true }"))
        val notice = File("src/main/java/com/akylas/enforcedoze/ui/NoticeSink.java").readText()
        assertTrue(notice.contains("sink.debtGate.ledgerChecked(debt)"))
        assertTrue(notice.contains("if (event.getReason() == null) debtGate.clear(DebtRules.key(detail, event.getTarget()))"))
        assertTrue(notice.contains("if (privileged) debtGate.clear(EventCodes.ACCESS_LOST)"))
    }
}
