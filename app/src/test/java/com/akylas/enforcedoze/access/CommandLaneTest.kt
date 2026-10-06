package com.akylas.enforcedoze.access

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class CommandLaneTest {
    private fun result(command: String) = CommandResult(0, listOf(command), emptyList(), 0, false)

    @Test(timeout = 20_000)
    fun concurrentSubmittersExecuteInAdmissionOrderWithoutOverlap() {
        val executed = Collections.synchronizedList(mutableListOf<String>())
        val expected = mutableListOf<String>()
        val futures = mutableListOf<Future<CommandResult>>()
        val gate = Any()
        val active = AtomicInteger()
        val overlap = AtomicInteger()
        val backend = object : CommandBackend {
            override val level = AccessLevel.SHELL
            override fun execute(command: String): CommandResult {
                if (active.incrementAndGet() != 1) overlap.incrementAndGet()
                try {
                    executed.add(command)
                    return result(command)
                } finally { active.decrementAndGet() }
            }
            override fun reset() = Unit
        }
        val submitters = Executors.newFixedThreadPool(6)
        try {
            CommandLane(backend).use { lane ->
                val start = CountDownLatch(1)
                val submissions = (1..60).map { index ->
                    submitters.submit {
                        start.await()
                        // Establish actual admission order, not an assumption about thread scheduling.
                        synchronized(gate) {
                            expected.add("$index")
                            futures.add(lane.submit("$index"))
                        }
                    }
                }
                start.countDown()
                submissions.forEach { it.get(5, TimeUnit.SECONDS) }
                futures.forEach { assertTrue(it.get(5, TimeUnit.SECONDS).ok) }
                assertEquals(expected, executed)
                assertEquals(0, overlap.get())
                assertEquals(AccessLevel.SHELL, lane.level)
            }
        } finally { submitters.shutdownNow() }
    }

    @Test(timeout = 20_000)
    fun timeoutResetsBackendBeforeNextCommandAndNextCommandRuns() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val backend = object : CommandBackend {
            override val level = AccessLevel.APP
            override fun execute(command: String): CommandResult {
                events.add(command)
                if (command == "wedged") {
                    started.countDown()
                    release.await()
                }
                return result(command)
            }
            override fun reset() { events.add("reset"); release.countDown() }
        }
        CommandLane(backend).use { lane ->
            val wedged = lane.submit("wedged", 1_000)
            assertTrue("Backend actually started", started.await(5, TimeUnit.SECONDS))
            val next = lane.submit("next", 5_000)
            val timeout = wedged.get(5, TimeUnit.SECONDS)
            assertTrue(timeout.timedOut)
            assertFalse(timeout.ok)
            assertEquals(-1, timeout.exitCode)
            assertTrue("Timeout duration includes its execution budget", timeout.durationMs >= 1_000)
            assertEquals(listOf("next"), next.get(5, TimeUnit.SECONDS).stdout)
            assertEquals(listOf("wedged", "reset", "next"), events)
        }
    }

    @Test(timeout = 20_000)
    fun timeoutExcludesQueueWaitAndControlNeverQueuesBehindReads() {
        val readStarted = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val readBackend = object : CommandBackend {
            override val level = AccessLevel.APP
            override fun execute(command: String): CommandResult {
                readStarted.countDown()
                releaseRead.await()
                return result(command)
            }
            override fun reset() { releaseRead.countDown() }
        }
        val controlBackend = object : CommandBackend {
            override val level = AccessLevel.SHELL
            override fun execute(command: String): CommandResult = result(command)
            override fun reset() = Unit
        }
        CommandLane(readBackend, "reads").use { reads ->
            CommandLane(controlBackend, "control").use { control ->
                val first = reads.submit("read", 10_000)
                assertTrue("First read holds its lane", readStarted.await(5, TimeUnit.SECONDS))
                val queued = reads.submit("queued", 1_000)
                assertEquals(listOf("unforce"), control.run("unforce", 5_000).stdout)
                assertFalse("Control completed while the read is still blocked", first.isDone)
                // Keep the read lane blocked beyond the queued command's whole execution budget.
                // A bounded Future wait, rather than a sleep in the unrelated control backend,
                // also asserts that the queued command has not completed or timed out early.
                try {
                    queued.get(2, TimeUnit.SECONDS)
                    fail("Queued read completed before its blocker was released")
                } catch (_: TimeoutException) { }
                releaseRead.countDown()
                assertTrue(first.get(5, TimeUnit.SECONDS).ok)
                assertTrue("Queue wait must not consume the execution budget", queued.get(5, TimeUnit.SECONDS).ok)
            }
        }
    }

    @Test(timeout = 20_000)
    fun concurrentPipeDrainsCompleteAndReturnedListsAreImmutableSnapshots() {
        CommandLane(ShellCommandRunner()).use { lane ->
            val output = lane.run("i=0; while [ \"\$i\" -lt 4000 ]; do echo out; echo err >&2; i=\$((i+1)); done", 10_000)
            assertTrue(output.toString(), output.ok)
            assertEquals(4000, output.stdout.size)
            assertEquals(4000, output.stderr.size)
            assertEquals("out", output.stdout.last())
            assertEquals("err", output.stderr.last())
            try {
                (output.stdout as MutableList<String>).add("changed")
                fail("Mutable result")
            } catch (_: UnsupportedOperationException) { }
        }
        val mutable = mutableListOf("original")
        val fake = object : CommandBackend {
            override val level = AccessLevel.APP
            override fun execute(command: String) = CommandResult(0, mutable, mutable, 0, false)
            override fun reset() = Unit
        }
        CommandLane(fake).use { lane ->
            val snapshot = lane.run("read")
            mutable.add("later")
            assertEquals(listOf("original"), snapshot.stdout)
            assertEquals(listOf("original"), snapshot.stderr)
        }
    }

    @Test(timeout = 20_000)
    fun deadlineIncludesQueueWaitAndExpiredExternalMutationNeverExecutes() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executed = Collections.synchronizedList(mutableListOf<String>())
        val backend = object : CommandBackend {
            override val level = AccessLevel.SHELL
            override fun execute(command: String): CommandResult {
                executed.add(command)
                if (command == "blocker") { started.countDown(); release.await() }
                return result(command)
            }
            override fun reset() { release.countDown() }
        }
        CommandLane(backend).use { lane ->
            val blocker = lane.submit("blocker", 10_000)
            assertTrue("Blocker actually started", started.await(5, TimeUnit.SECONDS))
            val timeout = lane.runWithDeadline("external-mutation", System.nanoTime() + TimeUnit.SECONDS.toNanos(1)) { true }
            assertTrue("Queue wait consumes the external deadline", timeout.timedOut)
            assertFalse(blocker.isDone)
            release.countDown()
            assertTrue(blocker.get(5, TimeUnit.SECONDS).ok)
            assertTrue(lane.run("marker", 5_000).ok)
            assertEquals("Expired request cannot run after PendingResult would be finished", listOf("blocker", "marker"), executed)
        }
    }

    @Test(timeout = 20_000)
    fun deadlineRechecksAdmissionAtBackendAndRejectsExpiredDeadline() {
        val backend = object : CommandBackend {
            override val level = AccessLevel.SHELL
            override fun execute(command: String): CommandResult = error("Denied command reached backend")
            override fun reset() = Unit
        }
        CommandLane(backend).use { lane ->
            val denied = lane.runWithDeadline("mutation", System.nanoTime() + TimeUnit.SECONDS.toNanos(5)) { false }
            assertFalse(denied.ok)
            assertFalse(denied.timedOut)
            assertEquals(listOf("ADMISSION_DENIED"), denied.stderr)
            assertTrue(lane.runWithDeadline("expired", System.nanoTime() - 1) { error("Expired admission checked") }.timedOut)
        }
    }

    @Test(timeout = 20_000)
    fun deadlineAbortsRunningBackendAndLeavesLaneUsable() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val resets = AtomicInteger()
        val backend = object : CommandBackend {
            override val level = AccessLevel.SHELL
            override fun execute(command: String): CommandResult {
                if (command == "wedged") {
                    started.countDown()
                    release.await()
                }
                return result(command)
            }
            override fun reset() { resets.incrementAndGet(); release.countDown() }
        }
        val callers = Executors.newSingleThreadExecutor()
        try {
            CommandLane(backend).use { lane ->
                val wedged = callers.submit<CommandResult> {
                    lane.runWithDeadline("wedged", System.nanoTime() + TimeUnit.SECONDS.toNanos(1)) { true }
                }
                assertTrue("Deadline must abort a running backend, not just an unstarted request", started.await(5, TimeUnit.SECONDS))
                assertTrue(wedged.get(5, TimeUnit.SECONDS).timedOut)
                assertEquals(listOf("next"), lane.run("next", 5_000).stdout)
                assertEquals(1, resets.get())
            }
        } finally {
            release.countDown()
            callers.shutdownNow()
        }
    }

    @Test(timeout = 20_000)
    fun deadlineExecutionTimeoutExcludesSupervisorQueueWait() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val submitted = CountDownLatch(1)
        val resets = AtomicInteger()
        val backend = object : CommandBackend {
            override val level = AccessLevel.SHELL
            override fun execute(command: String): CommandResult {
                if (command == "blocker") { started.countDown(); release.await() }
                return result(command)
            }
            override fun reset() { resets.incrementAndGet(); release.countDown() }
        }
        val callers = Executors.newSingleThreadExecutor()
        try {
            CommandLane(backend).use { lane ->
                val blocker = lane.submit("blocker", 10_000)
                assertTrue(started.await(5, TimeUnit.SECONDS))
                val read = callers.submit<CommandResult> {
                    submitted.countDown()
                    lane.runWithDeadline("short-read", System.nanoTime() + TimeUnit.SECONDS.toNanos(5),
                        TimeUnit.MILLISECONDS.toNanos(100)) { true }
                }
                assertTrue(submitted.await(5, TimeUnit.SECONDS))
                try {
                    read.get(250, TimeUnit.MILLISECONDS)
                    fail("Queued read completed before its holder was released")
                } catch (_: TimeoutException) { }
                assertFalse(blocker.isDone)
                release.countDown()
                assertTrue(blocker.get(5, TimeUnit.SECONDS).ok)
                val result = read.get(5, TimeUnit.SECONDS)
                assertFalse("queue wait must not consume the execution timeout", result.timedOut)
                assertEquals(listOf("short-read"), result.stdout)
                assertEquals("no spurious reset", 0, resets.get())
            }
        } finally { release.countDown(); callers.shutdownNow() }
    }

    @Test(timeout = 20_000)
    fun deadlineExecutionTimeoutExcludesBackendCleanupQueueWait() {
        val started = CountDownLatch(1)
        val cleaning = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val submitted = CountDownLatch(1)
        val backend = object : CommandBackend {
            override val level = AccessLevel.SHELL
            override fun execute(command: String): CommandResult {
                if (command == "blocker") {
                    started.countDown()
                    try { CountDownLatch(1).await() }
                    catch (_: InterruptedException) {
                        cleaning.countDown()
                        releaseCleanup.await()
                    }
                }
                return result(command)
            }
            override fun reset() = Unit
        }
        val callers = Executors.newSingleThreadExecutor()
        try {
            CommandLane(backend).use { lane ->
                val blocker = lane.submit("blocker", 1_000)
                assertTrue(started.await(5, TimeUnit.SECONDS))
                assertTrue(blocker.get(5, TimeUnit.SECONDS).timedOut)
                assertTrue(cleaning.await(5, TimeUnit.SECONDS))
                val read = callers.submit<CommandResult> {
                    submitted.countDown()
                    lane.runWithDeadline("short-read", System.nanoTime() + TimeUnit.SECONDS.toNanos(5),
                        TimeUnit.MILLISECONDS.toNanos(100)) { true }
                }
                assertTrue(submitted.await(5, TimeUnit.SECONDS))
                try {
                    read.get(250, TimeUnit.MILLISECONDS)
                    fail("Read completed while the backend worker was still cleaning up")
                } catch (_: TimeoutException) { }
                releaseCleanup.countDown()
                val result = read.get(5, TimeUnit.SECONDS)
                assertFalse("worker queue wait must not consume the execution timeout", result.timedOut)
                assertEquals(listOf("short-read"), result.stdout)
            }
        } finally { releaseCleanup.countDown(); callers.shutdownNow() }
    }

    @Test(timeout = 20_000)
    fun sharedDeadlineCapsLongerExecutionTimeout() {
        assertDeadlineTimeout(sharedMs = 1_000, executionMs = 5_000)
    }

    @Test(timeout = 20_000)
    fun executionTimeoutCapsLongerSharedDeadline() {
        assertDeadlineTimeout(sharedMs = 5_000, executionMs = 100)
    }

    private fun assertDeadlineTimeout(sharedMs: Long, executionMs: Long) {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val resets = AtomicInteger()
        val backend = object : CommandBackend {
            override val level = AccessLevel.SHELL
            override fun execute(command: String): CommandResult {
                if (command == "wedged") { started.countDown(); release.await() }
                return result(command)
            }
            override fun reset() { resets.incrementAndGet(); release.countDown() }
        }
        val callers = Executors.newSingleThreadExecutor()
        try {
            CommandLane(backend).use { lane ->
                val wedged = callers.submit<CommandResult> {
                    lane.runWithDeadline("wedged", System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(sharedMs),
                        TimeUnit.MILLISECONDS.toNanos(executionMs)) { true }
                }
                assertTrue("backend executes before timeout", started.await(5, TimeUnit.SECONDS))
                val result = wedged.get(2, TimeUnit.SECONDS)
                assertTrue("the shorter budget aborts the running command", result.timedOut)
                assertTrue(result.durationMs >= minOf(sharedMs, executionMs))
                assertEquals(listOf("next"), lane.run("next", 5_000).stdout)
                assertEquals("supervisor resets exactly once before reuse", 1, resets.get())
            }
        } finally { release.countDown(); callers.shutdownNow() }
    }

    @Test(timeout = 20_000)
    fun realProcessIsDestroyedOnTimeoutAndLaneRemainsUsable() {
        val command = "exec sleep 60"
        // Start outside the short execution budget, so process creation cannot race the timeout.
        // The process outlives the whole test unless the runner actually destroys it.
        val process = ProcessBuilder("sh", "-c", command).start()
        val backend = ShellCommandRunner({ AccessLevel.APP }) { requested ->
            if (requested == command) process else ProcessBuilder("sh", "-c", requested).start()
        }
        try {
            CommandLane(backend).use { lane ->
                assertTrue("Timeout starts with a live process", process.isAlive)
                assertTrue(lane.run(command, 1_000).timedOut)
                assertTrue("Timed-out process must be dead", process.waitFor(5, TimeUnit.SECONDS))
                val next = lane.run("echo alive", 5_000)
                assertTrue(next.ok)
                assertEquals(listOf("alive"), next.stdout)
            }
        } finally {
            process.destroyForcibly()
            assertTrue("Owned process ended", process.waitFor(5, TimeUnit.SECONDS))
        }
    }
}
