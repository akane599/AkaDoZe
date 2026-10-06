package com.akylas.enforcedoze.access

import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.logging.Level
import java.util.logging.Logger

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
        return runWithDeadline(command, deadlineNanos, Long.MAX_VALUE, admission)
    }

    /** The execution timeout starts on the backend worker, still capped by the shared deadline. */
    fun runWithDeadline(
        command: String,
        deadlineNanos: Long,
        executionTimeoutNanos: Long,
        admission: Admission,
    ): CommandResult {
        require(executionTimeoutNanos > 0) { "Timeout must be positive" }
        val started = System.nanoTime()
        val remaining = deadlineNanos - started
        if (remaining <= 0) return expired(started)
        val task = queue.submit<CommandResult> {
            executeBeforeDeadline(command, deadlineNanos, executionTimeoutNanos, admission, started)
        }
        return awaitQueuedDeadline(task, remaining, started)
    }

    private fun awaitQueuedDeadline(task: Future<CommandResult>, remaining: Long, started: Long): CommandResult {
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
            return deadlineExecutionFailure(e, started)
        }
    }

    private fun executeBeforeDeadline(
        command: String,
        deadlineNanos: Long,
        executionTimeoutNanos: Long,
        admission: Admission,
        started: Long,
    ): CommandResult {
        if (deadlineNanos - System.nanoTime() <= 0) return expired(started)
        // Signal from the worker, not the supervisor: preceding pipe cleanup also excludes queue time.
        val executionDeadline = FutureTask<Long> {
            val now = System.nanoTime()
            now + minOf(executionTimeoutNanos, maxOf(0L, deadlineNanos - now))
        }
        val execution = worker.submit<CommandResult> {
            executionDeadline.run()
            executeAdmitted(command, deadlineNanos, admission, started)
        }
        return awaitDeadlineExecution(execution, executionDeadline, deadlineNanos, started)
    }

    private fun executeAdmitted(command: String, deadlineNanos: Long, admission: Admission, started: Long): CommandResult {
        if (System.nanoTime() >= deadlineNanos) return expired(started)
        if (!admission.allowed()) return CommandResult(-1, emptyList(), listOf("ADMISSION_DENIED"), elapsed(started), false)
        if (System.nanoTime() >= deadlineNanos) return expired(started)
        return backend.execute(command)
    }

    private fun awaitDeadlineExecution(
        execution: Future<CommandResult>,
        executionDeadline: Future<Long>,
        deadlineNanos: Long,
        started: Long,
    ): CommandResult {
        return try {
            val deadline = executionDeadline.get(maxOf(0L, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS)
            execution.get(maxOf(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
        } catch (_: TimeoutException) {
            execution.cancel(true)
            resetBackend()
            expired(started)
        } catch (e: InterruptedException) {
            execution.cancel(true)
            resetBackend()
            Thread.currentThread().interrupt()
            failure(e, elapsed(started))
        } catch (e: ExecutionException) {
            resetBackend()
            deadlineExecutionFailure(e, started)
        }
    }

    private fun deadlineExecutionFailure(error: ExecutionException, started: Long): CommandResult {
        return failure(error.cause ?: error, elapsed(started))
    }

    private fun expired(started: Long) = CommandResult.snapshot(-1, emptyList(), emptyList(), elapsed(started), true)

    private fun execute(command: String, timeoutMs: Long): CommandResult {
        val started = System.nanoTime()
        val task = worker.submit<CommandResult> { backend.execute(command) }
        return try {
            val result = task.get(timeoutMs, TimeUnit.MILLISECONDS)
            CommandResult.snapshot(result.exitCode, result.stdout, result.stderr, elapsed(started), result.timedOut)
        } catch (_: TimeoutException) {
            task.cancel(true)
            resetBackend()
            CommandResult.snapshot(-1, emptyList(), emptyList(), elapsed(started), true)
        } catch (e: InterruptedException) {
            task.cancel(true)
            resetBackend()
            Thread.currentThread().interrupt()
            failure(e, elapsed(started))
        } catch (e: ExecutionException) {
            resetBackend()
            failure(e.cause ?: e, elapsed(started))
        }
    }

    override fun close() {
        queue.shutdownNow()
        worker.shutdownNow()
        resetBackend()
    }

    private fun resetBackend() {
        try {
            backend.reset()
        } catch (error: Exception) {
            Logger.getLogger(CommandLane::class.java.name).log(Level.WARNING, "Command backend reset failed", error)
        }
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
