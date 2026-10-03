package com.akylas.enforcedoze.doze

import com.akylas.enforcedoze.doze.parse.DozeStateParser
import org.junit.Assert.*
import org.junit.Test

class ExternalReapplyPolicyTest {
    private val clock = FakeClock(0)
    private val policy = WatchdogPolicy(clock)
    private fun reapply(deep: String = "IDLE", light: String = "OVERRIDE", maintenance: Boolean = false, apiLevel: Int = 36) =
        policy.onExternalReapply(DozeStateParser.parse("mState=$deep mLightState=$light"), maintenance, apiLevel)
    private fun motion() =
        policy.onIdleChanged(DozeStateParser.parse("mState=ACTIVE mLightState=ACTIVE"), false, false, true)
    private fun skipped(reason: ReapplySkip) = Decision.SKIP(reason)

    @Test fun twentyRequestsWithinAMinuteAdmitAtMostOneEnterWithoutDeferring() {
        val decisions = (0 until 20).map {
            clock.elapsed = it * 3_000L
            reapply()
        }
        assertEquals("a burst reserves exactly one enter", 1, decisions.count { it == Decision.REFORCE })
        assertEquals("all other requests are spacing skips, never deferred", 19,
            decisions.count { it == skipped(ReapplySkip.EXTERNAL_REAPPLY_SPACING) })
        clock.elapsed = 60_000
        assertEquals("spacing admits at the exact boundary", Decision.REFORCE, reapply())
    }

    @Test fun ordinaryEnterSpacesOnlyExternalReforcesWithoutSpendingBudget() {
        policy.recordEnter()
        clock.elapsed = 10_000
        assertEquals("external reapply stays spaced from the enter", skipped(ReapplySkip.EXTERNAL_REAPPLY_SPACING), reapply())
        assertEquals("automatic motion reforce is immediate after an ordinary enter", Decision.REFORCE, motion())
        for (attempt in 1 until WatchdogPolicy.MAX_REFORCES) {
            clock.elapsed = 10_000 + attempt * WatchdogPolicy.MIN_INTERVAL_MS
            assertEquals("ordinary enter does not consume a reforce", Decision.REFORCE, reapply())
        }
        clock.elapsed += WatchdogPolicy.MIN_INTERVAL_MS
        assertEquals(skipped(ReapplySkip.EXTERNAL_REAPPLY_BUDGET), reapply())
    }

    @Test fun ordinaryEnterSpacingForExternalEndsAtExactlySixtySeconds() {
        policy.recordEnter()
        for (elapsed in listOf(10_000L, 59_999L)) {
            clock.elapsed = elapsed
            assertEquals(skipped(ReapplySkip.EXTERNAL_REAPPLY_SPACING), reapply())
        }
        clock.elapsed = 60_000
        assertEquals(Decision.REFORCE, reapply())
    }

    @Test fun ordinaryEnterAfterAReforceRestartsSpacingWithoutResettingTheSessionBudget() {
        assertEquals(Decision.REFORCE, reapply())
        clock.elapsed = 10_000
        policy.recordEnter()
        clock.elapsed = 60_000
        assertEquals(skipped(ReapplySkip.EXTERNAL_REAPPLY_SPACING), reapply())
        clock.elapsed = 70_000
        assertEquals(Decision.REFORCE, reapply())
        for (attempt in 1..3) {
            clock.elapsed += WatchdogPolicy.MIN_INTERVAL_MS
            assertEquals(Decision.REFORCE, motion())
        }
        clock.elapsed += WatchdogPolicy.MIN_INTERVAL_MS
        assertEquals(skipped(ReapplySkip.EXTERNAL_REAPPLY_BUDGET), reapply())
    }

    @Test fun externalAndOrdinaryMotionReforcesShareTheFiveAttemptSessionBudget() {
        for (attempt in 0 until WatchdogPolicy.MAX_REFORCES) {
            clock.elapsed = attempt * WatchdogPolicy.MIN_INTERVAL_MS
            assertEquals("both sources reserve from one budget", Decision.REFORCE,
                if (attempt % 2 == 0) reapply() else motion())
        }
        clock.elapsed += WatchdogPolicy.MIN_INTERVAL_MS
        assertEquals("external cannot bypass motion's spent budget", skipped(ReapplySkip.EXTERNAL_REAPPLY_BUDGET), reapply())
        assertEquals("motion cannot bypass external's spent budget", Decision.IGNORE, motion())
        policy.resetSession()
        assertEquals("only a new session replenishes the budget", Decision.REFORCE, reapply())
    }

    @Test fun maintenanceReadingsAndLatchedNonIdleMaintenanceAlwaysSkipWithoutSpendingBudget() {
        assertEquals(skipped(ReapplySkip.EXTERNAL_REAPPLY_MAINTENANCE), reapply(deep = "IDLE_MAINTENANCE"))
        assertEquals(skipped(ReapplySkip.EXTERNAL_REAPPLY_MAINTENANCE), reapply(light = "IDLE_MAINTENANCE"))
        for (deep in DeepState.entries) {
            if (deep != DeepState.IDLE) {
                assertEquals("latched maintenance protects non-idle and unreadable states", skipped(ReapplySkip.EXTERNAL_REAPPLY_MAINTENANCE),
                    reapply(deep = deep.name, light = "ACTIVE", maintenance = true))
            }
        }
        assertEquals("maintenance is owned by the engine until its end transition", skipped(ReapplySkip.EXTERNAL_REAPPLY_MAINTENANCE),
            reapply(maintenance = true))
        for (attempt in 0 until WatchdogPolicy.MAX_REFORCES) {
            clock.elapsed = attempt * WatchdogPolicy.MIN_INTERVAL_MS
            assertEquals("maintenance skips leave the budget untouched", Decision.REFORCE, reapply())
        }
    }

    @Test fun unknownDeepNeverAuthorizesAnExternalEnter() {
        for (deep in listOf("UNKNOWN", "OEM", "")) {
            assertEquals(skipped(ReapplySkip.EXTERNAL_REAPPLY_STATE_UNKNOWN), reapply(deep = deep))
        }
        assertEquals(skipped(ReapplySkip.EXTERNAL_REAPPLY_STATE_UNKNOWN),
            policy.onExternalReapply(DozeStateParser.parse("mLightState=IDLE"), false, 36))
        assertEquals("unknown skips do not reserve spacing", Decision.REFORCE, reapply())
    }

    @Test fun unknownLightRejectsNonIdleDeepOnApi24AndLater() {
        for (apiLevel in listOf(24, 36)) {
            for (deep in listOf("ACTIVE", "INACTIVE", "IDLE_PENDING", "SENSING", "LOCATING")) {
                assertEquals("unreadable light state cannot authorize cutting a possible maintenance window",
                    skipped(ReapplySkip.EXTERNAL_REAPPLY_STATE_UNKNOWN),
                    reapply(deep = deep, light = "UNKNOWN", apiLevel = apiLevel))
            }
            assertEquals("missing light is also unverified", skipped(ReapplySkip.EXTERNAL_REAPPLY_STATE_UNKNOWN),
                policy.onExternalReapply(DozeStateParser.parse("mState=INACTIVE"), false, apiLevel))
        }
        assertEquals("unknown light rejection does not spend budget", Decision.REFORCE, reapply())
    }

    @Test fun api23DoesNotRejectForItsUnavailableLightState() {
        assertEquals(Decision.REFORCE, reapply(deep = "INACTIVE", light = "UNKNOWN", apiLevel = 23))
    }

    @Test fun verifiedDeepIdleDoesNotRequireAKnownLightState() {
        assertEquals(Decision.REFORCE, reapply(light = "UNKNOWN"))
    }

    @Test fun precheckNeverReservesAndFinalAdmissionRechecksSpacingAndBudget() {
        repeat(20) { assertNull("passing pre-checks spend neither spacing nor budget", policy.precheckExternalReapply()) }
        policy.recordEnter()
        assertEquals("an enter after a passing pre-check still vetoes reservation",
            skipped(ReapplySkip.EXTERNAL_REAPPLY_SPACING), reapply())
        assertEquals(ReapplySkip.EXTERNAL_REAPPLY_SPACING, policy.precheckExternalReapply())
        clock.elapsed = 60_000
        for (attempt in 0 until WatchdogPolicy.MAX_REFORCES) {
            assertNull(policy.precheckExternalReapply())
            assertEquals(Decision.REFORCE, motion())
            assertEquals("a competing reforce after the pre-check is authoritative",
                skipped(if (attempt == WatchdogPolicy.MAX_REFORCES - 1) ReapplySkip.EXTERNAL_REAPPLY_BUDGET
                    else ReapplySkip.EXTERNAL_REAPPLY_SPACING), reapply())
            clock.elapsed += WatchdogPolicy.MIN_INTERVAL_MS
        }
        repeat(20) {
            assertEquals(ReapplySkip.EXTERNAL_REAPPLY_BUDGET, policy.precheckExternalReapply())
        }
        assertEquals("pre-checks do not replenish the budget", skipped(ReapplySkip.EXTERNAL_REAPPLY_BUDGET), reapply())
    }

    @Test fun resetSessionClearsOrdinaryEnterSpacing() {
        policy.recordEnter()
        assertEquals(ReapplySkip.EXTERNAL_REAPPLY_SPACING, policy.precheckExternalReapply())
        policy.resetSession()
        assertNull(policy.precheckExternalReapply())
        assertEquals(Decision.REFORCE, reapply())
    }

    @Test fun externalSpacingSkipDoesNotStealTheAutomaticDeferredCallback() {
        assertEquals(Decision.REFORCE, motion())
        clock.elapsed = 1
        assertEquals(skipped(ReapplySkip.EXTERNAL_REAPPLY_SPACING), reapply())
        assertEquals("external skips do not schedule or claim automatic retries", Decision.DEFER(60_000), motion())
        assertEquals(Decision.IGNORE, motion())
        clock.elapsed = 60_000
        assertEquals(Decision.REFORCE, motion())
        assertEquals("an ordinary motion reforce also spaces external requests", skipped(ReapplySkip.EXTERNAL_REAPPLY_SPACING), reapply())
    }
}
