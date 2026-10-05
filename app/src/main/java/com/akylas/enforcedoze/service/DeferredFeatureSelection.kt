package com.akylas.enforcedoze.service

import com.akylas.enforcedoze.monitor.EventCodes

/** Worker-confined, one-shot selection. Null means timed out/failed: preserve network access. */
class DeferredFeatureSelection(
    private val generation: Long,
    private val currentGeneration: GenerationSource,
    private val admission: Admission,
    private val selected: Selected,
) {
    // Own SAM types: java.util.function is API 24+, minSdk is 23.
    fun interface GenerationSource { fun get(): Long }
    fun interface Admission { fun admitted(): Boolean }
    fun interface Selected { fun accept(playingMusic: Boolean?) }

    private var completed = false

    fun complete(playingMusic: Boolean?): Boolean {
        if (completed || generation != currentGeneration.get() || !admission.admitted()) return false
        completed = true
        selected.accept(playingMusic)
        return true
    }

    fun noListener(sink: com.akylas.enforcedoze.doze.DozeEventSink) {
        sink.emit(com.akylas.enforcedoze.doze.DozeEvent(
            com.akylas.enforcedoze.doze.EventType.ERROR, EventCodes.MUSIC_SELECTION_UNAVAILABLE,
        ))
        complete(false)
    }

    fun cancel() { completed = true }
}
