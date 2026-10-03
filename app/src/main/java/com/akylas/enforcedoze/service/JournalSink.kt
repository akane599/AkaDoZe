package com.akylas.enforcedoze.service

import android.content.Context
import android.util.Log
import com.akylas.enforcedoze.doze.Clock
import com.akylas.enforcedoze.doze.DozeEvent
import com.akylas.enforcedoze.doze.DozeEventSink
import com.akylas.enforcedoze.doze.EventType
import com.akylas.enforcedoze.monitor.JournalDb
import com.akylas.enforcedoze.monitor.JournalEvent
import com.akylas.enforcedoze.monitor.HistoryMerger
import com.akylas.enforcedoze.doze.parse.IdlingHistoryParser
import java.util.concurrent.TimeUnit

/** Process-lifetime sink. Journal failures are diagnostic and must never interrupt restoration. */
class JournalSink(context: Context, private val clock: Clock) : DozeEventSink {
    private val db = JournalDb(context)
    private val bootId = JournalDb.currentBootId(context)
    private val sinks = EventSinks()
    fun addSink(sink: DozeEventSink) = sinks.addSink(sink)
    fun removeSink(sink: DozeEventSink) = sinks.removeSink(sink)
    @Volatile var sessionId: Long = 0L
        private set

    fun beginSession() {
        sessionId = maxOf(sessionId + 1, clock.wallTime())
    }

    override fun emit(event: DozeEvent) = record(event, null, null)

    fun screen(type: EventType, battery: Int, charging: Boolean) {
        record(DozeEvent(type, type.name), battery.takeIf { it in 0..100 }, charging)
    }

    /** Called only on doze-worker, after the ending session's exit events have been enqueued. */
    fun importHistory(output: List<String>, nowElapsed: Long) {
        if (bootId < 0) return
        val endingSession = sessionId
        try {
            val history = IdlingHistoryParser.parse(output, nowElapsed)
            if (history.events.isEmpty()) return
            // Query the stored rows, not copies with missing ids; insertAll updates the anchor in place.
            val events = db.querySession(endingSession, bootId).get(2, TimeUnit.SECONDS)
            val start = events.firstOrNull { it.type == EventType.SCREEN_OFF } ?: return
            val merged = HistoryMerger.merge(events, history, start.elapsedRealtime, bootId)
            db.insertAll(merged.events).get(2, TimeUnit.SECONDS)
        } catch (error: Exception) {
            emit(DozeEvent(EventType.ERROR, "HISTORY_IMPORT_FAILED"))
            Log.e("DozeJournal", "History import failed", error)
        }
    }

    private fun record(event: DozeEvent, battery: Int?, charging: Boolean?) {
        try {
            db.insert(JournalEvent.fromDozeEvent(
                event, bootId, clock.elapsedRealtime(), clock.wallTime(), sessionId, battery, charging,
            ))
        } catch (error: Exception) {
            Log.e("DozeJournal", "Journal enqueue failed", error)
        }
        sinks.emit(event)
    }
}
