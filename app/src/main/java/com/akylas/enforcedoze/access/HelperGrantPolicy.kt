package com.akylas.enforcedoze.access

/** Recorded helpers are never retried implicitly, even after a user revokes access. */
object HelperGrantPolicy {
    enum class Trigger { AUTOMATIC, EXPLICIT }
    enum class NotRunReason { RECORD_WRITE_FAILED }

    // Reserved policy-only exit code: a backend did not produce these entries.
    private val recordWriteFailed = CommandResult(
        Int.MIN_VALUE, emptyList(), listOf(NotRunReason.RECORD_WRITE_FAILED.name), 0, false,
    )

    @JvmStatic
    fun notRunReason(result: CommandResult): NotRunReason? {
        return if (result == recordWriteFailed) NotRunReason.RECORD_WRITE_FAILED else null
    }

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
            if (!persist(record.toSet())) {
                for (notRun in commands.keys - results.keys) results[notRun] = recordWriteFailed
                break
            }
            results[item] = execute(command)
        }
        return results
    }
}
