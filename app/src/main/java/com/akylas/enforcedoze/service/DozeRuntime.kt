package com.akylas.enforcedoze.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.util.Log
import com.akylas.enforcedoze.access.AccessLevel
import com.akylas.enforcedoze.access.AccessManager
import com.akylas.enforcedoze.access.CapabilityResolver
import com.akylas.enforcedoze.access.CommandCatalog
import com.akylas.enforcedoze.access.CommandResult
import com.akylas.enforcedoze.access.CommandRunner
import com.akylas.enforcedoze.access.Feature
import com.akylas.enforcedoze.access.FeatureStatus
import com.akylas.enforcedoze.access.Grants
import com.akylas.enforcedoze.access.PackageNames
import com.akylas.enforcedoze.doze.Action
import com.akylas.enforcedoze.doze.DeepState
import com.akylas.enforcedoze.doze.DozeConfig
import com.akylas.enforcedoze.doze.DozeController
import com.akylas.enforcedoze.doze.DozeEvent
import com.akylas.enforcedoze.doze.EventType
import com.akylas.enforcedoze.doze.ExitResult
import com.akylas.enforcedoze.doze.LightState
import com.akylas.enforcedoze.doze.RestoreLedger
import com.akylas.enforcedoze.doze.SafetyNet
import com.akylas.enforcedoze.doze.SensorMode
import com.akylas.enforcedoze.doze.WatchdogPolicy
import com.akylas.enforcedoze.doze.parse.DozeStateParser
import com.akylas.enforcedoze.doze.parse.DozeStateReading
import com.akylas.enforcedoze.doze.parse.SensorModeParser

/** Owned by MyApplication: one controller/ledger for service, recovery and future self-test callers. */
class DozeRuntime(context: Context) {
    private val app = context.applicationContext
    val access: AccessManager = AccessManager.getInstance(app)
    val clock = AndroidClock()
    val journal = JournalSink(app, clock)
    val store = SharedPrefsLedgerStore(app, journal)
    private var commandDeadline: Long? = null // Only accessed on doze-worker.
    private var selfTestRecorder: MutableList<SelfTestCommand>? = null // Only accessed on doze-worker.
    val control: CommandRunner = object : CommandRunner {
        override val level: AccessLevel get() = access.level
        override fun run(command: String, timeoutMs: Long): CommandResult {
            val remaining = commandDeadline?.minus(clock.elapsedRealtime())
            if (remaining != null && remaining <= 0) return CommandResult(-1, emptyList(), emptyList(), 0, true)
            return access.control().run(command, minOf(timeoutMs, remaining ?: timeoutMs))
                .also { selfTestRecorder?.add(SelfTestCommand.of(command, it)) }
        }
    }
    private val diagnosticLogger: (String, Throwable) -> Unit = { message, error ->
        Log.w("DozeRuntime", message, error)
    }
    val controller = DozeController(
        control, CommandCatalog, CapabilityResolver, store, clock, journal, Build.VERSION.SDK_INT, grants(),
        diagnosticLogger,
        { access.state.resolved },
    )
    val watchdog = WatchdogPolicy(clock)
    val session = SessionLifecycle()
    var sessionActive: Boolean
        get() = session.active
        set(value) {
            session.active = value
            screenOffPending = false
        }
    @Volatile private var screenOffPending = false

    fun screenOffReceived() {
        screenOffPending = true
        bumpGeneration()
    }
    @Volatile var allowToken: String = app.packageName
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val selfTests = SelfTestQueue(diagnosticLogger)
    private var shutdownQueued = false
    private var pendingRecoveries = 0
    private var deferred: Runnable? = null

    @Synchronized
    fun attachService(): Handler {
        selfTests.attach()
        return worker()
    }

    @Synchronized
    fun detachService(teardown: Runnable) {
        selfTests.detach()
        // Enqueue atomically with detach, before an idle shutdown can retire this worker.
        worker().post(teardown)
    }

    @Synchronized
    fun worker(): Handler {
        return handler ?: HandlerThread("doze-worker").let {
            it.start()
            thread = it
            Handler(it.looper).also { newHandler -> handler = newHandler }
        }
    }

    /** Called at the end of queued teardown; a newly attached service can retain the same worker. */
    @Synchronized
    fun quitIfDetached() {
        if (selfTests.attached || pendingRecoveries > 0 || shutdownQueued) return
        shutdownQueued = true
        worker().looper.queue.addIdleHandler {
            synchronized(this) {
                if (selfTests.attached || pendingRecoveries > 0) {
                    shutdownQueued = false
                    false
                } else if (handler?.hasMessages(0) == true) {
                    // Also drain delayed callbacks; never overlap a draining and replacement worker.
                    true
                } else {
                    thread?.quitSafely()
                    thread = null
                    handler = null
                    shutdownQueued = false
                    false
                }
            }
        }
    }

    fun grants() = Grants(
        app.checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED,
        app.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED,
    )

    /** Safe on the receiver thread, even while enter() is blocked in a command. */
    @Synchronized
    fun bumpGeneration(): Long {
        deferred?.let { handler?.removeCallbacks(it) }
        deferred = null
        return controller.bumpGeneration()
    }

    @Synchronized
    fun deferWatchdog(callback: Runnable, untilElapsed: Long) {
        deferred?.let { handler?.removeCallbacks(it) }
        deferred = callback
        worker().postDelayed(callback, maxOf(0, untilElapsed - clock.elapsedRealtime()))
    }

    /** Runs on doze-worker after every exit-time import attempt, including skipped imports. */
    @Volatile var afterHistoryImport: Runnable? = null

    fun importHistory() {
        try {
            if (!grants().dump && access.level < AccessLevel.SHELL) return
            val result = access.reads().run("dumpsys deviceidle", 8_000)
            val now = clock.elapsedRealtime()
            if (result.ok) journal.importHistory(result.stdout, now)
            else journal.emit(DozeEvent(EventType.ERROR, "HISTORY_READ_FAILED"))
        } catch (_: Exception) {
            journal.emit(DozeEvent(EventType.ERROR, "HISTORY_READ_FAILED"))
        } finally {
            try { afterHistoryImport?.run() } catch (_: Exception) { /* Presentation only. */ }
        }
    }

    /** doze-worker only. Raw outputs are recorded for this run alone; BUSY while a session is active. */
    fun runSelfTest(kind: SelfTestKind): SelfTestResult {
        if (!selfTests.attached || !recoverAccess()) return SelfTestResult(kind, SelfTestOutcome.CANCELLED)
        val commands = mutableListOf<SelfTestCommand>()
        val feature = if (kind == SelfTestKind.DOZE) Feature.FORCE_DOZE else Feature.MOTION_SENSORS
        journal.beginSelfTest(feature)
        selfTestRecorder = commands
        val result = try {
            SelfTest(
                controller, CapabilityResolver, journal::addSink, journal::removeSink,
                { sessionActive }, ::checkSafety, { selfTests.attached && !screenOffPending }, diagnosticLogger,
            )
                .run(kind, DozeConfig(Build.VERSION.SDK_INT, access.level, grants(), allowToken = allowToken))
        } finally {
            selfTestRecorder = null
            journal.endSelfTest()
        }
        return result.copy(commands = commands.toList())
    }

    /** Queued callbacks run on doze-worker; immediate rejection may run on the caller thread. */
    @Synchronized
    fun requestSelfTest(kind: SelfTestKind, callback: SelfTestCallback) {
        selfTests.request(kind, callback, { job ->
            worker().post {
                try { job.run() } finally { quitIfDetached() }
            }
        }, { runSelfTest(kind) })
    }

    fun recordAccessDebt() {
        if (!access.state.resolved) return
        try {
            val ledger = store.load()
            if (AccessRecovery.hasShellDebt(ledger, Build.VERSION.SDK_INT)) journal.emit(DozeEvent(EventType.RECOVERY_DEBT, "ACCESS_LOST"))
        } catch (_: Exception) { journal.emit(DozeEvent(EventType.RECOVERY_DEBT, "LEDGER_LOAD_FAILED")) }
    }

    fun readState(): DozeStateReading {
        if (control.level >= AccessLevel.SHELL) {
            val prefix = if (Build.VERSION.SDK_INT >= 24) "cmd deviceidle" else "dumpsys deviceidle"
            return DozeStateReading(
                DozeStateParser.parseDeep(runRead("$prefix get deep")) ?: DeepState.UNKNOWN,
                DozeStateParser.parseLight(runRead("$prefix get light")) ?: LightState.UNKNOWN,
                null, null, null, null, emptyMap(),
            )
        }
        if (grants().dump) return DozeStateParser.parse(runRead("dumpsys deviceidle"))
        val power = app.getSystemService(PowerManager::class.java)
        // False means not idle, not necessarily ACTIVE. Keep all non-idle coverage UNKNOWN.
        return DozeStateReading(
            if (power.isDeviceIdleMode) DeepState.IDLE else DeepState.UNKNOWN,
            if (Build.VERSION.SDK_INT >= 33 && power.isDeviceLightIdleMode) LightState.IDLE else LightState.UNKNOWN,
            null, null, null, null, emptyMap(),
        )
    }

    private fun runRead(command: String): List<String> = try {
        control.run(command, 8_000).let { if (it.ok) it.stdout else emptyList() }
    } catch (_: Exception) { emptyList() }

    fun recordExit(result: ExitResult) {
        for (error in result.errors) {
            journal.emit(DozeEvent(EventType.RESTORE_FAILED, error.name))
            journal.emit(DozeEvent(EventType.ERROR, error.name))
        }
    }

    private val readiness = AccessReadiness()

    /** Main-thread invalidation wins over a recovery already running on the worker. */
    fun invalidateAccess() = readiness.invalidate()

    fun accessReadyForEnter(): Boolean = readiness.ready(access.state)

    /** doze-worker only: access-return recovery precedes resuming even an existing session. */
    fun recoverAccess(): Boolean = readiness.recover(access.state, { access.state }) {
        bumpGeneration()
        recordExit(controller.reconcile(Build.VERSION.SDK_INT, grants()))
        checkSafety()
    }

    fun hasPendingRestore(): Boolean = try {
        store.load().entries.isNotEmpty() || store.corruptLines.isNotEmpty() || store.loadFailed
    } catch (_: Exception) { true }

    /** Healthy sessions are not reconciled; unresolved access leaves durable intent untouched. */
    fun reconcileAndCheck() {
        if (!access.state.resolved) {
            invalidateAccess()
            return
        }
        if (!sessionActive) invalidateAccess()
        if (accessReadyForEnter()) checkSafety() else recoverAccess()
    }

    fun requestSafetyCheck() = requestRestoreOnly(Runnable {})

    /** No session or FGS: retain the receiver at most nine seconds, including discovery and commands. */
    @Synchronized
    fun requestRestoreOnly(completed: Runnable) {
        pendingRecoveries++
        val worker = worker()
        val main = Handler(android.os.Looper.getMainLooper())
        val deadline = clock.elapsedRealtime() + 9_000L
        val finished = java.util.concurrent.atomic.AtomicBoolean()
        lateinit var listener: AccessManager.Listener
        lateinit var timeout: Runnable
        fun finish() {
            if (!finished.compareAndSet(false, true)) return
            access.removeListener(listener)
            main.removeCallbacks(timeout)
            synchronized(this@DozeRuntime) { pendingRecoveries-- }
            try { completed.run() } finally { worker.post { quitIfDetached() } }
        }
        listener = AccessManager.Listener {
            worker.post {
                if (!finished.get() && access.state.resolved) {
                    try {
                        withDeadline(deadline, Runnable { reconcileAndCheck() })
                    } finally {
                        if (access.state.resolved) finish()
                    }
                }
            }
        }
        timeout = Runnable { finish() }
        main.postDelayed(timeout, 9_000L)
        access.addListener(listener)
    }

    /** User-confirmed dismiss. Call on doze-worker; commits before returning and preserves valid entries. */
    fun clearRetainedCorruption() = synchronized(controller) {
        store.clearRetainedCorruption()
        recordCorruptionDebt()
    }

    private fun recordCorruptionDebt() {
        try {
            store.recordCorruptionDebt()
        } catch (_: Exception) {
            journal.emit(DozeEvent(EventType.RECOVERY_DEBT, "LEDGER_RECOVERY_COMMIT_FAILED"))
        }
    }

    /** DUMP readbacks are the oracle; fail safe on damaged/lost intent, preserving all evidence. */
    fun checkSafety() = synchronized(controller) { checkSafetyLocked() }

    private fun checkSafetyLocked() {
        if (!access.state.resolved) return
        val ledger = try { store.load() } catch (_: Exception) { RestoreLedger() }
        val recoveryNeeded = LedgerRecovery.needsRecovery(store.corruptLines, store.loadFailed)
        val hasForce = LedgerRecovery.hasForceIntent(ledger, store.corruptLines, store.loadFailed)
        if (CapabilityResolver.status(Feature.DOZE_STATE_READ, control.level, Build.VERSION.SDK_INT, grants())
            != FeatureStatus.Available
        ) {
            recordCorruptionDebt()
            if (recoveryNeeded || ledger.entries.isNotEmpty()) journal.emit(DozeEvent(EventType.RECOVERY_DEBT, "SAFETY_READ_UNAVAILABLE"))
            return
        }
        val token = ledger.entries.firstOrNull { it.feature == Feature.MOTION_SENSORS }?.target ?: allowToken
        val sensor = SensorModeParser.parse(runRead("dumpsys sensorservice"))
        val idle = DozeStateParser.parse(runRead("dumpsys deviceidle"))
        if (!access.state.resolved) return
        val actions = SafetyNet.check(sensor, idle.forceIdle, control.level, token, hasForce)
        for (action in actions) {
            if (!access.state.resolved) return
            // Healthy ledger-backed restriction/force belongs to the active session, not an orphan.
            if (sessionActive && !recoveryNeeded && control.level >= AccessLevel.SHELL &&
                (action == Action.UNFORCE || action == Action.RESTORE_SENSORS &&
                    ledger.entries.any { it.feature == Feature.MOTION_SENSORS })
            ) continue
            when (action) {
                Action.RAISE_DEBT -> journal.emit(DozeEvent(EventType.RECOVERY_DEBT, action.name))
                Action.RESTORE_SENSORS, Action.UNFORCE -> {
                    val feature = if (action == Action.RESTORE_SENSORS) Feature.MOTION_SENSORS else Feature.FORCE_DOZE
                    if (CapabilityResolver.status(feature, control.level, Build.VERSION.SDK_INT, grants()) != FeatureStatus.Available) {
                        journal.emit(DozeEvent(EventType.RECOVERY_DEBT, action.name))
                        continue
                    }
                    bumpGeneration()
                    try {
                        CommandCatalog.setEnabled(feature, Build.VERSION.SDK_INT, false)?.forEach {
                            if (!access.state.resolved) return
                            control.run(it, 8_000)
                        }
                    } catch (_: Exception) {
                        journal.emit(DozeEvent(EventType.ERROR, "SAFETY_COMMAND_FAILED"))
                    }
                    val verified = if (action == Action.RESTORE_SENSORS) {
                        SensorModeParser.parse(runRead("dumpsys sensorservice")).mode == SensorMode.NORMAL
                    } else DozeStateParser.parse(runRead("dumpsys deviceidle")).forceIdle == false
                    if (!access.state.resolved) return
                    journal.emit(DozeEvent(
                        if (!verified) EventType.RESTORE_FAILED else if (action == Action.RESTORE_SENSORS)
                            EventType.SENSORS_RESTORED else EventType.VERIFY,
                        action.name, feature = feature,
                    ))
                    if (!verified) journal.emit(DozeEvent(EventType.RECOVERY_DEBT, action.name, feature = feature))
                }
            }
        }
        if (recoveryNeeded) {
            val recovered = LedgerRecovery.recoveryVerified(
                SensorModeParser.parse(runRead("dumpsys sensorservice")).mode,
                DozeStateParser.parse(runRead("dumpsys deviceidle")).forceIdle,
            )
            if (!access.state.resolved) return
            if (recovered && !store.loadFailed) {
                try {
                    store.clearCorruptionAfterRecovery()
                } catch (_: Exception) {
                    journal.emit(DozeEvent(EventType.RECOVERY_DEBT, "LEDGER_RECOVERY_COMMIT_FAILED"))
                }
            }
        }
        recordCorruptionDebt()
    }

    /** Teardown shares a single command-time budget; durable debt survives any exhausted budget. */
    fun withDeadline(deadline: Long, action: Runnable) {
        commandDeadline = deadline
        try { action.run() } finally { commandDeadline = null }
    }

    fun configureAllowToken(value: String?) {
        val candidate = value?.trim().orEmpty()
        allowToken = if (PackageNames.isValid(candidate)) candidate else app.packageName
    }
}
