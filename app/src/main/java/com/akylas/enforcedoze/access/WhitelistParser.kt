package com.akylas.enforcedoze.access

import java.util.Collections

enum class WhitelistParseReason { COMMAND_FAILED, TIMED_OUT, PARTIALLY_PARSED, EMPTY }

data class WhitelistParseResult(
    val packages: List<String>,
    val unparsedLineCount: Int,
    val parseReason: WhitelistParseReason?,
) {
    /** Partial and failed reads cannot prove package absence for edit readback. */
    val verified: Boolean get() = parseReason == null || parseReason == WhitelistParseReason.EMPTY
}

internal data class WhitelistRow(val packageName: String, val deepDozeMember: Boolean) {
    companion object {
        private val kinds = setOf("system", "system-excidle", "user")

        fun parse(line: String): WhitelistRow? {
            val fields = line.trim().split(',').map(String::trim)
            if (fields.size != 3 || fields[0] !in kinds ||
                (fields[1] != "android" && !PackageNames.isValid(fields[1])) ||
                fields[2].toIntOrNull()?.let { it >= 0 } != true
            ) return null
            // Except-idle exemptions do not establish deep-Doze whitelist membership.
            return WhitelistRow(fields[1], fields[0] != "system-excidle")
        }
    }
}

object WhitelistParser {
    @JvmStatic
    fun parse(result: CommandResult): WhitelistParseResult {
        val packages = linkedSetOf<String>()
        var unparsed = 0
        for (line in result.stdout) {
            if (line.isBlank()) continue
            val row = WhitelistRow.parse(line)
            if (row == null) unparsed++
            else if (row.deepDozeMember) packages.add(row.packageName)
        }
        val reason = when {
            result.timedOut -> WhitelistParseReason.TIMED_OUT
            !result.ok -> WhitelistParseReason.COMMAND_FAILED
            unparsed > 0 -> WhitelistParseReason.PARTIALLY_PARSED
            packages.isEmpty() -> WhitelistParseReason.EMPTY
            else -> null
        }
        return WhitelistParseResult(Collections.unmodifiableList(packages.toList()), unparsed, reason)
    }
}
