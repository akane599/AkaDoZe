package com.akylas.enforcedoze.access

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class CommandLaneCleanupTest {
    @Test(timeout = 20_000)
    fun throwingResetAfterTimeoutStillInterruptsBackendAndRunsNextQueuedCommand() = checkTimeout(false)

    @Test(timeout = 20_000)
    fun throwingResetAfterDeadlineStillInterruptsBackendAndRunsNextQueuedCommand() = checkTimeout(true)

    private fun checkTimeout(deadline: Boolean) {
        val started = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val resets = AtomicInteger()
        val backend = object : CommandBackend {
            override val level = AccessLevel.SHELL
            override fun execute(command: String): CommandResult {
                if (command == "wedged") {
                    started.countDown()
                    try { release.await() } catch (error: InterruptedException) {
                        interrupted.countDown()
                        throw error
                    }
                }
                return CommandResult(0, listOf(command), emptyList(), 0, false)
            }
            override fun reset() { resets.incrementAndGet(); throw IllegalStateException("dead binder") }
        }
        val callers = Executors.newSingleThreadExecutor()
        val lane = CommandLane(backend)
        try {
            val wedged = callers.submit<CommandResult> {
                if (deadline) lane.runWithDeadline("wedged", System.nanoTime() + TimeUnit.SECONDS.toNanos(1)) { true }
                else lane.run("wedged", 1_000)
            }
            assertTrue("Backend actually started", started.await(5, TimeUnit.SECONDS))
            val next = lane.submit("next", 5_000)
            val timeout = wedged.get(8, TimeUnit.SECONDS)
            val nextAttempt = runCatching { next.get(8, TimeUnit.SECONDS) }
            assertTrue("Next queued command must complete despite reset failure: $nextAttempt", nextAttempt.isSuccess)
            val nextResult = nextAttempt.getOrThrow()
            assertTrue("Reset failure must not prevent next queued command: $nextResult", nextResult.ok)
            assertEquals(listOf("next"), nextResult.stdout)
            assertTrue("Original failure remains a timeout: $timeout", timeout.timedOut)
            assertEquals(0L, interrupted.count)
            assertEquals(1, resets.get())
        } finally {
            release.countDown()
            callers.shutdownNow()
            // Baseline close also throws; do not let cleanup mask the regression assertion.
            try { lane.close() } catch (_: IllegalStateException) { }
        }
    }

    @Test fun throwingResetCannotEscapeClose() {
        val backend = object : CommandBackend {
            override val level = AccessLevel.SHELL
            override fun execute(command: String) = CommandResult(0, emptyList(), emptyList(), 0, false)
            override fun reset() { throw IllegalStateException("dead binder") }
        }
        assertTrue("Close must finish executor cleanup despite reset failure", runCatching { CommandLane(backend).close() }.isSuccess)
    }
}
