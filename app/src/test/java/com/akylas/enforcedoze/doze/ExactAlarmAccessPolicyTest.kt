package com.akylas.enforcedoze.doze

import org.junit.Assert.*
import org.junit.Test

class ExactAlarmAccessPolicyTest {
    @Test fun actualCapabilitySelectsExactOrBestEffortWithoutSuppressingRearm() {
        for (api in listOf(31, 32, 33, 34, 36)) {
            val denied = ExactAlarmAccessPolicy.requery(api, false, true, true)
            val granted = ExactAlarmAccessPolicy.requery(api, true, true, true)
            assertFalse("API $api denial must not report exact access", denied.exactAllowed)
            assertTrue("denial retains the inexact schedule fallback", denied.shouldRearm)
            assertTrue("actual capability, including an allowlist exemption, is authority", granted.exactAllowed)
            assertTrue(granted.shouldRearm)
        }
    }

    @Test fun processStartAfterRevokeRetainsBestEffortBoundaryOnlyForEnabledConfiguredIntent() {
        // F9: Android removes the exact alarm and kills the process on revoke; a listener rebind
        // restarts it without a grant broadcast or foreground return. Only persisted intent survives.
        val beforeRevoke = ExactAlarmAccessPolicy.requery(36, true, true, true)
        assertEquals(ExactAlarmAccessPolicy.Access(true, true), beforeRevoke)
        for ((userEnabled, hasPeriods) in listOf(true to true, false to true, true to false, false to false)) {
            val restarted = ExactAlarmAccessPolicy.requery(36, false, userEnabled, hasPeriods)
            assertFalse("process start must use the revoked capability", restarted.exactAllowed)
            assertEquals("only enabled, configured intent can replace the lost boundary",
                userEnabled && hasPeriods, restarted.shouldRearm)
        }
        val periods = listOf("22:33-22:43")
        assertEquals("a restart before the missed start arms that start, not the current window",
            SchedulePolicy.Boundary(22 * 60 + 33, 0, 1), SchedulePolicy.nextBoundary(periods, 22 * 60 + 32))
    }

    @Test fun api23Through30DoNotRequireSpecialAccess() {
        for (api in 23..30) {
            assertEquals(
                ExactAlarmAccessPolicy.Access(exactAllowed = true, shouldRearm = true),
                ExactAlarmAccessPolicy.requery(api, false, true, true),
            )
        }
    }

    @Test fun masterOffOrNoPeriodsNeverRearmsRegardlessOfExactAccess() {
        for (granted in listOf(false, true)) {
            for ((userEnabled, hasPeriods) in listOf(false to true, true to false, false to false)) {
                val access = ExactAlarmAccessPolicy.requery(36, granted, userEnabled, hasPeriods)
                assertEquals("status must still reflect the actual capability", granted, access.exactAllowed)
                assertFalse("off or unconfigured intent cannot rearm", access.shouldRearm)
            }
        }
    }

    @Test fun duplicateGrantIsIdempotentButEveryCallUsesFreshIntentAndCapability() {
        val first = ExactAlarmAccessPolicy.requery(36, true, true, true)
        val duplicate = ExactAlarmAccessPolicy.requery(36, true, true, true)
        assertEquals("no consumed-grant state may suppress the next rearm", first, duplicate)
        assertTrue(duplicate.shouldRearm)
        assertFalse("grant-then-revoke cannot reuse a cached grant",
            ExactAlarmAccessPolicy.requery(36, false, true, true).exactAllowed)
        assertFalse("a later grant cannot revive master-off",
            ExactAlarmAccessPolicy.requery(36, true, false, true).shouldRearm)
        assertFalse("removed periods cannot be revived by a duplicate grant",
            ExactAlarmAccessPolicy.requery(36, true, true, false).shouldRearm)
    }

    @Test fun grantInsideCurrentWindowOnlyRearmsTheStrictlyFutureBoundary() {
        val access = ExactAlarmAccessPolicy.requery(36, true, true, true)
        val periods = listOf("22:00-06:00")
        assertTrue(access.shouldRearm)
        assertTrue(SchedulePolicy.isInside(periods, 23 * 60))
        assertEquals(SchedulePolicy.Boundary(360, 1, 420), SchedulePolicy.nextBoundary(periods, 23 * 60))
        // Requery returns access/rearm intent only, never an apply-current-window or service-start action.
        assertEquals(ExactAlarmAccessPolicy.Access(true, true), access)
    }
}
