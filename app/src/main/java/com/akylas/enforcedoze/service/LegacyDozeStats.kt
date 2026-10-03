package com.akylas.enforcedoze.service

/** Retains the historical StringSet format; malformed legacy rows never reach numeric UI parsing. */
object LegacyDozeStats {
    enum class Kind { ENTER, EXIT, EXIT_MAINTENANCE, ENTER_MAINTENANCE }
    data class Entry(val time: Long, val battery: Float, val kind: Kind, val raw: String)
    data class Interval(val start: Entry, val end: Entry, val maintenance: Boolean)

    @JvmStatic
    fun parse(raw: String): Entry? {
        val parts = raw.split(',')
        if (parts.size != 3) return null
        val time = parts[0].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val battery = parts[1].toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..100f } ?: return null
        val kind = Kind.entries.firstOrNull { it.name == parts[2] } ?: return null
        return Entry(time, battery, kind, raw)
    }

    @JvmStatic
    fun newest(rows: Collection<String>): Set<String> = rows.mapNotNull(::parse)
        .sortedWith(compareByDescending<Entry> { it.time }.thenBy { it.raw })
        .take(1000).mapTo(linkedSetOf()) { it.raw }

    @JvmStatic
    fun intervals(rows: Collection<String>): List<Interval> {
        var enter: Entry? = null
        var maintenance: Entry? = null
        val result = mutableListOf<Interval>()
        for (entry in rows.mapNotNull(::parse).sortedWith(compareBy<Entry> { it.time }.thenBy { it.kind.ordinal })) {
            when (entry.kind) {
                Kind.ENTER -> { enter = entry; maintenance = null }
                Kind.EXIT -> {
                    enter?.let { result += Interval(it, entry, false) }
                    enter = null
                    maintenance = null
                }
                Kind.EXIT_MAINTENANCE -> if (enter != null) maintenance = entry
                Kind.ENTER_MAINTENANCE -> {
                    maintenance?.let { result += Interval(it, entry, true) }
                    maintenance = null
                }
            }
        }
        return result.sortedByDescending { it.end.time }
    }
}
