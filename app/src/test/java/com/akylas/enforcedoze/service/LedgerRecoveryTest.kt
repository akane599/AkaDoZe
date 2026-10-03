package com.akylas.enforcedoze.service

import com.akylas.enforcedoze.access.AccessLevel
import com.akylas.enforcedoze.access.Feature
import com.akylas.enforcedoze.doze.Action
import com.akylas.enforcedoze.doze.LedgerEntry
import com.akylas.enforcedoze.doze.RestoreLedger
import com.akylas.enforcedoze.doze.RestoreLedgerCodec
import com.akylas.enforcedoze.doze.SafetyNet
import com.akylas.enforcedoze.doze.SensorMode
import com.akylas.enforcedoze.doze.parse.SensorModeReading
import org.junit.Assert.*
import org.junit.Test

class LedgerRecoveryTest {
    private val token = "com.akylas.enforcedoze"
    private val normal = SensorModeReading(SensorMode.NORMAL, null)
    private val corruptForce = "1|FORCE_DOZE|~|false|broken|0|false|36"

    @Test fun corruptForceLineWithForcedIdleFailsSafeToUnforce() {
        val decoded = RestoreLedgerCodec.decode(corruptForce)
        assertEquals(1, decoded.corruptLines.size)
        assertTrue(decoded.ledger.entries.isEmpty())
        val hasForce = LedgerRecovery.hasForceIntent(decoded.ledger, decoded.corruptLines, false)
        assertEquals(listOf(Action.UNFORCE), SafetyNet.check(normal, true, AccessLevel.SHELL, token, hasForce))
    }

    @Test fun failedLoadWithForcedIdleFailsSafeToUnforce() {
        val hasForce = LedgerRecovery.hasForceIntent(RestoreLedger(), emptyList(), true)
        assertEquals(listOf(Action.UNFORCE), SafetyNet.check(normal, true, AccessLevel.ROOT, token, hasForce))
    }

    @Test fun corruptIntentWithAccessLossRaisesDebtNotUnforce() {
        val decoded = RestoreLedgerCodec.decode(corruptForce)
        val hasForce = LedgerRecovery.hasForceIntent(decoded.ledger, decoded.corruptLines, false)
        assertEquals(listOf(Action.RAISE_DEBT), SafetyNet.check(normal, true, AccessLevel.APP, token, hasForce))
    }

    @Test fun corruptLinesSurviveNextSaveAlongsideValidIntent() {
        val damaged = RestoreLedgerCodec.decode(corruptForce)
        val valid = RestoreLedger(listOf(LedgerEntry(Feature.MOTION_SENSORS, token, "NORMAL", 123, apiLevel = 36)))
        val saved = LedgerRecovery.encodePreservingCorruption(valid, damaged.corruptLines)
        val loaded = RestoreLedgerCodec.decode(saved)
        assertEquals(valid, loaded.ledger)
        assertEquals(listOf(corruptForce), loaded.corruptLines.map { it.line })
    }

    @Test fun damageIsClearableOnlyAfterBothRecoveryReadbacks() {
        assertTrue(LedgerRecovery.recoveryVerified(SensorMode.NORMAL, false))
        assertFalse(LedgerRecovery.recoveryVerified(SensorMode.NORMAL, true))
        assertFalse(LedgerRecovery.recoveryVerified(SensorMode.NORMAL, null))
        assertFalse(LedgerRecovery.recoveryVerified(SensorMode.RESTRICTED, false))
        assertFalse(LedgerRecovery.recoveryVerified(SensorMode.UNVERIFIED, false))
    }

    @Test fun clearingVerifiedDamageDoesNotUnforceFutureUnownedSessions() {
        val damaged = RestoreLedgerCodec.decode(corruptForce)
        assertTrue(LedgerRecovery.hasForceIntent(damaged.ledger, damaged.corruptLines, false))
        assertTrue(LedgerRecovery.recoveryVerified(SensorMode.NORMAL, false))
        val cleared = RestoreLedgerCodec.decode(RestoreLedgerCodec.encode(damaged.ledger))
        val hasForce = LedgerRecovery.hasForceIntent(cleared.ledger, cleared.corruptLines, false)
        assertFalse(hasForce)
        assertTrue(SafetyNet.check(normal, true, AccessLevel.SHELL, token, hasForce).isEmpty())
    }

    @Test fun retainedNonSensorDamageDoesNotOwnRecoveryOnLaterStartup() {
        val retained = RestoreLedgerCodec.decode(listOf(
            "1|WIFI|~|true|broken|0|false|36",
            "1|AIRPLANE|~|false|broken|0|false|36",
            "1|FUTURE_RADIO|~|true|10|0|false|37",
            "unreadable",
        ).joinToString("\n"))
        assertEquals(4, retained.corruptLines.size)
        val hasForce = LedgerRecovery.hasForceIntent(retained.ledger, retained.corruptLines, false)
        assertFalse("Retained damage must not own force recovery", hasForce)
        assertTrue("A later unowned session must not be unforced",
            SafetyNet.check(normal, true, AccessLevel.SHELL, token, hasForce).isEmpty())
    }

    @Test fun healthyForceIntentStillOwnsRecoveryAndEmptyLedgerDoesNot() {
        val ledger = RestoreLedger(listOf(LedgerEntry(Feature.FORCE_DOZE, null, "false", 10, apiLevel = 36)))
        assertTrue(LedgerRecovery.hasForceIntent(ledger, emptyList(), false))
        assertFalse(LedgerRecovery.hasForceIntent(RestoreLedger(), emptyList(), false))
    }
}
