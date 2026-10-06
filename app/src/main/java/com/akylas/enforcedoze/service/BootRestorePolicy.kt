package com.akylas.enforcedoze.service

import com.akylas.enforcedoze.doze.RestoreLedgerCodec

/** Receiver preflight; null snapshots mean unreadable intent, never an empty ledger. */
object BootRestorePolicy {
    @JvmStatic
    fun shouldRestore(encoded: String?, retained: String?): Boolean =
        encoded == null || retained == null || hasPending(encoded, retained)

    @JvmStatic
    fun restoreIfPending(encoded: String?, retained: String?, restore: Runnable): Boolean {
        if (!shouldRestore(encoded, retained)) return false
        restore.run()
        return true
    }

    private fun hasPending(encoded: String, retained: String): Boolean {
        val decoded = RestoreLedgerCodec.decode(encoded)
        val damaged = decoded.corruptLines + RestoreLedgerCodec.decode(retained).corruptLines
        return decoded.ledger.entries.isNotEmpty() || damaged.any(LedgerRecovery::recoverable)
    }
}
