package com.akylas.enforcedoze.monitor

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.provider.Settings
import android.util.Log
import com.akylas.enforcedoze.doze.DeepState
import com.akylas.enforcedoze.doze.EventType
import com.akylas.enforcedoze.doze.LightState
import com.akylas.enforcedoze.doze.SensorMode
import com.akylas.enforcedoze.doze.parse.HistoryKind
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Shared submission seam; the write and its diagnostics run on the journal executor. */
internal fun submitJournalInsert(
    worker: ExecutorService,
    onFailure: ((Exception) -> Unit)?,
    insert: () -> Long,
): Future<Long> = worker.submit<Long> {
    try {
        insert()
    } catch (error: Exception) {
        onFailure?.invoke(error)
        throw error // Keep the existing failed-Future contract for callers that observe it.
    }
}

/** Android adapter: all public journal operations are queued on one background worker. */
class JournalDb(context: Context) : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, 1) {
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "doze-journal") }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                bootId INTEGER NOT NULL, elapsedRealtime INTEGER NOT NULL, wallTime INTEGER NOT NULL,
                sessionId INTEGER NOT NULL, source TEXT NOT NULL, type TEXT, historyKind TEXT,
                deep TEXT, light TEXT, sensor TEXT, battery INTEGER, charging INTEGER, detail TEXT,
                historyTruncated INTEGER NOT NULL DEFAULT 0
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX events_session ON events(sessionId)")
        db.execSQL("CREATE INDEX events_wall ON events(wallTime)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 has no predecessor. Future migrations must preserve the journal.
        error("Unsupported journal migration: $oldVersion -> $newVersion")
    }

    fun insert(event: JournalEvent, onFailure: ((Exception) -> Unit)? = null): Future<Long> =
        submitJournalInsert(worker, onFailure) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                val id = save(db, event)
                prune(db)
                db.setTransactionSuccessful()
                id
            } finally {
                db.endTransaction()
            }
        }

    /** Existing ids update their row, e.g. the merger's durable HISTORY_TRUNCATED session flag. */
    fun insertAll(events: List<JournalEvent>): Future<List<Long>> {
        val snapshot = events.toList()
        return worker.submit<List<Long>> {
            val db = writableDatabase
            db.beginTransaction()
            try {
                val ids = snapshot.map { save(db, it) }
                prune(db)
                db.setTransactionSuccessful()
                ids
            } finally {
                db.endTransaction()
            }
        }
    }

    @JvmOverloads
    fun querySession(sessionId: Long, bootId: Int? = null): Future<List<JournalEvent>> =
        worker.submit<List<JournalEvent>> {
            val selection = if (bootId == null) "sessionId=?" else "sessionId=? AND bootId=?"
            val args = if (bootId == null) arrayOf(sessionId.toString()) else
                arrayOf(sessionId.toString(), bootId.toString())
            readableDatabase.query("events", null, selection, args, null, null, "bootId, elapsedRealtime, id")
                .use { read(it) }
        }

    @JvmOverloads
    fun queryRecent(limit: Int = MAX_ROWS): Future<List<JournalEvent>> {
        require(limit in 1..MAX_ROWS)
        return worker.submit<List<JournalEvent>> {
            readableDatabase.query("events", null, null, null, null, null, "wallTime DESC, id DESC", limit.toString())
                .use { read(it) }.sortedWith(compareBy<JournalEvent> { it.bootId }.thenBy { it.elapsedRealtime })
        }
    }

    /** Pending operations drain before the helper closes; callers must not enqueue after close. */
    override fun close() {
        worker.execute { super.close() }
        worker.shutdown()
    }

    private fun save(db: SQLiteDatabase, event: JournalEvent): Long {
        val values = ContentValues().apply {
            put("bootId", event.bootId)
            put("elapsedRealtime", event.elapsedRealtime)
            put("wallTime", event.wallTime)
            put("sessionId", event.sessionId)
            put("source", event.source.name)
            put("type", event.type?.name)
            put("historyKind", event.historyKind?.name)
            put("deep", event.deep?.name)
            put("light", event.light?.name)
            put("sensor", event.sensor?.name)
            put("battery", event.battery)
            put("charging", event.charging?.let { if (it) 1 else 0 })
            put("detail", event.detail)
            put("historyTruncated", if (event.historyTruncated) 1 else 0)
        }
        if (event.id != null) {
            check(db.update("events", values, "id=?", arrayOf(event.id.toString())) == 1) {
                "Journal event no longer exists: ${event.id}"
            }
            return event.id
        }
        return db.insertOrThrow("events", null, values)
    }

    private fun prune(db: SQLiteDatabase) {
        db.delete("events", "wallTime < ?", arrayOf((System.currentTimeMillis() - RETENTION_MS).toString()))
        db.execSQL("DELETE FROM events WHERE id IN (SELECT id FROM events ORDER BY wallTime DESC, id DESC LIMIT -1 OFFSET $MAX_ROWS)")
    }

    private fun read(cursor: Cursor): List<JournalEvent> {
        val rows = mutableListOf<JournalEvent>()
        var skipped = 0
        while (cursor.moveToNext()) {
            val row = readRow(cursor)
            if (row == null) skipped++ else rows.add(row)
        }
        if (skipped > 0) {
            Log.w("DozeJournal", "Skipped $skipped journal rows with unknown source or invalid event kind")
        }
        return rows
    }

    private fun readRow(cursor: Cursor): JournalEvent? {
        val source = readEnum(cursor,"source", Source.entries) ?: return null
        val type = readEnum(cursor,"type", EventType.entries)
        val historyKind = readEnum(cursor,"historyKind", HistoryKind.entries)
        // Preserve JournalEvent's exactly-one-kind invariant after tolerant wire decoding.
        if ((type != null) == (historyKind != null)) return null
        return JournalEvent(
            bootId = cursor.getInt(cursor.getColumnIndexOrThrow("bootId")),
            elapsedRealtime = cursor.getLong(cursor.getColumnIndexOrThrow("elapsedRealtime")),
            wallTime = cursor.getLong(cursor.getColumnIndexOrThrow("wallTime")),
            sessionId = cursor.getLong(cursor.getColumnIndexOrThrow("sessionId")),
            source = source,
            type = type,
            deep = readEnum(cursor,"deep", DeepState.entries),
            light = readEnum(cursor,"light", LightState.entries),
            sensor = readEnum(cursor,"sensor", SensorMode.entries),
            battery = cursor.text("battery")?.toInt(),
            charging = cursor.text("charging")?.let { it == "1" },
            detail = cursor.text("detail"),
            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
            historyKind = historyKind,
            historyTruncated = cursor.getInt(cursor.getColumnIndexOrThrow("historyTruncated")) == 1,
        )
    }

    private fun <T : Enum<T>> readEnum(cursor: Cursor, column: String, values: List<T>): T? {
        val name = cursor.text(column)
        return values.firstOrNull { it.name == name }
    }

    private fun Cursor.text(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    companion object {
        const val DB_NAME = "doze_journal.db"
        const val MAX_ROWS = 20_000
        const val RETENTION_MS = 14L * 24 * 60 * 60 * 1_000

        /** -1 means BOOT_COUNT unavailable; it is not evidence of a known boot. */
        @JvmStatic
        fun currentBootId(context: Context): Int =
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    }
}
