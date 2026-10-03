package com.akylas.enforcedoze.service

import com.akylas.enforcedoze.access.Feature
import com.akylas.enforcedoze.doze.CorruptLedgerLine
import com.akylas.enforcedoze.doze.DozeEvent
import com.akylas.enforcedoze.doze.DozeEventSink
import com.akylas.enforcedoze.doze.EventType
import org.junit.Assert.*
import org.junit.Test

class LedgerDamageDebtTest {
    private val events = mutableListOf<DozeEvent>()
    private val debt = LedgerDamageDebt(DozeEventSink(events::add))
    private fun line(token: String, broken: String = "broken") =
        CorruptLedgerLine(1, "1|$token|~|true|$broken|0|false|36")

    @Test fun retainedDamageIsFeatureSpecificDebtIncludingUnknownFutureToken() {
        val lines = listOf(line("WIFI"), line("AIRPLANE"), line("FUTURE_RADIO"), CorruptLedgerLine(4, "unreadable"))
        debt.update(lines, false)
        assertEquals(4, events.size)
        assertTrue(events.all { it.type == EventType.RECOVERY_DEBT && it.detail == "LEDGER_DAMAGED" })
        assertEquals(Feature.WIFI, events[0].feature)
        assertEquals("WIFI", events[0].target)
        assertEquals(Feature.AIRPLANE, events[1].feature)
        assertNull(events[2].feature)
        assertEquals("Unknown tokens are carried without changing EventType or the codec", "FUTURE_RADIO", events[2].target)
        assertNull(events[3].feature)
        assertNull(events[3].target)
        assertTrue("Raw damaged payload never reaches events", events.none { it.detail.contains("broken") })
    }

    @Test fun unchangedDebtDoesNotRepeatAcrossPollsSavesOrLineRenumbering() {
        val lines = listOf(line("WIFI"), line("AIRPLANE"))
        debt.update(lines, false)
        repeat(3) { debt.update(lines.reversed().map { it.copy(lineNumber = 10) }, false) }
        assertEquals(2, events.size)
    }

    @Test fun changedDamageOfSameFeatureIsANewDebtTransition() {
        debt.update(listOf(line("WIFI")), false)
        debt.update(listOf(line("WIFI", "other-broken")), false)
        debt.update(listOf(line("WIFI", "other-broken")), false)
        assertEquals(2, events.size)
        assertTrue(events.all { it.feature == Feature.WIFI })
    }

    @Test fun clearingThenNewDamageRearmsDebtWithoutRepeatingUnchangedFeatures() {
        debt.update(listOf(line("WIFI"), line("AIRPLANE")), false)
        debt.update(listOf(line("AIRPLANE")), false)
        assertEquals(2, events.size)
        debt.update(listOf(line("WIFI"), line("AIRPLANE")), false)
        assertEquals(3, events.size)
        assertEquals(Feature.WIFI, events.last().feature)
        debt.update(emptyList(), false)
        debt.update(listOf(line("WIFI")), false)
        assertEquals(4, events.size)
    }

    @Test fun unreadableStoreDebtIsAlsoTransitionOnly() {
        repeat(3) { debt.update(emptyList(), true) }
        assertEquals(1, events.size)
        debt.update(emptyList(), false)
        debt.update(emptyList(), true)
        assertEquals(2, events.size)
    }

    @Test fun featureClassificationDoesNotRequireSuccessfullyDecodingPayload() {
        Feature.values().forEach { feature ->
            val current = line(feature.name)
            val legacy = current.copy(line = current.line.removeSuffix("|36"))
            val eligible = feature == Feature.FORCE_DOZE || feature == Feature.MOTION_SENSORS
            assertEquals(feature.name, LedgerRecovery.featureToken(current))
            assertEquals(feature.name, LedgerRecovery.featureToken(legacy))
            assertEquals(eligible, LedgerRecovery.recoverable(current))
            assertEquals(eligible, LedgerRecovery.recoverable(legacy))
        }
        assertEquals("MOTION_SENSORS", LedgerRecovery.featureToken(CorruptLedgerLine(1, "1|MOTION_SENSORS")))
        assertFalse(LedgerRecovery.recoverable(line("FUTURE_RADIO")))
        listOf("unreadable", "1||broken", "2|MOTION_SENSORS|broken", "MOTION_SENSORS").forEach {
            assertNull(LedgerRecovery.featureToken(CorruptLedgerLine(1, it)))
            assertFalse(LedgerRecovery.recoverable(CorruptLedgerLine(1, it)))
        }
    }
}
