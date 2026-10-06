package com.akylas.enforcedoze.service

import com.akylas.enforcedoze.access.*
import com.akylas.enforcedoze.doze.*
import com.akylas.enforcedoze.monitor.EventCodes
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import com.akylas.enforcedoze.MyApplication
import com.akylas.enforcedoze.ui.NoticeSink
import com.akylas.enforcedoze.ui.DebtRules
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RuntimeDebtNoticeTest {
    private val app = RuntimeEnvironment.getApplication()
    private val manager = app.getSystemService(NotificationManager::class.java)
    private fun debtNotice() = shadowOf(manager).getNotification(8802)

    @Before fun allowNotices() { shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS) }

    @After fun resetNoticeState() {
        NoticeSink::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        (NoticeSink::class.java.getDeclaredField("debtViews").apply { isAccessible = true }.get(null) as AtomicInteger).set(0)
        MyApplication::class.java.getDeclaredField("context").apply { isAccessible = true }.set(null, null)
        MyApplication::class.java.getDeclaredField("dozeRuntime").apply { isAccessible = true }.set(null, null)
        app.getSharedPreferences("notices", Context.MODE_PRIVATE).edit().clear().commit()
    }
    private val notices = DebtNoticeStore()
    private var gate = DebtRules.NoticeGate(notices)
    private val ledger = InMemoryLedgerStore()
    private val runner = FakeRunner().apply { level = AccessLevel.NONE }
    private val events = mutableListOf<DozeEvent>()
    private val core = DozeController(
        runner, CommandCatalog, CapabilityResolver, ledger, FakeClock(),
        DozeEventSink(::emit), 36, Grants(true, true),
    )

    private fun emit(event: DozeEvent) {
        events += event
        NoticeSink.get(app).emit(event)
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
        assertNotNull("Outstanding restore debt keeps the native notice", debtNotice())
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
        NoticeSink.restoresChecked(app, DebtRules.ledgerState(ledger.load().entries, damaged))
    }

    @Test fun accessLostThreeFailedRestoresThenThreeVerifiesCancelExactlyOnceWithoutUi() {
        loseAccess()
        restoreAccess()
        assertTrue(core.reconcile().complete)
        assertEquals(listOf(Feature.FORCE_DOZE, Feature.BATTERY_SAVER, Feature.WIFI),
            events.filter { it.type == EventType.VERIFY && it.reason == null }.map { it.feature })
        assertTrue(ledger.load().entries.isEmpty())
        assertNotNull("VERIFY alone must not cancel before durable ledger cleanup", debtNotice())
        checkLedger()
        assertNull("runtime clean-ledger observation cancels the posted notice", debtNotice())
        repeat(3) { checkLedger() }
        NoticeSink::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        checkLedger()
        assertNull("screen cycles and a new process gate keep the notice settled", debtNotice())
        assertFalse(app.getSharedPreferences("notices", Context.MODE_PRIVATE).getBoolean("debtPosted", true))
    }

    @Test fun oneRemainingDebtKeepsNoticeUntilLastEntryIsVerified() {
        loseAccess()
        restoreAccess(wifi = "0")
        assertFalse(core.reconcile().complete)
        assertEquals(Feature.WIFI, ledger.load().entries.single().feature)
        checkLedger()
        assertNotNull("partial restore must retain the notice", debtNotice())
        val posted = debtNotice()
        runner.replies("settings get global wifi_on", "0")
        core.reconcile()
        checkLedger()
        assertSame("unchanged debt is not re-posted on every pass", posted, debtNotice())
        runner.replies("settings get global wifi_on", "1")
        assertTrue(core.reconcile().complete)
        checkLedger()
        assertNull("The last durable restore cancels the native notice", debtNotice())
    }

    @Test fun damagedOrUnreadableLedgerAndEventRaisedDebtKeepNotice() {
        gate.offer(EventCodes.LEDGER_DAMAGED, false, notices::post)
        gate.ledgerChecked(true)
        gate.ledgerChecked(true) // Runtime also fails closed when load throws.
        assertEquals(0, notices.cancels)
        gate.offer("RESTORE_SENSORS", false, notices::post)
        gate.ledgerChecked(false)
        assertEquals("clean ledger cannot disprove a failed SafetyNet readback", 0, notices.cancels)
        gate.clear("RESTORE_SENSORS")
        gate.ledgerChecked(false)
        assertEquals(1, notices.cancels)
    }

    @Test fun successfulReadbackWithFailedLedgerCleanupDoesNotCancel() {
        loseAccess()
        restoreAccess()
        ledger.failSave = true
        assertFalse(core.reconcile().complete)
        assertEquals(3, events.count { it.type == EventType.VERIFY && it.reason == null })
        checkLedger()
        assertNotNull("failed durable cleanup retains debt despite successful readbacks", debtNotice())
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
        gate.ledgerChecked(false)
        assertEquals(1, notices.cancels)
        gate.offer(Feature.WIFI.name, true, notices::post)
        gate.ledgerChecked(false)
        gate.offer(Feature.WIFI.name, false) { false }
        gate.ledgerChecked(false)
        assertEquals(1, notices.cancels)
        assertTrue(gate.offer(Feature.WIFI.name, false, notices::post))
        gate.ledgerChecked(false)
        assertEquals(2, notices.cancels)
    }

    @Test fun unattemptedRestoreKeepsStarvationNoticeAfterDebtFreeCheck() {
        ledger.save(RestoreLedger(listOf(LedgerEntry(Feature.WIFI, null, "1", 0))))
        emit(DozeEvent(EventType.RECOVERY_DEBT, EventCodes.RESTORE_WINDOW_STARVED))
        val debt = DebtRules.isDebt(ledger.load().entries, false, false)
        assertFalse("An unattempted restore is not attempted debt", debt)
        NoticeSink.restoresChecked(app, debt)
        assertNotNull("Unattempted durable intent must retain the starvation notice", debtNotice())
    }

    @Test fun uiDebtFreeCheckCannotCancelPendingStarvation() {
        emit(DozeEvent(EventType.RECOVERY_DEBT, EventCodes.RESTORE_WINDOW_STARVED))
        NoticeSink.ledgerChecked(app, false)
        NoticeSink.cancelDebt(app)
        assertNotNull("UI debt-free reads do not establish an empty ledger", debtNotice())
        assertTrue(app.getSharedPreferences("notices", Context.MODE_PRIVATE)
            .getStringSet("debtNotified", emptySet())!!.contains(EventCodes.RESTORE_WINDOW_STARVED))
    }

    @Test fun runtimeStarvationSettlesOnlyAfterReadableEmptyLedger() {
        ledger.save(RestoreLedger(listOf(LedgerEntry(Feature.WIFI, null, "1", 0))))
        emit(DozeEvent(EventType.RECOVERY_DEBT, EventCodes.RESTORE_WINDOW_STARVED))
        val posted = debtNotice()
        fun check(load: () -> RestoreLedger = ledger::load, damaged: () -> Boolean = { false }) {
            updateRuntimeDebtNotice(load, damaged, { NoticeSink.restoresChecked(app, it) },
                { _, _ -> fail("Notice update should succeed") })
        }
        check()
        assertSame("Runtime must retain unattempted pending intent", posted, debtNotice())
        NoticeSink::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        check()
        assertSame("Persisted starvation survives a recreated gate", posted, debtNotice())
        ledger.save(RestoreLedger())
        check(damaged = { true })
        assertNotNull("Corrupt lines prevent empty-ledger settlement", debtNotice())
        check(load = { throw IllegalStateException("unreadable") })
        assertNotNull("Unreadable intent cannot settle starvation", debtNotice())
        check()
        assertNull("Readable empty ledger settles starvation", debtNotice())
        check()
        assertFalse(app.getSharedPreferences("notices", Context.MODE_PRIVATE).getBoolean("debtPosted", true))
        emit(DozeEvent(EventType.RECOVERY_DEBT, EventCodes.RESTORE_WINDOW_STARVED))
        assertNotNull("A later starved window is re-armed", debtNotice())
    }

    @Test fun terminalSkipIsWiredToJournaledStarvationDebt() {
        val source = File("src/main/java/com/akylas/enforcedoze/service/DozeRuntime.kt").readText()
        val window = source.substringAfter("RestoreOnlyRequest(").substringBefore(").start(deadline)")
        val wiring = Regex("""shared\s*,\s*\{\s*journal\.emit\(DozeEvent\(EventType\.RECOVERY_DEBT,\s*EventCodes\.RESTORE_WINDOW_STARVED\)\)\s*}\s*,\s*::hasPendingRestore""")
        assertTrue("Terminal skipped callback must journal RECOVERY_DEBT/RESTORE_WINDOW_STARVED before the pending probe", wiring.containsMatchIn(window))
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
        assertTrue(debtRule.contains("DebtRules.ledgerState(ledger.entries, damaged())"))
        assertTrue("load failure cannot be treated as an empty ledger", debtRule.contains("catch (_: Exception) { DebtRules.LedgerState.DEBT }"))
        val notice = File("src/main/java/com/akylas/enforcedoze/ui/NoticeSink.java").readText()
        assertTrue(notice.contains("sink.debtGate.ledgerChecked(ledger)"))
        assertTrue(notice.contains("if (event.getReason() == null) debtGate.clear(DebtRules.key(detail, event.getTarget()))"))
        assertTrue(notice.contains("if (privileged) debtGate.clear(EventCodes.ACCESS_LOST)"))
    }
}
