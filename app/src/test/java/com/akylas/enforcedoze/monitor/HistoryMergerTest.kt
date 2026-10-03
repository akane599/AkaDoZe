package com.akylas.enforcedoze.monitor

import com.akylas.enforcedoze.doze.DeepState
import com.akylas.enforcedoze.doze.EventType
import com.akylas.enforcedoze.doze.parse.HistoryEvent
import com.akylas.enforcedoze.doze.parse.HistoryKind
import com.akylas.enforcedoze.doze.parse.IdlingHistoryParser
import org.junit.Assert.*
import org.junit.Test

class HistoryMergerTest {
    private fun app(time: Long, type: EventType, boot: Int = 7, session: Long = 20) =
        JournalEvent(boot, time, 1_000_000 + time, session, Source.APP, type)
    private val session = listOf(app(10_000, EventType.SCREEN_OFF), app(100_000, EventType.SCREEN_ON))

    @Test fun parserOutputImportsStatesWallTimeAndReason() {
        val history = IdlingHistoryParser.parse("""
            Idling history:
              normal: -10s (motion)
              deep-idle: -60s
              light-idle: -95s
        """.trimIndent(), 100_000)
        val merge = HistoryMerger.merge(session, history, 10_000, 7)
        assertEquals(3, merge.importedEvents.size)
        assertFalse(merge.truncated)
        val deep = merge.importedEvents.single { it.historyKind == HistoryKind.DEEP_IDLE }
        assertEquals(Source.OS_HISTORY, deep.source)
        assertEquals(DeepState.IDLE, deep.deep)
        assertEquals(1_040_000L, deep.wallTime)
        assertEquals(20L, deep.sessionId)
        assertEquals("motion", merge.importedEvents.single { it.historyKind == HistoryKind.NORMAL }.detail)
        val summary = SessionAggregator.summarize(merge.events).single()
        assertEquals(30_000L, summary.coverageMs.getValue(Coverage.LIGHT_IDLE))
        assertEquals(50_000L, summary.coverageMs.getValue(Coverage.DEEP_IDLE))
    }

    @Test fun importsDeduplicateSameKindWithinOneSecondAndRemainIdempotent() {
        val history = listOf(HistoryEvent(HistoryKind.DEEP_IDLE, 20_000, null))
        val first = HistoryMerger.merge(session, history, 10_000, 7)
        val again = HistoryMerger.merge(first.events,
            listOf(HistoryEvent(HistoryKind.DEEP_IDLE, 20_999, null)), 10_000, 7)
        assertTrue(again.importedEvents.isEmpty())
        assertEquals(first.events, again.events)
        val different = HistoryMerger.merge(first.events,
            listOf(HistoryEvent(HistoryKind.DEEP_MAINT, 20_999, null)), 10_000, 7)
        assertEquals(1, different.importedEvents.size)
        val later = HistoryMerger.merge(first.events,
            listOf(HistoryEvent(HistoryKind.DEEP_IDLE, 21_001, null)), 10_000, 7)
        assertEquals(1, later.importedEvents.size)
    }

    @Test fun appReadbackDedupesButDoesNotDiscardOsExitReason() {
        val verified = app(20_000, EventType.VERIFY).copy(deep = DeepState.IDLE)
        val merge = HistoryMerger.merge(session + verified,
            listOf(HistoryEvent(HistoryKind.DEEP_IDLE, 20_500, null),
                HistoryEvent(HistoryKind.NORMAL, 50_000, "motion")), 10_000, 7)
        assertEquals(1, merge.importedEvents.size)
        assertEquals("motion", merge.importedEvents.single().detail)
        val normal = app(50_000, EventType.IDLE_CHANGED).copy(
            deep = DeepState.ACTIVE, light = com.akylas.enforcedoze.doze.LightState.ACTIVE)
        val withAppNormal = HistoryMerger.merge(session + verified + normal,
            listOf(HistoryEvent(HistoryKind.NORMAL, 50_000, "motion")), 10_000, 7)
        assertEquals(mapOf("motion" to 1), SessionAggregator.summarize(withAppNormal.events).single().exitsByReason)
    }

    @Test fun truncationFlagLivesOnAnchorAndEmptyHistoryClaimsNoEvidence() {
        val merge = HistoryMerger.merge(session,
            listOf(HistoryEvent(HistoryKind.DEEP_IDLE, 11_000, null)), 10_000, 7)
        assertTrue(merge.truncated)
        assertTrue(merge.events.single { it.type == EventType.SCREEN_OFF }.historyTruncated)
        val empty = HistoryMerger.merge(session, emptyList<HistoryEvent>(), 10_000, 7)
        assertFalse(empty.truncated)
        assertEquals(session, empty.events)
        val overwritten = HistoryMerger.merge(session,
            listOf(HistoryEvent(HistoryKind.DEEP_IDLE, 110_000, null)), 10_000, 7)
        assertTrue(overwritten.truncated)
        assertTrue(overwritten.importedEvents.isEmpty())
        assertTrue(Problem.HISTORY_TRUNCATED in SessionAggregator.summarize(overwritten.events).single().problems)
    }

    @Test fun differentBootDoesNotDeduplicateAndWrongOrUnknownBootDoesNotImport() {
        val otherBoot = JournalEvent(6, 20_000, 100_000, 20, Source.OS_HISTORY, null,
            historyKind = HistoryKind.DEEP_IDLE)
        val history = listOf(HistoryEvent(HistoryKind.DEEP_IDLE, 20_000, null))
        val merge = HistoryMerger.merge(session + otherBoot, history, 10_000, 7)
        assertEquals(1, merge.importedEvents.size)
        assertEquals(7, merge.importedEvents.single().bootId)
        assertTrue(otherBoot in merge.events)
        assertTrue(HistoryMerger.merge(session, history, 10_000, 8).importedEvents.isEmpty())
        assertTrue(HistoryMerger.merge(session, history, 10_000, -1).importedEvents.isEmpty())
    }

    @Test fun importsOnlyLatestCarryInAndSessionRangeNotNegativeOrFutureTimes() {
        val merge = HistoryMerger.merge(session, listOf(
            HistoryEvent(HistoryKind.NORMAL, -1, "old boot"),
            HistoryEvent(HistoryKind.NORMAL, 1_000, null),
            HistoryEvent(HistoryKind.DEEP_IDLE, 5_000, null),
            HistoryEvent(HistoryKind.NORMAL, 100_001, "screen"),
        ), 10_000, 7)
        assertEquals(listOf(5_000L), merge.importedEvents.map { it.elapsedRealtime })
        assertEquals(100.0, SessionAggregator.summarize(merge.events).single()
            .coveragePercent.getValue(Coverage.DEEP_IDLE), 0.001)
    }
}
