package com.akylas.enforcedoze.service

import android.content.Context
import com.akylas.enforcedoze.access.Prefs
import com.akylas.enforcedoze.doze.CorruptLedgerLine
import com.akylas.enforcedoze.doze.DozeEvent
import com.akylas.enforcedoze.doze.DozeEventSink
import com.akylas.enforcedoze.doze.EventType
import com.akylas.enforcedoze.doze.LedgerStore
import com.akylas.enforcedoze.doze.RestoreLedger
import com.akylas.enforcedoze.doze.RestoreLedgerCodec

/** This file is excluded from backup. Never store device-owned restoration intent in default prefs. */
class SharedPrefsLedgerStore(context: Context, private val sink: DozeEventSink) : LedgerStore {
    private val prefs = context.getSharedPreferences("doze_ledger", Context.MODE_PRIVATE)
    // SharedPreferences changes its memory map even when commit() fails. Only expose committed intent.
    private var committedLedger: RestoreLedger? = null
    var corruptLines: List<CorruptLedgerLine> = emptyList()
        private set
    var loadFailed: Boolean = false
        private set

    @Synchronized
    override fun load(): RestoreLedger {
        committedLedger?.let { return it }
        return try {
            val encoded = prefs.getString(Prefs.RESTORE_LEDGER, Prefs.DEFAULT_RESTORE_LEDGER).orEmpty()
            val retained = prefs.getString(CORRUPT_LINES, "").orEmpty()
            val decoded = RestoreLedgerCodec.decode(encoded)
            corruptLines = (decoded.corruptLines + RestoreLedgerCodec.decode(retained).corruptLines)
                .distinctBy { it.line }
            if (corruptLines.isNotEmpty()) {
                // Raw damaged lines can contain package names: journal their locations, not payloads.
                sink.emit(DozeEvent(EventType.ERROR, "LEDGER_CORRUPT_LINES=${corruptLines.size}"))
                corruptLines.forEach { sink.emit(DozeEvent(EventType.ERROR, "LEDGER_CORRUPT_LINE=${it.lineNumber}")) }
            }
            loadFailed = false
            decoded.ledger.also { committedLedger = it }
        } catch (error: Exception) {
            loadFailed = true
            sink.emit(DozeEvent(EventType.ERROR, "LEDGER_LOAD_FAILED"))
            throw error
        }
    }

    @Synchronized
    override fun save(ledger: RestoreLedger) {
        check(!loadFailed) { "Cannot overwrite unreadable restoration intent" }
        check(prefs.edit()
            .putString(Prefs.RESTORE_LEDGER, LedgerRecovery.encodePreservingCorruption(ledger, corruptLines))
            .putString(CORRUPT_LINES, corruptLines.joinToString("\n") { it.line })
            .commit()) { "Ledger commit failed" }
        committedLedger = ledger
    }

    /** Only the caller's NORMAL + mForceIdle=false readbacks authorize discarding damaged lines. */
    @Synchronized
    fun clearCorruptionAfterRecovery(ledger: RestoreLedger) {
        check(!loadFailed) { "Unreadable ledger remains recovery debt" }
        sink.emit(DozeEvent(EventType.ERROR, "LEDGER_CORRUPT_RECOVERED_LINES=${corruptLines.size}"))
        check(prefs.edit()
            .putString(Prefs.RESTORE_LEDGER, RestoreLedgerCodec.encode(ledger))
            .remove(CORRUPT_LINES)
            .commit()) { "Recovered ledger commit failed" }
        corruptLines = emptyList()
        committedLedger = ledger
    }

    companion object {
        private const val CORRUPT_LINES = "restoreLedgerCorruptLines"
    }
}
