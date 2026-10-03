package com.akylas.enforcedoze.service

import com.akylas.enforcedoze.doze.EventType
import com.akylas.enforcedoze.doze.parse.HistoryKind
import com.akylas.enforcedoze.doze.parse.IdlingHistoryParser
import com.akylas.enforcedoze.monitor.HistoryMerger
import com.akylas.enforcedoze.monitor.JournalEvent
import com.akylas.enforcedoze.monitor.Source
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class HistoryImportWiringTest {
    @Test fun endingSessionImportsParserHistoryPreservingStoredAnchorIdsAndDeduplicating() {
        val start = JournalEvent(7, 10_000, 1_010_000, 20, Source.APP, EventType.SCREEN_OFF, id = 41)
        val end = JournalEvent(7, 100_000, 1_100_000, 20, Source.APP, EventType.SCREEN_ON, id = 42)
        val history = IdlingHistoryParser.parse(listOf(
            "Idling history:",
            "  normal: -5s (screen)",
            "  deep-maint: -20s",
            "  deep-idle: -60s",
        ), 100_000)
        val merged = HistoryMerger.merge(listOf(start, end), history, start.elapsedRealtime, 7)
        assertEquals(3, merged.importedEvents.size)
        assertTrue(merged.truncated)
        val anchor = merged.events.single { it.type == EventType.SCREEN_OFF }
        assertEquals(41L, anchor.id)
        assertTrue(anchor.historyTruncated)
        assertEquals(42L, merged.events.single { it.type == EventType.SCREEN_ON }.id)
        assertTrue(merged.importedEvents.all { it.sessionId == 20L && it.bootId == 7 && it.source == Source.OS_HISTORY })
        val idle = merged.importedEvents.single { it.historyKind == HistoryKind.DEEP_IDLE }
        assertEquals(40_000L, idle.elapsedRealtime)
        assertEquals(1_040_000L, idle.wallTime)
        assertTrue(HistoryMerger.merge(merged.events, history, start.elapsedRealtime, 7).importedEvents.isEmpty())
        assertTrue(HistoryMerger.merge(listOf(start, end), history, start.elapsedRealtime, -1).importedEvents.isEmpty())
    }

    @Test fun exitWorkerReadsDumpAndPersistsFullMergeAfterStoredIdQuery() {
        val service = File("src/main/java/com/akylas/enforcedoze/ForceDozeService.java").readText()
        val receiver = service.substringAfter("private void receiveOnWorker(")
        assertTrue(receiver.contains("if (exitTrigger) runtime.importHistory()"))
        assertTrue(receiver.indexOf("handleScreenOn(this, 0, 0)") < receiver.indexOf("runtime.importHistory()"))
        val runtime = File("src/main/java/com/akylas/enforcedoze/service/DozeRuntime.kt").readText()
            .substringAfter("fun importHistory()").substringBefore("fun recordAccessDebt()")
        assertTrue(runtime.contains("if (!grants().dump && access.level < AccessLevel.SHELL) return"))
        assertTrue(runtime.contains("access.reads().run(\"dumpsys deviceidle\", 8_000)"))
        assertTrue(runtime.contains("if (result.ok) journal.importHistory(result.stdout, now)"))
        val sink = File("src/main/java/com/akylas/enforcedoze/service/JournalSink.kt").readText()
            .substringAfter("fun importHistory(").substringBefore("private fun record(")
        assertTrue(sink.contains("if (bootId < 0) return"))
        assertTrue(sink.contains("IdlingHistoryParser.parse(output, nowElapsed)"))
        assertTrue(sink.contains("db.querySession(endingSession, bootId).get"))
        assertTrue(sink.contains("HistoryMerger.merge(events, history, start.elapsedRealtime, bootId)"))
        assertTrue("persist updated existing-id anchor too", sink.contains("db.insertAll(merged.events).get"))
    }
}
