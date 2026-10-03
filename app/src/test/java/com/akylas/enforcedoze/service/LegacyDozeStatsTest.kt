package com.akylas.enforcedoze.service

import org.junit.Assert.*
import org.junit.Test

class LegacyDozeStatsTest {
    @Test fun malformedRowsAreSkippedWithoutNumericParsingFailures() {
        for (raw in listOf("", "1", "1,2", "no,50,ENTER", "-1,50,ENTER", "1,NaN,ENTER",
            "1,Infinity,EXIT", "1,101,EXIT", "1,-1,ENTER", "1,50,OTHER", "1,50,ENTER,extra")) {
            assertNull(raw, LegacyDozeStats.parse(raw))
        }
        assertEquals(57f, LegacyDozeStats.parse("100,57.0,ENTER")?.battery)
    }

    @Test fun capKeepsNewest1000NumericallyWithoutMutatingInput() {
        val rows = (1..1200).map { "$it,50.0,ENTER" }.toMutableSet()
        rows += "malformed"
        val retained = LegacyDozeStats.newest(rows)
        assertEquals(1000, retained.size)
        assertTrue(retained.contains("1200,50.0,ENTER"))
        assertTrue(retained.contains("201,50.0,ENTER"))
        assertFalse(retained.contains("200,50.0,ENTER"))
        assertEquals(1201, rows.size)
    }

    @Test fun maintenanceIsInsideTheSessionAndSupportsDecimalBatteriesAndChargingGain() {
        val intervals = LegacyDozeStats.intervals(listOf("10,40.0,ENTER", "20,41.0,EXIT_MAINTENANCE",
            "bad", "30,43.0,ENTER_MAINTENANCE", "40,45.0,EXIT"))
        assertEquals(2, intervals.size)
        assertEquals(10L, intervals[0].start.time)
        assertEquals(40L, intervals[0].end.time)
        assertFalse(intervals[0].maintenance)
        assertEquals(-5f, intervals[0].start.battery - intervals[0].end.battery)
        assertTrue(intervals[1].maintenance)
        assertEquals(20L, intervals[1].start.time)
        assertEquals(30L, intervals[1].end.time)
    }

    @Test fun orphanExitsAndUnfinishedSessionsDoNotProduceCards() {
        assertTrue(LegacyDozeStats.intervals(listOf("1,50,EXIT", "2,50,EXIT_MAINTENANCE",
            "3,50,ENTER_MAINTENANCE", "4,50,ENTER")).isEmpty())
        val session = SessionLifecycle()
        assertFalse(session.recordExit())
        assertFalse(session.recordEnter(false))
        assertFalse(session.recordExit())
        assertTrue(session.recordEnter(true))
        assertTrue(session.hasEnter)
        assertTrue(session.recordExit())
        assertFalse(session.recordExit())
    }
}
