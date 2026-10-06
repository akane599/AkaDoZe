package com.akylas.enforcedoze.monitor

import android.app.Application
import com.akylas.enforcedoze.TestAppState
import com.akylas.enforcedoze.doze.EventType
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class JournalDbRobolectricTest {
    private lateinit var journal: JournalDb

    @Before
    fun setUp() {
        TestAppState.reset()
        ShadowLog.clear()
        journal = JournalDb(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        val worker = JournalDb::class.java.getDeclaredField("worker").apply { isAccessible = true }
            .get(journal) as ExecutorService
        journal.close()
        assertTrue("journal close must drain its worker", worker.awaitTermination(5, TimeUnit.SECONDS))
        ShadowLog.clear()
        TestAppState.reset()
    }

    @Test
    fun unknownTypeDoesNotHideOtherRows() {
        insertRow(1)
        insertRow(2, type = "FUTURE_EVENT")
        insertRow(3, type = "SCREEN_ON")

        val result = runCatching { journal.querySession(7, 1).get(5, TimeUnit.SECONDS) }
        assertTrue("unknown type must not fail the whole journal query: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals("other rows stay in order", listOf(1L, 3L), result.getOrThrow().map { it.elapsedRealtime })
        assertEquals(listOf(EventType.SCREEN_OFF, EventType.SCREEN_ON), result.getOrThrow().map { it.type })
    }

    @Test
    fun unknownNullableStatesDecodeToNullWithoutLosingRowData() {
        insertRow(1, deep = "FUTURE_DEEP", light = "FUTURE_LIGHT", sensor = "FUTURE_SENSOR")

        val row = journal.querySession(7).get(5, TimeUnit.SECONDS).single()
        assertNull(row.deep)
        assertNull(row.light)
        assertNull(row.sensor)
        assertNull(row.historyKind)
        assertEquals(EventType.SCREEN_OFF, row.type)
        assertEquals(Source.APP, row.source)
        assertEquals(42, row.battery)
        assertEquals(true, row.charging)
        assertEquals("retained detail", row.detail)
        assertTrue(row.historyTruncated)
        assertNotNull(row.id)
        assertTrue(ShadowLog.getLogsForTag("DozeJournal").isEmpty())
    }

    @Test
    fun unknownKindDecodesToNullWhenTheOtherKindIsValid() {
        insertRow(1, type = "FUTURE_EVENT", source = "OS_HISTORY", historyKind = "DEEP_IDLE")
        insertRow(2, historyKind = "FUTURE_HISTORY")

        val rows = journal.querySession(7).get(5, TimeUnit.SECONDS)
        assertEquals(listOf(1L, 2L), rows.map { it.elapsedRealtime })
        assertNull(rows[0].type)
        assertEquals(com.akylas.enforcedoze.doze.parse.HistoryKind.DEEP_IDLE, rows[0].historyKind)
        assertEquals(EventType.SCREEN_OFF, rows[1].type)
        assertNull(rows[1].historyKind)
    }

    @Test
    fun unknownSourceAndInvalidKindsAreSkippedWithOneWarningPerQuery() {
        insertRow(1)
        insertRow(2, source = "FUTURE_SOURCE")
        insertRow(3, type = null, historyKind = "FUTURE_HISTORY")
        insertRow(4, historyKind = "DEEP_IDLE")
        insertRow(5, type = null)
        insertRow(6, type = "SCREEN_ON")

        val rows = journal.queryRecent().get(5, TimeUnit.SECONDS)
        assertEquals("valid recent rows survive in order", listOf(1L, 6L), rows.map { it.elapsedRealtime })
        val warnings = ShadowLog.getLogsForTag("DozeJournal")
        assertEquals("skips must be aggregated once per query", 1, warnings.size)
        assertEquals("Skipped 4 journal rows with unknown source or invalid event kind", warnings.single().msg)
        assertEquals(android.util.Log.WARN, warnings.single().type)
    }

    @Test
    fun knownNamesAndSqlNullsStillReadWithoutWarnings() {
        insertRow(1)
        insertRow(2, type = null, source = "OS_HISTORY", historyKind = "DEEP_IDLE")

        val rows = journal.queryRecent().get(5, TimeUnit.SECONDS)
        assertEquals(2, rows.size)
        assertNull(rows[0].deep)
        assertNull(rows[0].light)
        assertNull(rows[0].sensor)
        assertEquals(Source.OS_HISTORY, rows[1].source)
        assertNull(rows[1].type)
        assertEquals(com.akylas.enforcedoze.doze.parse.HistoryKind.DEEP_IDLE, rows[1].historyKind)
        assertTrue("valid queries must not warn", ShadowLog.getLogsForTag("DozeJournal").isEmpty())
    }

    private fun insertRow(
        elapsed: Long,
        type: String? = "SCREEN_OFF",
        source: String = "APP",
        historyKind: String? = null,
        deep: String? = null,
        light: String? = null,
        sensor: String? = null,
    ) {
        journal.writableDatabase.execSQL(
            """INSERT INTO events (bootId, elapsedRealtime, wallTime, sessionId, source, type,
                historyKind, deep, light, sensor, battery, charging, detail, historyTruncated)
                VALUES (1, ?, ?, 7, ?, ?, ?, ?, ?, ?, 42, 1, 'retained detail', 1)""".trimIndent(),
            arrayOf(elapsed, elapsed, source, type, historyKind, deep, light, sensor),
        )
    }
}
