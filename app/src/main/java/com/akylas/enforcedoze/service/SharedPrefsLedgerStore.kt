package com.akylas.enforcedoze.service

import android.content.Context
import android.content.SharedPreferences
import com.akylas.enforcedoze.access.Prefs
import com.akylas.enforcedoze.doze.CorruptLedgerLine
import com.akylas.enforcedoze.doze.DozeEvent
import com.akylas.enforcedoze.doze.DozeEventSink
import com.akylas.enforcedoze.doze.EventType
import com.akylas.enforcedoze.doze.LedgerStore
import com.akylas.enforcedoze.doze.RestoreLedger
import com.akylas.enforcedoze.doze.RestoreLedgerCodec

/** This file is excluded from backup. Never store device-owned restoration intent in default prefs. */
class SharedPrefsLedgerStore internal constructor(
    private val prefs: SharedPreferences,
    private val sink: DozeEventSink,
) : LedgerStore {
    constructor(context: Context, sink: DozeEventSink) : this(
        context.getSharedPreferences("doze_ledger", Context.MODE_PRIVATE), sink,
    )
    // SharedPreferences changes its memory map even when commit() fails. Only expose committed intent.
    private var committedLedger: RestoreLedger? = null
    private var announcedCorruptLines: List<CorruptLedgerLine> = emptyList()
    private var corruptionDebt = LedgerDamageDebt(sink)
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
            announcedCorruptLines = RestoreLedgerCodec.decode(prefs.getString(ANNOUNCED_CORRUPT_LINES, "").orEmpty()).corruptLines
            corruptionDebt = LedgerDamageDebt(sink, announcedCorruptLines)
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

    /** NORMAL + mForceIdle=false prove recovery only for sensor/force records. */
    @Synchronized
    fun clearCorruptionAfterRecovery() {
        val ledger = load()
        check(!loadFailed) { "Unreadable ledger remains recovery debt" }
        val retained = corruptLines.filterNot(LedgerRecovery::recoverable)
        val recoveredCount = corruptLines.size - retained.size
        if (recoveredCount == 0) return
        persistCorruption(ledger, retained)
        sink.emit(DozeEvent(EventType.ERROR, "LEDGER_CORRUPT_RECOVERED_LINES=$recoveredCount"))
    }

    /** Explicit user-confirmed dismiss of retained evidence; never discards sensor/force recovery intent. */
    @Synchronized
    fun clearRetainedCorruption() {
        val ledger = load()
        check(!loadFailed) { "Unreadable ledger remains recovery debt" }
        val pendingRecovery = corruptLines.filter(LedgerRecovery::recoverable)
        if (pendingRecovery.size == corruptLines.size) return
        persistCorruption(ledger, pendingRecovery)
    }

    /** Persist the announced snapshot so unchanged retained debt is not re-emitted on restart. */
    @Synchronized
    fun recordCorruptionDebt() {
        if (!loadFailed) load()
        corruptionDebt.update(corruptLines, loadFailed)
        if (!loadFailed) {
            if (announcedCorruptLines.map { it.line }.toSet() != corruptLines.map { it.line }.toSet()) {
                check(prefs.edit()
                    .putString(ANNOUNCED_CORRUPT_LINES, corruptLines.joinToString("\n") { it.line })
                    .commit()) { "Corruption debt commit failed" }
                announcedCorruptLines = corruptLines
            }
        }
    }

    private fun persistCorruption(ledger: RestoreLedger, lines: List<CorruptLedgerLine>) {
        // Cleared evidence must also stop suppressing a future, identical damaged record.
        val remainingRaw = lines.map { it.line }.toSet()
        val stillAnnounced = announcedCorruptLines.filter { it.line in remainingRaw }
        val edit = prefs.edit()
            .putString(Prefs.RESTORE_LEDGER, LedgerRecovery.encodePreservingCorruption(ledger, lines))
            .putString(ANNOUNCED_CORRUPT_LINES, stillAnnounced.joinToString("\n") { it.line })
        if (lines.isEmpty()) edit.remove(CORRUPT_LINES)
        else edit.putString(CORRUPT_LINES, lines.joinToString("\n") { it.line })
        check(edit.commit()) { "Recovered ledger commit failed" }
        // Do not publish the new snapshot if SharedPreferences only changed its memory map.
        corruptLines = lines
        announcedCorruptLines = stillAnnounced
        corruptionDebt = LedgerDamageDebt(sink, stillAnnounced)
        committedLedger = ledger
    }

    companion object {
        private const val CORRUPT_LINES = "restoreLedgerCorruptLines"
        private const val ANNOUNCED_CORRUPT_LINES = "restoreLedgerAnnouncedCorruptLines"
    }
}
