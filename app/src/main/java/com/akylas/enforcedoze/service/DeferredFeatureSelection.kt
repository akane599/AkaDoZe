package com.akylas.enforcedoze.service

import java.util.function.BooleanSupplier
import java.util.function.Consumer
import java.util.function.LongSupplier

/** Worker-confined, one-shot selection. Null means timed out/failed: preserve network access. */
class DeferredFeatureSelection(
    private val generation: Long,
    private val currentGeneration: LongSupplier,
    private val admission: BooleanSupplier,
    private val selected: Consumer<Boolean?>,
) {
    private var completed = false

    fun complete(playingMusic: Boolean?): Boolean {
        if (completed || generation != currentGeneration.asLong || !admission.asBoolean) return false
        completed = true
        selected.accept(playingMusic)
        return true
    }

    fun cancel() { completed = true }
}
