package com.akylas.enforcedoze.access

import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** A lane owns its backend. reset must abort the active process/session and is called before reuse. */
interface CommandBackend {
    val level: AccessLevel
    fun execute(command: String): CommandResult
    fun reset()
}

/**
 * FIFO admission, one command in flight, and an execution timeout (queue time is excluded).
 * The supervisor can abort a blocked backend; the worker remains serial even during pipe cleanup.
 * Each control/read lane must have its own backend and executors.
 */
class CommandLane(
    private val backend: CommandBackend,
    name: String = "command",
) : CommandRunner, AutoCloseable {
    private val queue = singleThread("$name-lane")
    private val worker = singleThread("$name-backend")
    override val level: AccessLevel get() = backend.level

    fun submit(command: String, timeoutMs: Long = CommandRunner.DEFAULT_TIMEOUT_MS): Future<CommandResult> {
        require(timeoutMs > 0) { "Timeout must be positive" }
        return queue.submit<CommandResult> { execute(command, timeoutMs) }
    }

    override fun run(command: String, timeoutMs: Long): CommandResult {
        val task = submit(command, timeoutMs)
        // Once admitted, interruption of a caller must not abandon a possibly applied mutation.
        var interrupted = false
        try {
            while (true) {
                try {
                    return task.get()
                } catch (_: InterruptedException) {
                    interrupted = true
                } catch (e: ExecutionException) {
                    return failure(e.cause ?: e, 0)
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun execute(command: String, timeoutMs: Long): CommandResult {
        val started = System.nanoTime()
        val task = worker.submit<CommandResult> { backend.execute(command) }
        return try {
            val result = task.get(timeoutMs, TimeUnit.MILLISECONDS)
            CommandResult.snapshot(result.exitCode, result.stdout, result.stderr, elapsed(started), result.timedOut)
        } catch (_: TimeoutException) {
            backend.reset()
            task.cancel(true)
            CommandResult.snapshot(-1, emptyList(), emptyList(), elapsed(started), true)
        } catch (e: InterruptedException) {
            backend.reset()
            task.cancel(true)
            Thread.currentThread().interrupt()
            failure(e, elapsed(started))
        } catch (e: ExecutionException) {
            backend.reset()
            failure(e.cause ?: e, elapsed(started))
        }
    }

    override fun close() {
        queue.shutdownNow()
        backend.reset()
        worker.shutdownNow()
    }

    companion object {
        internal fun elapsed(started: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        internal fun failure(error: Throwable, duration: Long): CommandResult =
            CommandResult.snapshot(-1, emptyList(), listOf(error.toString()), duration, false)

        private fun singleThread(name: String): ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, name).apply { isDaemon = true }
        }
    }
}
