package com.akylas.enforcedoze.doze

import com.akylas.enforcedoze.access.AccessLevel
import com.akylas.enforcedoze.access.Feature
import com.akylas.enforcedoze.doze.parse.DozeStateParser
import com.akylas.enforcedoze.doze.parse.SensorModeReading
import org.junit.Assert.*
import org.junit.Test

class DozePolicyTest {
    private val clock = FakeClock(0)
    private val policy = WatchdogPolicy(clock)
    private fun trigger(state: String = "ACTIVE", screenOn: Boolean = false, charging: Boolean = false, admission: Boolean = true) =
        policy.onIdleChanged(DozeStateParser.parse("mState=$state"), screenOn, charging, admission)

    @Test fun maintenanceIdleAndUnknownNeverTriggerWatchdog() {
        for (state in listOf("IDLE_MAINTENANCE", "IDLE", "OEM", "")) {
            assertEquals(Decision.IGNORE, trigger(state))
        }
        assertEquals(Decision.IGNORE, trigger(screenOn = true))
        assertEquals(Decision.IGNORE, trigger(charging = true, admission = false))
        assertEquals(Decision.IGNORE, trigger(admission = false))
        assertEquals(Decision.REFORCE, trigger(charging = true))
    }

    @Test fun watchdogReturnsOnlyOneDeferredRetryThenRespectsSessionCapAndReset() {
        assertEquals(Decision.REFORCE, trigger())
        clock.elapsed = 1
        assertEquals(Decision.DEFER(60_000), trigger())
        clock.elapsed = 59_999
        assertEquals(Decision.IGNORE, trigger())
        clock.elapsed = 60_000
        assertEquals(Decision.REFORCE, trigger())
        assertEquals(Decision.DEFER(120_000), trigger())
        for (i in 2..4) {
            clock.elapsed = i * 60_000L
            assertEquals(Decision.REFORCE, trigger())
        }
        clock.elapsed += 60_000
        assertEquals(Decision.IGNORE, trigger())
        policy.resetSession()
        assertEquals(Decision.REFORCE, trigger())
    }

    @Test fun safetyNetRestoresSensorsWithAppDumpButRaisesUnforceDebt() {
        val restricted = SensorModeReading(SensorMode.RESTRICTED, "com.example.app")
        assertEquals(listOf(Action.RESTORE_SENSORS, Action.RAISE_DEBT), SafetyNet.check(restricted, true, AccessLevel.APP, "com.example.app", true))
        assertEquals(listOf(Action.RESTORE_SENSORS, Action.UNFORCE), SafetyNet.check(restricted, true, AccessLevel.SHELL, "com.example.app", true))
        assertEquals(listOf(Action.RAISE_DEBT), SafetyNet.check(restricted, true, AccessLevel.NONE, "com.example.app", true))
        assertTrue(SafetyNet.check(SensorModeReading(SensorMode.UNVERIFIED, null), null, AccessLevel.ROOT, "com.example.app", true).isEmpty())
        assertTrue(SafetyNet.check(SensorModeReading(SensorMode.NORMAL, null), false, AccessLevel.APP, "com.example.app", true).isEmpty())
    }
}

class RestoreLedgerTest {
    @Test fun codecRoundTripsSeparatorsNullEmptyUnicodeAndDurableRestart() {
        val entries = listOf(
            LedgerEntry(Feature.WIFI, "percent%pipe|newline\ncarriage\rnull~漢字", "", 0),
            LedgerEntry(Feature.FORCE_DOZE, null, null, 123, 7, true, 34),
        )
        val ledger = RestoreLedger(entries)
        val decoded = RestoreLedgerCodec.decode(RestoreLedgerCodec.encode(ledger))
        assertEquals(ledger, decoded.ledger)
        assertTrue(decoded.corruptLines.isEmpty())
        val store = InMemoryLedgerStore()
        store.save(ledger)
        assertEquals(ledger, store.restart().load())
    }

    @Test fun corruptLinesAreDroppedAndReportedWithoutLosingValidNeighbors() {
        val valid = "1|WIFI|~|1|0|0|false"
        val bad = listOf("bad", "2|WIFI|~|1|0|0|false", "1|NO_FEATURE|~|1|0|0|false",
            "1|WIFI|%XX|1|0|0|false", "1|WIFI|~|1|0|-1|false", "1|WIFI|~|1|0|0|maybe",
            "1|WIFI|~|1|-1|0|false", "1|WIFI|~|1|99999999999999999999999|0|false")
        val result = RestoreLedgerCodec.decode((listOf(valid) + bad + valid).joinToString("\n"))
        assertEquals(2, result.ledger.entries.size)
        assertEquals(bad, result.corruptLines.map { it.line })
        assertEquals((2..9).toList(), result.corruptLines.map { it.lineNumber })
    }

    @Test fun codecAcceptsLegacyMissingApiAndRejectsMalformedApiWithoutLosingNeighbors() {
        val legacy = "1|LOCATION|~|2|0|0|false"
        val modern = "1|LOCATION|~|1|0|0|false|34"
        val bad = listOf("$legacy|invalid", "$legacy|22", "$legacy|", "$legacy|34|extra")
        val result = RestoreLedgerCodec.decode((listOf(legacy, modern) + bad).joinToString("\n"))
        assertEquals(listOf(null, 34), result.ledger.entries.map { it.apiLevel })
        assertEquals(bad, result.corruptLines.map { it.line })
        assertEquals(result.ledger, RestoreLedgerCodec.decode(RestoreLedgerCodec.encode(result.ledger)).ledger)
    }

    @Test fun ledgerDefensivelyCopiesAndRejectsJavaStyleMutation() {
        val source = mutableListOf(LedgerEntry(Feature.WIFI, null, "1", 0))
        val ledger = RestoreLedger(source)
        source.clear()
        assertEquals(1, ledger.entries.size)
        assertThrows(UnsupportedOperationException::class.java) { (ledger.entries as MutableList).clear() }
    }

    @Test fun packageReadsNeverBorrowDifferentUserOrUnstructuredPermission() {
        assertNull(FeatureReadback.value(Feature.NOTIFICATION_BLOCK, 36,
            listOf("android.permission.POST_NOTIFICATIONS: granted=true, flags=[USER_SET]"), "com.example.app"))
        assertNull(FeatureReadback.value(Feature.APP_SUSPEND, 36,
            listOf("  Package [com.example.app] (abc):", "    User 10: suspended=false"), "com.example.app"))
        assertNull(FeatureReadback.value(Feature.WIFI, 36, listOf("2"), null))
        assertNull(FeatureReadback.value(Feature.LOCATION, 29, listOf("null"), null))
        assertEquals("2", FeatureReadback.value(Feature.LOCATION, 29, listOf("2"), null))
    }
}
