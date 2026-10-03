package com.akylas.enforcedoze.monitor

import com.akylas.enforcedoze.doze.DeepState
import com.akylas.enforcedoze.doze.EventType
import com.akylas.enforcedoze.doze.LightState
import com.akylas.enforcedoze.doze.parse.HistoryEvent
import com.akylas.enforcedoze.doze.parse.HistoryKind
import com.akylas.enforcedoze.doze.parse.IdlingHistory
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
        if (bootId < 0) return HistoryMergeResult(appEvents.toList(), emptyList(), false)
        val anchor = appEvents.firstOrNull {
            it.bootId == bootId && it.elapsedRealtime == sessionStartElapsed &&
                it.type == EventType.SCREEN_OFF
        } ?: return HistoryMergeResult(appEvents.toList(), emptyList(), false)
        val session = appEvents.filter { it.bootId == bootId && it.sessionId == anchor.sessionId }
        val end = session.filter { it.type == EventType.SCREEN_ON }
            .minOfOrNull { it.elapsedRealtime } ?: session.maxOf { it.elapsedRealtime }
        val validHistory = historyEvents.filter { it.elapsedRealtime >= 0 }.sortedBy { it.elapsedRealtime }
        val truncated = validHistory.firstOrNull()?.elapsedRealtime?.let { it > sessionStartElapsed } ?: false
        val history = validHistory.filter { it.elapsedRealtime <= end }
        val carryIn = history.lastOrNull { it.elapsedRealtime < sessionStartElapsed }
        val candidates = history.filter { it.elapsedRealtime >= sessionStartElapsed || it == carryIn }
        val imported = mutableListOf<JournalEvent>()
        for (entry in candidates) {
            val duplicate = (session.asSequence() + imported.asSequence()).any {
                kindOf(it) == entry.kind && abs(it.elapsedRealtime - entry.elapsedRealtime) <= 1_000 &&
                    (it.source == Source.OS_HISTORY || entry.reason.isNullOrBlank())
            }
            if (duplicate) continue
            val states = statesFor(entry.kind)
            imported += JournalEvent(
                bootId = bootId,
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
        val merged = appEvents.map {
            if (it == anchor && truncated) it.copy(historyTruncated = true) else it
        } + imported
        return HistoryMergeResult(
            merged.sortedWith(compareBy<JournalEvent> { it.bootId }.thenBy { it.elapsedRealtime }),
            imported.toList(),
            truncated,
        )
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
