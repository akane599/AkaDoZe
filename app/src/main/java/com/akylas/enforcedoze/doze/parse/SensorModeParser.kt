package com.akylas.enforcedoze.doze.parse

import com.akylas.enforcedoze.doze.SensorMode

data class SensorModeReading(val mode: SensorMode, val allowToken: String?)

object SensorModeParser {
    private val modeLine = Regex("^\\s*Mode\\s*:\\s*([^\\s:]+)\\s*(?::\\s*(.*?)\\s*)?$")

    @JvmStatic
    fun parse(output: List<String>): SensorModeReading = parse(output.joinToString("\n"))

    @JvmStatic
    fun parse(output: String): SensorModeReading {
        for (line in output.lineSequence()) {
            val match = modeLine.matchEntire(line) ?: continue
            val mode = when (match.groupValues[1]) {
                "NORMAL" -> SensorMode.NORMAL
                "RESTRICTED" -> SensorMode.RESTRICTED
                else -> SensorMode.OTHER
            }
            val allowToken = match.groupValues[2].trim().takeIf { it.isNotEmpty() }
            return SensorModeReading(mode, allowToken)
        }
        return SensorModeReading(SensorMode.UNVERIFIED, null)
    }
}
