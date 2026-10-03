package com.akylas.enforcedoze.access

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.FutureTask

/** App-sh by default. A Shizuku adapter injects the same Process contract. Always wrap in a lane. */
class ShellCommandRunner @JvmOverloads constructor(
    private val levelProvider: () -> AccessLevel = { AccessLevel.APP },
    private val startProcess: (String) -> Process = { ProcessBuilder("sh", "-c", it).start() },
) : CommandBackend {
    private val lock = Any()
    private var generation = 0L
    private var active: Process? = null
    override val level: AccessLevel get() = levelProvider()

    override fun execute(command: String): CommandResult {
        val started = System.nanoTime()
        val admitted = synchronized(lock) { generation }
        // Do not hold the reset lock over a binder call or ProcessBuilder.start().
        val process = startProcess(command)
        synchronized(lock) {
            if (admitted != generation || Thread.currentThread().isInterrupted) {
                process.destroy()
                throw InterruptedException("Process creation cancelled")
            }
            active = process
        }
        val stdout = drain(process.inputStream, "command-stdout")
        val stderr = drain(process.errorStream, "command-stderr")
        try {
            process.outputStream.close()
            val exit = process.waitFor()
            // No result observes lists still being filled by the other drain.
            return CommandResult.snapshot(exit, stdout.get(), stderr.get(), CommandLane.elapsed(started), false)
        } finally {
            synchronized(lock) {
                if (active === process) active = null
            }
            process.destroy()
            stdout.cancel(true)
            stderr.cancel(true)
        }
    }

    override fun reset() {
        val process = synchronized(lock) {
            generation++
            active.also { active = null }
        }
        process?.destroy()
    }

    private fun drain(stream: InputStream, name: String): FutureTask<List<String>> {
        val task = FutureTask<List<String>> {
            BufferedReader(InputStreamReader(stream)).use { reader ->
                val lines = ArrayList<String>()
                var line = reader.readLine()
                while (line != null) {
                    lines.add(line)
                    line = reader.readLine()
                }
                lines
            }
        }
        Thread(task, name).apply { isDaemon = true }.start()
        return task
    }
}
