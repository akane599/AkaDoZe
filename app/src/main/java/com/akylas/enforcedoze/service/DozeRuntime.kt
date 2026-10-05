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
class DozeRuntime(context: Context, val clock: AndroidClock, val journal: JournalSink) {
    private val app = context.applicationContext
    val access: AccessManager = AccessManager.getInstance(app)
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
    private val resets = ServiceResetQueue(this) { job -> worker().post(job) }
    private var shutdownQueued = false
    private var pendingRecoveries = 0
    private var deferred: Runnable? = null

    @Synchronized
    fun attachService(): Handler {
        selfTests.attach()
        resets.attachService()
        access.startServiceRootDiscovery()
        return worker()
    }

    @Synchronized
    fun detachService(teardown: Runnable) {
        selfTests.detach()
        // Enqueue atomically with detach, before an idle shutdown can retire this worker.
        resets.detachService(teardown)
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
        watchdog.cancelDeferred()
        return controller.bumpGeneration()
    }

    @Synchronized
    fun deferWatchdog(callback: Runnable, untilElapsed: Long) {
        // Replacement retains a scheduled retry, so it must retain the policy's deferred flag.
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

    private var announcedAccess: com.akylas.enforcedoze.access.AccessState? = null

    /** doze-worker only: feed both the timeline and NoticeSink, once per resolved change. */
    fun announceAccess() {
        val state = access.state
        if (!state.resolved || state == announcedAccess) return
        announcedAccess = state
        AccessRecovery.announce(state, journal, Runnable { recordAccessDebt() })
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
            if (Build.VERSION.SDK_INT < 24) return DozeStateParser.parse(runRead("dumpsys deviceidle"))
            val prefix = "cmd deviceidle"
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
    fun invalidateAccess() {
        readiness.invalidate()
        bumpGeneration()
    }

    fun sessionMode(): SessionMode = SessionAccess.mode(
        access.level, grants(),
        android.preference.PreferenceManager.getDefaultSharedPreferences(app)
            .getBoolean("disableMotionSensors", true),
        access.state.resolved,
    )

    /** Attached service supplies its live screen/charging/call/deadline/consent admission. */
    @Volatile var forwardAdmission: (() -> Boolean)? = null

    fun accessReadyForEnter(): Boolean = readiness.ready(access.state)

    /** doze-worker only: access-return recovery precedes resuming even an existing session. */
    fun recoverAccess(): Boolean = readiness.recover(access.state, { access.state }) {
        bumpGeneration()
        recordExit(controller.reconcile(Build.VERSION.SDK_INT, grants()))
        checkSafety()
    }

    /**
     * Caller stops the service first. Cancels pending enter on the caller thread, then restores and
     * resets on doze-worker after the attached service's teardown. Without a service, posts immediately.
     * The callback also runs on doze-worker; presentation must hop to main.
     * Preferences are never cleared here, including when restoration leaves debt.
     */
    @Synchronized
    fun resetSystemState(callback: SystemResetCallback) {
        bumpGeneration()
        resets.resetSystemState(Runnable {
            try {
                val onError: (Throwable) -> Unit = { error ->
                    diagnosticLogger("System reset failed", error)
                    journal.emit(DozeEvent(EventType.ERROR, "RESET_FAILED"))
                }
                val result = SystemReset.runJob({
                    sessionActive = false
                    session.recordExit()
                    SystemReset.run(control, Build.VERSION.SDK_INT, app.packageName,
                        permissionGranted = { permission ->
                            app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
                        },
                        restore = {
                            val exit = controller.reconcile(Build.VERSION.SDK_INT, grants())
                            recordExit(exit)
                            checkSafety()
                            val remaining = store.load()
                            SystemReset.restoreOutcome(exit.complete, remaining, store.loadFailed, store.corruptLines)
                        },
                        onError = onError,
                    )
                }, onError = onError)
                callback.onComplete(result)
            } finally { quitIfDetached() }
        })
    }

    /**
     * After the user confirmed the reset result: runs its deferred revokes on doze-worker, then [restart]
     * there. Android normally kills this process during a deferred revoke, so [restart] may never run.
     */
    @Synchronized
    fun finishReset(deferred: List<ResetCommandId>, restart: Runnable) {
        bumpGeneration()
        resets.finishReset(Runnable {
            try {
                try {
                    SystemReset.runDeferred(control, Build.VERSION.SDK_INT, app.packageName, deferred)
                } finally { restart.run() }
            } finally { quitIfDetached() }
        })
    }

    fun hasPendingRestore(): Boolean = try {
        store.load().entries.isNotEmpty() || LedgerRecovery.needsRecovery(store.corruptLines, store.loadFailed)
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

    /** No FGS: a bounded wakeful window; later SHELL/ROOT is awaited by the one shared [continuation]. */
    @JvmOverloads
    fun requestRestoreOnly(completed: Runnable, source: RecoveryAccess = access) =
        requestRestoreOnly(completed, source, true)

    /** Main thread only: created once, then re-armed by every window that ends without SHELL/ROOT. */
    private var continuation: RestoreContinuation? = null

    @Synchronized
    private fun requestRestoreOnly(completed: Runnable, source: RecoveryAccess, allowContinuation: Boolean) {
        pendingRecoveries++
        val worker = worker()
        val main = Handler(android.os.Looper.getMainLooper())
        val power = app.getSystemService(PowerManager::class.java)
        val wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "forcedoze:restore")
        wakeLock.acquire(30_000L)
        val deadline = clock.elapsedRealtime() + 9_000L
        main.post {
            val shared = if (!allowContinuation) null else continuation ?: RestoreContinuation(source,
                { action -> main.post { action() } },
                { requestRestoreOnly(Runnable {}, source, false) },
                {
                    // Never post to a retired window's worker; late debt briefly owns a fresh worker.
                    synchronized(this@DozeRuntime) {
                        pendingRecoveries++
                        worker().post {
                            try { announceAccess(); recordCorruptionDebt() }
                            finally {
                                synchronized(this@DozeRuntime) { pendingRecoveries-- }
                                quitIfDetached()
                            }
                        }
                    }
                },
                {
                    // Late APP discovery owns its worker and wakeful budget independently of the
                    // expired receiver window, while the shared continuation still awaits SHELL.
                    synchronized(this@DozeRuntime) {
                        pendingRecoveries++
                        val appWakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "forcedoze:restore")
                        appWakeLock.acquire(30_000L)
                        val appDeadline = clock.elapsedRealtime() + 9_000L
                        worker().post {
                            try {
                                if (hasPendingRestore()) {
                                    withDeadline(appDeadline, Runnable { reconcileAndCheck() })
                                }
                            } finally {
                                try { if (appWakeLock.isHeld) appWakeLock.release() }
                                catch (_: RuntimeException) { /* Timeout release may race on API 23-27. */ }
                                synchronized(this@DozeRuntime) { pendingRecoveries-- }
                                quitIfDetached()
                            }
                        }
                    }
                },
            ).also { continuation = it }
            RestoreOnlyRequest(source, clock::elapsedRealtime, { action -> main.post { action() } },
                { deadline, action ->
                    val timer = object : android.os.CountDownTimer(
                        maxOf(1, deadline - clock.elapsedRealtime()), 9_000L,
                    ) {
                        override fun onTick(remaining: Long) = Unit
                        override fun onFinish() { action() }
                    }.start()
                    ({ timer.cancel() })
                },
                { action -> worker.post { action() } },
                { deadline ->
                    withDeadline(deadline, Runnable {
                        announceAccess()
                        reconcileAndCheck()
                    })
                },
                {
                    synchronized(this@DozeRuntime) { pendingRecoveries-- }
                    try {
                        if (!selfTests.attached) access.finishRootDiscovery(detached = true)
                        completed.run()
                    } finally {
                        try { if (wakeLock.isHeld) wakeLock.release() }
                        catch (_: RuntimeException) { /* Timeout release may race on API 23-27. */ }
                        worker.post { quitIfDetached() }
                    }
                },
                shared,
                { journal.emit(DozeEvent(EventType.RECOVERY_DEBT, "RESTORE_WINDOW_STARVED")) },
                ::hasPendingRestore,
            ).start(deadline)
        }
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
            if (SessionAccess.keepsSafetyIntent(
                    action, sessionMode(), sessionActive && forwardAdmission?.invoke() == true,
                    recoveryNeeded, ledger, allowToken,
                )) continue
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

/** Reset/detach posting policy; all calls are made while holding the runtime monitor. */
internal class ServiceResetQueue(private val lock: Any = Any(), private val post: (Runnable) -> Unit) {
    private var attached = false
    private val pending = mutableListOf<Runnable>()

    fun finishReset(job: Runnable) = synchronized(lock) { post(job) }

    fun attachService() { attached = true }

    fun resetSystemState(job: Runnable) {
        if (attached) pending += job else post(job)
    }

    fun detachService(teardown: Runnable) {
        attached = false
        post(teardown)
        pending.forEach(post)
        pending.clear()
    }
}

/** Pure timeout decision shared by the service and JVM regressions. */
object TeardownTimeout {
    @JvmStatic
    fun shouldReport(finished: Boolean): Boolean = !finished
}
