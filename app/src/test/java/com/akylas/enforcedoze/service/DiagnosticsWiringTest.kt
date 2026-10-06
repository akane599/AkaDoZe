package com.akylas.enforcedoze.service

import com.akylas.enforcedoze.doze.DozeEvent
import com.akylas.enforcedoze.doze.DozeEventSink
import com.akylas.enforcedoze.doze.EventType
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class DiagnosticsWiringTest {
    @Test fun throwingSubscriberIsLoggedAndOtherSubscribersKeepEventOrder() {
        val error = IllegalStateException("subscriber gone")
        val logged = mutableListOf<Throwable>()
        val events = mutableListOf<DozeEvent>()
        val sinks = EventSinks { _, failure -> logged += failure }
        sinks.addSink(DozeEventSink { throw error })
        sinks.addSink(DozeEventSink { events += it })
        val first = DozeEvent(EventType.IDLE_CHANGED, "IDLE_CHANGED")
        val second = DozeEvent(EventType.ERROR, "FEATURE_SELECTION_FAILED")
        sinks.emit(first)
        sinks.emit(second)
        assertEquals(listOf(first, second), events)
        assertEquals(listOf(error, error), logged)
    }

    @Test fun selfTestWorkAndCallbackFailuresAreLoggedWithoutChangingOneShotCompletion() {
        val workError = IllegalStateException("test work failed")
        val callbackError = IllegalStateException("presentation failed")
        val logged = mutableListOf<Throwable>()
        val queue = SelfTestQueue { _, error -> logged += error }
        val tasks = mutableListOf<Runnable>()
        var completed = 0
        queue.attach()
        queue.request(SelfTestKind.DOZE, { result ->
            completed++
            assertEquals(SelfTestOutcome.FAILED, result.outcome)
            throw callbackError
        }, { tasks += it; true }, { throw workError })
        tasks.single().run()
        assertEquals(1, completed)
        assertEquals(listOf(workError, callbackError), logged)
    }

    @Test fun androidAdaptersWireDiagnosticLoggersAndObserveJournalWriteFailures() {
        val runtime = File("src/main/java/com/akylas/enforcedoze/service/DozeRuntime.kt").readText()
        assertTrue(runtime.contains("Log.w(\"DozeRuntime\", message, error)"))
        assertTrue(runtime.contains("grants(),\n        diagnosticLogger,"))
        assertTrue(runtime.contains("SelfTestQueue(diagnosticLogger)"))
        val journal = File("src/main/java/com/akylas/enforcedoze/service/JournalSink.kt").readText()
        assertTrue(journal.contains("EventSinks { message, error -> Log.w(\"DozeJournal\", message, error) }"))
        assertTrue(journal.contains("{ error -> Log.e(\"DozeJournal\", \"Journal write failed\", error) }"))
        val db = File("src/main/java/com/akylas/enforcedoze/monitor/JournalDb.kt").readText()
        val insert = db.substringAfter("fun insert(event:").substringBefore("fun insertAll(")
        assertTrue(insert.contains("onFailure: ((Exception) -> Unit)? = null"))
        assertTrue(insert.contains("submitJournalInsert(worker, onFailure)"))
        assertFalse(insert.contains(".get("))
    }

    @Test fun everyServiceGroupEnterUsesGuardAndIdleFailureHasNoRetry() {
        val service = File("src/main/java/com/akylas/enforcedoze/ForceDozeService.java").readText()
        assertFalse("No raw group enter may escape the worker", service.contains(".enterGroups("))
        assertEquals("three service paths use the guarded adapter", 3, Regex("enterGroupsSafely\\(selectedGroups,").findAll(service).count())
        assertEquals("only the pass-through adapter calls the controller", 1, Regex("\\.enterGroupsSafely\\(").findAll(service).count())
        assertTrue(service.contains("return runtime.getController().enterGroupsSafely(config, generation, admission, errorDetail);"))
        val idle = service.substringAfter("private void idleChanged()")
            .substringBefore("private void forceOnly")
        assertTrue(idle.contains("enterGroupsSafely(selectedGroups"))
        assertTrue(idle.contains("this::forceAdmitted, EventCodes.FEATURE_SELECTION_FAILED"))
        assertTrue(idle.indexOf("recordVerifiedEnter();") < idle.indexOf("enterGroupsSafely(selectedGroups"))
    }
}
