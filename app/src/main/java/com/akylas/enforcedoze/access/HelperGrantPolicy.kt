package com.akylas.enforcedoze.access

/** Recorded helpers are never retried implicitly, even after a user revokes access. */
object HelperGrantPolicy {
    enum class Trigger { AUTOMATIC, EXPLICIT }

    fun commands(
        helpers: Map<String, String>,
        applied: Set<String>,
        trigger: Trigger,
    ): Map<String, String> = helpers.filterKeys { trigger == Trigger.EXPLICIT || it !in applied }

    /** A reset must durably forget a helper before revoking it, so a later automatic grant can retry. */
    fun forget(applied: Set<String>, keys: Set<String>, persist: (Set<String>) -> Boolean): Boolean =
        persist(applied - keys)

    /** Persist before each mutation: a grant can kill the process, even during an explicit retry. */
    fun runAttempts(
        commands: Map<String, String>,
        applied: Set<String>,
        persist: (Set<String>) -> Boolean,
        execute: (String) -> CommandResult,
    ): Map<String, CommandResult> {
        val record = applied.toMutableSet()
        val results = linkedMapOf<String, CommandResult>()
        for ((item, command) in commands) {
            record.add(item)
            // Transport failures consume the automatic attempt too. Only an explicit action retries.
            if (!persist(record.toSet())) break
            results[item] = execute(command)
        }
        return results
    }
}
