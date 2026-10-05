package com.akylas.enforcedoze.service

import com.akylas.enforcedoze.access.CommandRunner
import com.akylas.enforcedoze.access.PackageNames
import java.util.Collections

enum class ResetRestoreOutcome { COMPLETE, REMAINING_DEBT }

/** OK requires a confirming readback; transport success alone is never sufficient. */
enum class ResetCommandOutcome { OK, FAILED, TIMEOUT, UNVERIFIED }

enum class ResetCommandId {
    DISABLE_DEVICE_IDLE, ENABLE_DEVICE_IDLE, REVOKE_DUMP, REVOKE_READ_LOGS,
    REVOKE_READ_PHONE_STATE, REVOKE_WRITE_SECURE_SETTINGS, REVOKE_WRITE_SETTINGS,
}

data class ResetCommandResult(val id: ResetCommandId, val outcome: ResetCommandOutcome)

/**
 * [commands] are the steps that ran. [deferred] steps have not run: revoking a runtime permission this app
 * holds makes Android kill its process, so they wait for [SystemReset.runDeferred] after the user has seen
 * this result. They are never counted as OK, so [complete] covers only the steps that ran; the dialog
 * does not call the reset finished while [deferred] is non-empty.
 */
data class SystemResetResult @JvmOverloads constructor(
    val restoreOutcome: ResetRestoreOutcome,
    val commands: List<ResetCommandResult>,
    val deferred: List<ResetCommandId> = emptyList(),
    /** The job threw before a result was known: preserve all preferences and allow a retry. */
    val failed: Boolean = false,
) {
    val complete: Boolean get() = !failed && restoreOutcome == ResetRestoreOutcome.COMPLETE &&
        commands.all { it.outcome == ResetCommandOutcome.OK }
}

fun interface SystemResetCallback {
    fun onComplete(result: SystemResetResult)
}

/** Synchronous plan, called only on doze-worker by the runtime. Does not clear any preferences. */
object SystemReset {
    /** Pure boundary for the runtime's whole worker job, before result delivery. */
    fun runJob(job: () -> SystemResetResult): SystemResetResult = try {
        job()
    } catch (_: Exception) {
        failedResult()
    }

    private fun failedResult() = SystemResetResult(
        ResetRestoreOutcome.REMAINING_DEBT, emptyList(), failed = true,
    )

    fun run(
        control: CommandRunner,
        apiLevel: Int,
        packageName: String,
        // Own-app platform permission check: true = granted, false = denied, null = unknown.
        permissionGranted: (String) -> Boolean? = { null },
        restore: () -> ResetRestoreOutcome,
    ): SystemResetResult {
        val pkg = PackageNames.requireValid(packageName)
        // The restore callback includes ledger readbacks and durable reconciliation before returning.
        val restoreOutcome = try { restore() } catch (_: Exception) { return failedResult() }
        val sessions = SessionAccess.canRunSessions(control.level)
        val (deferred, now) = commands(apiLevel, pkg).partition { (id, command) ->
            // Not granted: Android skips the revoke without killing, so it runs (and is checked) in place.
            sessions && id in PROCESS_KILLING &&
                (try { permissionGranted(command.substringAfterLast(' ')) } catch (_: Exception) { null }) != false
        }
        val results = now.map { (id, command) ->
            val outcome = if (!sessions) ResetCommandOutcome.UNVERIFIED else try {
                val result = control.run(command)
                when {
                    result.timedOut -> ResetCommandOutcome.TIMEOUT
                    result.ok -> when (id) {
                        ResetCommandId.DISABLE_DEVICE_IDLE -> verifyDeviceIdle(control, apiLevel, false)
                        ResetCommandId.ENABLE_DEVICE_IDLE -> verifyDeviceIdle(control, apiLevel, true)
                        ResetCommandId.REVOKE_WRITE_SETTINGS -> verifyWriteSettings(control, pkg)
                        else -> verifiedOutcome(permissionGranted(command.substringAfterLast(' '))?.not())
                    }
                    result.exitCode < 0 -> ResetCommandOutcome.UNVERIFIED
                    else -> ResetCommandOutcome.FAILED
                }
            } catch (_: Exception) { ResetCommandOutcome.UNVERIFIED }
            ResetCommandResult(id, outcome)
        }
        return SystemResetResult(restoreOutcome, Collections.unmodifiableList(results),
            Collections.unmodifiableList(deferred.map { it.first }))
    }

    /**
     * doze-worker only, after the user confirmed the reported result and preferences were cleared. Android
     * normally kills this process during the first revoke, so nothing here is reported or read back.
     */
    fun runDeferred(control: CommandRunner, apiLevel: Int, packageName: String, deferred: List<ResetCommandId>) {
        val pkg = PackageNames.requireValid(packageName)
        for ((id, command) in commands(apiLevel, pkg)) {
            if (id !in deferred || id !in PROCESS_KILLING) continue
            if (!SessionAccess.canRunSessions(control.level)) return
            try { control.run(command) } catch (_: Exception) {}
        }
    }

    /** Revokes of dangerous (runtime) permissions: when one is granted, revoking it kills this app's uid. */
    private val PROCESS_KILLING = setOf(ResetCommandId.REVOKE_READ_PHONE_STATE)

    private fun commands(apiLevel: Int, pkg: String): List<Pair<ResetCommandId, String>> {
        val suffix = if (apiLevel >= 24) " all" else ""
        return listOf(
            ResetCommandId.DISABLE_DEVICE_IDLE to "dumpsys deviceidle disable$suffix",
            ResetCommandId.ENABLE_DEVICE_IDLE to "dumpsys deviceidle enable$suffix",
            ResetCommandId.REVOKE_DUMP to "pm revoke $pkg android.permission.DUMP",
            ResetCommandId.REVOKE_READ_LOGS to "pm revoke $pkg android.permission.READ_LOGS",
            ResetCommandId.REVOKE_READ_PHONE_STATE to "pm revoke $pkg android.permission.READ_PHONE_STATE",
            ResetCommandId.REVOKE_WRITE_SECURE_SETTINGS to "pm revoke $pkg android.permission.WRITE_SECURE_SETTINGS",
            ResetCommandId.REVOKE_WRITE_SETTINGS to "appops set $pkg WRITE_SETTINGS default",
        )
    }

    private fun verifiedOutcome(confirmed: Boolean?): ResetCommandOutcome = when (confirmed) {
        true -> ResetCommandOutcome.OK
        false -> ResetCommandOutcome.FAILED
        null -> ResetCommandOutcome.UNVERIFIED
    }

    private fun verifyWriteSettings(control: CommandRunner, pkg: String): ResetCommandOutcome {
        val result = control.run("appops get $pkg WRITE_SETTINGS")
        return when {
            result.timedOut -> ResetCommandOutcome.TIMEOUT
            !result.ok || result.stderr.any { it.isNotBlank() } -> ResetCommandOutcome.UNVERIFIED
            else -> {
                verifiedOutcome(writeSettingsIsDefault(result.stdout))
            }
        }
    }

    private val writeSettingsMode = Regex("""WRITE_SETTINGS: (\w+)(?:;.*| \(running\))?""")

    /**
     * AOSP `appops get <pkg> WRITE_SETTINGS` (AppOpsService shell, M through 16): setting the op back to
     * its default prunes it, which prints "No operations." (plus "Default mode: default" from Q on);
     * a kept op prints "WRITE_SETTINGS: <mode>" with optional "; time=…" history. UID modes override
     * the package mode, and anything else is unknown.
     */
    internal fun writeSettingsIsDefault(stdout: List<String>): Boolean? {
        val lines = stdout.map(String::trim).filter(String::isNotEmpty)
        if (lines == listOf("No operations.") || lines == listOf("No operations.", "Default mode: default")) return true
        val mode = writeSettingsMode.matchEntire(lines.singleOrNull() ?: return null)?.groupValues?.get(1)
        return when (mode) {
            "default" -> true
            "allow", "ignore", "deny", "foreground" -> false
            else -> null
        }
    }

    private fun verifyDeviceIdle(control: CommandRunner, apiLevel: Int, enabled: Boolean): ResetCommandOutcome {
        // The combined "enabled all" value is an AND: 0 does not prove both modes disabled.
        val reads = if (apiLevel >= 24) listOf("cmd deviceidle enabled deep", "cmd deviceidle enabled light")
            else listOf("dumpsys deviceidle enabled")
        val outcomes = reads.map { command ->
            try {
                val result = control.run(command)
                when {
                    result.timedOut -> ResetCommandOutcome.TIMEOUT
                    !result.ok -> ResetCommandOutcome.UNVERIFIED
                    else -> {
                        val value = when (result.stdout.filterNot(String::isBlank).singleOrNull()?.trim()) {
                            "1" -> true
                            "0" -> false
                            else -> null
                        }
                        verifiedOutcome(value?.let { it == enabled })
                    }
                }
            } catch (_: Exception) { ResetCommandOutcome.UNVERIFIED }
        }
        return when {
            ResetCommandOutcome.TIMEOUT in outcomes -> ResetCommandOutcome.TIMEOUT
            ResetCommandOutcome.FAILED in outcomes -> ResetCommandOutcome.FAILED
            ResetCommandOutcome.UNVERIFIED in outcomes -> ResetCommandOutcome.UNVERIFIED
            else -> ResetCommandOutcome.OK
        }
    }
}
