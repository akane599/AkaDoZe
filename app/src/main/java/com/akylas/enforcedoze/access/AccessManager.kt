package com.akylas.enforcedoze.access

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.preference.PreferenceManager
import com.akylas.enforcedoze.NotificationService
import java.util.Collections
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess

data class AccessState @JvmOverloads constructor(
    val level: AccessLevel, val reason: Reason?, val grants: Grants, val uid: Int?,
    val resolved: Boolean = true, val rootProbeTimedOut: Boolean = false,
)
data class ShizukuState(val level: AccessLevel, val reason: Reason?, val uid: Int?)

/** App-lifetime Android adapter. Root discovery and blocking commands run off the main thread. */
class AccessManager private constructor(context: Context) : com.akylas.enforcedoze.service.RecoveryAccess {
    private val app = context.applicationContext
    private val prefs = PreferenceManager.getDefaultSharedPreferences(app)
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val shizukuListeners = CopyOnWriteArraySet<ShizukuListener>()
    private val probePending = AtomicBoolean(false)
    private val probes = Executors.newSingleThreadExecutor { Thread(it, "access-probe").apply { isDaemon = true } }
    private val resolution = AccessResolution()
    @Volatile private var mode = prefs.getString(Prefs.EXECUTION_MODE, Prefs.DEFAULT_EXECUTION_MODE)
    @Volatile var shizukuState = ShizukuState(AccessLevel.NONE, Reason.SHIZUKU_NOT_RUNNING, null)
        private set
    @Volatile override var state = AccessState(AccessLevel.APP, null, readGrants(), android.os.Process.myUid(), resolved = false)
        private set
    val level: AccessLevel get() = state.level

    fun interface Listener { fun onAccessChanged(state: AccessState) }
    fun interface ShizukuListener { fun onShizukuChanged(state: ShizukuState) }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ -> refreshShizuku() }
    private val binderReceived = Shizuku.OnBinderReceivedListener { resolution.sawBinder(); refreshShizuku() }
    private val binderDead = Shizuku.OnBinderDeadListener { refreshShizuku() }
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.EXECUTION_MODE) {
            mode = prefs.getString(Prefs.EXECUTION_MODE, Prefs.DEFAULT_EXECUTION_MODE)
            if (mode == Prefs.MODE_ROOT) probeRoot() else publish()
        }
    }
    private val controlRunner = guardedLane("access-control")
    private val readRunner = guardedLane("access-reads")

    init {
        val discoveryStart = android.os.SystemClock.elapsedRealtime()
        resolution.startDiscovery(discoveryStart)
        // CountDownTimer measures its deadline with elapsedRealtime, including sleep.
        main.post {
            object : android.os.CountDownTimer(
                maxOf(1, discoveryStart + 10_000L - android.os.SystemClock.elapsedRealtime()), 10_000L,
            ) {
                override fun onTick(remaining: Long) = Unit
                override fun onFinish() { publish() }
            }.start()
        }
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        // Shared ownership: a legacy activity cannot unregister this listener for the service.
        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        refreshShizuku()
        if (mode == Prefs.MODE_ROOT) probeRoot()
    }

    fun control(): CommandRunner = controlRunner
    fun reads(): CommandRunner = readRunner

    /** External receivers use an absolute System.nanoTime deadline, including control queue wait. */
    fun controlWithDeadline(command: String, deadlineNanos: Long, admission: CommandLane.Admission): CommandResult =
        controlRunner.runWithDeadline(command, deadlineNanos, admission)

    override fun addListener(listener: Listener) {
        synchronized(lock) {
            if (listeners.add(listener)) {
                val initial = state
                main.post { if (listener in listeners) listener.onAccessChanged(initial) }
            }
        }
    }

    override fun removeListener(listener: Listener) { listeners.remove(listener) }

    /** Shizuku availability is observed independently of the selected execution mode. */
    fun addShizukuListener(listener: ShizukuListener) {
        synchronized(lock) {
            if (shizukuListeners.add(listener)) {
                val initial = shizukuState
                main.post { if (listener in shizukuListeners) listener.onShizukuChanged(initial) }
            }
        }
    }

    fun removeShizukuListener(listener: ShizukuListener) { shizukuListeners.remove(listener) }

    fun refresh() {
        refreshShizuku()
        if (mode == Prefs.MODE_ROOT) probeRoot()
    }

    fun refreshShizuku() {
        val next = try {
            val alive = Shizuku.pingBinder()
            if (alive) resolution.sawBinder()
            when {
                !alive || Shizuku.isPreV11() ->
                    ShizukuState(AccessLevel.NONE, Reason.SHIZUKU_NOT_RUNNING, null)
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED ->
                    ShizukuState(AccessLevel.NONE, Reason.SHIZUKU_PERMISSION_MISSING, null)
                else -> {
                    val uid = Shizuku.getUid()
                    ShizukuState(if (uid == 0) AccessLevel.ROOT else AccessLevel.SHELL, null, uid)
                }
            }
        } catch (_: Exception) {
            ShizukuState(AccessLevel.NONE, Reason.SHIZUKU_NOT_RUNNING, null)
        }
        synchronized(lock) {
            if (next != shizukuState) {
                shizukuState = next
                main.post {
                    for (listener in shizukuListeners) listener.onShizukuChanged(next)
                }
            }
            publish()
        }
    }

    fun checkShizukuPermission(): Int = try {
        if (!Shizuku.pingBinder() || Shizuku.isPreV11()) PackageManager.PERMISSION_DENIED
        else Shizuku.checkSelfPermission()
    } catch (_: Exception) { PackageManager.PERMISSION_DENIED }

    fun requestShizukuPermission() {
        try {
            if (!Shizuku.pingBinder() || Shizuku.isPreV11()) {
                refreshShizuku()
                return
            }
            if (checkShizukuPermission() != PackageManager.PERMISSION_GRANTED) Shizuku.requestPermission(0)
        } catch (_: Exception) {
            // Binder death can race the permission request even after a successful ping.
            refreshShizuku()
        }
    }

    /** Returns one transport result per attempted helper, including failures; does not short-circuit. */
    fun grantHelpers(): Map<String, CommandResult> {
        requireBackgroundThread()
        val results = linkedMapOf<String, CommandResult>()
        for ((item, command) in GrantCommands.forApp(Build.VERSION.SDK_INT, app.packageName, NotificationService::class.java.name)) {
            results[item] = controlRunner.run(command)
        }
        publish() // Refresh actual DUMP/WSS grants after the commands, not based on their exit codes.
        return Collections.unmodifiableMap(results)
    }

    private fun readGrants() = Grants(
        app.checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED,
        app.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED,
    )

    private fun publish() = synchronized(lock) {
        val shizuku = shizukuState
        val next = when (mode) {
            Prefs.MODE_SHIZUKU -> resolution.shizuku(shizuku, readGrants(), android.os.Process.myUid(), android.os.SystemClock.elapsedRealtime())
            Prefs.MODE_ROOT -> resolution.root(readGrants(), android.os.Process.myUid())
            else -> AccessState(AccessLevel.APP, Reason.NO_ACCESS, readGrants(), android.os.Process.myUid())
        }
        if (next != state) {
            state = next
            main.post { for (listener in listeners) listener.onAccessChanged(next) }
        }
    }

    /** Callers own bounded backoff and only retry discovery while recovery/session work needs it. */
    fun retryRootProbe() {
        if (mode == Prefs.MODE_ROOT && resolution.canRetryRoot()) probeRoot()
    }

    fun finishRootDiscovery() {
        resolution.finishRootDiscovery()
        publish()
    }

    private fun probeRoot() {
        if (!probePending.compareAndSet(false, true)) return
        resolution.rootProbeStarted()
        publish()
        probes.execute {
            try {
                CommandLane(RootCommandRunner(), "access-su-probe").use { probe ->
                    val result = probe.run("id -u")
                    resolution.rootProbeFinished(result.ok && result.stdout.singleOrNull()?.trim() == "0", result.timedOut)
                }
            } catch (_: Exception) {
                resolution.rootProbeFinished(false, false)
            } finally {
                probePending.set(false)
                publish()
            }
        }
    }

    private interface DeadlineRunner : CommandRunner {
        fun runWithDeadline(command: String, deadlineNanos: Long, admission: CommandLane.Admission): CommandResult
    }

    private fun guardedLane(name: String): DeadlineRunner {
        // Each lane has its OWN root session and process backend: no cross-lane queue or reset.
        val root = RootCommandRunner()
        val appShell = ShellCommandRunner()
        val shizuku = ShellCommandRunner({ shizukuState.level }) { command ->
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
            )
            method.isAccessible = true
            method.invoke(null, arrayOf("sh", "-c", command), null, null) as ShizukuRemoteProcess
        }
        val backend = object : CommandBackend {
            @Volatile private var active: CommandBackend? = null
            override val level: AccessLevel get() = this@AccessManager.level
            override fun execute(command: String): CommandResult {
                val selected = when (mode) {
                    Prefs.MODE_SHIZUKU -> if (shizukuState.level != AccessLevel.NONE) shizuku else appShell
                    Prefs.MODE_ROOT -> if (state.level == AccessLevel.ROOT) root else appShell
                    else -> appShell
                }
                active = selected
                return try { selected.execute(command) } finally { active = null }
            }
            override fun reset() { active?.reset() }
        }
        val lane = CommandLane(backend, name)
        return object : DeadlineRunner {
            override val level: AccessLevel get() = lane.level
            override fun run(command: String, timeoutMs: Long): CommandResult {
                requireBackgroundThread()
                return lane.run(command, timeoutMs)
            }
            override fun runWithDeadline(command: String, deadlineNanos: Long, admission: CommandLane.Admission): CommandResult {
                requireBackgroundThread()
                return lane.runWithDeadline(command, deadlineNanos, admission)
            }
        }
    }

    private fun requireBackgroundThread() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Blocking access command on main thread" }
    }

    companion object {
        @Volatile private var instance: AccessManager? = null
        @JvmStatic
        fun getInstance(context: Context): AccessManager = instance ?: synchronized(this) {
            instance ?: AccessManager(context).also { instance = it }
        }
    }
}
