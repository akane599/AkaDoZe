package com.akylas.enforcedoze.service

import com.akylas.enforcedoze.access.Feature
import com.akylas.enforcedoze.doze.CorruptLedgerLine
import com.akylas.enforcedoze.doze.RestoreLedger
import com.akylas.enforcedoze.doze.RestoreLedgerCodec

/** Pure persistence policy: damaged intent remains evidence, never an empty successful ledger. */
object LedgerRecovery {
    @JvmStatic
    fun hasForceIntent(ledger: RestoreLedger, corruptLines: List<CorruptLedgerLine>, loadFailed: Boolean): Boolean =
        loadFailed || corruptLines.isNotEmpty() || ledger.entries.any { it.feature == Feature.FORCE_DOZE }

    @JvmStatic
    fun recoveryVerified(sensorMode: com.akylas.enforcedoze.doze.SensorMode, forceIdle: Boolean?): Boolean =
        sensorMode == com.akylas.enforcedoze.doze.SensorMode.NORMAL && forceIdle == false

    @JvmStatic
    fun encodePreservingCorruption(ledger: RestoreLedger, corruptLines: List<CorruptLedgerLine>): String =
        (listOf(RestoreLedgerCodec.encode(ledger)) + corruptLines.map { it.line })
            .filter { it.isNotEmpty() }.joinToString("\n")
}
