package com.akylas.enforcedoze.service

import com.akylas.enforcedoze.ExternalControlReceiver.Admission
import com.akylas.enforcedoze.MyApplication.LazyJournal
import com.akylas.enforcedoze.access.ExternalCallRateLimiter
import com.akylas.enforcedoze.access.ExternalControlPolicy
import com.akylas.enforcedoze.access.ExternalControlPolicy.Action
import com.akylas.enforcedoze.access.ExternalControlPolicy.Decision
import com.akylas.enforcedoze.access.ExternalControlPolicy.DenialReason
import com.akylas.enforcedoze.access.Feature
import com.akylas.enforcedoze.access.Prefs
import com.akylas.enforcedoze.doze.DozeEvent
import com.akylas.enforcedoze.doze.DozeEventSink
import com.akylas.enforcedoze.doze.EventType
import com.akylas.enforcedoze.monitor.JournalIdentity
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** Executes production ownership/admission seams without Android construction or mocking. */
class LazyJournalTest {
    private class Journal {
        val bootId = 7
        val identity = JournalIdentity()
        val sinks = EventSinks()
        val rows = mutableListOf<DozeEvent>()
        fun emit(event: DozeEvent) {
            rows += event
            sinks.emit(event)
        }
    }

    private class Owner {
        val lazy = LazyJournal<Journal>()
        val journals = AtomicInteger()
        val notices = AtomicInteger()
        val runtimes = AtomicInteger()
        val delivered = mutableListOf<DozeEvent>()
        fun journal(): Journal = lazy.get({ journals.incrementAndGet(); Journal() }, {
            notices.incrementAndGet()
            it.sinks.addSink(DozeEventSink { event -> delivered += event })
        })
        fun runtime(): Journal { runtimes.incrementAndGet(); return journal() }
    }

    @Test fun bothClosedGatesSkipHostileExtrasAndRuntimeFactory() {
        for (action in listOf(Action.ENABLE_SERVICE, Action.ADD_WHITELIST)) {
            val owner = Owner()
            var decodes = 0
            val reasons = mutableListOf<DenialReason>()
            Admission.run(ExternalControlPolicy.evaluate(action, false, false),
                { decodes++; error("hostile extras must not be decoded") },
                { Decision() }, { reasons += it; owner.journal() }, { owner.runtime() })
            assertEquals(0, decodes)
            assertEquals(0, owner.runtimes.get())
            assertEquals(1, owner.journals.get())
            assertEquals(listOf(if (action.privileged) DenialReason.PRIVILEGED_CONTROL_DISABLED
                else DenialReason.BASIC_CONTROL_DISABLED), reasons)
        }
    }

    @Test fun malformedAndPolicyInvalidInputsKeepReasonsWithoutRuntime() {
        val cases = listOf<Pair<() -> String?, DenialReason>>(
            { throw IllegalArgumentException("bad parcel type") } to DenialReason.INVALID_EXTRA,
            { Prefs.EXECUTION_MODE } to DenialReason.PROTECTED_SETTING,
            { "untrusted unknown key" } to DenialReason.UNKNOWN_SETTING,
            { "disableWhenCharging" } to DenialReason.INVALID_BOOLEAN,
        )
        for ((decode, expected) in cases) {
            val owner = Owner()
            val reasons = mutableListOf<DenialReason>()
            Admission.run(ExternalControlPolicy.evaluate(Action.CHANGE_SETTING, true, true),
                decode, { ExternalControlPolicy.evaluate(Action.CHANGE_SETTING, true, true, it, "invalid") },
                { reasons += it; owner.journal() }, { owner.runtime() })
            assertEquals(listOf(expected), reasons)
            assertEquals(0, owner.runtimes.get())
        }
        var runtimeCalls = 0
        val reasons = mutableListOf<DenialReason>()
        Admission.run(ExternalControlPolicy.evaluate(Action.ADD_WHITELIST, true, true),
            { "not a package; hostile" },
            { ExternalControlPolicy.evaluate(Action.ADD_WHITELIST, true, true, packageName = it) },
            { reasons += it }, { runtimeCalls++ })
        assertEquals(listOf(DenialReason.INVALID_PACKAGE), reasons)
        assertEquals(0, runtimeCalls)
    }

    @Test fun admittedRequestInvokesRuntimeFactoryOnceWithDecodedInput() {
        val owner = Owner()
        var decodes = 0
        var received: String? = null
        Admission.run(ExternalControlPolicy.evaluate(Action.ADD_WHITELIST, true, true),
            { decodes++; "com.example.app" },
            { ExternalControlPolicy.evaluate(Action.ADD_WHITELIST, true, true, packageName = it) },
            { fail("unexpected denial: $it") }, { received = it; owner.runtime() })
        assertEquals("com.example.app", received)
        assertEquals(1, decodes)
        assertEquals(1, owner.runtimes.get())
        assertEquals(1, owner.journals.get())
        assertEquals(1, owner.notices.get())
    }

    @Test fun journalFirstAndRuntimeFirstShareIdentityAndOneNoticeRegistration() {
        for (runtimeFirst in listOf(false, true)) {
            val owner = Owner()
            val first = if (runtimeFirst) owner.runtime() else owner.journal()
            first.identity.beginSession(100)
            first.emit(DozeEvent(EventType.SCREEN_OFF, "session"))
            val second = if (runtimeFirst) owner.journal() else owner.runtime()
            assertSame(first, second)
            assertEquals(7, second.bootId)
            assertEquals(100L, second.identity.sessionId)
            second.identity.beginSelfTest(Feature.FORCE_DOZE, 200)
            assertEquals(-200L, first.identity.forEvent(Feature.FORCE_DOZE))
            second.identity.endSelfTest()
            assertEquals(100L, first.identity.forEvent(null))
            second.emit(DozeEvent(EventType.EXTERNAL_CALL, "denied"))
            assertEquals(1, owner.journals.get())
            assertEquals(1, owner.notices.get())
            assertEquals(first.rows, owner.delivered)
            assertEquals(2, owner.delivered.size)
        }
    }

    @Test fun noticeRegistrationFailureKeepsTheFirstJournal() {
        val lazy = LazyJournal<Journal>()
        val journals = mutableListOf<Journal>()
        var registrations = 0
        fun journal(): Journal = lazy.get({ Journal().also { journals += it } }, {
            registrations++
            if (registrations == 1) error("notice registration failed")
        })

        assertThrows(IllegalStateException::class.java) { journal() }
        assertSame("registration failure must not replace the published journal", journals[0], journal())
        assertSame(journals[0], journal())
        assertEquals("the factory must run once even if registration throws", 1, journals.size)
        assertEquals("notice registration is attempted once", 1, registrations)
    }

    @Test fun concurrentJournalAndRuntimeCallsConstructAndRegisterOnce() {
        val owner = Owner()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = (0..1).map { index -> pool.submit(Callable {
                check(start.await(2, TimeUnit.SECONDS))
                if (index == 0) owner.journal() else owner.runtime()
            }) }
            start.countDown()
            val journals = futures.map { it.get(2, TimeUnit.SECONDS) }
            assertSame(journals[0], journals[1])
            assertEquals(1, owner.journals.get())
            assertEquals(1, owner.notices.get())
            journals[0].emit(DozeEvent(EventType.EXTERNAL_CALL, "denied"))
            assertEquals(1, owner.delivered.size)
        } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test fun suppressionSkipsJournalFactoryAndRetainsNextAdmittedSummary() {
        val owner = Owner()
        var now = 0L
        var lookups = 0
        val limiter = ExternalCallRateLimiter({ now })
        val summaries = mutableListOf<Long>()
        fun journal() = Admission.journal(limiter, Action.ADD_WHITELIST,
            { lookups++; owner.journal() }, { sink, count ->
                summaries += count
                sink.emit(DozeEvent(EventType.EXTERNAL_CALL, "action=ADD_WHITELIST suppressed=$count"))
            })
        repeat(10) { assertSame(owner.journal(), journal()) }
        repeat(3) { assertNull(journal()) }
        assertEquals(10, lookups)
        assertEquals(1, owner.journals.get())
        assertEquals(0, owner.runtimes.get())
        assertTrue(summaries.isEmpty())
        now = 60_000
        val admitted = journal()
        assertSame(owner.journal(), admitted)
        assertEquals(listOf(3L), summaries)
        admitted?.emit(DozeEvent(EventType.EXTERNAL_CALL, "denied action=ADD_WHITELIST"))
        assertEquals(2, owner.delivered.size)
        journal()
        assertEquals(listOf(3L), summaries)
        assertEquals(1, owner.journals.get())
        assertEquals(0, owner.runtimes.get())
    }
}
