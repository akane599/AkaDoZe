package com.akylas.enforcedoze.service

import android.app.Application
import android.os.SystemClock
import com.akylas.enforcedoze.access.*
import com.akylas.enforcedoze.doze.*
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RestoreWindowDeadlineTest {
    private val lanes = mutableListOf<CommandLane>()
    private var runtime: DozeRuntime? = null

    @After fun cleanup() {
        lanes.forEach { it.close() }
        runtime?.let { it.worker().looper.quit() }
        AccessManager::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }

    private fun runtime(backend: CommandBackend, submitted: CountDownLatch? = null): DozeRuntime {
        val app = RuntimeEnvironment.getApplication()
        val clock = AndroidClock()
        val core = DozeRuntime(app, clock, JournalSink(app, clock))
        runtime = core
        AccessManager::class.java.getDeclaredField("state").apply { isAccessible = true }
            .set(core.access, AccessState(AccessLevel.SHELL, null, Grants(true, true), 2000))
        val lane = CommandLane(backend).also { lanes.add(it) }
        val field = AccessManager::class.java.getDeclaredField("controlRunner").apply { isAccessible = true }
        // Replace only the Android transport: execute the real runtime runner and both real lane queues.
        field.set(core.access, Proxy.newProxyInstance(field.type.classLoader, arrayOf(field.type)) { _, method, args ->
            when (method.name) {
                "getLevel" -> lane.level
                "run" -> {
                    submitted?.countDown()
                    lane.run(args[0] as String, args[1] as Long)
                }
                "runWithDeadline" -> {
                    submitted?.countDown()
                    lane.runWithDeadline(args[0] as String, args[1] as Long, args[2] as CommandLane.Admission)
                }
                else -> error("Unexpected runner method ${method.name}")
            }
        })
        return core
    }

    private fun <T> onWorker(core: DozeRuntime, action: () -> T): FutureTask<T> =
        FutureTask<T> { action() }.also { core.worker().post(it) }

    @Test(timeout = 20_000) fun sharedRestoreDeadlineIncludesControlQueueWait() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val submitted = CountDownLatch(1)
        val executed = Collections.synchronizedList(mutableListOf<String>())
        val core = runtime(object : CommandBackend {
            override val level = AccessLevel.SHELL
            override fun execute(command: String): CommandResult {
                executed.add(command)
                if (command == "blocker") { started.countDown(); release.await() }
                return CommandResult(0, emptyList(), emptyList(), 0, false)
            }
            override fun reset() { release.countDown() }
        }, submitted)
        val lane = lanes.single()
        val blocker = lane.submit("blocker", 10_000)
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val restore = onWorker(core) {
                var result: CommandResult? = null
                core.withDeadline(core.clock.elapsedRealtime() + 100, Runnable {
                    result = core.control.run("pm unsuspend com.example.restore", 5_000)
                })
                result
            }
            assertTrue("restore reached control-lane admission", submitted.await(5, TimeUnit.SECONDS))
            SystemClock.sleep(101)
            // Wait past the translated nanoTime budget while keeping the preceding command held.
            CountDownLatch(1).await(250, TimeUnit.MILLISECONDS)
            assertFalse(blocker.isDone)
            release.countDown()
            blocker.get(5, TimeUnit.SECONDS)
            val result = restore.get(5, TimeUnit.SECONDS)
            lane.run("marker", 5_000)
            assertEquals("expired queued restore must never reach backend", listOf("blocker", "marker"), executed)
            assertTrue("queue wait consumes the shared budget", result!!.timedOut)
        } finally { release.countDown() }
    }

    @Test(timeout = 20_000) fun restoreOnlyPassLeavesUnreachedEntryAndItsJournalUntouched() {
        val executed = Collections.synchronizedList(mutableListOf<String>())
        val core = runtime(object : CommandBackend {
            override val level = AccessLevel.SHELL
            override fun execute(command: String): CommandResult {
                executed.add(command)
                if (command == "pm unsuspend com.example.first") SystemClock.sleep(9_001)
                return CommandResult(0, emptyList(), emptyList(), 0, false)
            }
            override fun reset() = Unit
        })
        val unreached = LedgerEntry(Feature.APP_SUSPEND, "com.example.second", "0", 123, apiLevel = 28)
        val first = LedgerEntry(Feature.APP_SUSPEND, "com.example.first", "0", 456, apiLevel = 28)
        val events = Collections.synchronizedList(mutableListOf<DozeEvent>())
        core.journal.addSink(DozeEventSink { events.add(it) })
        onWorker(core) {
            core.store.save(RestoreLedger(listOf(unreached, first)))
            core.withDeadline(core.clock.elapsedRealtime() + 9_000, Runnable { core.reconcileAndCheck() })
        }.get(5, TimeUnit.SECONDS)
        assertEquals("unreached entry keeps every persisted ledger field", unreached,
            core.store.load().entries.single { it.target == unreached.target })
        assertFalse("no command runs for unreached entry", executed.any { it.contains(unreached.target!!) })
        assertTrue("first restore command actually exhausted the window", executed.contains("pm unsuspend com.example.first"))
        assertTrue("unreached entry has no false failure or recovery debt", events.none {
            it.target == unreached.target && it.type in setOf(EventType.RESTORE_FAILED, EventType.RECOVERY_DEBT)
        })
    }
}
