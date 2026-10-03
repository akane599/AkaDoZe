package com.akylas.enforcedoze.doze

import com.akylas.enforcedoze.doze.parse.DozeStateParser
import org.junit.Assert.*
import org.junit.Test

class ExternalReapplyPolicyTest {
    private val clock = FakeClock(0)
    private val policy = WatchdogPolicy(clock)
    private fun reapply(deep: String = "IDLE", light: String = "OVERRIDE", maintenance: Boolean = false) =
        policy.onExternalReapply(DozeStateParser.parse("mState=$deep mLightState=$light"), maintenance)
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

    @Test fun ordinaryEnterStartsSpacingForExternalAndAutomaticReforcesWithoutSpendingBudget() {
        policy.recordEnter()
        clock.elapsed = 59_999
        assertEquals(skipped(ReapplySkip.EXTERNAL_REAPPLY_SPACING), reapply())
        assertEquals(Decision.DEFER(60_000), motion())
        for (attempt in 1..WatchdogPolicy.MAX_REFORCES) {
            clock.elapsed = attempt * WatchdogPolicy.MIN_INTERVAL_MS
            assertEquals("ordinary enter does not consume a reforce", Decision.REFORCE, reapply())
        }
        clock.elapsed += WatchdogPolicy.MIN_INTERVAL_MS
        assertEquals(skipped(ReapplySkip.EXTERNAL_REAPPLY_BUDGET), reapply())
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
            policy.onExternalReapply(DozeStateParser.parse("mLightState=IDLE"), false))
        assertEquals("unknown skips do not reserve spacing", Decision.REFORCE, reapply())
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
