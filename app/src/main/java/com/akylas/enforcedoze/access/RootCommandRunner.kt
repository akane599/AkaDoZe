package com.akylas.enforcedoze.access

import eu.chainfire.libsuperuser.Shell
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Persistent libsuperuser session, private to one lane; never used on the main thread. */
class RootCommandRunner : CommandBackend {
    private val lock = Any()
    private var generation = 0L
    private var session: Shell.Interactive? = null
    override val level: AccessLevel = AccessLevel.ROOT

    override fun execute(command: String): CommandResult {
        val started = System.nanoTime()
        val shell = openedSession()
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Root command cancelled")
        val completed = CountDownLatch(1)
        val result = AtomicReference<CommandResult>()
        // libsuperuser owns concurrent stdout/stderr gobblers and calls this after both markers.
        shell.addCommand(command, 0, Shell.OnCommandResultListener2 { _, exit, stdout, stderr ->
            result.set(CommandResult.snapshot(exit, stdout, stderr, CommandLane.elapsed(started), false))
            completed.countDown()
        })
        completed.await()
        return result.get()
    }

    private fun openedSession(): Shell.Interactive {
        val admitted: Long
        synchronized(lock) {
            session?.takeIf { it.isRunning }?.let { return it }
            admitted = generation
        }
        val opened = CountDownLatch(1)
        val success = AtomicBoolean(false)
        val candidate = Shell.Builder()
            .useSU()
            .setAutoHandler(false)
            .setWantSTDERR(true)
            .setWatchdogTimeout(0) // The lane owns the millisecond timeout, including shell opening.
            .setMinimalLogging(true)
            .open { available, _ ->
                success.set(available)
                opened.countDown()
            }
        synchronized(lock) {
            if (admitted != generation || Thread.currentThread().isInterrupted) {
                candidate.kill()
                throw InterruptedException("Root session creation cancelled")
            }
            session = candidate
        }
        opened.await()
        check(success.get() && candidate.isRunning) { "Root shell unavailable" }
        return candidate
    }

    override fun reset() {
        val shell = synchronized(lock) {
            generation++
            session.also { session = null }
        }
        shell?.kill()
    }
}
