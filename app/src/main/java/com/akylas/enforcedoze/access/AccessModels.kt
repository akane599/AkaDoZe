package com.akylas.enforcedoze.access

import java.util.Collections

enum class AccessLevel {
    NONE, APP, SHELL, ROOT;

    val isPrivileged: Boolean get() = this >= SHELL
}

data class CommandResult(
    val exitCode: Int,
    val stdout: List<String>,
    val stderr: List<String>,
    val durationMs: Long,
    val timedOut: Boolean,
) {
    // Transport success only. Feature effectiveness must be checked by readback.
    val ok: Boolean get() = exitCode == 0 && !timedOut

    companion object {
        @JvmStatic
        fun snapshot(
            exitCode: Int,
            stdout: List<String>,
            stderr: List<String>,
            durationMs: Long,
            timedOut: Boolean,
        ): CommandResult = CommandResult(
            exitCode,
            Collections.unmodifiableList(ArrayList(stdout)),
            Collections.unmodifiableList(ArrayList(stderr)),
            durationMs,
            timedOut,
        )
    }
}

interface CommandRunner {
    val level: AccessLevel
    fun run(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): CommandResult
    fun run(command: String): CommandResult = run(command, DEFAULT_TIMEOUT_MS)

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 8_000
    }
}

enum class Feature {
    FORCE_DOZE, DOZE_STATE_READ, TUNABLES, MOTION_SENSORS, BATTERY_SAVER,
    WIFI, MOBILE_DATA, BLUETOOTH, AIRPLANE, LOCATION, BIOMETRICS, APP_SUSPEND,
    NOTIFICATION_BLOCK, WHITELIST_EDIT, FOCUSED_APP, SENSOR_PRIVACY_ALL,
    SETPROP_DOZE, PM_DISABLE,
}

enum class Reason {
    REQUIRES_ROOT, SHIZUKU_NOT_RUNNING, SHIZUKU_PERMISSION_MISSING, NEEDS_DUMP,
    NEEDS_WRITE_SECURE_SETTINGS, API_TOO_OLD, NOT_EFFECTIVE_ON_THIS_VERSION,
    UNVERIFIED, NO_ACCESS,
}

data class Grants(val dump: Boolean, val writeSecureSettings: Boolean)

sealed interface FeatureStatus {
    data object Available : FeatureStatus
    data class Unavailable(val reason: Reason) : FeatureStatus
}
