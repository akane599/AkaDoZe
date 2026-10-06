package com.akylas.enforcedoze.service

import org.junit.Assert.*
import org.junit.Test

class BootRestorePolicyTest {
    @Test fun emptyLedgerNeverInvokesRestore() {
        assertFalse(BootRestorePolicy.restoreIfPending("", "", Runnable { fail("empty ledger built runtime") }))
    }

    @Test fun pendingLedgerInvokesRestoreExactlyOnce() {
        var restores = 0
        assertTrue(BootRestorePolicy.restoreIfPending("1|BIOMETRICS|~|1|0|0|false|36", "", Runnable { restores++ }))
        assertEquals("valid intent admits one runtime callback", 1, restores)
    }

    @Test fun recoverableDamageInEitherSnapshotInvokesRestore() {
        var restores = 0
        assertTrue(BootRestorePolicy.restoreIfPending("1|FORCE_DOZE|broken", "", Runnable { restores++ }))
        assertTrue(BootRestorePolicy.restoreIfPending("", "1|MOTION_SENSORS|broken", Runnable { restores++ }))
        assertEquals("encoded and retained recoverable damage both admit recovery", 2, restores)
    }

    @Test fun nonRecoverableDamageInEitherSnapshotNeverInvokesRestore() {
        val damage = "1|APP_SUSPEND|broken\n1|LOCATION|broken\nunknown"
        val restore = Runnable { fail("non-recoverable damage built runtime") }
        assertFalse(BootRestorePolicy.restoreIfPending(damage, "", restore))
        assertFalse(BootRestorePolicy.restoreIfPending("", damage, restore))
        assertFalse(BootRestorePolicy.restoreIfPending(damage, damage, restore))
    }

    @Test fun unreadableSnapshotsInvokeRestoreRatherThanDiscardUnknownIntent() {
        var restores = 0
        val restore = Runnable { restores++ }
        assertTrue(BootRestorePolicy.restoreIfPending(null, null, restore))
        assertTrue(BootRestorePolicy.restoreIfPending(null, "", restore))
        assertTrue(BootRestorePolicy.restoreIfPending("", null, restore))
        assertEquals("read failure must not masquerade as an empty ledger", 3, restores)
    }

    @Test fun admittedCallbackFailurePropagatesWithoutRetry() {
        val failure = IllegalStateException("runtime construction failed")
        var restores = 0
        try {
            BootRestorePolicy.restoreIfPending(null, null, Runnable { restores++; throw failure })
            fail("runtime failure must propagate")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
        assertEquals("callback failure is not retried as a snapshot read failure", 1, restores)
    }
}
