package com.akylas.enforcedoze.doze

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
        var groupsAdmitted = !core && DozeStateParser.parseDeep(read(Feature.FORCE_DOZE, null, false)) == DeepState.IDLE
        val requests = buildList {
            if (core) {
                if (config.batterySaver) add(Feature.BATTERY_SAVER to null)
                if (config.restrictSensors) add(Feature.MOTION_SENSORS to config.allowToken)
                add(Feature.FORCE_DOZE to null)
            }
            config.features.sortedBy { it.ordinal }.forEach { add(it to null) }
            config.appsToSuspend.sorted().forEach { add((if (apiLevel >= 24) Feature.APP_SUSPEND else Feature.PM_DISABLE) to it) }
            config.packagesToBlockNotifications.sorted().forEach { add(Feature.NOTIFICATION_BLOCK to it) }
        }
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
        fun admitted() = accessResolved() && admission()
        return synchronized(this) {
            this.apiLevel = apiLevel
            this.grants = grants
            var ledger = try {
                store.load()
            } catch (_: Exception) {
                emit(EventType.ERROR, reason = Reason.UNVERIFIED)
                return@synchronized ExitResult(emptyList(), RestoreLedger(), listOf(ExitError.LEDGER_LOAD_FAILED))
            }
            val errors = mutableListOf<ExitError>()
            val restored = mutableListOf<LedgerEntry>()
            // Stable core priority, then reverse durable apply order (airplane before radios).
            val ordered = ledger.entries.asReversed().filter(selected).sortedBy {
                when (it.feature) {
                    Feature.MOTION_SENSORS -> 0
                    Feature.FORCE_DOZE -> 1
                    Feature.BATTERY_SAVER -> 2
                    else -> 3
                }
            }
            for (entry in ordered) {
                if (!admitted()) break
                val entryApi = entry.apiLevel ?: apiLevel
                val unavailable = resolver.status(entry.feature, control.level, entryApi, grants) as? FeatureStatus.Unavailable
                val commands = restoreCommands(entry, entryApi)
                // Older versions persisted unread originals but never mutated their features.
                var success = entry.originalValue == null
                if (success) emit(EventType.SKIPPED, entry.feature, entry.target, Reason.UNVERIFIED)
                var debtReason = unavailable?.reason
                if (unavailable == null && commands != null) {
                    for (command in commands) {
                        if (!admitted()) return@synchronized ExitResult(restored.toList(), ledger, errors.toList())
                        val current = resolver.status(entry.feature, control.level, entryApi, grants) as? FeatureStatus.Unavailable
                        if (current != null) {
                            debtReason = current.reason
                            break
                        }
                        run(command)
                    }
                    if (!admitted()) return@synchronized ExitResult(restored.toList(), ledger, errors.toList())
                    if (debtReason == null) {
                        success = verifyRestore(entry, entryApi)
                        if (!success && entry.feature == Feature.MOTION_SENSORS && lastSensor != SensorMode.UNVERIFIED &&
                            resolver.status(entry.feature, control.level, entryApi, grants) == FeatureStatus.Available
                        ) {
                            commands.forEach { run(it) }
                            success = verifyRestore(entry, entryApi)
                        }
                    }
                }
                // A backend can die during the last command/readback, too.
                if (!success && debtReason == null) {
                    debtReason = (resolver.status(entry.feature, control.level, entryApi, grants) as? FeatureStatus.Unavailable)?.reason
                }
                if (!admitted()) break
                val updated = ledger.entries.toMutableList()
                val index = updated.indexOf(entry)
                if (success) {
                    updated.removeAt(index)
                } else {
                    updated[index] = entry.copy(
                        attempts = if (entry.attempts == Int.MAX_VALUE) entry.attempts else entry.attempts + 1,
                        debt = debtReason != null,
                    )
                    emit(EventType.RESTORE_FAILED, entry.feature, entry.target, debtReason ?: Reason.UNVERIFIED)
                }
                val next = RestoreLedger(updated)
                try {
                    store.save(next)
                    ledger = next
                    if (success && entry.originalValue != null) restored.add(entry)
                } catch (_: Exception) {
                    // Keep the durable intent and continue restoring the other entries.
                    errors.add(ExitError.LEDGER_SAVE_FAILED)
                    emit(EventType.ERROR, entry.feature, entry.target, Reason.UNVERIFIED)
                }
                // Announce new failures or debt transitions, not every retry/screen cycle.
                // A verified restore with failed durable cleanup must still announce retained intent.
                val newFailure = entry.attempts == 0 && !entry.debt
                val debtChanged = entry.debt != (debtReason != null)
                if ((success || newFailure || debtChanged) &&
                    ledger.entries.any { sameKey(it, entry.feature, entry.target) }
                ) {
                    emit(EventType.RECOVERY_DEBT, entry.feature, entry.target, debtReason ?: Reason.UNVERIFIED)
                }
            }
            ExitResult(restored.toList(), ledger, errors.toList())
        }
    }

    @JvmOverloads
    fun reconcile(apiLevel: Int = this.apiLevel, grants: Grants = this.grants): ExitResult = exit(apiLevel, grants)

    /** Temporarily restore/reapply only durable radio entries; never consult current preferences. */
    @Synchronized
    fun maintenance(restore: Boolean, generation: Long, admission: () -> Boolean): EnterResult {
        val steps = mutableListOf<StepResult>()
        fun admitted() = accessResolved() && generation == currentGeneration && admission()
        if (!admitted()) return EnterResult(EnterStatus.CANCELLED, steps)
        val ledger = try { store.load() } catch (_: Exception) {
            emit(EventType.ERROR, reason = Reason.UNVERIFIED)
            return EnterResult(EnterStatus.CANCELLED, steps)
        }
        val entries = ledger.entries.filter { it.feature in radios }
        for (entry in if (restore) entries.asReversed() else entries) {
            if (!admitted()) return EnterResult(EnterStatus.CANCELLED, steps)
            val entryApi = entry.apiLevel ?: apiLevel
            val unavailable = resolver.status(entry.feature, control.level, entryApi, grants) as? FeatureStatus.Unavailable
            if (unavailable != null) {
                steps.add(skip(entry.feature, entry.target, unavailable.reason))
                continue
            }
            val commands = if (restore) restoreCommands(entry, entryApi) else catalog.apply(entry.feature, entryApi, entry.target)
            if (entry.debt || commands == null) {
                steps.add(skip(entry.feature, entry.target, Reason.UNVERIFIED))
                continue
            }
            for (command in commands) {
                if (!admitted()) return EnterResult(EnterStatus.CANCELLED, steps)
                val lost = resolver.status(entry.feature, control.level, entryApi, grants) as? FeatureStatus.Unavailable
                if (lost != null) return EnterResult(EnterStatus.CANCELLED, steps + skip(entry.feature, entry.target, lost.reason))
                run(command)
            }
            if (!admitted()) return EnterResult(EnterStatus.CANCELLED, steps)
            val verified = if (restore) verifyRestore(entry, entryApi) else {
                readValue(entry.feature, entry.target, false, entryApi) == FeatureReadback.appliedValue(entry.feature)
            }
            steps.add(StepResult(entry.feature, entry.target,
                if (verified) StepStatus.VERIFIED else StepStatus.UNVERIFIED,
                if (verified) null else Reason.UNVERIFIED))
            if (!restore) emit(EventType.VERIFY, entry.feature, entry.target, if (verified) null else Reason.UNVERIFIED)
            if (!verified && restore) emit(EventType.RESTORE_FAILED, entry.feature, entry.target, Reason.UNVERIFIED)
        }
        return EnterResult(EnterStatus.COMPLETED, steps)
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

    private fun verifyRestore(entry: LedgerEntry, apiLevel: Int): Boolean {
        val value = if (entry.feature == Feature.APP_SUSPEND && entry.originalValue == "0") {
            FeatureReadback.restoredSuspensionValue(
                read(entry.feature, entry.target, original = true, apiLevel = apiLevel), entry.target, control.level,
            )
        } else readValue(entry.feature, entry.target, original = true, apiLevel = apiLevel)
        val expected = if (entry.feature == Feature.NOTIFICATION_BLOCK && apiLevel < 33)
            entry.originalValue?.substringBeforeLast(',') else entry.originalValue
        val verified = value != null && value == expected
        emit(EventType.VERIFY, entry.feature, entry.target, if (verified) null else Reason.UNVERIFIED,
            sensor = if (entry.feature == Feature.MOTION_SENSORS) lastSensor else null)
        if (verified && entry.feature == Feature.MOTION_SENSORS) {
            emit(EventType.SENSORS_RESTORED, entry.feature, entry.target, sensor = SensorMode.NORMAL)
        }
        return verified
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

    private fun readValue(feature: Feature, target: String?, original: Boolean, apiLevel: Int = this.apiLevel): String? {
        val output = read(feature, target, original, apiLevel)
        if (feature == Feature.MOTION_SENSORS) lastSensor = SensorModeParser.parse(output).mode
        return FeatureReadback.value(feature, apiLevel, output, target)
    }

    private fun read(feature: Feature, target: String?, original: Boolean, apiLevel: Int = this.apiLevel): List<String> {
        val command = if (original) catalog.originalValueRead(feature, apiLevel, target)
            else catalog.readback(feature, apiLevel, target)
        val result = command?.let(::run)
        return if (result?.ok == true) result.stdout else emptyList()
    }

    private fun run(command: String): CommandResult? = try {
        control.run(command)
    } catch (_: RuntimeException) {
        emit(EventType.ERROR, reason = Reason.UNVERIFIED)
        null
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
        val radios = setOf(Feature.WIFI, Feature.MOBILE_DATA, Feature.BLUETOOTH, Feature.AIRPLANE, Feature.LOCATION)
        val supported = setOf(
            Feature.MOTION_SENSORS, Feature.BATTERY_SAVER, Feature.FORCE_DOZE, Feature.WIFI,
            Feature.MOBILE_DATA, Feature.BLUETOOTH, Feature.AIRPLANE, Feature.LOCATION,
            Feature.BIOMETRICS, Feature.APP_SUSPEND, Feature.NOTIFICATION_BLOCK, Feature.PM_DISABLE,
            Feature.SETPROP_DOZE, Feature.SENSOR_PRIVACY_ALL,
        )
    }
}
