package com.akylas.enforcedoze.service

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BootRestoreWiringTest {
    @Test fun bootHandoffKeepsBroadcastPriorityUntilFollowUpAcquisition() {
        val fixture = RestoreWindowFixture()
        var broadcastHeld = true
        fixture.ready()
        fixture.start {
            broadcastHeld = false
            fixture.events += "pending.finish"
        }
        fixture.drainMain()
        fixture.now = 7_001L
        fixture.runWorker()
        fixture.drainMain()
        assertEquals("S1 handoff must precede goAsync completion",
            listOf("acquire follow-up", "pending.finish"), fixture.events)
        assertFalse("broadcast completion is not postponed to follow-up completion", broadcastHeld)
        fixture.drain()
    }

    @Test fun bootReceiverUsesBoundedCommonRestoreWindowWithoutExtraHandoff() {
        val receiver = source("BootCompleteReceiver.java")
        assertTrue("receiver retains goAsync until common window completion", receiver.contains("PendingResult pending = goAsync();"))
        assertTrue("pending.finish uses the common completion callback", receiver.contains("requestRestoreOnly(pending::finish)"))
        val runtime = source("service/DozeRuntime.kt")
        val window = runtime.substringAfter("private fun requestRestoreOnly(").substringBefore("fun clearRetainedCorruption")
        assertTrue("wakelock keeps its 30 second bound", window.contains("wakeLock.acquire(30_000L)"))
        assertTrue("goAsync window keeps its 9 second deadline", window.contains("clock.elapsedRealtime() + 9_000L"))
        assertTrue("runtime completion still finishes the broadcast", window.contains("completed.run()"))
        assertTrue("pending intent uses the runtime's authoritative ledger query", window.contains("::hasPendingRestore,"))
    }

    private fun source(path: String): String = listOf(
        File("src/main/java/com/akylas/enforcedoze", path),
        File("app/src/main/java/com/akylas/enforcedoze", path),
    ).first { it.isFile }.readText()
}
