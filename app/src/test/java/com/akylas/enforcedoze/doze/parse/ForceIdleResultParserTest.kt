package com.akylas.enforcedoze.doze.parse

import com.akylas.enforcedoze.doze.DeepState
import org.junit.Assert.assertEquals
import org.junit.Test

class ForceIdleResultParserTest {
    @Test
    fun currentAndApi23RepliesAreInformationalForced() {
        assertEquals(ForceIdleResult.Forced, ForceIdleResultParser.parse("Now forced in to deep idle mode\r\n\r\n"))
        assertEquals(ForceIdleResult.Forced, ForceIdleResultParser.parse(listOf("Now forced in to idle mode", "")))
    }

    @Test
    fun notEnabledAndEveryStoppedStateAreTyped() {
        assertEquals(ForceIdleResult.NotEnabled, ForceIdleResultParser.parse("Unable to go deep idle; not enabled"))
        for (state in DeepState.entries) {
            assertEquals(
                ForceIdleResult.StoppedAt(state),
                ForceIdleResultParser.parse("Unable to go deep idle; stopped at ${state.name}"),
            )
        }
        assertEquals(
            ForceIdleResult.StoppedAt(DeepState.UNKNOWN),
            ForceIdleResultParser.parse("Unable to go deep idle; stopped at OEM_STATE"),
        )
    }

    @Test
    fun garbageAndExtraOutputStayUnknownWithOriginalRawText() {
        for (raw in listOf("", "\r\n denied \r\n", "Now forced in to deep idle mode\nerror", "Unable to go deep idle; stopped at", "Unable to go deep idle; stopped at IDLE extra")) {
            assertEquals(ForceIdleResult.Unknown(raw), ForceIdleResultParser.parse(raw))
        }
    }
}
