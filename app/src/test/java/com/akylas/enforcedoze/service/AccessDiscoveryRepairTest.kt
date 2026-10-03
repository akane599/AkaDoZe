package com.akylas.enforcedoze.service

import com.akylas.enforcedoze.access.*
import com.akylas.enforcedoze.doze.*
import org.junit.Assert.*
import org.junit.Test

class AccessDiscoveryRepairTest {
    private val grants = Grants(false, false)
    private val absent = ShizukuState(AccessLevel.NONE, Reason.SHIZUKU_NOT_RUNNING, null)

    @Test fun shizukuDeathAfterResolvedPrivilegeIsResolvedNoAccess() {
        val resolution = AccessResolution()
        assertEquals(AccessLevel.SHELL, resolution.shizuku(ShizukuState(AccessLevel.SHELL, null, 2000), grants, 10001, 0).level)
        val dead = resolution.shizuku(absent, grants, 10001, 1)
        assertTrue("binder death is not discovery", dead.resolved)
        assertEquals(AccessLevel.APP, dead.level)
    }

    @Test fun rootProbeTimeoutAfterBudgetSettlesNoAccess() {
        val resolution = AccessResolution()
        repeat(4) { attempt ->
            resolution.rootProbeStarted()
            resolution.rootProbeFinished(false, true)
            if (attempt < 3) assertFalse(resolution.root(grants, 10001).resolved)
        }
        val exhausted = resolution.root(grants, 10001)
        assertTrue("initial probe plus three retries exhaust discovery", exhausted.resolved)
        assertEquals(AccessLevel.APP, exhausted.level)
        assertEquals(Reason.NO_ACCESS, exhausted.reason)
    }

    @Test fun shizukuColdDiscoveryExpiresWithoutBinder() {
        val resolution = AccessResolution()
        resolution.startDiscovery(500)
        assertFalse(resolution.shizuku(absent, grants, 10001, 10_499).resolved)
        assertTrue("ten second elapsed window settles no access", resolution.shizuku(absent, grants, 10001, 10_500).resolved)
    }

    @Test fun resolvedNoAccessEmitsAccessChangedAndAccessLostDebt() {
        val resolution = AccessResolution()
        resolution.shizuku(ShizukuState(AccessLevel.SHELL, null, 2000), grants, 10001, 0)
        val state = resolution.shizuku(absent, grants, 10001, 1)
        val store = InMemoryLedgerStore()
        store.save(RestoreLedger(listOf(LedgerEntry(Feature.APP_SUSPEND, "com.example.app", "0", 0))))
        val events = mutableListOf<DozeEvent>()
        val sink = DozeEventSink { events += it }
        AccessRecovery.announce(state, sink, Runnable {
            if (AccessRecovery.hasShellDebt(store.load(), 36)) sink.emit(DozeEvent(EventType.RECOVERY_DEBT, "ACCESS_LOST"))
        })
        assertTrue("paused notice and timeline use ACCESS_CHANGED", events.any { it.type == EventType.ACCESS_CHANGED && it.detail == "APP NO_ACCESS" })
        assertTrue("pending privileged intent records ACCESS_LOST", events.any { it.type == EventType.RECOVERY_DEBT && it.detail == "ACCESS_LOST" })
    }

    @Test fun unresolvedRestoreTimeoutThenLaterReadyRequestsExactlyOneMoreRestore() {
        val fixture = WindowFixture()
        fixture.start()
        fixture.timeout()
        assertEquals(1, fixture.finished)
        fixture.access.publish(fixture.access.state.copy(resolved = true, level = AccessLevel.SHELL))
        repeat(3) { fixture.access.publish(fixture.access.state) }
        assertEquals("late readiness has one continuation", 1, fixture.retries)
        assertEquals("continuation actually restores", 1, fixture.restores)
        assertTrue("ready completion drops its listener", fixture.access.listeners.isEmpty())
    }

    @Test fun retainedNonRecoverableDamageDoesNotBuildRuntime() {
        val retained = "1|APP_SUSPEND|broken\n1|LOCATION|broken\nunknown"
        var builds = 0
        val pending = BootRestorePolicy.shouldRestore(false, retained, retained)
        if (pending) builds++ // The receivers gate getDozeRuntime on this exact policy.
        assertFalse("non-recoverable damage cannot be restored", pending)
        assertEquals(0, builds)
        assertTrue(BootRestorePolicy.shouldRestore(false, "", "1|FORCE_DOZE|broken"))
        assertTrue(BootRestorePolicy.shouldRestore(false, "", "1|MOTION_SENSORS|broken"))
    }

    @Test fun rootRefreshDoesNotWithdrawResolvedPrivilege() {
        val resolution = AccessResolution()
        resolution.rootProbeStarted()
        resolution.rootProbeFinished(true, false)
        val before = resolution.root(grants, 10001)
        resolution.rootProbeStarted()
        assertEquals("resume probe must not publish an artificial access change", before, resolution.root(grants, 10001))
        resolution.rootProbeFinished(false, true)
        assertTrue("loss after completed discovery is resolved", resolution.root(grants, 10001).resolved)
    }

    @Test fun lateNoAccessAnnouncesOnceAndStillRestoresWhenReady() {
        val fixture = WindowFixture()
        fixture.start()
        fixture.timeout()
        fixture.access.publish(fixture.access.state.copy(resolved = true))
        fixture.access.publish(fixture.access.state)
        assertEquals(1, fixture.debts)
        fixture.access.publish(fixture.access.state.copy(level = AccessLevel.SHELL))
        fixture.access.publish(fixture.access.state)
        assertEquals(1, fixture.retries)
    }

    @Test fun continuationCannotArmAnotherRestoreWindow() {
        val fixture = WindowFixture()
        fixture.start(allowContinuation = false)
        fixture.timeout()
        fixture.access.publish(fixture.access.state.copy(resolved = true, level = AccessLevel.SHELL))
        assertEquals("follow-up is terminal even if discovery races its timeout", 0, fixture.retries)
        assertTrue(fixture.access.listeners.isEmpty())
    }

    private class FakeAccess : RecoveryAccess {
        override var state = AccessState(AccessLevel.APP, Reason.NO_ACCESS, Grants(false, false), 10001, resolved = false)
        val listeners = linkedSetOf<AccessManager.Listener>()
        override fun addListener(listener: AccessManager.Listener) { listeners += listener; listener.onAccessChanged(state) }
        override fun removeListener(listener: AccessManager.Listener) { listeners -= listener }
        fun publish(next: AccessState) { state = next; listeners.toList().forEach { it.onAccessChanged(next) } }
    }

    private class WindowFixture {
        val access = FakeAccess()
        var now = 0L
        var finished = 0
        var retries = 0
        var debts = 0
        var restores = 0
        lateinit var timeout: () -> Unit
        fun start(allowContinuation: Boolean = true) {
            RestoreOnlyRequest(access, { now }, { it() }, { deadline, callback ->
                timeout = { now = deadline; callback() }; ({})
            }, { _, done -> restores++; done() }, { finished++ }, { retries++; start(false) }, { debts++ }, allowContinuation).start()
        }
    }
}
