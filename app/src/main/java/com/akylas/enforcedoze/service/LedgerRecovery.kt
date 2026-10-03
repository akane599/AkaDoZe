package com.akylas.enforcedoze.service

import com.akylas.enforcedoze.access.Feature
import com.akylas.enforcedoze.doze.CorruptLedgerLine
import com.akylas.enforcedoze.doze.DozeEvent
import com.akylas.enforcedoze.doze.DozeEventSink
import com.akylas.enforcedoze.doze.EventType
import com.akylas.enforcedoze.doze.RestoreLedger
import com.akylas.enforcedoze.doze.RestoreLedgerCodec

/** Pure persistence policy: damaged intent remains evidence, never an empty successful ledger. */
object LedgerRecovery {
    /** Both current and legacy seven-field records start with 1|FEATURE|. No payload decoding. */
    @JvmStatic
    fun featureToken(line: CorruptLedgerLine): String? {
        val parts = line.line.split('|', limit = 3)
        return parts.getOrNull(1)?.takeIf { parts[0] == "1" && it.isNotEmpty() }
    }

    @JvmStatic
    fun recoverable(line: CorruptLedgerLine): Boolean =
        featureToken(line) in setOf(Feature.FORCE_DOZE.name, Feature.MOTION_SENSORS.name)

    @JvmStatic
    fun needsRecovery(corruptLines: List<CorruptLedgerLine>, loadFailed: Boolean): Boolean =
        loadFailed || corruptLines.any(::recoverable)

    @JvmStatic
    fun hasForceIntent(ledger: RestoreLedger, corruptLines: List<CorruptLedgerLine>, loadFailed: Boolean): Boolean =
        needsRecovery(corruptLines, loadFailed) || ledger.entries.any { it.feature == Feature.FORCE_DOZE }

    @JvmStatic
    fun recoveryVerified(sensorMode: com.akylas.enforcedoze.doze.SensorMode, forceIdle: Boolean?): Boolean =
        sensorMode == com.akylas.enforcedoze.doze.SensorMode.NORMAL && forceIdle == false

    @JvmStatic
    fun encodePreservingCorruption(ledger: RestoreLedger, corruptLines: List<CorruptLedgerLine>): String =
        (listOf(RestoreLedgerCodec.encode(ledger)) + corruptLines.map { it.line })
            .filter { it.isNotEmpty() }.joinToString("\n")
}

/** Announce changed damaged intent, not every safety poll. Raw payloads never enter the journal. */
internal class LedgerDamageDebt(
    private val sink: DozeEventSink,
    announcedLines: List<CorruptLedgerLine> = emptyList(),
) {
    private var announced: Map<String?, Set<String>> = snapshot(announcedLines)
    private var announcedLoadFailure = false

    private fun snapshot(lines: List<CorruptLedgerLine>): Map<String?, Set<String>> =
        lines.groupBy(LedgerRecovery::featureToken)
            .mapValues { (_, group) -> group.map { it.line }.toSet() }

    fun update(corruptLines: List<CorruptLedgerLine>, loadFailed: Boolean) {
        val current = snapshot(corruptLines)
        current.forEach { (token, lines) ->
            if (announced[token] != lines) {
                val feature = Feature.values().firstOrNull { it.name == token }
                sink.emit(DozeEvent(EventType.RECOVERY_DEBT, "LEDGER_DAMAGED", feature = feature, target = token))
            }
        }
        if (loadFailed && !announcedLoadFailure) {
            sink.emit(DozeEvent(EventType.RECOVERY_DEBT, "LEDGER_DAMAGED"))
        }
        announced = current
        announcedLoadFailure = loadFailed
    }
}
