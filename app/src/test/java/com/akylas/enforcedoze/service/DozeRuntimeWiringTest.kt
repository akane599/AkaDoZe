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
