package com.akylas.enforcedoze.access

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class RootCommandRunnerTest {
    @Test(timeout = 5_000)
    fun interruptedCommandKillsBeforeCallerUnwindsAndNextCommandReopens() {
        val entered = CountDownLatch(1)
        val unwound = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val sessions = Collections.synchronizedList(mutableListOf<FakeSession>())
        val runner = RootCommandRunner { available ->
            FakeSession(entered).also { sessions.add(it); available(true) }
        }
        val caller = Thread {
            try {
                runner.execute("wedged")
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                // AccessManager can clear active immediately after this return.
                unwound.countDown()
            }
        }.apply { isDaemon = true }
        try {
            caller.start()
            assertTrue("command reached the persistent session", entered.await(1, TimeUnit.SECONDS))
            caller.interrupt()
            assertTrue("execute unwound", unwound.await(1, TimeUnit.SECONDS))
            caller.join(1_000)
            assertFalse("owned caller ended", caller.isAlive)
            assertTrue(failure.get() is InterruptedException)
            // No supervisor reset: the lost-active schedule must already be safe.
            assertTrue("root session killed before active can be cleared", sessions.single().killed.get())
            assertEquals(listOf("next"), runner.execute("next").stdout)
            assertEquals("next command opens a fresh session", 2, sessions.size)
            assertEquals(listOf("again"), runner.execute("again").stdout)
            assertEquals("successful commands retain their session", 2, sessions.size)
        } finally {
            caller.interrupt()
            runner.reset()
            caller.join(1_000)
        }
    }

    @Test(timeout = 5_000)
    fun interruptedOpeningKillsBeforeCallerUnwindsAndNextCommandReopens() {
        val opening = CountDownLatch(1)
        val unwound = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val sessions = Collections.synchronizedList(mutableListOf<FakeSession>())
        val runner = RootCommandRunner { available ->
            FakeSession(CountDownLatch(1)).also {
                sessions.add(it)
                if (sessions.size == 1) opening.countDown() else available(true)
            }
        }
        val caller = Thread {
            try {
                runner.execute("opening")
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                unwound.countDown()
            }
        }.apply { isDaemon = true }
        try {
            caller.start()
            assertTrue("session creation started", opening.await(1, TimeUnit.SECONDS))
            // The factory returns without its availability callback, so this wait is opened.await().
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (caller.state != Thread.State.WAITING && System.nanoTime() < deadline) Thread.yield()
            assertEquals("caller reached the opening wait", Thread.State.WAITING, caller.state)
            caller.interrupt()
            assertTrue("execute unwound", unwound.await(1, TimeUnit.SECONDS))
            caller.join(1_000)
            assertFalse("owned caller ended", caller.isAlive)
            assertTrue(failure.get() is InterruptedException)
            assertTrue("opening session killed before caller unwinds", sessions.single().killed.get())
            assertEquals(listOf("next"), runner.execute("next").stdout)
            assertEquals("next command opens a fresh session", 2, sessions.size)
            assertEquals(listOf("again"), runner.execute("again").stdout)
            assertEquals("successful commands retain their session", 2, sessions.size)
        } finally {
            caller.interrupt()
            runner.reset()
            caller.join(1_000)
        }
    }

    private class FakeSession(private val entered: CountDownLatch) : RootSession {
        val killed = AtomicBoolean(false)
        override val isRunning: Boolean get() = !killed.get()
        override fun addCommand(command: String, onResult: (Int, List<String>, List<String>) -> Unit) {
            check(isRunning)
            if (command == "wedged") entered.countDown()
            else onResult(0, listOf(command), emptyList())
        }
        override fun kill() { killed.set(true) }
    }
}
