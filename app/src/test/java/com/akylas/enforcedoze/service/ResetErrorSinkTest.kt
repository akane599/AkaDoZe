package com.akylas.enforcedoze.service

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.akylas.enforcedoze.TestAppState
import com.akylas.enforcedoze.access.AccessManager
import com.akylas.enforcedoze.access.Prefs
import com.akylas.enforcedoze.doze.EventType
import com.akylas.enforcedoze.doze.FakeRunner
import com.akylas.enforcedoze.doze.DozeEvent
import com.akylas.enforcedoze.doze.DozeEventSink
import com.akylas.enforcedoze.monitor.EventCodes
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ResetErrorSinkTest {
    private val app = RuntimeEnvironment.getApplication()
    private val events = mutableListOf<DozeEvent>()
    private val jobs = java.util.ArrayDeque<Runnable>()
    private val journal = JournalSink(app, AndroidClock()).also { it.addSink(DozeEventSink(events::add)) }
    private val runtime = run {
        TestAppState.selectNonRootMode(app)
        TestAppState.runtimeWithoutRoot(app, AndroidClock(), journal)
    }.also {
        // Execute the actual runtime job, but use a deterministic serial queue instead of a thread.
        set(it, "resets", ServiceResetQueue(it) { job -> jobs.add(job) })
        set(it, "shutdownQueued", true)
    }

    @After fun clearAccessSingleton() {
        AccessManager::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, null)
        }
    }

    @Test fun resetJobSinkJournalsResetFailedAndLogsExactThrowable() {
        val error = IllegalStateException("ledger read failed")
        val prefs = app.getSharedPreferences("throwing_ledger", Context.MODE_PRIVATE)
        set(runtime.store, "prefs", object : SharedPreferences by prefs {
            override fun getString(key: String?, defValue: String?): String? = throw error
        })
        var report: SystemResetResult? = null

        runtime.resetSystemState { report = it }
        assertEquals(1, jobs.size)
        runNextJob()

        assertTrue("throwing job still delivers a failed report", report?.failed == true)
        assertTrue("the actual SQ-105 sink must journal RESET_FAILED",
            events.any { it.detail == EventCodes.RESET_FAILED })
        val warning = ShadowLog.getLogsForTag("DozeRuntime").single {
            it.throwable === error && it.msg == "System reset failed"
        }
        assertEquals(Log.WARN, warning.type)
        assertEquals("System reset failed", warning.msg)
    }

    @Test fun historyReadFailureLogsOriginalThrowableAndStillCompletesPresentation() {
        val error = IllegalStateException("history binder failure")
        historyRunner { throw error }
        var completed = false
        runtime.afterHistoryImport = Runnable { completed = true }

        runtime.importHistory()

        assertTrue(completed)
        assertEquals(EventCodes.HISTORY_READ_FAILED, events.single().detail)
        val warning = ShadowLog.getLogsForTag("DozeRuntime").single { it.msg == "History read failed" }
        assertSame(error, warning.throwable)
        assertEquals(Log.WARN, warning.type)
    }

    @Test fun historyReadSuccessAndFailedResultsRetainTheirJournalAndPresentationFlow() {
        for (exit in listOf(0, 1)) {
            events.clear()
            ShadowLog.clear()
            historyRunner { FakeRunner.result("", exit = exit) }
            var completed = false
            runtime.afterHistoryImport = Runnable { completed = true }

            runtime.importHistory()

            assertTrue(completed)
            assertEquals(if (exit == 0) 0 else 1,
                events.count { it.detail == EventCodes.HISTORY_READ_FAILED })
            assertTrue("a command result failure is not an exception", ShadowLog.getLogsForTag("DozeRuntime").isEmpty())
        }
    }

    private fun historyRunner(reply: () -> com.akylas.enforcedoze.access.CommandResult) {
        set(runtime.access, "state", com.akylas.enforcedoze.access.AccessState(
            com.akylas.enforcedoze.access.AccessLevel.SHELL, null,
            com.akylas.enforcedoze.access.Grants(false, false), 2000,
        ))
        val lane = AccessManager::class.java.getDeclaredField("readRunner")
        // Implement the existing private lane interface; no host shell/root process is started.
        val runner = java.lang.reflect.Proxy.newProxyInstance(lane.type.classLoader, arrayOf(lane.type)) { _, method, args ->
            assertEquals("run", method.name)
            assertEquals("dumpsys deviceidle", args[0])
            assertEquals(8_000L, args[1])
            reply()
        }
        set(runtime.access, "readRunner", runner)
    }

    @Test fun deferredThrowIsJournaledAndLogsExactThrowableBeforeRestart() {
        val error = IllegalStateException("deferred runner failed")
        val runner = FakeRunner().apply { answer(readLogsRevoke) { throw error } }
        set(runtime, "control", runner)
        var restarted = false
        runtime.finishReset(listOf(ResetCommandId.REVOKE_READ_LOGS), Runnable {
            assertResetWarning(error)
            restarted = true
        })
        runNextJob()
        assertTrue(restarted)
        assertEquals(listOf(readLogsRevoke), runner.commands)
    }

    @Test fun deferredFailedAndTimedOutResultsAreJournaledAndLogged() {
        for (reply in listOf(FakeRunner.result("", exit = 1), FakeRunner.result("", exit = -1),
            FakeRunner.result("", timeout = true))) {
            events.clear()
            ShadowLog.clear()
            val runner = FakeRunner().apply { answer(readLogsRevoke) { reply } }
            set(runtime, "control", runner)
            runtime.finishReset(listOf(ResetCommandId.REVOKE_READ_LOGS), Runnable {})
            runNextJob()
            val warning = ShadowLog.getLogsForTag("DozeRuntime").single()
            assertResetWarning(warning.throwable)
            assertEquals("Deferred reset revoke failed: exit=${reply.exitCode}, timedOut=${reply.timedOut}", warning.throwable.message)
        }
    }

    @Test fun deferredFalseAndThrowingForgetAreJournaledBeforeRestartWithoutRevoking() {
        for (throws in listOf(false, true)) {
            events.clear()
            ShadowLog.clear()
            val error = IllegalStateException("helper persistence failed")
            val prefs = app.getSharedPreferences(Prefs.HELPER_GRANTS, Context.MODE_PRIVATE)
            prefs.edit().putStringSet(Prefs.APPLIED_HELPERS, setOf("READ_PHONE_STATE")).commit()
            set(runtime.access, "helperPrefs", object : SharedPreferences by prefs {
                override fun edit(): SharedPreferences.Editor {
                    if (throws) throw error
                    val editor = prefs.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply {
                            editor.putStringSet(key, values)
                        }
                        override fun commit(): Boolean = false
                    }
                }
            })
            val runner = FakeRunner()
            set(runtime, "control", runner)
            var restarted = false
            runtime.finishReset(listOf(ResetCommandId.REVOKE_READ_PHONE_STATE), Runnable {
                assertEquals(1, events.count { it.detail == EventCodes.RESET_FAILED })
                restarted = true
            })
            runNextJob()
            assertTrue(restarted)
            assertTrue("forget must succeed before any revoke", runner.commands.isEmpty())
            val warning = ShadowLog.getLogsForTag("DozeRuntime").single()
            assertResetWarning(warning.throwable)
            if (throws) assertSame(error, warning.throwable)
            else assertEquals("Could not forget reset helper READ_PHONE_STATE before revoke", warning.throwable.message)
        }
    }

    @Test fun firstDeferredRevokeFailureIsJournaledBeforeRestartDespiteSuccessfulJoinedExit() {
        val runner = FakeRunner().apply {
            beforeMutation = { command ->
                answer(command) {
                    FakeRunner.result("__AKADOZE_RESET_REVOKE_READ_LOGS=1\n__AKADOZE_RESET_REVOKE_READ_PHONE_STATE=0")
                }
            }
        }
        set(runtime, "control", runner)
        var restarted = false
        runtime.finishReset(listOf(ResetCommandId.REVOKE_READ_LOGS, ResetCommandId.REVOKE_READ_PHONE_STATE), Runnable {
            val warning = ShadowLog.getLogsForTag("DozeRuntime").single()
            assertResetWarning(warning.throwable)
            assertEquals("Deferred reset revoke REVOKE_READ_LOGS failed: exit=1", warning.throwable.message)
            restarted = true
        })
        runNextJob()

        assertTrue(restarted)
        assertEquals(1, runner.commands.size)
    }

    @Test fun deferredSuccessDoesNotEmitResetFailure() {
        set(runtime, "control", FakeRunner())
        runtime.finishReset(listOf(ResetCommandId.REVOKE_READ_LOGS), Runnable {})
        runNextJob()
        assertTrue(events.isEmpty())
        assertTrue(ShadowLog.getLogsForTag("DozeRuntime").isEmpty())
    }

    private val readLogsRevoke get() = "pm revoke ${app.packageName} android.permission.READ_LOGS; echo \"__AKADOZE_RESET_REVOKE_READ_LOGS=\$?\""

    private fun assertResetWarning(error: Throwable) {
        assertEquals(1, events.count { it.type == EventType.ERROR && it.detail == EventCodes.RESET_FAILED })
        val warning = ShadowLog.getLogsForTag("DozeRuntime").single {
            it.throwable === error && it.msg == "System reset failed"
        }
        assertEquals(Log.WARN, warning.type)
        assertEquals("System reset failed", warning.msg)
    }

    private fun runNextJob() {
        val job = jobs.removeFirst()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            executor.submit(job).get(10, java.util.concurrent.TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS))
        }
    }

    private fun set(target: Any, name: String, value: Any) {
        target.javaClass.getDeclaredField(name).apply {
            isAccessible = true
            set(target, value)
        }
    }
}
