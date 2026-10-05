package com.akylas.enforcedoze.service

import com.akylas.enforcedoze.monitor.EventCodes

import android.content.Context
import android.util.Log
import com.akylas.enforcedoze.access.Feature
import com.akylas.enforcedoze.monitor.JournalIdentity
import com.akylas.enforcedoze.doze.Clock
import com.akylas.enforcedoze.doze.DozeEvent
import com.akylas.enforcedoze.doze.DozeEventSink
import com.akylas.enforcedoze.doze.EventType
import com.akylas.enforcedoze.monitor.JournalDb
import com.akylas.enforcedoze.monitor.JournalEvent
import com.akylas.enforcedoze.monitor.HistoryMerger
import com.akylas.enforcedoze.doze.parse.IdlingHistoryParser
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Process-lifetime sink. Journal failures are diagnostic and must never interrupt restoration. */
class JournalSink(context: Context, private val clock: Clock) : DozeEventSink {
    private val db = JournalDb(context)
    val bootId = JournalDb.currentBootId(context)
    private val sinks = EventSinks { message, error -> Log.w("DozeJournal", message, error) }
    fun addSink(sink: DozeEventSink) = sinks.addSink(sink)
    fun removeSink(sink: DozeEventSink) = sinks.removeSink(sink)
    private val identity = JournalIdentity()
    val sessionId: Long get() = identity.sessionId

    /** Read-only journal access for the Monitor; futures complete on the journal worker, never block main. */
    @JvmOverloads
    fun queryRecent(limit: Int = JournalDb.MAX_ROWS): Future<List<JournalEvent>> = db.queryRecent(limit)
    fun querySession(sessionId: Long, bootId: Int): Future<List<JournalEvent>> = db.querySession(sessionId, bootId)

    @JvmOverloads
    fun beginSession(wallTime: Long = clock.wallTime()) {
        identity.beginSession(wallTime)
    }

    fun beginSelfTest(feature: Feature) = identity.beginSelfTest(feature, clock.wallTime())
    fun endSelfTest() = identity.endSelfTest()

    override fun emit(event: DozeEvent) = record(event, null, null)

    @JvmOverloads
    fun screen(
        type: EventType,
        battery: Int,
        charging: Boolean,
        elapsedRealtime: Long = clock.elapsedRealtime(),
        wallTime: Long = clock.wallTime(),
    ) {
        record(DozeEvent(type, type.name), battery.takeIf { it in 0..100 }, charging, elapsedRealtime, wallTime)
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
            emit(DozeEvent(EventType.ERROR, EventCodes.HISTORY_IMPORT_FAILED))
            Log.e("DozeJournal", "History import failed", error)
        }
    }

    private fun record(
        event: DozeEvent,
        battery: Int?,
        charging: Boolean?,
        elapsedRealtime: Long = clock.elapsedRealtime(),
        wallTime: Long = clock.wallTime(),
    ) {
        try {
            db.insert(JournalEvent.fromDozeEvent(
                event, bootId, elapsedRealtime, wallTime, identity.forEvent(event.feature), battery, charging,
            )) { error -> Log.e("DozeJournal", "Journal write failed", error) }
        } catch (error: Exception) {
            Log.e("DozeJournal", "Journal enqueue failed", error)
        }
        sinks.emit(event)
    }
}
