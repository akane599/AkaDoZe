package com.akylas.enforcedoze.monitor

import com.akylas.enforcedoze.doze.DeepState
import com.akylas.enforcedoze.doze.EventType
import com.akylas.enforcedoze.doze.LightState
import com.akylas.enforcedoze.doze.parse.HistoryEvent
import com.akylas.enforcedoze.doze.parse.HistoryKind
import com.akylas.enforcedoze.doze.parse.IdlingHistory
import com.akylas.enforcedoze.doze.parse.IdlingHistoryParser
import kotlin.math.abs

data class HistoryMergeResult(
    val events: List<JournalEvent>,
    val importedEvents: List<JournalEvent>,
    val truncated: Boolean,
)

object HistoryMerger {
    /**
     * History is a snapshot from [bootId], not a timestamp transferable across boots.
     * A matching session anchor is required. One pre-start transition supplies carry-in evidence.
     * Persist `events` (including an updated truncation flag on the session anchor) if storing the merge.
     */
    @JvmStatic
    fun merge(
        appEvents: List<JournalEvent>,
        historyEvents: IdlingHistory,
        sessionStartElapsed: Long,
        bootId: Int,
    ): HistoryMergeResult = merge(appEvents, historyEvents.events, sessionStartElapsed, bootId)

    @JvmStatic
    fun merge(
        appEvents: List<JournalEvent>,
        historyEvents: List<HistoryEvent>,
        sessionStartElapsed: Long,
        bootId: Int,
    ): HistoryMergeResult {
        if (bootId < 0) return unchanged(appEvents)
        val anchor = sessionAnchor(appEvents, sessionStartElapsed, bootId) ?: return unchanged(appEvents)
        val session = appEvents.filter { it.bootId == bootId && it.sessionId == anchor.sessionId }
        val end = sessionEnd(session)
        val validHistory = historyEvents.filter { it.elapsedRealtime >= 0 }.sortedBy { it.elapsedRealtime }
        val truncated = isTruncated(validHistory, sessionStartElapsed)
        val candidates = historyCandidates(validHistory, sessionStartElapsed, end)
        val imported = importHistory(candidates, session, anchor)
        return HistoryMergeResult(mergedEvents(appEvents, imported, anchor, truncated), imported, truncated)
    }

    private fun unchanged(events: List<JournalEvent>): HistoryMergeResult {
        return HistoryMergeResult(events.toList(), emptyList(), false)
    }

    private fun sessionAnchor(events: List<JournalEvent>, start: Long, bootId: Int): JournalEvent? {
        return events.firstOrNull {
                it.bootId == bootId && it.elapsedRealtime == start && it.type == EventType.SCREEN_OFF
            }
    }

    private fun sessionEnd(events: List<JournalEvent>): Long {
        return events.filter { it.type == EventType.SCREEN_ON }.minOfOrNull { it.elapsedRealtime }
                ?: events.maxOf { it.elapsedRealtime }
    }

    // A late first transition alone is normal on a fresh boot. Only a full ring can lose
    // older transitions; flag it when those retained transitions cannot cover the start.
    private fun isTruncated(history: List<HistoryEvent>, start: Long): Boolean {
        return history.size == IdlingHistoryParser.HISTORY_CAPACITY && history.first().elapsedRealtime > start
    }

    private fun historyCandidates(history: List<HistoryEvent>, start: Long, end: Long): List<HistoryEvent> {
        val inRange = history.filter { it.elapsedRealtime <= end }
        val carryIn = inRange.lastOrNull { it.elapsedRealtime < start }
        return inRange.filter { it.elapsedRealtime >= start || it == carryIn }
    }

    private fun importHistory(
        candidates: List<HistoryEvent>, session: List<JournalEvent>, anchor: JournalEvent,
    ): List<JournalEvent> {
        val imported = mutableListOf<JournalEvent>()
        for (entry in candidates) {
            if (isDuplicate(entry, session.asSequence() + imported.asSequence())) continue
            imported += historyRow(entry, anchor)
        }
        return imported.toList()
    }

    private fun isDuplicate(entry: HistoryEvent, events: Sequence<JournalEvent>): Boolean {
        return events.any {
            kindOf(it) == entry.kind && abs(it.elapsedRealtime - entry.elapsedRealtime) <= 1_000 &&
                (it.source == Source.OS_HISTORY || entry.reason.isNullOrBlank())
        }
    }

    private fun historyRow(entry: HistoryEvent, anchor: JournalEvent): JournalEvent {
        val states = statesFor(entry.kind)
        return JournalEvent(
            bootId = anchor.bootId,
            elapsedRealtime = entry.elapsedRealtime,
            wallTime = anchor.wallTime + (entry.elapsedRealtime - anchor.elapsedRealtime),
            sessionId = anchor.sessionId,
            source = Source.OS_HISTORY,
            type = null,
            deep = states.first,
            light = states.second,
            detail = entry.reason,
            historyKind = entry.kind,
        )
    }

    private fun mergedEvents(
        appEvents: List<JournalEvent>, imported: List<JournalEvent>, anchor: JournalEvent, truncated: Boolean,
    ): List<JournalEvent> {
        val merged = appEvents.map {
            if (it == anchor && truncated) it.copy(historyTruncated = true) else it
        } + imported
        return merged.sortedWith(compareBy<JournalEvent> { it.bootId }.thenBy { it.elapsedRealtime })
    }

    internal fun statesFor(kind: HistoryKind): Pair<DeepState?, LightState?> = when (kind) {
        HistoryKind.NORMAL -> DeepState.ACTIVE to LightState.ACTIVE
        HistoryKind.DEEP_IDLE -> DeepState.IDLE to LightState.OVERRIDE
        HistoryKind.DEEP_MAINT -> DeepState.IDLE_MAINTENANCE to LightState.OVERRIDE
        // Light transitions provide no deep-state evidence.
        HistoryKind.LIGHT_IDLE -> null to LightState.IDLE
        HistoryKind.LIGHT_MAINT -> null to LightState.IDLE_MAINTENANCE
    }

    private fun kindOf(event: JournalEvent): HistoryKind? = event.historyKind ?: when {
        event.deep == DeepState.IDLE -> HistoryKind.DEEP_IDLE
        event.deep == DeepState.IDLE_MAINTENANCE -> HistoryKind.DEEP_MAINT
        event.light == LightState.IDLE -> HistoryKind.LIGHT_IDLE
        event.light == LightState.IDLE_MAINTENANCE -> HistoryKind.LIGHT_MAINT
        event.deep == DeepState.ACTIVE && event.light == LightState.ACTIVE -> HistoryKind.NORMAL
        else -> null
    }
}
