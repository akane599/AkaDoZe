package com.akylas.enforcedoze.monitor

import java.util.Locale

object ReportFormatter {
    private val packageName = Regex("(?<![A-Za-z0-9_])[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+(?![A-Za-z0-9_])")

    @JvmStatic
    fun format(
        summaries: List<SessionSummary>,
        events: List<JournalEvent>,
        appVersion: String,
        deviceInfo: Map<String, String>,
    ): String = buildString {
        appendLine("AkaDoZe monitor report")
        appendLine("App version: ${safe(appVersion)}")
        appendLine("Device:")
        for ((key, value) in deviceInfo.toSortedMap()) appendLine("  ${safe(key)}: ${safe(value)}")
        appendLine("Coverage: observed state intervals only; missing evidence is UNKNOWN.")
        appendLine("Battery: only fully non-charging sample intervals; unknown charging is excluded.")
        appendLine()
        appendLine("Sessions (${summaries.size})")
        for (summary in summaries) {
            appendLine("Session ${summary.sessionId} (boot ${summary.bootId})")
            appendLine("  Start wall time ms: ${summary.startWallTime}")
            appendLine("  Elapsed range ms: ${summary.startElapsed}..${summary.endElapsed}")
            appendLine("  Duration ms: ${summary.durationMs}")
            appendLine("  First verified deep IDLE ms: ${summary.timeToFirstDeepIdleMs ?: "UNVERIFIED"}")
            for (kind in Coverage.entries) {
                appendLine("  $kind: ${decimal(summary.coveragePercent.getValue(kind))}% (${summary.coverageMs.getValue(kind)} ms)")
            }
            appendLine("  Maintenance windows: ${summary.maintenanceCount}")
            appendLine("  Exits by reason: ${summary.exitsByReason.entries.joinToString { "${safe(it.key)}=${it.value}" }.ifEmpty { "none" }}")
            appendLine("  Re-forces: ${summary.reforceCount}")
            appendLine("  Sensors verified restricted: ${summary.sensorsRestricted}")
            appendLine("  Battery drop percentage points: ${summary.batteryDrop ?: "UNVERIFIED"}")
            appendLine("  Battery percentage points/hour: ${summary.batteryPercentPerHour?.let { decimal(it) } ?: "UNVERIFIED"}")
            appendLine("  Non-charging sampled ms: ${summary.nonChargingSampledMs}")
            appendLine("  Problems: ${summary.problems.joinToString().ifEmpty { "none" }}")
            appendLine()
        }
        appendLine("Timeline (${events.size})")
        for (event in events.sortedWith(compareBy<JournalEvent> { it.bootId }.thenBy { it.elapsedRealtime })) {
            append("boot=${event.bootId} session=${event.sessionId} elapsed=${event.elapsedRealtime} wall=${event.wallTime}")
            append(" source=${event.source} kind=${event.type ?: event.historyKind}")
            append(" deep=${event.deep ?: "UNKNOWN"} light=${event.light ?: "UNKNOWN"}")
            append(" sensor=${event.sensor ?: "UNVERIFIED"} battery=${event.battery ?: "UNVERIFIED"}")
            append(" charging=${event.charging ?: "UNKNOWN"}")
            if (event.historyTruncated) append(" historyTruncated=true")
            if (!event.detail.isNullOrEmpty()) append(" detail=${safe(event.detail)}")
            appendLine()
        }
    }

    private fun decimal(value: Double): String = String.format(Locale.US, "%.2f", value)
    private fun safe(value: String): String = packageName.replace(value, "[package]")
        .map { if (it.isISOControl()) ' ' else it }.joinToString("")
}
