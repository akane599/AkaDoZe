package com.akylas.enforcedoze.access

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class CommandLaneTest {
    private fun result(command: String) = CommandResult(0, listOf(command), emptyList(), 0, false)

    @Test(timeout = 5_000)
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
                submissions.forEach { it.get(2, TimeUnit.SECONDS) }
                futures.forEach { assertTrue(it.get(2, TimeUnit.SECONDS).ok) }
                assertEquals(expected, executed)
                assertEquals(0, overlap.get())
                assertEquals(AccessLevel.SHELL, lane.level)
            }
        } finally { submitters.shutdownNow() }
    }

    @Test(timeout = 5_000)
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
            val wedged = lane.submit("wedged", 150)
            assertTrue(started.await(1, TimeUnit.SECONDS))
            val next = lane.submit("next", 1_000)
            val timeout = wedged.get(2, TimeUnit.SECONDS)
            assertTrue(timeout.timedOut)
            assertFalse(timeout.ok)
            assertEquals(-1, timeout.exitCode)
            assertTrue(timeout.durationMs >= 100)
            assertEquals(listOf("next"), next.get(2, TimeUnit.SECONDS).stdout)
            assertEquals(listOf("wedged", "reset", "next"), events)
        }
    }

    @Test(timeout = 5_000)
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
            override fun execute(command: String): CommandResult {
                Thread.sleep(100) // Longer than the queued read's 50 ms execution timeout.
                return result(command)
            }
            override fun reset() = Unit
        }
        CommandLane(readBackend, "reads").use { reads ->
            CommandLane(controlBackend, "control").use { control ->
                val first = reads.submit("read", 1_000)
                assertTrue(readStarted.await(1, TimeUnit.SECONDS))
                val queued = reads.submit("queued", 50)
                assertEquals(listOf("unforce"), control.run("unforce", 500).stdout)
                assertFalse(first.isDone)
                releaseRead.countDown()
                assertTrue(first.get(2, TimeUnit.SECONDS).ok)
                assertTrue(queued.get(2, TimeUnit.SECONDS).ok)
            }
        }
    }

    @Test(timeout = 5_000)
    fun concurrentPipeDrainsCompleteAndReturnedListsAreImmutableSnapshots() {
        CommandLane(ShellCommandRunner()).use { lane ->
            val output = lane.run("i=0; while [ \"\$i\" -lt 4000 ]; do echo out; echo err >&2; i=\$((i+1)); done", 3_000)
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

    @Test(timeout = 5_000)
    fun realProcessIsDestroyedOnTimeoutAndLaneRemainsUsable() {
        val process = AtomicReference<Process>()
        val backend = ShellCommandRunner({ AccessLevel.APP }) { command ->
            ProcessBuilder("sh", "-c", command).start().also { process.set(it) }
        }
        CommandLane(backend).use { lane ->
            assertTrue(lane.run("exec sleep 5", 100).timedOut)
            assertTrue("Timed-out process must be dead", process.get().waitFor(1, TimeUnit.SECONDS))
            val next = lane.run("echo alive", 1_000)
            assertTrue(next.ok)
            assertEquals(listOf("alive"), next.stdout)
        }
    }
}
