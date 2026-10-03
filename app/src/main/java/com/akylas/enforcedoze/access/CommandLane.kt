package com.akylas.enforcedoze.access

import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** External requests cannot infer whether a timed-out mutation landed. Readback owns VERIFIED. */
enum class ExternalCallOutcome {
    REQUESTED, VERIFIED, FAILED, DENIED, UNVERIFIED;

    companion object {
        @JvmStatic
        fun fromCommand(result: CommandResult): ExternalCallOutcome = when {
            result.timedOut -> UNVERIFIED
            !result.ok -> FAILED
            else -> REQUESTED
        }

        @JvmStatic
        fun fromReadback(matches: Boolean?): ExternalCallOutcome = when (matches) {
            null -> UNVERIFIED
            true -> VERIFIED
            false -> FAILED
        }
    }
}

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

    fun interface Admission { fun allowed(): Boolean }

    /** Absolute System.nanoTime deadline INCLUDING both queues; expired work never reaches the backend. */
    fun runWithDeadline(command: String, deadlineNanos: Long, admission: Admission): CommandResult {
        val started = System.nanoTime()
        val remaining = deadlineNanos - started
        if (remaining <= 0) return expired(started)
        val task = queue.submit<CommandResult> {
            val budget = deadlineNanos - System.nanoTime()
            if (budget <= 0) return@submit expired(started)
            val execution = worker.submit<CommandResult> {
                // The backend queue may still be cleaning up a preceding timed-out process.
                if (System.nanoTime() >= deadlineNanos) expired(started)
                else if (!admission.allowed()) CommandResult(-1, emptyList(), listOf("ADMISSION_DENIED"), elapsed(started), false)
                else if (System.nanoTime() >= deadlineNanos) expired(started)
                else backend.execute(command)
            }
            try {
                execution.get(budget, TimeUnit.NANOSECONDS)
            } catch (_: TimeoutException) {
                backend.reset()
                execution.cancel(true)
                expired(started)
            } catch (e: InterruptedException) {
                backend.reset()
                execution.cancel(true)
                Thread.currentThread().interrupt()
                failure(e, elapsed(started))
            } catch (e: ExecutionException) {
                backend.reset()
                failure(e.cause ?: e, elapsed(started))
            }
        }
        return try {
            val result = task.get(remaining, TimeUnit.NANOSECONDS)
            CommandResult.snapshot(result.exitCode, result.stdout, result.stderr, elapsed(started), result.timedOut)
        } catch (_: TimeoutException) {
            // Do not interrupt the supervisor: it owns reset of an already-running command.
            task.cancel(false)
            expired(started)
        } catch (e: InterruptedException) {
            task.cancel(false)
            Thread.currentThread().interrupt()
            failure(e, elapsed(started))
        } catch (e: ExecutionException) {
            failure(e.cause ?: e, elapsed(started))
        }
    }

    private fun expired(started: Long) = CommandResult.snapshot(-1, emptyList(), emptyList(), elapsed(started), true)

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
