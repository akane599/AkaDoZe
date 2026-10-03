package com.akylas.enforcedoze.service

import com.akylas.enforcedoze.access.CapabilityResolver
import com.akylas.enforcedoze.access.CommandResult
import com.akylas.enforcedoze.access.Feature
import com.akylas.enforcedoze.access.FeatureStatus
import com.akylas.enforcedoze.access.Reason
import com.akylas.enforcedoze.doze.DeepState
import com.akylas.enforcedoze.doze.DozeConfig
import com.akylas.enforcedoze.doze.DozeController
import com.akylas.enforcedoze.doze.DozeEventSink
import com.akylas.enforcedoze.doze.EventType
import com.akylas.enforcedoze.doze.ExitError
import com.akylas.enforcedoze.doze.SensorMode
import com.akylas.enforcedoze.doze.StepResult
import com.akylas.enforcedoze.doze.StepStatus

enum class SelfTestKind { DOZE, SENSORS }

enum class SelfTestOutcome {
    /** Applied, verified by readback, and the ledger restore was verified. */
    PASSED,

    /** Applied, but the readback did not show the expected state. */
    NOT_VERIFIED,

    /** Something was left unrestored: it stays in the ledger as recovery debt. */
    RESTORE_INCOMPLETE,

    /** Not available at the current access level/grants (see reason). */
    UNAVAILABLE,

    /** A screen-on/off transition cancelled the test before the step concluded. */
    CANCELLED,

    /** A screen-off session is active or an enter is pending; nothing was run. */
    BUSY,

    /** The controller failed unexpectedly (e.g. the ledger could not be written). */
    FAILED,
}

data class SelfTestCommand(
    val command: String,
    val exitCode: Int,
    val stdout: List<String>,
    val stderr: List<String>,
    val timedOut: Boolean,
) {
    companion object {
        const val MAX_LINES = 200
        const val MAX_LINE_CHARS = 1_000

        @JvmStatic
        fun of(command: String, result: CommandResult): SelfTestCommand {
            fun bounded(lines: List<String>): List<String> {
                val kept = lines.take(MAX_LINES).map {
                    if (it.length > MAX_LINE_CHARS) it.take(MAX_LINE_CHARS) + "…" else it
                }
                return if (lines.size > MAX_LINES) kept + "[truncated ${lines.size - MAX_LINES} lines]" else kept
            }
            return SelfTestCommand(command, result.exitCode, bounded(result.stdout), bounded(result.stderr), result.timedOut)
        }
    }
}

data class SelfTestResult @JvmOverloads constructor(
    val kind: SelfTestKind,
    val outcome: SelfTestOutcome,
    val reason: Reason? = null,
    /** Readback taken right after applying: deep state for DOZE, sensor mode for SENSORS. */
    val deep: DeepState? = null,
    val sensor: SensorMode? = null,
    val restoreComplete: Boolean = false,
    val restoreErrors: List<ExitError> = emptyList(),
    val commands: List<SelfTestCommand> = emptyList(),
)

fun interface SelfTestCallback {
    fun onResult(result: SelfTestResult)
}

/** Admission and one-shot completion shared by the Android queue and JVM queue regressions. */
internal class SelfTestQueue {
    @Volatile var attached = false
        private set
    private val pending = mutableSetOf<Request>()

    @Synchronized fun attach() { attached = true }
    @Synchronized fun detach() {
        attached = false
        pending.forEach { it.cancelled = true }
    }

    @Synchronized
    fun request(kind: SelfTestKind, callback: SelfTestCallback, post: (Runnable) -> Boolean, run: () -> SelfTestResult) {
        val request = Request(kind, callback)
        if (!attached) {
            request.complete(SelfTestResult(kind, SelfTestOutcome.CANCELLED))
            return
        }
        pending += request
        if (!post(Runnable {
            val admitted = synchronized(this) { pending.remove(request); attached && !request.cancelled }
            val result = if (!admitted) SelfTestResult(kind, SelfTestOutcome.CANCELLED) else {
                try { run() } catch (_: Exception) { SelfTestResult(kind, SelfTestOutcome.FAILED) }
            }
            request.complete(result)
        })) {
            pending.remove(request)
            request.complete(SelfTestResult(kind, SelfTestOutcome.CANCELLED))
        }
    }

    private class Request(val kind: SelfTestKind, val callback: SelfTestCallback) {
        var cancelled = false
        private var completed = false
        @Synchronized fun complete(result: SelfTestResult) {
            if (completed) return
            completed = true
            try { callback.onResult(result) } catch (_: Exception) { /* Presentation only. */ }
        }
    }
}

/**
 * Pure self-test core: the single controller applies one step through the ledger, the ledger exit
 * restores it in finally, then the SafetyNet check runs. Callers run it only on doze-worker.
 */
class SelfTest(
    private val controller: DozeController,
    private val resolver: CapabilityResolver,
    private val addSink: (DozeEventSink) -> Unit,
    private val removeSink: (DozeEventSink) -> Unit,
    private val sessionActive: () -> Boolean,
    private val safetyCheck: () -> Unit,
    private val admission: () -> Boolean = { true },
) {
    fun run(kind: SelfTestKind, config: DozeConfig): SelfTestResult {
        if (!admission()) return SelfTestResult(kind, SelfTestOutcome.CANCELLED)
        // Never race the real session: a pending enter or an admitted screen-off session owns the system.
        if (sessionActive()) return SelfTestResult(kind, SelfTestOutcome.BUSY)
        if (!SessionAccess.canRunSessions(config.level)) {
            return SelfTestResult(kind, SelfTestOutcome.UNAVAILABLE, Reason.NO_ACCESS)
        }
        val feature = if (kind == SelfTestKind.DOZE) Feature.FORCE_DOZE else Feature.MOTION_SENSORS
        val status = resolver.status(feature, config.level, config.apiLevel, config.grants)
        if (status is FeatureStatus.Unavailable) return SelfTestResult(kind, SelfTestOutcome.UNAVAILABLE, status.reason)

        var applying = true
        var concluded = false
        var deep: DeepState? = null
        var sensor: SensorMode? = null
        val sink = DozeEventSink { event ->
            if (applying && event.feature == feature) {
                if (event.type == EventType.VERIFY) {
                    event.deep?.let { deep = it }
                    event.sensor?.let { sensor = it }
                    concluded = true
                } else if (event.type == EventType.SKIPPED) {
                    concluded = true
                }
            }
        }
        addSink(sink)
        var step: StepResult? = null
        var failed = false
        var cancelled = false
        var restoreComplete = false
        var restoreErrors = emptyList<ExitError>()
        try {
            val generation = controller.bumpGeneration()
            // Core enter without battery saver; the sensor test stops before the force-idle step.
            val result = controller.enterCore(
                config.copy(restrictSensors = kind == SelfTestKind.SENSORS, batterySaver = false),
                generation,
            ) { admission() && (kind == SelfTestKind.DOZE || !concluded) }
            cancelled = !admission() || generation != controller.currentGeneration
            step = result.steps.lastOrNull { it.feature == feature }
        } catch (_: Exception) {
            failed = true
        } finally {
            applying = false
            try {
                val exit = controller.exit(config.apiLevel, config.grants)
                restoreComplete = ExitError.LEDGER_LOAD_FAILED !in exit.errors &&
                    exit.remaining.entries.none { it.feature == feature }
                restoreErrors = exit.errors
            } catch (_: Exception) {
                restoreComplete = false
            } finally {
                try {
                    safetyCheck()
                } finally {
                    removeSink(sink)
                }
            }
        }
        val applied = step
        val outcome = when {
            !restoreComplete -> SelfTestOutcome.RESTORE_INCOMPLETE
            failed -> SelfTestOutcome.FAILED
            cancelled || applied == null -> SelfTestOutcome.CANCELLED
            applied.alreadyOn -> SelfTestOutcome.NOT_VERIFIED
            applied.status == StepStatus.SKIPPED -> SelfTestOutcome.UNAVAILABLE
            applied.status == StepStatus.UNVERIFIED -> SelfTestOutcome.NOT_VERIFIED
            else -> SelfTestOutcome.PASSED
        }
        return SelfTestResult(
            kind, outcome, applied?.reason, deep, sensor, restoreComplete, restoreErrors,
        )
    }
}
