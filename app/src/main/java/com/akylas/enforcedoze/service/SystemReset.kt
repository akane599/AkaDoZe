package com.akylas.enforcedoze.service

import com.akylas.enforcedoze.access.CommandRunner
import com.akylas.enforcedoze.access.PackageNames
import java.util.Collections

enum class ResetRestoreOutcome { COMPLETE, REMAINING_DEBT }

/** Command transport results, not a claim of readback-verified system effectiveness. */
enum class ResetCommandOutcome { OK, FAILED, TIMEOUT, UNVERIFIED }

enum class ResetCommandId {
    DISABLE_DEVICE_IDLE, ENABLE_DEVICE_IDLE, REVOKE_DUMP, REVOKE_READ_LOGS,
    REVOKE_READ_PHONE_STATE, REVOKE_WRITE_SECURE_SETTINGS, REVOKE_WRITE_SETTINGS,
}

data class ResetCommandResult(val id: ResetCommandId, val outcome: ResetCommandOutcome)

data class SystemResetResult(
    val restoreOutcome: ResetRestoreOutcome,
    val commands: List<ResetCommandResult>,
) {
    val complete: Boolean get() = restoreOutcome == ResetRestoreOutcome.COMPLETE &&
        commands.all { it.outcome == ResetCommandOutcome.OK }
}

fun interface SystemResetCallback {
    fun onComplete(result: SystemResetResult)
}

/** Synchronous plan, called only on doze-worker by the runtime. Does not clear any preferences. */
object SystemReset {
    fun run(
        control: CommandRunner,
        apiLevel: Int,
        packageName: String,
        restore: () -> ResetRestoreOutcome,
    ): SystemResetResult {
        val pkg = PackageNames.requireValid(packageName)
        // The restore callback includes ledger readbacks and durable reconciliation before returning.
        val restoreOutcome = try { restore() } catch (_: Exception) { ResetRestoreOutcome.REMAINING_DEBT }
        val suffix = if (apiLevel >= 24) " all" else ""
        val commands = listOf(
            ResetCommandId.DISABLE_DEVICE_IDLE to "dumpsys deviceidle disable$suffix",
            ResetCommandId.ENABLE_DEVICE_IDLE to "dumpsys deviceidle enable$suffix",
            ResetCommandId.REVOKE_DUMP to "pm revoke $pkg android.permission.DUMP",
            ResetCommandId.REVOKE_READ_LOGS to "pm revoke $pkg android.permission.READ_LOGS",
            ResetCommandId.REVOKE_READ_PHONE_STATE to "pm revoke $pkg android.permission.READ_PHONE_STATE",
            ResetCommandId.REVOKE_WRITE_SECURE_SETTINGS to "pm revoke $pkg android.permission.WRITE_SECURE_SETTINGS",
            ResetCommandId.REVOKE_WRITE_SETTINGS to "pm revoke $pkg android.permission.WRITE_SETTINGS",
        ).map { (id, command) ->
            val outcome = if (!SessionAccess.canRunSessions(control.level)) ResetCommandOutcome.UNVERIFIED else try {
                val result = control.run(command)
                when {
                    result.timedOut -> ResetCommandOutcome.TIMEOUT
                    result.ok -> ResetCommandOutcome.OK
                    result.exitCode < 0 -> ResetCommandOutcome.UNVERIFIED
                    else -> ResetCommandOutcome.FAILED
                }
            } catch (_: Exception) { ResetCommandOutcome.UNVERIFIED }
            ResetCommandResult(id, outcome)
        }
        return SystemResetResult(restoreOutcome, Collections.unmodifiableList(commands))
    }
}
