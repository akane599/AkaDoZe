package com.akylas.enforcedoze.access

import com.akylas.enforcedoze.doze.DozeController
import com.akylas.enforcedoze.doze.DozeEventSink
import com.akylas.enforcedoze.doze.FakeClock
import com.akylas.enforcedoze.doze.InMemoryLedgerStore
import com.akylas.enforcedoze.doze.LedgerEntry
import com.akylas.enforcedoze.doze.RestoreLedger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class RadioSettleCommandLaneTest {
    @Test(timeout = 5_000)
    fun timedOutRootRereadDoesNotReopenSuWithinTheSameSettle() {
        val opens = AtomicInteger()
        val kills = AtomicInteger()
        val reads = AtomicInteger()
        val resets = AtomicInteger()
        val blockedReread = CountDownLatch(1)
        val shellKilled = CountDownLatch(1)
        val root = RootCommandRunner { available ->
            opens.incrementAndGet()
            available(true)
            object : RootSession {
                @Volatile private var running = true
                override val isRunning: Boolean get() = running
                override fun addCommand(command: String, onResult: (Int, List<String>, List<String>) -> Unit) {
                    val output = when (command) {
                        "cmd connectivity airplane-mode" -> "disabled"
                        "settings get global mobile_data" -> {
                            // Only timeout/reset can release this read, regardless of supervisor scheduling.
                            if (reads.incrementAndGet() > 1) blockedReread.await()
                            "0"
                        }
                        else -> ""
                    }
                    onResult(0, listOf(output), emptyList())
                }
                override fun kill() {
                    if (running) {
                        running = false
                        kills.incrementAndGet()
                        blockedReread.countDown()
                        shellKilled.countDown()
                    }
                }
            }
        }
        val backend = object : CommandBackend {
            override val level: AccessLevel get() = root.level
            override fun execute(command: String): CommandResult = root.execute(command)
            override fun reset() {
                resets.incrementAndGet()
                root.reset()
            }
        }
        CommandLane(backend, "radio-settle-test").use { lane ->
            val store = InMemoryLedgerStore()
            store.save(RestoreLedger(listOf(
                LedgerEntry(Feature.MOBILE_DATA, null, "1", 0, apiLevel = 36),
                LedgerEntry(Feature.AIRPLANE, null, "0", 0, apiLevel = 36),
            )))
            val clock = FakeClock()
            val waits = mutableListOf<Long>()
            val controller = DozeController(
                lane, CommandCatalog, CapabilityResolver, store, clock, DozeEventSink {}, 36, Grants(true, true),
                sleeper = { waits.add(it); clock.elapsed += it },
            )
            val result = controller.exit()
            assertTrue("timeout must finish killing the shell", shellKilled.await(1, TimeUnit.SECONDS))
            assertFalse(result.complete)
            assertEquals("only the initial read and timed-out reread reach su", 2, reads.get())
            assertEquals("one lane timeout reset before close", 1, resets.get())
            assertEquals("settle must not open another su shell", 1, opens.get())
            assertEquals("the timed-out shell is killed once", 1, kills.get())
            assertEquals(listOf(150L), waits)
            assertEquals(Feature.MOBILE_DATA, result.remaining.entries.single().feature)
        }
    }
}
