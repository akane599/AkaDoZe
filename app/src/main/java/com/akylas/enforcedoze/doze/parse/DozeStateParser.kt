package com.akylas.enforcedoze.doze.parse

import com.akylas.enforcedoze.doze.DeepState
import com.akylas.enforcedoze.doze.LightState

data class DozeStateReading(
    val deep: DeepState?,
    val light: LightState?,
    val forceIdle: Boolean?,
    val quickDozeActivated: Boolean?,
    val screenOn: Boolean?,
    val charging: Boolean?,
    val settings: Map<String, String>,
)

object DozeStateParser {
    private val fieldLine = Regex("^\\s*m\\w+=")
    private val field = Regex(
        "(?:^|\\s)(mState|mLightState|mForceIdle|mQuickDozeActivated|mScreenOn|mCharging)=(\\S+)",
    )
    private val setting = Regex("^\\s*([A-Za-z0-9_]+)=(.*)$")

    @JvmStatic
    fun parse(output: List<String>): DozeStateReading = parse(output.joinToString("\n"))

    @JvmStatic
    fun parse(output: String): DozeStateReading {
        val fields = mutableMapOf<String, String>()
        val settings = linkedMapOf<String, String>()
        var settingsIndent: Int? = null
        for (line in output.lineSequence()) {
            val indent = line.takeWhile { it.isWhitespace() }.length
            val headerIndent = settingsIndent
            if (headerIndent != null) {
                val match = setting.matchEntire(line)
                if (indent > headerIndent && match != null) {
                    settings[match.groupValues[1]] = match.groupValues[2].trim()
                    continue
                }
                settingsIndent = null
            }
            if (line.trim() == "Settings:") {
                settingsIndent = indent
            } else if (fieldLine.containsMatchIn(line)) {
                for (match in field.findAll(line)) {
                    fields[match.groupValues[1]] = match.groupValues[2]
                }
            }
        }
        return DozeStateReading(
            deep = fields["mState"]?.let(::deepToken),
            light = fields["mLightState"]?.let(::lightToken),
            forceIdle = fields["mForceIdle"]?.let(::booleanToken),
            quickDozeActivated = fields["mQuickDozeActivated"]?.let(::booleanToken),
            screenOn = fields["mScreenOn"]?.let(::booleanToken),
            charging = fields["mCharging"]?.let(::booleanToken),
            settings = settings.toMap(),
        )
    }

    /** A command reply must be one exact token, not a substring or multiple output lines. */
    @JvmStatic
    fun parseDeep(output: String): DeepState? = output.trim().takeIf { it.isNotEmpty() }?.let(::deepToken)

    @JvmStatic
    fun parseDeep(output: List<String>): DeepState? = parseDeep(output.joinToString("\n"))

    @JvmStatic
    fun parseLight(output: String): LightState? = output.trim().takeIf { it.isNotEmpty() }?.let(::lightToken)

    @JvmStatic
    fun parseLight(output: List<String>): LightState? = parseLight(output.joinToString("\n"))

    /** Used for both `get force` and `get quick`; missing or malformed replies are unverified. */
    @JvmStatic
    fun parseBoolean(output: String): Boolean? = booleanToken(output.trim())

    @JvmStatic
    fun parseBoolean(output: List<String>): Boolean? = parseBoolean(output.joinToString("\n"))

    internal fun deepToken(token: String): DeepState =
        DeepState.entries.firstOrNull { it.name == token } ?: DeepState.UNKNOWN

    private fun lightToken(token: String): LightState =
        LightState.entries.firstOrNull { it.name == token } ?: LightState.UNKNOWN

    private fun booleanToken(token: String): Boolean? = when (token) {
        "true" -> true
        "false" -> false
        else -> null
    }
}
