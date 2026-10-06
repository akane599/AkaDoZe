package com.akylas.enforcedoze.service

import com.akylas.enforcedoze.access.CommandRunner
import com.akylas.enforcedoze.access.PackageNames
import com.akylas.enforcedoze.doze.CorruptLedgerLine
import com.akylas.enforcedoze.doze.RestoreLedger
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
 * [commands] are the steps that ran. [deferred] steps have not run: revoking a runtime or gid-mapped
 * permission this app holds makes Android kill its process, so they wait for [SystemReset.runDeferred]
 * after the user has seen this result. They are never counted as OK, so [complete] covers only the steps
 * that ran; the dialog does not call the reset finished while [deferred] is non-empty.
 */
data class SystemResetResult @JvmOverloads constructor(
    val restoreOutcome: ResetRestoreOutcome,
    val commands: List<ResetCommandResult>,
    val deferred: List<ResetCommandId> = emptyList(),
    /** The job threw before a result was known: preserve user settings and allow a retry. */
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
    fun restoreOutcome(
        exitComplete: Boolean,
        remaining: RestoreLedger,
        loadFailed: Boolean,
        corruptLines: List<CorruptLedgerLine>,
    ): ResetRestoreOutcome =
        if (exitComplete && remaining.entries.isEmpty() && !loadFailed && corruptLines.isEmpty()) {
            ResetRestoreOutcome.COMPLETE
        } else ResetRestoreOutcome.REMAINING_DEBT

    /** Pure boundary for the runtime's whole worker job, before result delivery. */
    fun runJob(job: () -> SystemResetResult, onError: (Throwable) -> Unit = {}): SystemResetResult = try {
        job()
    } catch (error: Exception) {
        onError(error)
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
        onError: (Throwable) -> Unit = {},
        forgetHelpers: (Set<String>) -> Boolean,
        restore: () -> ResetRestoreOutcome,
    ): SystemResetResult {
        val pkg = PackageNames.requireValid(packageName)
        // The restore callback includes ledger readbacks and durable reconciliation before returning.
        val restoreOutcome = try { restore() } catch (error: Exception) {
            onError(error)
            return failedResult()
        }
        val sessions = SessionAccess.canRunSessions(control.level)
        val (deferred, now) = commands(apiLevel, pkg).partition { (id, command) ->
            // Not granted: Android skips the revoke without killing, so it runs (and is checked) in place.
            sessions && id in PROCESS_KILLING &&
                (try { permissionGranted(command.substringAfterLast(' ')) } catch (error: Exception) {
                    onError(error)
                    null
                }) != false
        }
        val results = now.map { (id, command) ->
            val outcome = when {
                !sessions -> ResetCommandOutcome.UNVERIFIED
                !forgetBeforeRevoke(id, forgetHelpers, onError) -> ResetCommandOutcome.FAILED
                else -> runInline(control, apiLevel, pkg, id, command, permissionGranted, onError)
            }
            ResetCommandResult(id, outcome)
        }
        return SystemResetResult(restoreOutcome, Collections.unmodifiableList(results),
            Collections.unmodifiableList(deferred.map { it.first }))
    }

    /**
     * doze-worker only, after the user confirmed the reported result. Durably forget matching helpers
     * again here: a service start may have re-recorded them since the report's preference clear.
     * Submit eligible revokes as one ';'-joined command so a failed revoke does not skip the rest.
     * Completion after this process is killed is backend-dependent and unverified on Shizuku devices.
     * No deferred step is readback-confirmed.
     */
    fun runDeferred(
        control: CommandRunner,
        apiLevel: Int,
        packageName: String,
        deferred: List<ResetCommandId>,
        onError: (Throwable) -> Unit = {},
        forgetHelpers: (Set<String>) -> Boolean,
    ): List<ResetCommandResult> {
        val pkg = PackageNames.requireValid(packageName)
        val selected = commands(apiLevel, pkg).filter { (id, _) -> id in deferred && id in PROCESS_KILLING }
        if (!SessionAccess.canRunSessions(control.level)) {
            return selected.map { ResetCommandResult(it.first, ResetCommandOutcome.UNVERIFIED) }
        }
        val results = selected.map { (id, _) ->
            ResetCommandResult(id, if (forgetBeforeRevoke(id, forgetHelpers, onError))
                ResetCommandOutcome.UNVERIFIED else ResetCommandOutcome.FAILED)
        }
        val command = selected.filterIndexed { index, _ -> results[index].outcome != ResetCommandOutcome.FAILED }
            .joinToString("; ") { it.second }
        if (command.isNotEmpty()) submitDeferred(control, command, onError)
        return results
    }

    private fun submitDeferred(control: CommandRunner, command: String, onError: (Throwable) -> Unit) {
        try {
            val result = control.run(command)
            if (result.timedOut || !result.ok) {
                onError(IllegalStateException("Deferred reset revoke failed: exit=${result.exitCode}, timedOut=${result.timedOut}"))
            }
        } catch (error: Exception) {
            onError(error)
        }
    }

    /** Only reset permissions that are also automatic GrantCommands helpers have a record key. */
    private val helperKeys = mapOf(
        ResetCommandId.REVOKE_DUMP to "DUMP",
        ResetCommandId.REVOKE_WRITE_SECURE_SETTINGS to "WRITE_SECURE_SETTINGS",
        ResetCommandId.REVOKE_READ_PHONE_STATE to "READ_PHONE_STATE",
    )

    private fun forgetBeforeRevoke(
        id: ResetCommandId,
        forgetHelpers: (Set<String>) -> Boolean,
        onError: (Throwable) -> Unit,
    ): Boolean {
        val key = helperKeys[id] ?: return true
        val forgotten = try { forgetHelpers(setOf(key)) } catch (error: Exception) {
            onError(error)
            return false
        }
        if (!forgotten) onError(IllegalStateException("Could not forget reset helper $key before revoke"))
        return forgotten
    }

    private fun runInline(
        control: CommandRunner,
        apiLevel: Int,
        pkg: String,
        id: ResetCommandId,
        command: String,
        permissionGranted: (String) -> Boolean?,
        onError: (Throwable) -> Unit,
    ): ResetCommandOutcome = try {
        val result = control.run(command)
        when {
            result.timedOut -> ResetCommandOutcome.TIMEOUT
            result.ok -> readback(control, apiLevel, pkg, id, command, permissionGranted, onError)
            result.exitCode < 0 -> ResetCommandOutcome.UNVERIFIED
            else -> ResetCommandOutcome.FAILED
        }
    } catch (error: Exception) {
        onError(error)
        ResetCommandOutcome.UNVERIFIED
    }

    private fun readback(
        control: CommandRunner,
        apiLevel: Int,
        pkg: String,
        id: ResetCommandId,
        command: String,
        permissionGranted: (String) -> Boolean?,
        onError: (Throwable) -> Unit,
    ): ResetCommandOutcome = when (id) {
        ResetCommandId.DISABLE_DEVICE_IDLE -> verifyDeviceIdle(control, apiLevel, false, onError)
        ResetCommandId.ENABLE_DEVICE_IDLE -> verifyDeviceIdle(control, apiLevel, true, onError)
        ResetCommandId.REVOKE_WRITE_SETTINGS -> verifyWriteSettings(control, pkg)
        else -> verifiedOutcome(permissionGranted(command.substringAfterLast(' '))?.not())
    }

    /** Runtime READ_PHONE_STATE, plus READ_LOGS's log/update_engine_log gids in AOSP platform.xml. */
    private val PROCESS_KILLING = setOf(
        ResetCommandId.REVOKE_READ_LOGS,
        ResetCommandId.REVOKE_READ_PHONE_STATE,
    )

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

    private fun verifyDeviceIdle(
        control: CommandRunner,
        apiLevel: Int,
        enabled: Boolean,
        onError: (Throwable) -> Unit,
    ): ResetCommandOutcome {
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
            } catch (error: Exception) {
                onError(error)
                ResetCommandOutcome.UNVERIFIED
            }
        }
        return listOf(ResetCommandOutcome.TIMEOUT, ResetCommandOutcome.FAILED, ResetCommandOutcome.UNVERIFIED)
            .firstOrNull { it in outcomes } ?: ResetCommandOutcome.OK
    }
}
