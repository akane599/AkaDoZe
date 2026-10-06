package com.akylas.enforcedoze.service

import com.akylas.enforcedoze.doze.Decision
import com.akylas.enforcedoze.doze.DeepState
import com.akylas.enforcedoze.doze.FakeClock
import com.akylas.enforcedoze.doze.ReapplySkip
import com.akylas.enforcedoze.doze.WatchdogPolicy
import com.akylas.enforcedoze.doze.parse.DozeStateParser
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DozeRuntimeWiringTest {
    @Test fun runtimeReceivesTheAppOwnedJournalAndClockWithoutAnotherSink() {
        val runtime = File("src/main/java/com/akylas/enforcedoze/service/DozeRuntime.kt").readText()
        assertTrue(runtime.contains("class DozeRuntime(context: Context, val clock: AndroidClock, val journal: JournalSink)"))
        assertFalse(runtime.contains("JournalSink(app, clock)"))
        val app = File("src/main/java/com/akylas/enforcedoze/MyApplication.java").readText()
        assertTrue(app.contains("new DozeRuntime(app, CLOCK, getJournal(app))"))
        assertTrue(app.contains("public static synchronized JournalSink getJournal(Context context)"))
        val journal = app.substringAfter("public static synchronized JournalSink getJournal(")
            .substringBefore("/**")
        assertTrue(journal.contains("JOURNAL.get(() -> new JournalSink(app, CLOCK),"))
        assertTrue(journal.contains("journal -> journal.addSink(NoticeSink.get(app))"))
        assertFalse(journal.contains("getDozeRuntime("))
        assertFalse(journal.contains("AccessManager"))
        assertEquals(1, Regex("NoticeSink.get").findAll(app).count())
        val notice = File("src/main/java/com/akylas/enforcedoze/ui/NoticeSink.java").readText()
        val denied = notice.substringAfter("private void onExternalCall(").substringBefore("private void notifyStartDenied(")
        assertFalse(denied.contains("getDozeRuntime"))
        assertTrue(denied.contains("if (shown) notices.edit().putBoolean(key, true).apply()"))
    }

    @Test fun controllerReceivesLiveCommandDeadlineRemainingBudget() {
        val runtime = File("src/main/java/com/akylas/enforcedoze/service/DozeRuntime.kt").readText()
        val controller = runtime.substringAfter("val controller = DozeController(").substringBefore("val watchdog")
        assertTrue("controller must share the command runner's live remaining budget",
            controller.contains("{ commandDeadline?.minus(clock.elapsedRealtime()) },"))
        val runner = runtime.substringAfter("val control: CommandRunner").substringBefore("private val diagnosticLogger")
        assertTrue(runner.contains("val remaining = deadline - clock.elapsedRealtime()"))
        assertFalse("execution timeout must not shorten the shared queue deadline", runner.contains("minOf(timeoutMs, remaining)"))
        assertTrue(runner.contains("TimeUnit.MILLISECONDS.toNanos(remaining)"))
        assertTrue(runner.contains("TimeUnit.MILLISECONDS.toNanos(timeoutMs)"))
        assertTrue(runner.contains("access.controlWithDeadline(command, deadlineNanos, executionTimeoutNanos) { clock.elapsedRealtime() < deadline }"))
        assertTrue("no shared deadline retains the execution-only timeout", runner.contains(
            "return access.control().run(command, timeoutMs)"))
    }

    private fun shellReadState(): String =
        File("src/main/java/com/akylas/enforcedoze/service/DozeRuntime.kt").readText()
            .substringAfter("fun readState(): DozeStateReading {")
            .substringBefore("if (grants().dump)")
            .substringAfter("if (control.level >= AccessLevel.SHELL) {")

    @Test fun marshmallowShellPollingReturnsParsedPlainDumpBeforeGetCommands() {
        val source = shellReadState()
        val dumpReturn = "if (Build.VERSION.SDK_INT < 24) return DozeStateParser.parse(runRead(\"dumpsys deviceidle\"))"
        assertTrue("API 23 must return the parsed plain dump", source.contains(dumpReturn))
        assertTrue("M must return before the N+ token reads",
            source.indexOf(dumpReturn) < source.indexOf("return DozeStateReading("))
        assertFalse("M must not select dumpsys for unsupported get verbs",
            source.contains("else \"dumpsys deviceidle\""))
    }

    @Test fun nougatAndLaterKeepDeepAndLightGetCommandsAndUnknownFallbacks() {
        val source = shellReadState()
        assertTrue(source.contains("val prefix = \"cmd deviceidle\""))
        assertTrue(source.contains("DozeStateParser.parseDeep(runRead(\"\$prefix get deep\")) ?: DeepState.UNKNOWN"))
        assertTrue(source.contains("DozeStateParser.parseLight(runRead(\"\$prefix get light\")) ?: LightState.UNKNOWN"))
        assertTrue(source.indexOf("val prefix = \"cmd deviceidle\"") < source.indexOf("return DozeStateReading("))
    }

    @Test fun marshmallowMaintenanceDumpIsRecognizedWithoutLightAndNeverReforced() {
        val reading = DozeStateParser.parse(listOf(
            "  mState=IDLE_MAINTENANCE",
            "  mForceIdle=true",
        ))
        assertEquals(DeepState.IDLE_MAINTENANCE, reading.deep)
        assertNull("M has no light state", reading.light)
        assertEquals(true, SessionLifecycle.maintenanceState(reading.deep, reading.light))
        val watchdog = WatchdogPolicy(FakeClock())
        assertEquals(Decision.IGNORE, watchdog.onIdleChanged(reading, false, false, true))
        assertEquals(Decision.SKIP(ReapplySkip.EXTERNAL_REAPPLY_MAINTENANCE),
            watchdog.onExternalReapply(reading, false, 23))
    }

    @Test fun marshmallowIdleDumpEndsMaintenanceAndDoesNotTriggerAutomaticReforce() {
        val reading = DozeStateParser.parse(listOf("  mState=IDLE"))
        assertEquals(DeepState.IDLE, reading.deep)
        assertNull(reading.light)
        assertEquals(false, SessionLifecycle.maintenanceState(reading.deep, reading.light))
        assertEquals(Decision.IGNORE,
            WatchdogPolicy(FakeClock()).onIdleChanged(reading, false, false, true))
    }

    @Test fun marshmallowActiveDumpAllowsWatchdogAndExternalReapplyWithoutLight() {
        val reading = DozeStateParser.parse(listOf("  mState=ACTIVE"))
        assertEquals(DeepState.ACTIVE, reading.deep)
        assertNull(reading.light)
        assertEquals(Decision.REFORCE,
            WatchdogPolicy(FakeClock()).onIdleChanged(reading, false, false, true))
        assertEquals(Decision.REFORCE,
            WatchdogPolicy(FakeClock()).onExternalReapply(reading, false, 23))
    }

    @Test fun unreadableOrUnknownMarshmallowDumpCannotAuthorizeTransitionsOrReforce() {
        for (output in listOf(emptyList(), listOf("  mState=OEM_UNKNOWN"))) {
            val reading = DozeStateParser.parse(output)
            assertNull(reading.light)
            assertNull(SessionLifecycle.maintenanceState(reading.deep, reading.light))
            val watchdog = WatchdogPolicy(FakeClock())
            assertEquals(Decision.IGNORE, watchdog.onIdleChanged(reading, false, false, true))
            assertEquals(Decision.SKIP(ReapplySkip.EXTERNAL_REAPPLY_STATE_UNKNOWN),
                watchdog.onExternalReapply(reading, false, 23))
        }
    }
}
