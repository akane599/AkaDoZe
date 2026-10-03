package com.akylas.enforcedoze.doze

import com.akylas.enforcedoze.access.AccessLevel
import com.akylas.enforcedoze.access.Feature
import com.akylas.enforcedoze.access.Grants
import com.akylas.enforcedoze.access.Reason

interface Clock {
    fun elapsedRealtime(): Long
    fun wallTime(): Long
}

enum class EventType {
    SCREEN_OFF, SCREEN_ON, ENTER_STEP, VERIFY, REFORCE, IDLE_CHANGED, MAINT_START, MAINT_END,
    SENSORS_RESTRICTED, SENSORS_RESTORED, SKIPPED, RESTORE_FAILED, RECOVERY_DEBT,
    ACCESS_CHANGED, EXTERNAL_CALL, ERROR,
}

data class DozeEvent @JvmOverloads constructor(
    val type: EventType,
    val detail: String,
    val deep: DeepState? = null,
    val light: LightState? = null,
    val sensor: SensorMode? = null,
    val feature: Feature? = null,
    val target: String? = null,
    val reason: Reason? = null,
)

fun interface DozeEventSink {
    fun emit(event: DozeEvent)
}

data class DozeConfig @JvmOverloads constructor(
    val apiLevel: Int,
    val level: AccessLevel,
    val grants: Grants,
    val restrictSensors: Boolean = true,
    val allowToken: String = "com.akylas.enforcedoze",
    val batterySaver: Boolean = false,
    val features: Set<Feature> = emptySet(),
    val appsToSuspend: Set<String> = emptySet(),
    val packagesToBlockNotifications: Set<String> = emptySet(),
    val keepDozeEnforced: Boolean = true,
) {
    init {
        require(features.all { it in setOf(Feature.WIFI, Feature.MOBILE_DATA, Feature.BLUETOOTH,
            Feature.AIRPLANE, Feature.LOCATION, Feature.BIOMETRICS) }) { "Unsupported feature group" }
    }
}

enum class StepStatus { VERIFIED, UNVERIFIED, SKIPPED }
enum class EnterStatus { COMPLETED, CANCELLED }

data class StepResult(
    val feature: Feature,
    val target: String?,
    val status: StepStatus,
    val reason: Reason? = null,
)

data class EnterResult(val status: EnterStatus, val steps: List<StepResult>)
data class ExitResult(val restored: List<LedgerEntry>, val remaining: RestoreLedger) {
    val complete: Boolean get() = remaining.entries.isEmpty()
}
