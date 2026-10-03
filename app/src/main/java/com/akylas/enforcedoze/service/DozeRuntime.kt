package com.akylas.enforcedoze.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
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
    val control: CommandRunner = object : CommandRunner {
        override val level: AccessLevel get() = access.level
        override fun run(command: String, timeoutMs: Long): CommandResult {
            val remaining = commandDeadline?.minus(clock.elapsedRealtime())
            if (remaining != null && remaining <= 0) return CommandResult(-1, emptyList(), emptyList(), 0, true)
            return access.control().run(command, minOf(timeoutMs, remaining ?: timeoutMs))
        }
    }
    val controller = DozeController(
        control, CommandCatalog, CapabilityResolver, store, clock, journal, Build.VERSION.SDK_INT, grants(),
    )
    val watchdog = WatchdogPolicy(clock)
    val session = SessionLifecycle()
    var sessionActive: Boolean
        get() = session.active
        set(value) { session.active = value }
    @Volatile var allowToken: String = app.packageName
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var serviceAttached = false
    private var deferred: Runnable? = null

    @Synchronized
    fun attachService(): Handler {
        serviceAttached = true
        return worker()
    }

    @Synchronized
    fun detachService() { serviceAttached = false }

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
        if (!serviceAttached) {
            // Cleanup has finished. Drop redundant queued safety requests instead of draining them
            // concurrently with a replacement worker after the handler is detached.
            thread?.quit()
            thread = null
            handler = null
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

    fun importHistory() {
        if (!grants().dump && access.level < AccessLevel.SHELL) return
        try {
            val result = access.reads().run("dumpsys deviceidle", 8_000)
            val now = clock.elapsedRealtime()
            if (result.ok) journal.importHistory(result.stdout, now)
            else journal.emit(DozeEvent(EventType.ERROR, "HISTORY_READ_FAILED"))
        } catch (_: Exception) { journal.emit(DozeEvent(EventType.ERROR, "HISTORY_READ_FAILED")) }
    }

    fun recordAccessDebt() {
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

    /** Never reconcile an admitted screen-off session: doing so has exit semantics. */
    fun reconcileAndCheck() {
        if (!sessionActive) {
            bumpGeneration()
            recordExit(controller.reconcile(Build.VERSION.SDK_INT, grants()))
        }
        checkSafety()
    }

    @Synchronized
    fun requestSafetyCheck() {
        worker().post {
            try { reconcileAndCheck() } finally { quitIfDetached() }
        }
    }

    /** DUMP readbacks are the oracle; fail safe on damaged/lost intent, preserving all evidence. */
    fun checkSafety() = synchronized(controller) { checkSafetyLocked() }

    private fun checkSafetyLocked() {
        val ledger = try { store.load() } catch (_: Exception) { RestoreLedger() }
        val damaged = store.loadFailed || store.corruptLines.isNotEmpty()
        val hasForce = LedgerRecovery.hasForceIntent(ledger, store.corruptLines, store.loadFailed)
        if (CapabilityResolver.status(Feature.DOZE_STATE_READ, control.level, Build.VERSION.SDK_INT, grants())
            != FeatureStatus.Available
        ) {
            if (damaged || ledger.entries.isNotEmpty()) journal.emit(DozeEvent(EventType.RECOVERY_DEBT, "SAFETY_READ_UNAVAILABLE"))
            return
        }
        val token = ledger.entries.firstOrNull { it.feature == Feature.MOTION_SENSORS }?.target ?: allowToken
        val sensor = SensorModeParser.parse(runRead("dumpsys sensorservice"))
        val idle = DozeStateParser.parse(runRead("dumpsys deviceidle"))
        val actions = SafetyNet.check(sensor, idle.forceIdle, control.level, token, hasForce)
        for (action in actions) {
            // Healthy ledger-backed restriction/force belongs to the active session, not an orphan.
            if (sessionActive && !damaged && control.level >= AccessLevel.SHELL &&
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
                        CommandCatalog.setEnabled(feature, Build.VERSION.SDK_INT, false)?.forEach { control.run(it, 8_000) }
                    } catch (_: Exception) {
                        journal.emit(DozeEvent(EventType.ERROR, "SAFETY_COMMAND_FAILED"))
                    }
                    val verified = if (action == Action.RESTORE_SENSORS) {
                        SensorModeParser.parse(runRead("dumpsys sensorservice")).mode == SensorMode.NORMAL
                    } else DozeStateParser.parse(runRead("dumpsys deviceidle")).forceIdle == false
                    journal.emit(DozeEvent(
                        if (!verified) EventType.RESTORE_FAILED else if (action == Action.RESTORE_SENSORS)
                            EventType.SENSORS_RESTORED else EventType.VERIFY,
                        action.name,
                    ))
                    if (!verified) journal.emit(DozeEvent(EventType.RECOVERY_DEBT, action.name))
                }
            }
        }
        if (damaged) {
            val recovered = LedgerRecovery.recoveryVerified(
                SensorModeParser.parse(runRead("dumpsys sensorservice")).mode,
                DozeStateParser.parse(runRead("dumpsys deviceidle")).forceIdle,
            )
            if (recovered && !store.loadFailed) {
                try {
                    store.clearCorruptionAfterRecovery(ledger)
                } catch (_: Exception) {
                    journal.emit(DozeEvent(EventType.RECOVERY_DEBT, "LEDGER_RECOVERY_COMMIT_FAILED"))
                }
            } else journal.emit(DozeEvent(EventType.RECOVERY_DEBT, "LEDGER_DAMAGED"))
        }
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
