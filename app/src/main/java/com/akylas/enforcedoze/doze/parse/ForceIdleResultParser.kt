package com.akylas.enforcedoze.doze.parse

import com.akylas.enforcedoze.doze.DeepState

/** Informational command output only: Forced is not proof of the device's actual state. */
sealed interface ForceIdleResult {
    data object Forced : ForceIdleResult
    data object NotEnabled : ForceIdleResult
    data class StoppedAt(val state: DeepState) : ForceIdleResult
    data class Unknown(val raw: String) : ForceIdleResult
}

object ForceIdleResultParser {
    private val stoppedAt = Regex("Unable to go deep idle; stopped at (\\S+)")

    @JvmStatic
    fun parse(output: List<String>): ForceIdleResult = parse(output.joinToString("\n"))

    @JvmStatic
    fun parse(output: String): ForceIdleResult {
        val reply = output.trim()
        return when (reply) {
            "Now forced in to deep idle mode", "Now forced in to idle mode" -> ForceIdleResult.Forced
            "Unable to go deep idle; not enabled" -> ForceIdleResult.NotEnabled
            else -> {
                val match = stoppedAt.matchEntire(reply)
                if (match == null) {
                    ForceIdleResult.Unknown(output)
                } else {
                    ForceIdleResult.StoppedAt(DozeStateParser.deepToken(match.groupValues[1]))
                }
            }
        }
    }
}
