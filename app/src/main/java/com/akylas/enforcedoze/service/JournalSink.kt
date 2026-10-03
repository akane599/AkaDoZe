package com.akylas.enforcedoze.service

import android.content.Context
import android.util.Log
import com.akylas.enforcedoze.doze.Clock
import com.akylas.enforcedoze.doze.DozeEvent
import com.akylas.enforcedoze.doze.DozeEventSink
import com.akylas.enforcedoze.doze.EventType
import com.akylas.enforcedoze.monitor.JournalDb
import com.akylas.enforcedoze.monitor.JournalEvent

/** Process-lifetime sink. Journal failures are diagnostic and must never interrupt restoration. */
class JournalSink(context: Context, private val clock: Clock) : DozeEventSink {
    private val db = JournalDb(context)
    private val bootId = JournalDb.currentBootId(context)
    @Volatile var sessionId: Long = 0L
        private set

    fun beginSession() {
        sessionId = maxOf(sessionId + 1, clock.wallTime())
    }

    override fun emit(event: DozeEvent) = record(event, null, null)

    fun screen(type: EventType, battery: Int, charging: Boolean) {
        record(DozeEvent(type, type.name), battery.takeIf { it in 0..100 }, charging)
    }

    private fun record(event: DozeEvent, battery: Int?, charging: Boolean?) {
        try {
            db.insert(JournalEvent.fromDozeEvent(
                event, bootId, clock.elapsedRealtime(), clock.wallTime(), sessionId, battery, charging,
            ))
        } catch (error: Exception) {
            Log.e("DozeJournal", "Journal enqueue failed", error)
        }
    }
}
