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
 * On a fresh process, supply current API/grants to the constructor (or exit/reconcile); no preferences
 * are needed to recover. Event sinks must not throw.
 */
class DozeController @JvmOverloads constructor(
    private val control: CommandRunner,
    private val catalog: CommandCatalog,
    private val resolver: CapabilityResolver,
    private val store: LedgerStore,
    private val clock: Clock,
    private val sink: DozeEventSink,
    private var apiLevel: Int = 23,
    private var grants: Grants = Grants(false, false),
) {
    private val generation = AtomicLong()
    val currentGeneration: Long get() = generation.get()
    fun bumpGeneration(): Long = generation.incrementAndGet()

    @Synchronized
    fun enter(config: DozeConfig, generation: Long, admission: () -> Boolean): EnterResult {
        apiLevel = config.apiLevel
        grants = config.grants
        val steps = mutableListOf<StepResult>()
        val requests = buildList {
            if (config.restrictSensors) add(Feature.MOTION_SENSORS to config.allowToken)
            if (config.batterySaver) add(Feature.BATTERY_SAVER to null)
            add(Feature.FORCE_DOZE to null)
            config.features.sortedBy { it.ordinal }.forEach { add(it to null) }
            config.appsToSuspend.sorted().forEach { add(Feature.APP_SUSPEND to it) }
            config.packagesToBlockNotifications.sorted().forEach { add(Feature.NOTIFICATION_BLOCK to it) }
        }
        fun admitted(): Boolean = generation == currentGeneration && admission()
        requestsLoop@ for ((feature, target) in requests) {
            if (!admitted()) return EnterResult(EnterStatus.CANCELLED, steps.toList())
            emit(EventType.ENTER_STEP, feature, target)
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
            val commands = catalog.apply(feature, apiLevel, target)
            if (commands == null) {
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
                readValue(feature, target, original = true)
            }
            if (!admitted()) return EnterResult(EnterStatus.CANCELLED, steps.toList())
            // Already-on features belong to their existing owner; do not later undo their state.
            if (prior == null && original != null && original == FeatureReadback.appliedValue(feature)) {
                steps.add(verifyEnter(feature, target))
                if (!admitted()) return EnterResult(EnterStatus.CANCELLED, steps.toList())
                continue
            }
            if (prior == null) {
                ledger = RestoreLedger(ledger.entries + LedgerEntry(feature, target, original, clock.elapsedRealtime()))
                store.save(ledger) // A failure propagates: no command may run without durable intent.
            }
            if (original == null) {
                steps.add(unverified(feature, target))
                continue
            }
            for (command in commands) {
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
                    run(commands.single())
                    if (!admitted()) return EnterResult(EnterStatus.CANCELLED, steps.toList())
                    verified = verifyEnter(feature, target)
                }
            }
            steps.add(verified)
            if (!admitted()) return EnterResult(EnterStatus.CANCELLED, steps.toList())
        }
        return EnterResult(EnterStatus.COMPLETED, steps.toList())
    }

    /** Bump before waiting for the control lane so an active enter stops at its next boundary. */
    @JvmOverloads
    fun exit(apiLevel: Int = this.apiLevel, grants: Grants = this.grants): ExitResult {
        bumpGeneration()
        return synchronized(this) {
            this.apiLevel = apiLevel
            this.grants = grants
            var ledger = store.load()
            val restored = mutableListOf<LedgerEntry>()
            val ordered = ledger.entries.sortedBy {
                when (it.feature) {
                    Feature.MOTION_SENSORS -> 0
                    Feature.FORCE_DOZE -> 1
                    Feature.BATTERY_SAVER -> 2
                    else -> 3
                }
            }
            for (entry in ordered) {
                val unavailable = resolver.status(entry.feature, control.level, apiLevel, grants) as? FeatureStatus.Unavailable
                val commands = restoreCommands(entry)
                var success = false
                var debtReason = unavailable?.reason
                if (unavailable == null && commands != null) {
                    for (command in commands) {
                        val current = resolver.status(entry.feature, control.level, apiLevel, grants) as? FeatureStatus.Unavailable
                        if (current != null) {
                            debtReason = current.reason
                            break
                        }
                        run(command)
                    }
                    if (debtReason == null) {
                        success = verifyRestore(entry)
                        if (!success && entry.feature == Feature.MOTION_SENSORS && lastSensor != SensorMode.UNVERIFIED &&
                            resolver.status(entry.feature, control.level, apiLevel, grants) == FeatureStatus.Available
                        ) {
                            commands.forEach { run(it) }
                            success = verifyRestore(entry)
                        }
                    }
                }
                // A backend can die during the last command/readback, too.
                if (!success && debtReason == null) {
                    debtReason = (resolver.status(entry.feature, control.level, apiLevel, grants) as? FeatureStatus.Unavailable)?.reason
                }
                val updated = ledger.entries.toMutableList()
                val index = updated.indexOf(entry)
                if (success) {
                    updated.removeAt(index)
                } else {
                    updated[index] = entry.copy(
                        attempts = if (entry.attempts == Int.MAX_VALUE) entry.attempts else entry.attempts + 1,
                        debt = debtReason != null,
                    )
                    emit(if (debtReason != null) EventType.RECOVERY_DEBT else EventType.RESTORE_FAILED,
                        entry.feature, entry.target, debtReason ?: Reason.UNVERIFIED)
                }
                val next = RestoreLedger(updated)
                try {
                    store.save(next)
                    ledger = next
                    if (success) restored.add(entry)
                } catch (_: RuntimeException) {
                    // Keep the durable intent and continue restoring the other entries.
                    emit(EventType.ERROR, entry.feature, entry.target, Reason.UNVERIFIED)
                }
            }
            ExitResult(restored.toList(), ledger)
        }
    }

    @JvmOverloads
    fun reconcile(apiLevel: Int = this.apiLevel, grants: Grants = this.grants): ExitResult = exit(apiLevel, grants)

    private var lastDeep: DeepState? = null
    private var lastSensor: SensorMode = SensorMode.UNVERIFIED

    private fun verifyEnter(feature: Feature, target: String?): StepResult {
        val verified = when (feature) {
            Feature.MOTION_SENSORS -> {
                val reading = SensorModeParser.parse(read(feature, target, false))
                lastSensor = reading.mode
                emit(EventType.VERIFY, feature, target, sensor = reading.mode)
                (reading.mode == SensorMode.RESTRICTED && reading.allowToken == target).also {
                    if (it) emit(EventType.SENSORS_RESTRICTED, feature, target, sensor = reading.mode)
                }
            }
            Feature.FORCE_DOZE -> {
                lastDeep = DozeStateParser.parseDeep(read(feature, target, false))
                emit(EventType.VERIFY, feature, target, deep = lastDeep)
                lastDeep == DeepState.IDLE || lastDeep == DeepState.IDLE_MAINTENANCE
            }
            else -> (readValue(feature, target, false) == FeatureReadback.appliedValue(feature)).also {
                emit(EventType.VERIFY, feature, target, if (it) null else Reason.UNVERIFIED)
            }
        }
        return if (verified) StepResult(feature, target, StepStatus.VERIFIED) else unverified(feature, target)
    }

    private fun verifyRestore(entry: LedgerEntry): Boolean {
        val value = readValue(entry.feature, entry.target, original = true)
        val verified = value != null && value == entry.originalValue
        emit(EventType.VERIFY, entry.feature, entry.target, if (verified) null else Reason.UNVERIFIED,
            sensor = if (entry.feature == Feature.MOTION_SENSORS) lastSensor else null)
        if (verified && entry.feature == Feature.MOTION_SENSORS) {
            emit(EventType.SENSORS_RESTORED, entry.feature, entry.target, sensor = SensorMode.NORMAL)
        }
        return verified
    }

    private fun restoreCommands(entry: LedgerEntry): List<String>? {
        val original = entry.originalValue ?: return null
        if (entry.feature !in supported || entry.target != null && !PackageNames.isValid(entry.target)) return null
        return try {
            if (entry.feature == Feature.NOTIFICATION_BLOCK) {
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

    private fun readValue(feature: Feature, target: String?, original: Boolean): String? {
        val output = read(feature, target, original)
        if (feature == Feature.MOTION_SENSORS) lastSensor = SensorModeParser.parse(output).mode
        return FeatureReadback.value(feature, apiLevel, output, target)
    }

    private fun read(feature: Feature, target: String?, original: Boolean): List<String> {
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
    ) = sink.emit(DozeEvent(type, feature?.name ?: type.name, deep = deep, sensor = sensor,
        feature = feature, target = target, reason = reason))

    private fun sameKey(entry: LedgerEntry, feature: Feature, target: String?): Boolean =
        entry.feature == feature && (feature !in setOf(Feature.APP_SUSPEND, Feature.NOTIFICATION_BLOCK) || entry.target == target)

    private companion object {
        val supported = setOf(
            Feature.MOTION_SENSORS, Feature.BATTERY_SAVER, Feature.FORCE_DOZE, Feature.WIFI,
            Feature.MOBILE_DATA, Feature.BLUETOOTH, Feature.AIRPLANE, Feature.LOCATION,
            Feature.BIOMETRICS, Feature.APP_SUSPEND, Feature.NOTIFICATION_BLOCK,
        )
    }
}
