package com.akylas.enforcedoze.service

import android.content.SharedPreferences
import com.akylas.enforcedoze.access.AccessLevel
import com.akylas.enforcedoze.access.Feature
import com.akylas.enforcedoze.access.Prefs
import com.akylas.enforcedoze.doze.DozeEvent
import com.akylas.enforcedoze.doze.DozeEventSink
import com.akylas.enforcedoze.doze.LedgerEntry
import com.akylas.enforcedoze.doze.RestoreLedger
import com.akylas.enforcedoze.doze.RestoreLedgerCodec
import com.akylas.enforcedoze.doze.SafetyNet
import com.akylas.enforcedoze.doze.SensorMode
import com.akylas.enforcedoze.doze.parse.SensorModeReading
import org.junit.Assert.*
import org.junit.Test

/** Exercises the real store with the platform interfaces only; no Context or Robolectric. */
class SharedPrefsLedgerStoreTest {
    private val wifi = "1|WIFI|~|true|broken|0|false|36"
    private val airplane = "1|AIRPLANE|~|false|broken|0|false|36"
    private val motion = "1|MOTION_SENSORS|com.akylas.enforcedoze|NORMAL|broken|0|false|36"
    private val force = "1|FORCE_DOZE|~|false|broken|0|false"
    private val future = "1|FUTURE_RADIO|~|true|10|0|false|37"
    private val unreadable = "unreadable"
    private val retained = listOf(wifi, airplane, future, unreadable)
    private val valid = RestoreLedger(listOf(LedgerEntry(Feature.BLUETOOTH, null, "true", 10, apiLevel = 36)))
    private val events = mutableListOf<DozeEvent>()
    private val sink = DozeEventSink(events::add)

    @Test fun mixedDamageClearsOnlyMotionThenSurvivesStartupAndDurableDismiss() {
        val prefs = MemoryPrefs(mapOf(Prefs.RESTORE_LEDGER to (retained + motion).joinToString("\n")))
        val store = SharedPrefsLedgerStore(prefs, sink)
        assertTrue(store.load().entries.isEmpty())
        assertTrue(LedgerRecovery.recoveryVerified(SensorMode.NORMAL, false))
        store.clearCorruptionAfterRecovery()
        assertEquals(retained, store.corruptLines.map { it.line })
        assertEquals(1, prefs.commits)

        val nextPrefs = prefs.afterProcessRestart()
        val next = SharedPrefsLedgerStore(nextPrefs, sink)
        val ledger = next.load()
        assertEquals(retained, next.corruptLines.map { it.line })
        val hasForce = LedgerRecovery.hasForceIntent(ledger, next.corruptLines, next.loadFailed)
        assertFalse("Retained records do not authorize recovery unforce", hasForce)
        assertTrue("Second startup has no sensor-enable or unforce actions", SafetyNet.check(
            SensorModeReading(SensorMode.NORMAL, null), true, AccessLevel.SHELL,
            "com.akylas.enforcedoze", hasForce,
        ).isEmpty())

        next.save(valid)
        assertEquals("Retained damage does not block valid entries", valid, next.load())
        next.clearRetainedCorruption()
        assertTrue(next.corruptLines.isEmpty())
        val dismissedPrefs = nextPrefs.afterProcessRestart()
        val dismissed = SharedPrefsLedgerStore(dismissedPrefs, sink)
        assertEquals("Dismiss never touches valid entries", valid, dismissed.load())
        assertTrue("Dismiss is durable", dismissed.corruptLines.isEmpty())
        assertFalse(dismissedPrefs.contains(CORRUPT_LINES))
    }

    @Test fun verifiedRecoveryClearsCurrentMotionAndLegacyForceButKeepsValidEntries() {
        val encoded = RestoreLedgerCodec.encode(valid) + "\n" + (retained + motion + force).joinToString("\n")
        val prefs = MemoryPrefs(mapOf(Prefs.RESTORE_LEDGER to encoded))
        val store = SharedPrefsLedgerStore(prefs, sink)
        store.clearCorruptionAfterRecovery()
        val restarted = SharedPrefsLedgerStore(prefs.afterProcessRestart(), sink)
        assertEquals(valid, restarted.load())
        assertEquals(retained, restarted.corruptLines.map { it.line })
        assertTrue(events.any { it.detail == "LEDGER_CORRUPT_RECOVERED_LINES=2" })
    }

    @Test fun dismissBeforeLoadPreservesValidEntriesAndPendingSensorRecovery() {
        val prefs = MemoryPrefs(mapOf(
            Prefs.RESTORE_LEDGER to RestoreLedgerCodec.encode(valid) + "\n" + (retained + motion + force).joinToString("\n"),
        ))
        SharedPrefsLedgerStore(prefs, sink).clearRetainedCorruption()
        val restarted = SharedPrefsLedgerStore(prefs.afterProcessRestart(), sink)
        assertEquals(valid, restarted.load())
        assertEquals(listOf(motion, force), restarted.corruptLines.map { it.line })
        assertTrue(LedgerRecovery.needsRecovery(restarted.corruptLines, false))
    }

    @Test fun retainedKeyAndMainLedgerAreDeduplicatedAcrossSavesAndRecovery() {
        val prefs = MemoryPrefs(mapOf(
            Prefs.RESTORE_LEDGER to "$wifi\n$motion",
            CORRUPT_LINES to "$wifi\n$airplane\n$future\n$unreadable\n$motion",
        ))
        val store = SharedPrefsLedgerStore(prefs, sink)
        store.load()
        assertEquals(5, store.corruptLines.size)
        store.save(valid)
        val restarted = SharedPrefsLedgerStore(prefs.afterProcessRestart(), sink)
        assertEquals(valid, restarted.load())
        assertEquals(5, restarted.corruptLines.size)
        restarted.clearCorruptionAfterRecovery()
        assertEquals(retained.toSet(), restarted.corruptLines.map { it.line }.toSet())
    }

    @Test fun failedRecoveryCommitDoesNotPublishClearedSnapshotOrRecoveryEvent() {
        assertFailedClearKeepsEvidence { it.clearCorruptionAfterRecovery() }
    }

    @Test fun failedDismissCommitDoesNotPublishClearedSnapshot() {
        assertFailedClearKeepsEvidence { it.clearRetainedCorruption() }
    }

    private fun assertFailedClearKeepsEvidence(clear: (SharedPrefsLedgerStore) -> Unit) {
        val original = retained + motion
        val prefs = MemoryPrefs(mapOf(Prefs.RESTORE_LEDGER to original.joinToString("\n")))
        val store = SharedPrefsLedgerStore(prefs, sink)
        store.load()
        prefs.commitSucceeds = false
        try {
            clear(store)
            fail("Failed synchronous commit must throw")
        } catch (_: IllegalStateException) { }
        assertEquals(original, store.corruptLines.map { it.line })
        assertFalse(events.any { it.detail.startsWith("LEDGER_CORRUPT_RECOVERED_LINES=") })
        val restarted = SharedPrefsLedgerStore(prefs.afterProcessRestart(), sink)
        restarted.load()
        assertEquals(original, restarted.corruptLines.map { it.line })
    }

    @Test fun unreadablePreferencesCannotBeOverwrittenByEitherClearApi() {
        val prefs = MemoryPrefs(mapOf(Prefs.RESTORE_LEDGER to 42))
        val store = SharedPrefsLedgerStore(prefs, sink)
        listOf<() -> Unit>(store::clearRetainedCorruption, store::clearCorruptionAfterRecovery).forEach { clear ->
            try {
                clear()
                fail("Unreadable ledger must not be dismissed")
            } catch (_: ClassCastException) { }
        }
        assertTrue(store.loadFailed)
        assertEquals(0, prefs.commits)
        assertEquals(42, prefs.afterProcessRestart().all[Prefs.RESTORE_LEDGER])
    }

    @Test fun repeatedRecoveryWithOnlyRetainedDamageDoesNotCommit() {
        val prefs = MemoryPrefs(mapOf(Prefs.RESTORE_LEDGER to retained.joinToString("\n")))
        val store = SharedPrefsLedgerStore(prefs, sink)
        repeat(2) { store.clearCorruptionAfterRecovery() }
        assertEquals(0, prefs.commits)
        assertEquals(retained, store.corruptLines.map { it.line })
    }

    @Test fun unchangedRetainedDebtIsNotReannouncedAfterProcessRestart() {
        val prefs = MemoryPrefs(mapOf(Prefs.RESTORE_LEDGER to retained.joinToString("\n")))
        val store = SharedPrefsLedgerStore(prefs, sink)
        repeat(3) { store.recordCorruptionDebt() }
        assertEquals(4, events.count { it.detail == "LEDGER_DAMAGED" })
        assertEquals(1, prefs.commits)
        events.clear()
        val restarted = SharedPrefsLedgerStore(prefs.afterProcessRestart(), sink)
        restarted.recordCorruptionDebt()
        assertTrue("Unchanged retained debt is not a startup transition", events.none { it.detail == "LEDGER_DAMAGED" })
    }

    @Test fun durableDismissRearmsIdenticalFutureDamageWithoutTouchingValidEntries() {
        val prefs = MemoryPrefs(mapOf(Prefs.RESTORE_LEDGER to "$wifi\n" + RestoreLedgerCodec.encode(valid)))
        val store = SharedPrefsLedgerStore(prefs, sink)
        store.recordCorruptionDebt()
        store.clearRetainedCorruption()
        assertEquals(valid, store.load())
        val restartedPrefs = prefs.afterProcessRestart()
        restartedPrefs.edit().putString(Prefs.RESTORE_LEDGER, "$wifi\n" + RestoreLedgerCodec.encode(valid)).commit()
        events.clear()
        val restarted = SharedPrefsLedgerStore(restartedPrefs, sink)
        restarted.recordCorruptionDebt()
        assertEquals(1, events.count { it.detail == "LEDGER_DAMAGED" && it.feature == Feature.WIFI })
        assertEquals(valid, restarted.load())
    }

    @Test fun failedDebtBookkeepingCommitStillEmitsFeatureDebtButDoesNotPublishDurableAnnouncement() {
        val prefs = MemoryPrefs(mapOf(Prefs.RESTORE_LEDGER to wifi))
        val store = SharedPrefsLedgerStore(prefs, sink)
        prefs.commitSucceeds = false
        try {
            store.recordCorruptionDebt()
            fail("Debt bookkeeping must be synchronous")
        } catch (_: IllegalStateException) { }
        assertEquals(1, events.count { it.detail == "LEDGER_DAMAGED" && it.feature == Feature.WIFI })
        events.clear()
        val restarted = SharedPrefsLedgerStore(prefs.afterProcessRestart(), sink)
        restarted.recordCorruptionDebt()
        assertEquals("A failed durable announcement must not hide debt on restart", 1,
            events.count { it.detail == "LEDGER_DAMAGED" && it.feature == Feature.WIFI })
    }

    /** Android's memory map changes even on failed commit; durable state does not. */
    private class MemoryPrefs(initial: Map<String, Any?>) : SharedPreferences {
        private val memory = initial.toMutableMap()
        private var durable = initial.toMap()
        var commitSucceeds = true
        var commits = 0
        fun afterProcessRestart() = MemoryPrefs(durable)
        override fun getAll(): Map<String, *> = memory.toMap()
        override fun getString(key: String, defValue: String?): String? =
            if (memory.containsKey(key)) memory[key] as String? else defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = error("Unused")
        override fun getInt(key: String, defValue: Int): Int = error("Unused")
        override fun getLong(key: String, defValue: Long): Long = error("Unused")
        override fun getFloat(key: String, defValue: Float): Float = error("Unused")
        override fun getBoolean(key: String, defValue: Boolean): Boolean = error("Unused")
        override fun contains(key: String): Boolean = memory.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val updates = mutableMapOf<String, Any?>()
            private val removed = mutableSetOf<String>()
            private var clear = false
            override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
                removed.remove(key)
                updates[key] = value
            }
            override fun remove(key: String): SharedPreferences.Editor = apply {
                updates.remove(key)
                removed.add(key)
            }
            override fun clear(): SharedPreferences.Editor = apply { clear = true }
            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = error("Unused")
            override fun putInt(key: String, value: Int): SharedPreferences.Editor = error("Unused")
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = error("Unused")
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = error("Unused")
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = error("Unused")
            override fun commit(): Boolean {
                commits++
                if (clear) memory.clear()
                removed.forEach(memory::remove)
                memory.putAll(updates)
                if (commitSucceeds) durable = memory.toMap()
                return commitSucceeds
            }
            override fun apply() { error("Store must use synchronous commit, never apply") }
        }
    }

    companion object {
        private const val CORRUPT_LINES = "restoreLedgerCorruptLines"
    }
}
