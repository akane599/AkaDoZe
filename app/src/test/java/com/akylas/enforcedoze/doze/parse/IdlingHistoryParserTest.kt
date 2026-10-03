package com.akylas.enforcedoze.doze.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdlingHistoryParserTest {
    @Test
    fun fullDumpResolvesAllKindsAndOptionalReasonsAgainstInjectedNow() {
        val now = 12_000_000L
        val history = IdlingHistoryParser.parse(deviceIdleFixture(), now)
        assertEquals(
            listOf(
                HistoryEvent(HistoryKind.NORMAL, now - 3_723_456, "screen"),
                HistoryEvent(HistoryKind.LIGHT_IDLE, now - 3_300_012, null),
                HistoryEvent(HistoryKind.LIGHT_MAINT, now - 3_000_000, null),
                HistoryEvent(HistoryKind.DEEP_IDLE, now - 2_703_000, null),
                HistoryEvent(HistoryKind.DEEP_MAINT, now - 2_400_000, "alarm"),
            ),
            history.events,
        )
        assertEquals(now - 3_723_456, history.oldestElapsed)
    }

    @Test
    fun durationFieldsSignsZeroAndOmittedUnitsAreResolvedExactly() {
        val durations = mapOf(
            "-1d2h3m4s5ms" to -93_784_005L,
            "-1h2m3s456ms" to -3_723_456L,
            "-5s0ms" to -5_000L,
            "-12ms" to -12L,
            "0" to 0L,
            "+1d" to 86_400_000L,
            "2h" to 7_200_000L,
            "-3m" to -180_000L,
            "+4s" to 4_000L,
            "5ms" to 5L,
            "-1d5ms" to -86_400_005L,
        )
        for ((raw, offset) in durations) {
            val history = IdlingHistoryParser.parse("  Idling history:\n    deep-idle: $raw", 100_000_000)
            assertEquals(raw, listOf(HistoryEvent(HistoryKind.DEEP_IDLE, 100_000_000 + offset, null)), history.events)
            assertEquals(raw, 100_000_000 + offset, history.oldestElapsed)
        }
    }

    @Test
    fun crLfAndStdoutListsMatchStringParser() {
        val fixture = deviceIdleFixture().replace("\n", "\r\n")
        val expected = IdlingHistoryParser.parse(deviceIdleFixture(), 10_000_000)
        assertEquals(expected, IdlingHistoryParser.parse(fixture, 10_000_000))
        assertEquals(expected, IdlingHistoryParser.parse(fixture.lines(), 10_000_000))
    }

    @Test
    fun blockEndsAtBlankUnknownKeyOrNonEntryWithoutResuming() {
        for (terminator in listOf("", "  mState=IDLE", "  unknown: -5s0ms", "whitelist:")) {
            val history = IdlingHistoryParser.parse(
                "Idling history:\n  normal: -12ms\n$terminator\n  deep-idle: 0",
                100,
            )
            assertEquals(listOf(HistoryEvent(HistoryKind.NORMAL, 88, null)), history.events)
        }
    }

    @Test
    fun truncatedRingExposesOldestElapsedWithoutInventingEarlierEvents() {
        val entries = (1..100).joinToString("\n") { "  deep-idle: -${it}s0ms" }
        val history = IdlingHistoryParser.parse("Idling history:\n$entries", 1_000_000)
        assertEquals(100, history.events.size)
        assertEquals(900_000L, history.oldestElapsed)
        // A session starting before this boundary has incomplete OS-history coverage.
        assertTrue(checkNotNull(history.oldestElapsed) > 800_000L)
        assertEquals(
            listOf(HistoryEvent(HistoryKind.DEEP_IDLE, 988, null)),
            IdlingHistoryParser.parse("Idling history:\n deep-idle: -12ms", 1_000).events,
        )
    }

    @Test
    fun absentOrEmptyBlockDoesNotInventCoverage() {
        for (dump in listOf("", "permission denied", "normal: -12ms", "Idling history:", "Idling history:\n\n normal: 0")) {
            val history = IdlingHistoryParser.parse(dump, 100)
            assertTrue(history.events.isEmpty())
            assertNull(history.oldestElapsed)
        }
    }

    @Test
    fun malformedDurationsAndArithmeticOverflowNeverThrowOrWrap() {
        for (raw in listOf("-", "garbage", "12", "1s2h", "--5s", "1.5s", "99999999999999999999999ms", "9223372036854775807d", "9223372036854775807ms1s")) {
            assertTrue(raw, IdlingHistoryParser.parse("Idling history:\n deep-idle: $raw", 100).events.isEmpty())
        }
        assertTrue(IdlingHistoryParser.parse("Idling history:\n deep-idle: +1ms", Long.MAX_VALUE).events.isEmpty())
        assertTrue(IdlingHistoryParser.parse("Idling history:\n deep-idle: -1ms", Long.MIN_VALUE).events.isEmpty())
        assertTrue(IdlingHistoryParser.parse("Idling history:\n deep-idle: 9223372036854775s999ms", 0).events.isEmpty())
    }
}
