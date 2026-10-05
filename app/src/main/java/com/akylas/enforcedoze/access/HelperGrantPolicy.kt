package com.akylas.enforcedoze.access

/** Recorded helpers are never retried implicitly, even after a user revokes access. */
object HelperGrantPolicy {
    enum class Trigger { AUTOMATIC, EXPLICIT }

    fun commands(
        helpers: Map<String, String>,
        applied: Set<String>,
        trigger: Trigger,
    ): Map<String, String> = helpers.filterKeys { trigger == Trigger.EXPLICIT || it !in applied }
}
