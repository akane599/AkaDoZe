package com.akylas.enforcedoze.doze.parse

enum class HistoryKind { NORMAL, LIGHT_IDLE, LIGHT_MAINT, DEEP_IDLE, DEEP_MAINT }

data class HistoryEvent(
    val kind: HistoryKind,
    val elapsedRealtime: Long,
    val reason: String?,
)

data class IdlingHistory(val events: List<HistoryEvent>) {
    /** The earliest retained transition; alone this is not evidence that older history was lost. */
    val oldestElapsed: Long? get() = events.minOfOrNull { it.elapsedRealtime }
}

object IdlingHistoryParser {
    /**
     * AOSP DeviceIdleController.EVENT_BUFFER_SIZE (API 24+); API 23 has no history block.
     * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-7.0.0_r1/services/core/java/com/android/server/DeviceIdleController.java
     * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r1/apex/jobscheduler/service/java/com/android/server/DeviceIdleController.java
     */
    const val HISTORY_CAPACITY = 100

    private val entry = Regex(
        "^\\s*(normal|light-idle|light-maint|deep-idle|deep-maint):\\s*(\\S+)(?:\\s+\\((.*)\\))?\\s*$",
    )
    private val duration = Regex(
        "([+-]?)(?:(\\d+)d)?(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?(?:(\\d+)ms)?",
    )
    private val units = longArrayOf(86_400_000, 3_600_000, 60_000, 1_000, 1)

    @JvmStatic
    fun parse(output: List<String>, nowElapsed: Long): IdlingHistory =
        parse(output.joinToString("\n"), nowElapsed)

    @JvmStatic
    fun parse(output: String, nowElapsed: Long): IdlingHistory {
        val events = mutableListOf<HistoryEvent>()
        var inHistory = false
        for (line in output.lineSequence()) {
            if (!inHistory) {
                if (line.trim() == "Idling history:") inHistory = true
                continue
            }
            val match = entry.matchEntire(line) ?: break
            val offset = parseDuration(match.groupValues[2]) ?: break
            // Overflow is malformed evidence, not an event at a wrapped timestamp.
            if (offset > 0 && nowElapsed > Long.MAX_VALUE - offset ||
                offset < 0 && nowElapsed < Long.MIN_VALUE - offset
            ) {
                break
            }
            val kind = when (match.groupValues[1]) {
                "normal" -> HistoryKind.NORMAL
                "light-idle" -> HistoryKind.LIGHT_IDLE
                "light-maint" -> HistoryKind.LIGHT_MAINT
                "deep-idle" -> HistoryKind.DEEP_IDLE
                else -> HistoryKind.DEEP_MAINT
            }
            events += HistoryEvent(
                kind = kind,
                elapsedRealtime = nowElapsed + offset,
                reason = match.groups[3]?.value,
            )
        }
        return IdlingHistory(events.toList())
    }

    private fun parseDuration(raw: String): Long? {
        if (raw == "0" || raw == "+0" || raw == "-0") return 0
        val match = duration.matchEntire(raw) ?: return null
        if ((2..6).all { match.groupValues[it].isEmpty() }) return null
        var total = 0L
        for ((index, unit) in units.withIndex()) {
            val digits = match.groupValues[index + 2]
            if (digits.isEmpty()) continue
            val value = digits.toLongOrNull() ?: return null
            if (value > Long.MAX_VALUE / unit) return null
            val millis = value * unit
            if (total > Long.MAX_VALUE - millis) return null
            total += millis
        }
        return if (match.groupValues[1] == "-") -total else total
    }
}
