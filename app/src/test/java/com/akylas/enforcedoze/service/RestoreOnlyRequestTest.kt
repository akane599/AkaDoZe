package com.akylas.enforcedoze.service

import com.akylas.enforcedoze.access.*
import com.akylas.enforcedoze.doze.*
import org.junit.Assert.*
import org.junit.Test

class RestoreOnlyRequestTest {
    @Test fun armedContinuationPlusOpenWindowsRestoresKPlusOneWithoutDuplicateWrites() {
        val fixture = RestoreWindowFixture()
        val store = CountingLedgerStore()
        val runner = FakeRunner()
        val intent = RestoreLedger(listOf(LedgerEntry(Feature.FORCE_DOZE, null, "0", 0, apiLevel = 36)))
        store.save(intent)
        runner.beforeMutation = { assertEquals("mutation stays ledger-backed", intent, store.load()) }
        runner.replies("dumpsys deviceidle", "mForceIdle=false")
        val controller = controller(runner, store)
        fixture.restore = { controller.reconcile() }
        fixture.pending = { store.load().entries.isNotEmpty() }
        val expired = fixture.start()
        fixture.drainMain()
        expired()
        val openWindows = 2
        repeat(openWindows) { fixture.start() }
        fixture.drainMain()
        fixture.ready()
        fixture.drain()

        assertEquals("K open windows plus one shared follow-up", openWindows + 1, fixture.restores)
        assertEquals("only one follow-up", 1, fixture.retries)
        assertEquals("initial intent and one durable cleanup, no duplicate writes", 2, store.writes)
        assertEquals("no duplicate system mutation", listOf("cmd deviceidle unforce"), runner.mutations())
        assertTrue(store.load().entries.isEmpty())
        assertTrue(fixture.access.listeners.isEmpty())
    }

    @Test fun deadlineCutShortShellRestoreArmsOneFollowUpForRetainedIntent() {
        val fixture = RestoreWindowFixture()
        val store = CountingLedgerStore()
        val intent = RestoreLedger(listOf(LedgerEntry(Feature.FORCE_DOZE, null, "0", 0, apiLevel = 36)))
        store.save(intent)
        val runner = FakeRunner()
        runner.afterCommand = { fixture.now = 9_000L }
        runner.replies("dumpsys deviceidle", "mForceIdle=false")
        val controller = controller(runner, store)
        fixture.restore = { deadline -> controller.exit(admission = { fixture.now < deadline }) }
        fixture.pending = { store.load().entries.isNotEmpty() }
        fixture.ready()
        fixture.start()
        fixture.drainMain()
        fixture.runWorker()
        assertEquals("deadline leaves durable intent and attempts untouched", intent, store.load())
        fixture.drainMain()
        assertEquals("unfinished admitted restore still hands off", 1, fixture.retries)
        fixture.drain()
        assertEquals(2, fixture.restores)
        assertTrue(store.load().entries.isEmpty())
        assertEquals(2, store.writes)
    }

    @Test fun unresolvedWorkerSkipStillArmsWhenAccessSettlesBeforeCompletion() {
        val fixture = RestoreWindowFixture()
        var pending = true
        fixture.pending = { pending }
        fixture.restore = { if (fixture.access.state.resolved) pending = false }
        fixture.ready()
        fixture.start()
        fixture.drainMain()
        fixture.access.state = fixture.access.state.copy(resolved = false)
        fixture.runWorker()
        fixture.access.state = fixture.access.state.copy(resolved = true)
        fixture.drainMain()
        assertEquals("a skipped restore cannot masquerade as completed recovery", 1, fixture.retries)
        fixture.drain()
        assertFalse(pending)
        assertEquals(2, fixture.restores)
    }

    @Test fun unreadableLedgerHandsOffWithoutAnyWrite() {
        val fixture = RestoreWindowFixture()
        var writes = 0
        val store = object : LedgerStore {
            override fun load(): RestoreLedger = throw IllegalStateException("unreadable")
            override fun save(ledger: RestoreLedger) { writes++ }
        }
        val controller = controller(FakeRunner(), store)
        fixture.restore = { controller.reconcile() }
        fixture.pending = { try { store.load().entries.isNotEmpty() } catch (_: Exception) { true } }
        fixture.ready()
        fixture.start()
        fixture.drain()
        assertEquals("unreadable intent still needs recovery", 1, fixture.retries)
        assertEquals("terminal follow-up cannot loop", 2, fixture.restores)
        assertEquals("never overwrite unreadable intent", 0, writes)
        assertTrue(fixture.access.listeners.isEmpty())
    }

    @Test fun freshFollowUpIsAcquiredBeforeOldWindowCompletes() {
        val fixture = RestoreWindowFixture()
        fixture.ready()
        fixture.start { fixture.events += "release old" }
        fixture.drainMain()
        fixture.now = 7_001L
        fixture.runWorker()
        fixture.drainMain()
        assertEquals("handoff precedes old wakelock release", listOf("acquire follow-up", "release old"), fixture.events)
        fixture.drain()
    }

    @Test fun terminalStarvedWindowWithCleanLedgerDoesNotReportDebt() {
        val fixture = RestoreWindowFixture()
        val store = InMemoryLedgerStore()
        fixture.pending = { store.load().entries.isNotEmpty() }
        fixture.ready()
        val timeout = fixture.start(allowContinuation = false)
        fixture.drainMain()
        fixture.now = 7_001L
        fixture.runWorker()
        fixture.drainMain()
        timeout()
        fixture.ready()
        fixture.drain()
        assertEquals("A healthy empty ledger owes no restore", 0, fixture.skips)
        assertEquals("No attempt below the floor", 0, fixture.restores)
        assertEquals(0, fixture.retries)
        assertTrue(fixture.access.listeners.isEmpty())
    }

    @Test fun terminalStarvedWindowReportsDebtOnlyOnceWithoutRestore() {
        val fixture = RestoreWindowFixture()
        fixture.pending = { true }
        fixture.ready()
        val timeout = fixture.start(allowContinuation = false)
        fixture.drainMain()
        fixture.now = 7_001L
        fixture.runWorker()
        fixture.drainMain()
        timeout()
        fixture.ready()
        fixture.drain()
        assertEquals(0, fixture.restores)
        assertEquals(1, fixture.skips)
        assertEquals(0, fixture.retries)
    }

    @Test fun completedReadyRestoreNeedsNoFollowUp() {
        val fixture = RestoreWindowFixture()
        fixture.ready()
        fixture.start()
        fixture.drain()
        assertEquals(1, fixture.restores)
        assertEquals(0, fixture.retries)
        assertEquals(0, fixture.skips)
    }

    private fun controller(runner: FakeRunner, store: LedgerStore) = DozeController(
        runner, CommandCatalog, CapabilityResolver, store, FakeClock(), DozeEventSink {}, 36, Grants(false, false),
    )

    private class CountingLedgerStore : LedgerStore {
        private val delegate = InMemoryLedgerStore()
        var writes = 0
        override fun load() = delegate.load()
        override fun save(ledger: RestoreLedger) { delegate.save(ledger); writes++ }
    }
}

/** Same main-post and serial-worker ordering as DozeRuntime, without Android framework stubs. */
internal class RestoreWindowFixture {
    val access = WindowAccess()
    var now = 0L
    var restores = 0
    var retries = 0
    var skips = 0
    var pending: () -> Boolean = { false }
    var restore: (Long) -> Unit = {}
    val events = mutableListOf<String>()
    private val main = ArrayDeque<() -> Unit>()
    private val worker = ArrayDeque<() -> Unit>()
    private val continuation = RestoreContinuation(access, { main.addLast(it) }, {
        retries++
        events += "acquire follow-up"
        start(allowContinuation = false)
    }, {}, {})

    fun ready() = access.publish(access.state.copy(resolved = true, level = AccessLevel.SHELL))

    fun start(allowContinuation: Boolean = true, completed: () -> Unit = {}): () -> Unit {
        lateinit var timeout: () -> Unit
        RestoreOnlyRequest(access, { now }, { main.addLast(it) }, { deadline, action ->
            timeout = { now = maxOf(now, deadline); action() }
            ({})
        }, { worker.addLast(it) }, { deadline -> restores++; restore(deadline) }, completed,
            if (allowContinuation) continuation else null, { skips++ }, { pending() }).start()
        return timeout
    }

    fun drainMain() { while (main.isNotEmpty()) main.removeFirst()() }
    fun runWorker() { worker.removeFirst()() }
    fun drain() {
        drainMain()
        while (worker.isNotEmpty()) { runWorker(); drainMain() }
    }

    class WindowAccess : RecoveryAccess {
        override var state = AccessState(AccessLevel.APP, Reason.NO_ACCESS, Grants(false, false), 10001, resolved = false)
        val listeners = linkedSetOf<AccessManager.Listener>()
        override fun addListener(listener: AccessManager.Listener) { listeners += listener; listener.onAccessChanged(state) }
        override fun removeListener(listener: AccessManager.Listener) { listeners -= listener }
        fun publish(next: AccessState) { state = next; listeners.toList().forEach { it.onAccessChanged(next) } }
    }
}
