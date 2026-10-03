package com.akylas.enforcedoze.doze

import com.akylas.enforcedoze.access.Feature
import java.util.Collections

/** Legacy null originals were never mutated and are discarded on reconciliation. */
data class LedgerEntry @JvmOverloads constructor(
    val feature: Feature,
    val target: String?,
    val originalValue: String?,
    val appliedAtElapsed: Long,
    val attempts: Int = 0,
    val debt: Boolean = false,
    val apiLevel: Int? = null,
)

/** Defensive snapshot, including for Java callers. */
class RestoreLedger @JvmOverloads constructor(entries: List<LedgerEntry> = emptyList()) {
    val entries: List<LedgerEntry> = Collections.unmodifiableList(ArrayList(entries))

    override fun equals(other: Any?): Boolean = other is RestoreLedger && entries == other.entries
    override fun hashCode(): Int = entries.hashCode()
    override fun toString(): String = "RestoreLedger($entries)"
}

interface LedgerStore {
    fun load(): RestoreLedger

    /** Must finish durable persistence before returning; throw on failure. Android uses commit(). */
    fun save(ledger: RestoreLedger)
}

data class CorruptLedgerLine(val lineNumber: Int, val line: String)
data class LedgerDecodeResult(val ledger: RestoreLedger, val corruptLines: List<CorruptLedgerLine>)

/** Versioned, one entry per line. Percent escapes keep separators, newlines and null unambiguous. */
object RestoreLedgerCodec {
    @JvmStatic
    fun encode(ledger: RestoreLedger): String = ledger.entries.joinToString("\n") { entry ->
        listOf(
            "1", entry.feature.name, escape(entry.target), escape(entry.originalValue),
            entry.appliedAtElapsed.toString(), entry.attempts.toString(), entry.debt.toString(),
            entry.apiLevel?.toString() ?: "~",
        ).joinToString("|")
    }

    @JvmStatic
    fun decode(encoded: String): LedgerDecodeResult {
        val entries = mutableListOf<LedgerEntry>()
        val bad = mutableListOf<CorruptLedgerLine>()
        encoded.lineSequence().forEachIndexed { index, line ->
            if (line.isEmpty()) return@forEachIndexed
            val entry = try {
                val parts = line.split('|')
                require(parts.size in 7..8 && parts[0] == "1")
                LedgerEntry(
                    Feature.valueOf(parts[1]), unescape(parts[2]), unescape(parts[3]),
                    parts[4].toLong().also { require(it >= 0) },
                    parts[5].toInt().also { require(it >= 0) },
                    when (parts[6]) { "true" -> true; "false" -> false; else -> error("Invalid debt") },
                    parts.getOrNull(7)?.takeUnless { it == "~" }?.toInt()?.also { require(it >= 23) },
                )
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: IllegalStateException) {
                null
            }
            if (entry == null) bad.add(CorruptLedgerLine(index + 1, line)) else entries.add(entry)
        }
        return LedgerDecodeResult(RestoreLedger(entries), bad.toList())
    }

    private fun escape(value: String?): String = value?.replace("%", "%25")
        ?.replace("|", "%7C")?.replace("\n", "%0A")?.replace("\r", "%0D")
        ?.replace("~", "%7E") ?: "~"

    private fun unescape(value: String): String? {
        if (value == "~") return null
        return buildString {
            var index = 0
            while (index < value.length) {
                val char = value[index++]
                if (char != '%') {
                    require(char != '~' && char != '\r')
                    append(char)
                } else {
                    require(index + 2 <= value.length)
                    append(when (value.substring(index, index + 2)) {
                        "25" -> '%'; "7C" -> '|'; "0A" -> '\n'; "0D" -> '\r'; "7E" -> '~'
                        else -> throw IllegalArgumentException("Invalid escape")
                    })
                    index += 2
                }
            }
        }
    }
}
