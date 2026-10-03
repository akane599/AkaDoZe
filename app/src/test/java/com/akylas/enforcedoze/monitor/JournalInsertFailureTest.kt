package com.akylas.enforcedoze.monitor

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class JournalInsertFailureTest {
    @Test(timeout = 15_000)
    fun failedInsertLogsOnJournalWorkerWithoutBlockingCallerAndRetainsFailedFuture() {
        val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "test-journal") }
        val callers = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val observed = AtomicReference<Exception>()
        val logThread = AtomicReference<String>()
        val failure = IllegalStateException("SQLite write failed")
        try {
            val submission = callers.submit<java.util.concurrent.Future<Long>> {
                submitJournalInsert(worker, { error ->
                    observed.set(error)
                    logThread.set(Thread.currentThread().name)
                }) {
                    started.countDown()
                    release.await()
                    throw failure
                }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val inserted = submission.get(5, TimeUnit.SECONDS)
            assertFalse("Enqueue must return while the database write is blocked", inserted.isDone)
            assertNull(observed.get())
            release.countDown()
            try {
                inserted.get(5, TimeUnit.SECONDS)
                fail("Insert Future must retain the write exception")
            } catch (error: ExecutionException) {
                assertSame(failure, error.cause)
            }
            assertSame("Dropped Future failures must still reach the log callback", failure, observed.get())
            assertEquals("test-journal", logThread.get())
            assertEquals(7L, submitJournalInsert(worker, null) { 7L }.get(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            callers.shutdownNow()
            worker.shutdownNow()
        }
    }
}
