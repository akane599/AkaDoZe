package com.akylas.enforcedoze.service

import com.akylas.enforcedoze.doze.DeepState
import com.akylas.enforcedoze.doze.LightState
import org.junit.Assert.*
import org.junit.Test

class SessionLifecycleTest {
    @Test fun onlyEpochCheckedActivationCanStartADeactivatedSession() {
        val session = SessionLifecycle()
        assertTrue(session.activate(3, { 3 }, { false }))
        session.deactivate()
        assertFalse(session.active)
        assertFalse(session.activate(3, { 4 }, { false }))
        assertTrue(session.activate(4, { 4 }, { false }))
        assertFalse(SessionLifecycle::class.java.methods.any { it.name == "setActive" })
    }

    @Test fun chargerOnScreenWithNoSessionCannotUndoUserAirplaneMode() {
        assertFalse(SessionLifecycle.shouldExit(true, true, false, true))
        assertFalse(SessionLifecycle.shouldExit(true, false, false, true))
        assertTrue(SessionLifecycle.shouldExit(true, false, true, true))
        assertFalse(SessionLifecycle.shouldExit(true, false, true, false))
    }

    @Test fun exitBetweenAdmissionCheckAndActivationLeavesSessionInactive() {
        val session = SessionLifecycle()
        var epoch = 0L
        assertFalse(session.activate(0, { epoch }, {
            // Receiver invalidates between the last admission read and the worker's set.
            epoch++
            session.deactivate()
            false
        }))
        assertFalse(session.active)
    }

    @Test fun enterOnlyOnFirstVerifiedIdleIncludingLaterReforceAndExitOnlyOnce() {
        val session = SessionLifecycle()
        assertFalse(session.recordExit())
        assertFalse(session.recordEnter(false))
        assertFalse(session.recordExit())
        assertTrue(session.recordEnter(true))
        assertFalse(session.recordEnter(true))
        assertTrue(session.recordExit())
        assertFalse(session.recordExit())
    }

    @Test fun deepAndLightMaintenanceTransitionsRequireKnownReadback() {
        assertEquals(true, SessionLifecycle.maintenanceState(DeepState.IDLE_MAINTENANCE, LightState.OVERRIDE))
        assertEquals(true, SessionLifecycle.maintenanceState(DeepState.INACTIVE, LightState.IDLE_MAINTENANCE))
        assertEquals(false, SessionLifecycle.maintenanceState(DeepState.IDLE, LightState.OVERRIDE))
        assertEquals(false, SessionLifecycle.maintenanceState(DeepState.INACTIVE, LightState.IDLE))
        assertNull(SessionLifecycle.maintenanceState(DeepState.UNKNOWN, LightState.IDLE))
        assertNull(SessionLifecycle.maintenanceState(null, LightState.UNKNOWN))
        assertNull(SessionLifecycle.maintenanceState(DeepState.INACTIVE, LightState.UNKNOWN))
    }

    @Test fun teardownCannotWaitPastInputAnrThresholdAndCommandsShareBudget() {
        assertTrue(SessionLifecycle.TEARDOWN_WAIT_MS <= 4_000)
        assertTrue(SessionLifecycle.TEARDOWN_COMMAND_MS < SessionLifecycle.TEARDOWN_WAIT_MS)
    }
}
