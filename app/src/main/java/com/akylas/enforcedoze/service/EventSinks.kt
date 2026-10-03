package com.akylas.enforcedoze.service

import com.akylas.enforcedoze.doze.DozeEvent
import com.akylas.enforcedoze.doze.DozeEventSink
import java.util.concurrent.CopyOnWriteArrayList

/** Subscribers are diagnostic only: a presentation failure cannot interrupt system restoration. */
class EventSinks(
    private val diagnosticLogger: (String, Throwable) -> Unit = { _, _ -> },
) : DozeEventSink {
    private val sinks = CopyOnWriteArrayList<DozeEventSink>()
    fun addSink(sink: DozeEventSink) { sinks.addIfAbsent(sink) }
    fun removeSink(sink: DozeEventSink) { sinks.remove(sink) }
    override fun emit(event: DozeEvent) {
        for (sink in sinks) {
            try {
                sink.emit(event)
            } catch (error: Exception) {
                diagnosticLogger("Doze subscriber failed", error)
            }
        }
    }
}
