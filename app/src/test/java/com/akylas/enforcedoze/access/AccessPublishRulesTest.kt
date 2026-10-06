package com.akylas.enforcedoze.access

import org.junit.Assert.*
import org.junit.Test

class AccessPublishRulesTest {
    private val grants = Grants(true, false)
    private val absent = ShizukuState(AccessLevel.NONE, Reason.SHIZUKU_NOT_RUNNING, null)

    @Test fun modeChoiceKeepsGrantUidClockOrderAndResolution() {
        val resolution = AccessResolution(); resolution.startDiscovery(0)
        for (mode in listOf(Prefs.MODE_ROOT, Prefs.MODE_SHIZUKU, "app", null)) {
            val reads = mutableListOf<String>()
            val next = nextPublishedAccess(mode, resolution, absent,
                { reads += "grants"; grants }, { reads += "uid"; 10001 }, { reads += "clock"; 1 })
            assertEquals(if (mode == Prefs.MODE_SHIZUKU) listOf("grants", "uid", "clock") else listOf("grants", "uid"), reads)
            assertEquals(grants, next.grants); assertEquals(10001, next.uid)
            assertEquals(AccessLevel.APP, next.level)
            assertEquals(mode != Prefs.MODE_ROOT && mode != Prefs.MODE_SHIZUKU, next.resolved)
        }
        resolution.rootProbeStarted(); resolution.rootProbeFinished(true, false)
        assertEquals(AccessLevel.ROOT, nextPublishedAccess(Prefs.MODE_ROOT, resolution, absent, { grants }, { 10001 }, { error("root has no clock read") }).level)
        val shell = ShizukuState(AccessLevel.SHELL, null, 2000)
        assertEquals(2000, nextPublishedAccess(Prefs.MODE_SHIZUKU, resolution, shell, { grants }, { 10001 }, { 2 }).uid)
    }

    @Test fun equalStateDoesNotPostAndChangedStateUpdatesBeforeDeferredListeners() {
        var state = AccessState(AccessLevel.APP, Reason.NO_ACCESS, grants, 10001)
        val events = mutableListOf<String>()
        val queued = mutableListOf<() -> Unit>()
        val listeners = listOf(
            AccessManager.Listener { assertEquals(state, it); events += "first" },
            AccessManager.Listener { assertEquals(state, it); events += "second" },
        )
        val update: (AccessState) -> Unit = { events += "update"; state = it }
        val post: (AccessState) -> Unit = { next ->
            assertEquals("state is authoritative before main post", state, next)
            events += "post"; queued += { notifyAccessListeners(listeners, next) }
        }
        publishChangedAccess(state, state.copy(), update, post)
        assertTrue("equal publication is suppressed", events.isEmpty())
        val next = state.copy(level = AccessLevel.SHELL, uid = 2000)
        publishChangedAccess(state, next, update, post)
        assertEquals(listOf("update", "post"), events)
        queued.single()()
        assertEquals(listOf("update", "post", "first", "second"), events)
        notifyAccessListeners(emptyList(), next)
    }
}
