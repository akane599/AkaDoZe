package com.akylas.enforcedoze.doze

import com.akylas.enforcedoze.monitor.EventCodes
import com.akylas.enforcedoze.service.SessionMode
import com.akylas.enforcedoze.service.SessionAccess

import com.akylas.enforcedoze.access.AccessLevel
import com.akylas.enforcedoze.access.CapabilityResolver
import com.akylas.enforcedoze.access.CommandCatalog
import com.akylas.enforcedoze.access.CommandResult
import com.akylas.enforcedoze.access.CommandRunner
import com.akylas.enforcedoze.access.Feature
import com.akylas.enforcedoze.access.FeatureStatus
import com.akylas.enforcedoze.access.Grants
import com.akylas.enforcedoze.access.PackageNames
import com.akylas.enforcedoze.access.Reason
import com.akylas.enforcedoze.doze.parse.DozeStateParser
import com.akylas.enforcedoze.doze.parse.SensorModeParser
import java.util.concurrent.atomic.AtomicLong

/**
 * Blocking control-lane core. enter/exit are serialized; bumpGeneration is safe from the exit-trigger
 * thread while a command is in flight. An in-flight effect stays ledger-backed until exit can run.
 * Supply current API/grants at construction; exit/reconcile can refresh them without preferences.
 * Event sinks are diagnostic only; their failures cannot stop control work.
 */
class DozeController @JvmOverloads constructor(
    private val control: CommandRunner,
    private val catalog: CommandCatalog,
    private val resolver: CapabilityResolver,
    private val store: LedgerStore,
    private val clock: Clock,
    private val sink: DozeEventSink,
    private var apiLevel: Int,
    private var grants: Grants,
    private val diagnosticLogger: (String, Throwable) -> Unit = { _, _ -> },
    private val accessResolved: () -> Boolean = { true },
    private val remainingBudgetMs: () -> Long? = { null },
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) {
    private val generation = AtomicLong()
    val currentGeneration: Long get() = generation.get()
    fun bumpGeneration(): Long = generation.incrementAndGet()

    @Synchronized
    fun enter(config: DozeConfig, generation: Long, admission: () -> Boolean): EnterResult =
        enterConfigured(config, generation, admission, core = true)

    @Synchronized
    fun enterCore(config: DozeConfig, generation: Long, admission: () -> Boolean): EnterResult =
        enterConfigured(config.copy(features = emptySet(), appsToSuspend = emptySet(),
            packagesToBlockNotifications = emptySet()), generation, admission, core = true)

    /** Deferred selection cannot reforce; groups still require a fresh verified deep IDLE. */
    @Synchronized
    fun enterGroups(config: DozeConfig, generation: Long, admission: () -> Boolean): EnterResult =
        enterConfigured(config, generation, admission, core = false)

    /** Service boundary for deferred groups; null means the attempt failed, without retrying. */
    fun enterGroupsSafely(
        config: DozeConfig,
        generation: Long,
        admission: () -> Boolean,
        errorDetail: String,
    ): EnterResult? = try {
        enterGroups(config, generation, admission)
    } catch (error: Exception) {
        diagnosticLogger("Deferred group enter failed", error)
        emit(EventType.ERROR, detail = errorDetail)
        null
    }

    private fun enterConfigured(config: DozeConfig, generation: Long, admission: () -> Boolean, core: Boolean): EnterResult {
        apiLevel = config.apiLevel
        grants = config.grants
        val steps = mutableListOf<StepResult>()
        if (!accessResolved() || generation != currentGeneration || !admission()) return EnterResult(EnterStatus.CANCELLED, steps)
        var groupsAdmitted = config.mode == SessionMode.FORCE && !core &&
            DozeStateParser.parseDeep(read(Feature.FORCE_DOZE, null, false)) == DeepState.IDLE
        val requests = buildList {
            if (core) {
                if (config.batterySaver) add(Feature.BATTERY_SAVER to null)
                if (config.restrictSensors) add(Feature.MOTION_SENSORS to config.allowToken)
                add(Feature.FORCE_DOZE to null)
            }
            config.features.sortedBy { it.ordinal }.forEach { add(it to null) }
            config.appsToSuspend.sorted().forEach { add((if (apiLevel >= 24) Feature.APP_SUSPEND else Feature.PM_DISABLE) to it) }
            config.packagesToBlockNotifications.sorted().forEach { add(Feature.NOTIFICATION_BLOCK to it) }
        }.filter { SessionAccess.canRunFeature(config.mode, it.first) }
        fun admitted(): Boolean = accessResolved() && generation == currentGeneration && admission()
        requestsLoop@ for ((feature, target) in requests) {
            if (!admitted()) return EnterResult(EnterStatus.CANCELLED, steps.toList())
            emit(EventType.ENTER_STEP, feature, target)
            if (feature !in setOf(Feature.MOTION_SENSORS, Feature.BATTERY_SAVER, Feature.FORCE_DOZE) && !groupsAdmitted) {
                steps.add(skip(feature, target, Reason.UNVERIFIED))
                continue
            }
            if (feature !in supported || target != null && !PackageNames.isValid(target)) {
                steps.add(skip(feature, target, Reason.UNVERIFIED))
                continue
            }
            val level = minOf(control.level, config.level)
            val unavailable = resolver.status(feature, level, apiLevel, grants) as? FeatureStatus.Unavailable
            if (unavailable != null) {
                steps.add(skip(feature, target, unavailable.reason))
                continue
            }
            val legacyNotification = feature == Feature.NOTIFICATION_BLOCK && apiLevel < 33
            val commands = catalog.apply(feature, apiLevel, target)
            if (commands == null && (!legacyNotification || config.legacyNotificationTransaction == null)) {
                steps.add(skip(feature, target, Reason.UNVERIFIED))
                continue
            }
            var ledger = store.load()
            val prior = ledger.entries.firstOrNull { sameKey(it, feature, target) }
            if (prior?.debt == true) {
                steps.add(skip(feature, target, Reason.UNVERIFIED))
                continue
            }
            val original = if (prior != null) prior.originalValue else {
                readValue(feature, target, original = true)?.let {
                    if (legacyNotification) it + "," + config.legacyNotificationTransaction else it
                }
            }
            val applyCommands = if (legacyNotification) original?.let { legacyNotificationCommands(target, it, false, apiLevel) }
                else commands
            if (!admitted()) return EnterResult(EnterStatus.CANCELLED, steps.toList())
            // Already-on features belong to their existing owner; do not later undo their state.
            if (prior == null && original != null && (if (legacyNotification) original.startsWith("0,") else original == FeatureReadback.appliedValue(feature))) {
                steps.add(verifyEnter(feature, target).copy(alreadyOn = true))
                if (feature == Feature.FORCE_DOZE) groupsAdmitted = lastDeep == DeepState.IDLE
                if (!admitted()) return EnterResult(EnterStatus.CANCELLED, steps.toList())
                continue
            }
            if (original == null || applyCommands == null) {
                steps.add(unverified(feature, target))
                continue
            }
            if (prior == null) {
                ledger = RestoreLedger(ledger.entries + LedgerEntry(
                    feature, target, original, clock.elapsedRealtime(), apiLevel = apiLevel,
                ))
                store.save(ledger) // A failure propagates: no command may run without durable intent.
            }
            for (command in applyCommands) {
                if (!admitted()) return EnterResult(EnterStatus.CANCELLED, steps.toList())
                val nowUnavailable = resolver.status(feature, minOf(control.level, config.level), apiLevel, grants)
                    as? FeatureStatus.Unavailable
                if (nowUnavailable != null) {
                    steps.add(skip(feature, target, nowUnavailable.reason))
                    continue@requestsLoop
                }
                run(command)
            }
            if (!admitted()) return EnterResult(EnterStatus.CANCELLED, steps.toList())
            var verified = verifyEnter(feature, target)
            // Only a known non-idle state earns one retry. OEM/absent output never loops.
            if (feature == Feature.FORCE_DOZE && verified.status == StepStatus.UNVERIFIED && lastDeep != null &&
                lastDeep != DeepState.UNKNOWN
            ) {
                if (!admitted()) return EnterResult(EnterStatus.CANCELLED, steps.toList())
                if (resolver.status(feature, minOf(control.level, config.level), apiLevel, grants) == FeatureStatus.Available) {
                    run(applyCommands.single())
                    if (!admitted()) return EnterResult(EnterStatus.CANCELLED, steps.toList())
                    verified = verifyEnter(feature, target)
                }
            }
            steps.add(verified)
            if (feature == Feature.FORCE_DOZE) groupsAdmitted = verified.status == StepStatus.VERIFIED && lastDeep == DeepState.IDLE
            if (!admitted()) return EnterResult(EnterStatus.CANCELLED, steps.toList())
        }
        return EnterResult(EnterStatus.COMPLETED, steps.toList())
    }

    /** Bump before waiting for the control lane so an active enter stops at its next boundary. */
    @JvmOverloads
    fun exit(
        apiLevel: Int = this.apiLevel,
        grants: Grants = this.grants,
        admission: () -> Boolean = { true },
    ): ExitResult {
        bumpGeneration()
        return restoreLedger(apiLevel, grants, { true }, admission)
    }

    /** Wake the keyguard before USER_PRESENT without undoing the wait-for-unlock session. */
    @Synchronized
    fun restoreBiometrics(generation: Long, admission: () -> Boolean): ExitResult =
        restoreLedger(apiLevel, grants, { it.feature == Feature.BIOMETRICS }) {
            generation == currentGeneration && admission()
        }

    private fun restoreLedger(apiLevel: Int, grants: Grants, selected: (LedgerEntry) -> Boolean,
                              admission: () -> Boolean): ExitResult {
        return synchronized(this) {
            this.apiLevel = apiLevel
            this.grants = grants
            val ledger = loadRestoreLedger()
                ?: return@synchronized ExitResult(emptyList(), RestoreLedger(), listOf(ExitError.LEDGER_LOAD_FAILED))
            restoreEntries(ledger, selected, apiLevel, grants) { restoreAdmitted(admission) }
        }
    }

    private fun restoreAdmitted(admission: () -> Boolean): Boolean {
        return accessResolved() && admission()
    }

    private fun loadRestoreLedger(): RestoreLedger? {
        return try {
            store.load()
        } catch (error: Exception) {
            diagnosticLogger("Restore ledger load failed", error)
            emit(EventType.ERROR, reason = Reason.UNVERIFIED)
            null
        }
    }

    private fun orderedRestoreEntries(ledger: RestoreLedger, selected: (LedgerEntry) -> Boolean): List<LedgerEntry> {
        return ledger.entries.asReversed().filter(selected).sortedBy { restorePriority(it.feature) }
    }

    // Stable core priority, then reverse durable apply order (airplane before radios).
    private fun restorePriority(feature: Feature): Int {
        return when (feature) {
            Feature.MOTION_SENSORS -> 0
            Feature.FORCE_DOZE -> 1
            Feature.BATTERY_SAVER -> 2
            Feature.DOZE_STATE_READ, Feature.TUNABLES, Feature.WIFI, Feature.MOBILE_DATA,
            Feature.BLUETOOTH, Feature.AIRPLANE, Feature.LOCATION, Feature.BIOMETRICS,
            Feature.APP_SUSPEND, Feature.NOTIFICATION_BLOCK, Feature.WHITELIST_EDIT,
            Feature.FOCUSED_APP, Feature.SENSOR_PRIVACY_ALL, Feature.SETPROP_DOZE, Feature.PM_DISABLE -> 3
        }
    }

    private fun restoreEntries(
        ledger: RestoreLedger, selected: (LedgerEntry) -> Boolean, apiLevel: Int, grants: Grants,
        admission: () -> Boolean,
    ): ExitResult {
        var result = ExitResult(emptyList(), ledger)
        var airplaneRestored = false
        for (entry in orderedRestoreEntries(ledger, selected)) {
            if (!admission()) break
            val outcome = restoreEntry(entry, apiLevel, grants, airplaneRestored, admission) ?: break
            if (restoredAirplane(entry, outcome.first)) airplaneRestored = true
            result = recordRestore(result, entry, outcome.first, outcome.second)
        }
        return result
    }

    /** Null means admission was lost at a command boundary: retain the current durable intent. */
    private fun restoreEntry(
        entry: LedgerEntry, apiLevel: Int, grants: Grants, airplaneRestored: Boolean, admission: () -> Boolean,
    ): Pair<Boolean, Reason?>? {
        val entryApi = entry.apiLevel ?: apiLevel
        val unavailable = resolver.status(entry.feature, control.level, entryApi, grants) as? FeatureStatus.Unavailable
        val commands = restoreCommands(entry, entryApi)
        val success = skipUnreadOriginal(entry)
        val outcome = if (unavailable == null && commands != null) {
            restoreAvailableEntry(entry, entryApi, grants, commands, success, airplaneRestored, admission) ?: return null
        } else success to unavailable?.reason
        return admittedRestoreOutcome(refreshRestoreDebt(entry, entryApi, grants, outcome), admission)
    }

    /** The final admission boundary precedes any durable cleanup or failure bookkeeping. */
    private fun admittedRestoreOutcome(outcome: Pair<Boolean, Reason?>, admission: () -> Boolean): Pair<Boolean, Reason?>? {
        if (!admission()) return null
        return outcome
    }

    // Older versions persisted unread originals but never mutated their features.
    private fun skipUnreadOriginal(entry: LedgerEntry): Boolean {
        return (entry.originalValue == null).also {
            if (it) emit(EventType.SKIPPED, entry.feature, entry.target, Reason.UNVERIFIED)
        }
    }

    private fun restoreAvailableEntry(
        entry: LedgerEntry, entryApi: Int, grants: Grants, commands: List<String>, initialSuccess: Boolean,
        airplaneRestored: Boolean, admission: () -> Boolean,
    ): Pair<Boolean, Reason?>? {
        val execution = runRestoreCommands(entry, entryApi, { grants }, commands, admission)
        if (!execution.first) return null
        if (!admission()) return null
        if (execution.second != null) return initialSuccess to execution.second
        return verifyRestoreWithSensorRetry(entry, entryApi, grants, commands, airplaneRestored, admission) to null
    }

    /** The first value marks admission; the second preserves the backend's exact unavailable reason. */
    private fun runRestoreCommands(
        entry: LedgerEntry, entryApi: Int, grants: () -> Grants, commands: List<String>, admission: () -> Boolean,
    ): Pair<Boolean, Reason?> {
        for (command in commands) {
            if (!admission()) return false to null
            val unavailable = resolver.status(entry.feature, control.level, entryApi, grants()) as? FeatureStatus.Unavailable
            if (unavailable != null) return true to unavailable.reason
            run(command)
        }
        return true to null
    }

    private fun verifyRestoreWithSensorRetry(
        entry: LedgerEntry, entryApi: Int, grants: Grants, commands: List<String>, airplaneRestored: Boolean,
        admission: () -> Boolean,
    ): Boolean {
        val success = verifyRestore(entry, entryApi, airplaneRestored, admission)
        if (!shouldRetrySensorRestore(entry, entryApi, grants, success)) return success
        commands.forEach { run(it) }
        return verifyRestore(entry, entryApi, airplaneRestored, admission)
    }

    private fun shouldRetrySensorRestore(entry: LedgerEntry, entryApi: Int, grants: Grants, success: Boolean): Boolean {
        return !success && entry.feature == Feature.MOTION_SENSORS && lastSensor != SensorMode.UNVERIFIED &&
                resolver.status(entry.feature, control.level, entryApi, grants) == FeatureStatus.Available
    }

    // A backend can die during the last command/readback, too.
    private fun refreshRestoreDebt(
        entry: LedgerEntry, entryApi: Int, grants: Grants, outcome: Pair<Boolean, Reason?>,
    ): Pair<Boolean, Reason?> {
        if (outcome.first || outcome.second != null) return outcome
        val unavailable = resolver.status(entry.feature, control.level, entryApi, grants) as? FeatureStatus.Unavailable
        return false to unavailable?.reason
    }

    private fun restoredAirplane(entry: LedgerEntry, success: Boolean): Boolean {
        return success && entry.feature == Feature.AIRPLANE && entry.originalValue != null
    }

    private fun recordRestore(result: ExitResult, entry: LedgerEntry, success: Boolean, reason: Reason?): ExitResult {
        val next = updatedRestoreLedger(result.remaining, entry, success, reason)
        val saved = saveRestoreResult(result, next, entry, success)
        announceRetainedRestore(saved.remaining, entry, success, reason)
        return saved
    }

    private fun updatedRestoreLedger(
        ledger: RestoreLedger, entry: LedgerEntry, success: Boolean, reason: Reason?,
    ): RestoreLedger {
        val updated = ledger.entries.toMutableList()
        val index = updated.indexOf(entry)
        if (success) updated.removeAt(index) else {
            updated[index] = entry.copy(attempts = nextRestoreAttempt(entry), debt = reason != null)
            emit(EventType.RESTORE_FAILED, entry.feature, entry.target, reason ?: Reason.UNVERIFIED)
        }
        return RestoreLedger(updated)
    }

    private fun nextRestoreAttempt(entry: LedgerEntry): Int {
        return if (entry.attempts == Int.MAX_VALUE) entry.attempts else entry.attempts + 1
    }

    private fun saveRestoreResult(result: ExitResult, next: RestoreLedger, entry: LedgerEntry, success: Boolean): ExitResult {
        return try {
                store.save(next)
                result.copy(remaining = next, restored = restoredEntries(result.restored, entry, success))
            } catch (error: Exception) {
                diagnosticLogger("Restore ledger save failed", error)
                // Keep the durable intent and continue restoring the other entries.
                emit(EventType.ERROR, entry.feature, entry.target, Reason.UNVERIFIED,
                    detail = EventCodes.RESTORE_LEDGER_SAVE_FAILED)
                result.copy(errors = result.errors + ExitError.LEDGER_SAVE_FAILED)
            }
    }

    private fun restoredEntries(entries: List<LedgerEntry>, entry: LedgerEntry, success: Boolean): List<LedgerEntry> {
        return if (success && entry.originalValue != null) entries + entry else entries
    }

    private fun announceRetainedRestore(ledger: RestoreLedger, entry: LedgerEntry, success: Boolean, reason: Reason?) {
        if (shouldAnnounceRestore(entry, success, reason) && ledger.entries.any { sameKey(it, entry.feature, entry.target) }) {
            emit(EventType.RECOVERY_DEBT, entry.feature, entry.target, reason ?: Reason.UNVERIFIED)
        }
    }

    private fun shouldAnnounceRestore(entry: LedgerEntry, success: Boolean, reason: Reason?): Boolean {
        // Announce new failures or debt transitions, not every retry/screen cycle.
        // A verified restore with failed durable cleanup must still announce retained intent.
        val newFailure = entry.attempts == 0 && !entry.debt
        val debtChanged = entry.debt != (reason != null)
        return success || newFailure || debtChanged
    }

    @JvmOverloads
    fun reconcile(
        apiLevel: Int = this.apiLevel,
        grants: Grants = this.grants,
        admission: () -> Boolean = { true },
    ): ExitResult = exit(apiLevel, grants, admission)

    /** Temporarily restore/reapply only durable radio entries; never consult current preferences. */
    @Synchronized
    fun maintenance(restore: Boolean, generation: Long, admission: () -> Boolean): EnterResult {
        val steps = mutableListOf<StepResult>()
        val admitted = { maintenanceAdmitted(generation, admission) }
        if (!admitted()) return EnterResult(EnterStatus.CANCELLED, steps)
        val ledger = loadRestoreLedger() ?: return EnterResult(EnterStatus.CANCELLED, steps)
        return maintainEntries(ledger, restore, steps, admitted)
    }

    private fun maintenanceAdmitted(generation: Long, admission: () -> Boolean): Boolean {
        return accessResolved() && generation == currentGeneration && admission()
    }

    private fun maintenanceEntries(ledger: RestoreLedger, restore: Boolean): List<LedgerEntry> {
        val entries = ledger.entries.filter { it.feature in radios }
        return if (restore) entries.asReversed() else entries
    }

    private fun maintainEntries(
        ledger: RestoreLedger, restore: Boolean, steps: MutableList<StepResult>, admission: () -> Boolean,
    ): EnterResult {
        var airplaneRestored = false
        for (entry in maintenanceEntries(ledger, restore)) {
            if (!admission()) return EnterResult(EnterStatus.CANCELLED, steps)
            val result = maintainEntry(entry, restore, airplaneRestored, admission)
            steps.addAll(result.steps)
            if (result.status == EnterStatus.CANCELLED) return EnterResult(EnterStatus.CANCELLED, steps)
            if (maintenanceRestoredAirplane(entry, restore, result)) airplaneRestored = true
        }
        return EnterResult(EnterStatus.COMPLETED, steps)
    }

    private fun maintainEntry(
        entry: LedgerEntry, restore: Boolean, airplaneRestored: Boolean, admission: () -> Boolean,
    ): EnterResult {
        val entryApi = entry.apiLevel ?: apiLevel
        val unavailable = resolver.status(entry.feature, control.level, entryApi, grants) as? FeatureStatus.Unavailable
        if (unavailable != null) return maintenanceSkipped(entry, unavailable.reason)
        val commands = maintenanceCommands(entry, entryApi, restore)
        if (entry.debt || commands == null) return maintenanceSkipped(entry, Reason.UNVERIFIED)
        return executeMaintenanceEntry(entry, entryApi, commands, restore, airplaneRestored, admission)
    }

    private fun maintenanceCommands(entry: LedgerEntry, entryApi: Int, restore: Boolean): List<String>? {
        return if (restore) restoreCommands(entry, entryApi) else catalog.apply(entry.feature, entryApi, entry.target)
    }

    private fun maintenanceSkipped(entry: LedgerEntry, reason: Reason): EnterResult {
        return EnterResult(EnterStatus.COMPLETED, listOf(skip(entry.feature, entry.target, reason)))
    }

    private fun executeMaintenanceEntry(
        entry: LedgerEntry, entryApi: Int, commands: List<String>, restore: Boolean,
        airplaneRestored: Boolean, admission: () -> Boolean,
    ): EnterResult {
        val execution = runRestoreCommands(entry, entryApi, { grants }, commands, admission)
        if (!execution.first) return EnterResult(EnterStatus.CANCELLED, emptyList())
        val lost = execution.second
        if (lost != null) return EnterResult(EnterStatus.CANCELLED, listOf(skip(entry.feature, entry.target, lost)))
        if (!admission()) return EnterResult(EnterStatus.CANCELLED, emptyList())
        val verified = verifyMaintenance(entry, entryApi, restore, airplaneRestored, admission)
        return EnterResult(EnterStatus.COMPLETED, listOf(maintenanceVerdict(entry, restore, verified)))
    }

    private fun verifyMaintenance(
        entry: LedgerEntry, entryApi: Int, restore: Boolean, airplaneRestored: Boolean, admission: () -> Boolean,
    ): Boolean {
        return if (restore) verifyRestore(entry, entryApi, airplaneRestored, admission) else {
            readValue(entry.feature, entry.target, false, entryApi) == FeatureReadback.appliedValue(entry.feature)
        }
    }

    private fun maintenanceVerdict(entry: LedgerEntry, restore: Boolean, verified: Boolean): StepResult {
        val reason = if (verified) null else Reason.UNVERIFIED
        emitMaintenanceVerification(entry, restore, verified, reason)
        return StepResult(entry.feature, entry.target, if (verified) StepStatus.VERIFIED else StepStatus.UNVERIFIED, reason)
    }

    private fun emitMaintenanceVerification(entry: LedgerEntry, restore: Boolean, verified: Boolean, reason: Reason?) {
        if (!restore) emit(EventType.VERIFY, entry.feature, entry.target, reason)
        if (!verified && restore) emit(EventType.RESTORE_FAILED, entry.feature, entry.target, Reason.UNVERIFIED)
    }

    private fun maintenanceRestoredAirplane(entry: LedgerEntry, restore: Boolean, result: EnterResult): Boolean {
        return restore && result.steps.single().status == StepStatus.VERIFIED && entry.feature == Feature.AIRPLANE
    }

    private fun legacyNotificationCommands(target: String?, original: String, enabled: Boolean, apiLevel: Int): List<String>? {
        val values = original.split(',')
        if (values.size != 3 || values[0] !in setOf("0", "1") || target == null) return null
        val uid = values[1].toIntOrNull() ?: return null
        val transaction = values[2].toIntOrNull() ?: return null
        return catalog.legacyNotification(apiLevel, target, uid, transaction, enabled)
    }

    private var lastDeep: DeepState? = null
    private var lastSensor: SensorMode = SensorMode.UNVERIFIED

    private fun verifyEnter(feature: Feature, target: String?): StepResult {
        val verified = when (feature) {
            Feature.MOTION_SENSORS -> {
                val reading = SensorModeParser.parse(read(feature, target, false))
                lastSensor = reading.mode
                (reading.mode == SensorMode.RESTRICTED && reading.allowToken == target).also {
                    emit(EventType.VERIFY, feature, target, if (it) null else Reason.UNVERIFIED, sensor = reading.mode)
                    if (it) emit(EventType.SENSORS_RESTRICTED, feature, target, sensor = reading.mode)
                }
            }
            Feature.FORCE_DOZE -> {
                lastDeep = DozeStateParser.parseDeep(read(feature, target, false))
                (lastDeep == DeepState.IDLE || lastDeep == DeepState.IDLE_MAINTENANCE).also {
                    emit(EventType.VERIFY, feature, target, if (it) null else Reason.UNVERIFIED, deep = lastDeep)
                }
            }
            else -> readValue(feature, target, false).let { value ->
                if (feature == Feature.NOTIFICATION_BLOCK && apiLevel < 33) value?.startsWith("0,") == true
                else value == FeatureReadback.appliedValue(feature)
            }.also {
                emit(EventType.VERIFY, feature, target, if (it) null else Reason.UNVERIFIED)
            }
        }
        return StepResult(feature, target, if (verified) StepStatus.VERIFIED else StepStatus.UNVERIFIED,
            if (verified) null else Reason.UNVERIFIED)
    }

    private fun verifyRestore(
        entry: LedgerEntry,
        apiLevel: Int,
        airplaneRestored: Boolean,
        admission: () -> Boolean,
    ): Boolean {
        val readback = readResult(entry.feature, entry.target, original = true, apiLevel = apiLevel)
        val value = restoredValue(entry, apiLevel, readback)
        val expected = expectedRestoreValue(entry, apiLevel)
        // An initial timeout also resets the shared control lane; settling must not reopen its shell.
        val settled = if (readback?.timedOut == true) value else
            settleRestoreValue(entry, apiLevel, airplaneRestored, value, expected, admission)
        val verified = settled != null && settled == expected
        emitRestoreVerification(entry, verified)
        return verified
    }

    private fun expectedRestoreValue(entry: LedgerEntry, apiLevel: Int): String? {
        return if (entry.feature == Feature.NOTIFICATION_BLOCK && apiLevel < 33)
                entry.originalValue?.substringBeforeLast(',') else entry.originalValue
    }

    private fun settleRestoreValue(
        entry: LedgerEntry, apiLevel: Int, airplaneRestored: Boolean, value: String?, expected: String?,
        admission: () -> Boolean,
    ): String? {
        return if (FeatureReadback.needsAirplaneSettle(entry.feature, airplaneRestored, value, expected)) {
            settleRadioReadback(entry, apiLevel, value, expected, admission)
        } else value
    }

    private fun emitRestoreVerification(entry: LedgerEntry, verified: Boolean) {
        emit(EventType.VERIFY, entry.feature, entry.target, if (verified) null else Reason.UNVERIFIED,
            sensor = if (entry.feature == Feature.MOTION_SENSORS) lastSensor else null)
        if (verified && entry.feature == Feature.MOTION_SENSORS) {
            emit(EventType.SENSORS_RESTORED, entry.feature, entry.target, sensor = SensorMode.NORMAL)
        }
    }

    private fun restoredValue(entry: LedgerEntry, apiLevel: Int, result: CommandResult?): String? {
        val output = if (result?.ok == true) result.stdout else emptyList()
        if (entry.feature == Feature.MOTION_SENSORS) lastSensor = SensorModeParser.parse(output).mode
        return if (entry.feature == Feature.APP_SUSPEND && entry.originalValue == "0") {
            FeatureReadback.restoredSuspensionValue(output, entry.target, control.level)
        } else FeatureReadback.value(entry.feature, apiLevel, output, entry.target)
    }

    /** Three reads only, at most 750ms including waits and read timeouts; never repeat a mutation. */
    private fun settleRadioReadback(
        entry: LedgerEntry,
        apiLevel: Int,
        initial: String?,
        expected: String?,
        admission: () -> Boolean,
    ): String? {
        // The 750ms cap is per radio; a tight shared budget leaves later entries in the ledger.
        val command = catalog.originalValueRead(entry.feature, apiLevel, entry.target) ?: return initial
        // Leave one read-timeout margin in the caller's shared teardown budget.
        if (!settleAdmitted(RADIO_SETTLE_MS + RADIO_READ_MS, admission)) return initial
        val deadline = clock.elapsedRealtime() + RADIO_SETTLE_MS
        return rereadRadio(entry, apiLevel, command, initial, expected, deadline, admission)
    }

    private fun rereadRadio(
        entry: LedgerEntry, apiLevel: Int, command: String, initial: String?, expected: String?,
        deadline: Long, admission: () -> Boolean,
    ): String? {
        var value = initial
        repeat(RADIO_REREADS) {
            if (!awaitRadioRead(deadline, admission)) return value
            val result = run(command, RADIO_READ_MS)
            if (result?.timedOut == true) return value // The shared control lane reset its shell; do not reopen it just to settle.
            value = radioReadValue(entry, apiLevel, result)
            if (value == expected) return value
        }
        return value
    }

    private fun awaitRadioRead(deadline: Long, admission: () -> Boolean): Boolean {
        val available = minOf(deadline - clock.elapsedRealtime(), remainingBudgetMs() ?: Long.MAX_VALUE)
        if (!waitForRadioRead(available, admission)) return false
        return settleAdmitted(RADIO_READ_MS, admission) && deadline - clock.elapsedRealtime() >= RADIO_READ_MS
    }

    private fun radioReadValue(entry: LedgerEntry, apiLevel: Int, result: CommandResult?): String? {
        val output = if (result?.ok == true) result.stdout else emptyList()
        return FeatureReadback.value(entry.feature, apiLevel, output, entry.target)
    }

    private fun settleAdmitted(requiredMs: Long, admission: () -> Boolean): Boolean =
        admission() && (remainingBudgetMs() ?: Long.MAX_VALUE) >= requiredMs

    private fun waitForRadioRead(availableMs: Long, admission: () -> Boolean): Boolean {
        if (availableMs < RADIO_WAIT_MS + RADIO_READ_MS || !admission()) return false
        return try {
            sleeper(RADIO_WAIT_MS)
            true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun restoreCommands(entry: LedgerEntry, apiLevel: Int): List<String>? {
        val original = entry.originalValue ?: return null
        if (entry.feature !in supported || entry.target != null && !PackageNames.isValid(entry.target)) return null
        return try {
            if (entry.feature == Feature.NOTIFICATION_BLOCK && apiLevel < 33) {
                legacyNotificationCommands(entry.target, original, original.startsWith("1,"), apiLevel)
            } else if (entry.feature == Feature.NOTIFICATION_BLOCK) {
                val values = original.split(',')
                val target = entry.target ?: return null
                if (values.size != 3 || values.any { it != "0" && it != "1" }) return null
                catalog.restoreNotification(apiLevel, target, values[0] == "1", values[1] == "1", values[2] == "1")
            } else if (entry.feature == Feature.MOTION_SENSORS && original != "NORMAL") {
                null // Never guess or introduce another restriction during recovery.
            } else {
                catalog.restore(entry.feature, apiLevel, original, entry.target)
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun readValue(
        feature: Feature, target: String?, original: Boolean, apiLevel: Int = this.apiLevel,
        timeoutMs: Long = CommandRunner.DEFAULT_TIMEOUT_MS,
    ): String? {
        val output = read(feature, target, original, apiLevel, timeoutMs)
        if (feature == Feature.MOTION_SENSORS) lastSensor = SensorModeParser.parse(output).mode
        return FeatureReadback.value(feature, apiLevel, output, target)
    }

    private fun read(
        feature: Feature, target: String?, original: Boolean, apiLevel: Int = this.apiLevel,
        timeoutMs: Long = CommandRunner.DEFAULT_TIMEOUT_MS,
    ): List<String> {
        val result = readResult(feature, target, original, apiLevel, timeoutMs)
        return if (result?.ok == true) result.stdout else emptyList()
    }

    private fun readResult(
        feature: Feature, target: String?, original: Boolean, apiLevel: Int = this.apiLevel,
        timeoutMs: Long = CommandRunner.DEFAULT_TIMEOUT_MS,
    ): CommandResult? {
        val command = if (original) catalog.originalValueRead(feature, apiLevel, target)
            else catalog.readback(feature, apiLevel, target)
        return command?.let { run(it, timeoutMs) }
    }

    private fun run(command: String, timeoutMs: Long = CommandRunner.DEFAULT_TIMEOUT_MS): CommandResult? {
        return try {
            control.run(command, timeoutMs)
        } catch (error: RuntimeException) {
            diagnosticLogger("Control command failed", error)
            emit(EventType.ERROR, reason = Reason.UNVERIFIED, detail = EventCodes.CONTROL_RUN_FAILED)
            null
        }
    }

    private fun skip(feature: Feature, target: String?, reason: Reason): StepResult {
        emit(EventType.SKIPPED, feature, target, reason)
        return StepResult(feature, target, StepStatus.SKIPPED, reason)
    }

    private fun unverified(feature: Feature, target: String?): StepResult {
        emit(EventType.VERIFY, feature, target, Reason.UNVERIFIED)
        return StepResult(feature, target, StepStatus.UNVERIFIED, Reason.UNVERIFIED)
    }

    private fun emit(
        type: EventType,
        feature: Feature? = null,
        target: String? = null,
        reason: Reason? = null,
        deep: DeepState? = null,
        sensor: SensorMode? = null,
        detail: String = feature?.name ?: type.name,
    ) {
        try {
            sink.emit(DozeEvent(type, detail, deep = deep, sensor = sensor,
                feature = feature, target = target, reason = reason))
        } catch (error: Exception) {
            // Do not recursively emit into a failed diagnostic sink.
            diagnosticLogger("Doze event sink failed", error)
        }
    }

    private fun sameKey(entry: LedgerEntry, feature: Feature, target: String?): Boolean =
        entry.feature == feature && (feature !in setOf(Feature.APP_SUSPEND, Feature.NOTIFICATION_BLOCK, Feature.PM_DISABLE) || entry.target == target)

    private companion object {
        const val RADIO_REREADS = 3
        const val RADIO_WAIT_MS = 150L
        const val RADIO_READ_MS = 100L
        const val RADIO_SETTLE_MS = RADIO_REREADS * (RADIO_WAIT_MS + RADIO_READ_MS)
        val radios = setOf(Feature.WIFI, Feature.MOBILE_DATA, Feature.BLUETOOTH, Feature.AIRPLANE, Feature.LOCATION)
        val supported = setOf(
            Feature.MOTION_SENSORS, Feature.BATTERY_SAVER, Feature.FORCE_DOZE, Feature.WIFI,
            Feature.MOBILE_DATA, Feature.BLUETOOTH, Feature.AIRPLANE, Feature.LOCATION,
            Feature.BIOMETRICS, Feature.APP_SUSPEND, Feature.NOTIFICATION_BLOCK, Feature.PM_DISABLE,
            Feature.SETPROP_DOZE, Feature.SENSOR_PRIVACY_ALL,
        )
    }
}
