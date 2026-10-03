package com.akylas.enforcedoze.access

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class ExternalCallOutcomeTest {
    @Test fun timeoutTakesPrecedenceOverExitCodeAndCannotClaimDefiniteFailure() {
        for (exitCode in listOf(-1, 0, 1)) {
            val result = CommandResult(exitCode, emptyList(), emptyList(), 100, true)
            assertEquals("a timeout cannot tell whether an admitted command applied", ExternalCallOutcome.UNVERIFIED,
                ExternalCallOutcome.fromCommand(result))
        }
    }

    @Test fun definiteCommandFailureStaysFailedAndExitZeroIsNotReadbackVerification() {
        assertEquals(ExternalCallOutcome.FAILED,
            ExternalCallOutcome.fromCommand(CommandResult(1, emptyList(), listOf("denied"), 0, false)))
        assertEquals("exit zero is only REQUESTED until readback", ExternalCallOutcome.REQUESTED,
            ExternalCallOutcome.fromCommand(CommandResult(0, listOf("ok"), emptyList(), 0, false)))
    }

    @Test fun readbackDistinguishesUnknownFromVerifiedSuccessAndDefiniteMismatch() {
        assertEquals(ExternalCallOutcome.UNVERIFIED, ExternalCallOutcome.fromReadback(null))
        assertEquals(ExternalCallOutcome.VERIFIED, ExternalCallOutcome.fromReadback(true))
        assertEquals("known wrong whitelist membership is a definite failure", ExternalCallOutcome.FAILED,
            ExternalCallOutcome.fromReadback(false))
    }

    @Test(timeout = 5_000)
    fun laneDeadlineAfterAdmissionMapsToUnverifiedEvenWhenMutationHasApplied() {
        val applied = AtomicBoolean()
        val release = CountDownLatch(1)
        val backend = object : CommandBackend {
            override val level = AccessLevel.SHELL
            override fun execute(command: String): CommandResult {
                applied.set(true)
                release.await()
                return CommandResult(0, emptyList(), emptyList(), 0, false)
            }
            override fun reset() { release.countDown() }
        }
        CommandLane(backend).use { lane ->
            val result = lane.runWithDeadline("mutation", System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250)) { true }
            assertTrue("mutation reached the backend before the deadline", applied.get())
            assertTrue(result.timedOut)
            assertEquals("possibly applied mutation is never FAILED", ExternalCallOutcome.UNVERIFIED,
                ExternalCallOutcome.fromCommand(result))
        }
    }
}
