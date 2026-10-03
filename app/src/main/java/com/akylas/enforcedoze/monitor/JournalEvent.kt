package com.akylas.enforcedoze.monitor

import com.akylas.enforcedoze.doze.DeepState
import com.akylas.enforcedoze.doze.DozeEvent
import com.akylas.enforcedoze.doze.EventType
import com.akylas.enforcedoze.doze.LightState
import com.akylas.enforcedoze.doze.SensorMode
import com.akylas.enforcedoze.doze.parse.HistoryKind

enum class Source { APP, OS_HISTORY }

data class JournalEvent @JvmOverloads constructor(
    val bootId: Int,
    val elapsedRealtime: Long,
    val wallTime: Long,
    val sessionId: Long,
    val source: Source,
    val type: EventType?,
    val deep: DeepState? = null,
    val light: LightState? = null,
    val sensor: SensorMode? = null,
    val battery: Int? = null,
    val charging: Boolean? = null,
    val detail: String? = null,
    val id: Long? = null,
    val historyKind: HistoryKind? = null,
    val historyTruncated: Boolean = false,
) {
    init {
        require((type != null) != (historyKind != null)) { "Exactly one event kind is required" }
        require(battery == null || battery in 0..100) { "Battery must be a percentage" }
    }

    companion object {
        /** The adapter supplies the two clocks, boot and session; engine target packages stay private. */
        @JvmStatic
        @JvmOverloads
        fun fromDozeEvent(
            event: DozeEvent,
            bootId: Int,
            elapsedRealtime: Long,
            wallTime: Long,
            sessionId: Long,
            battery: Int? = null,
            charging: Boolean? = null,
        ): JournalEvent = JournalEvent(
            bootId, elapsedRealtime, wallTime, sessionId, Source.APP, event.type,
            event.deep, event.light, event.sensor, battery, charging,
            listOfNotNull(event.detail, event.reason?.name).distinct().joinToString(": "),
        )
    }
}
