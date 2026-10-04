package com.akylas.enforcedoze.doze.parse

import com.akylas.enforcedoze.access.Feature
import com.akylas.enforcedoze.doze.DeepState
import com.akylas.enforcedoze.doze.FeatureReadback
import com.akylas.enforcedoze.doze.LightState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DozeStateParserTest {
    @Test
    fun fullDumpReadsCombinedStatesFlagsAndOnlySettingsBlock() {
        val reading = DozeStateParser.parse(deviceIdleFixture())
        assertEquals(DeepState.IDLE, reading.deep)
        assertEquals(LightState.OVERRIDE, reading.light)
        assertEquals(true, reading.forceIdle)
        assertEquals(false, reading.quickDozeActivated)
        assertEquals(false, reading.screenOn)
        assertEquals(false, reading.charging)
        assertEquals(
            mapOf(
                "light_after_inactive_to" to "+3m0s0ms",
                "light_idle_to" to "+5m0s0ms",
                "inactive_to" to "+30m0s0ms",
                "sensing_to" to "+4m0s0ms",
                "idle_factor" to "2.0",
            ),
            reading.settings,
        )
    }

    @Test
    fun marshmallowDumpProvidesDeepReadbackAndIndependentForceIdleRestoreOracle() {
        val forced = """
            Settings:
              inactive_to=+30m0s0ms
              idle_to=+1h0m0s0ms
            mEnabled=true
            mForceIdle=true
            mScreenOn=false
            mCharging=false
            mState=IDLE
        """.trimIndent()
        val reading = DozeStateParser.parse(forced)
        assertEquals(DeepState.IDLE, reading.deep)
        assertEquals(true, reading.forceIdle)
        assertNull(reading.light)
        assertNull(reading.quickDozeActivated)
        assertEquals(DeepState.IDLE, DozeStateParser.parseDeep(forced.lines()))
        assertEquals("1", FeatureReadback.value(Feature.FORCE_DOZE, 23, forced.lines(), null))

        // A step may leave maintenance state; restoration depends on force, not deep ACTIVE.
        val restored = forced.replace("mForceIdle=true", "mForceIdle=false")
            .replace("mState=IDLE", "mState=IDLE_MAINTENANCE")
        assertEquals(DeepState.IDLE_MAINTENANCE, DozeStateParser.parseDeep(restored))
        assertEquals(false, DozeStateParser.parse(restored).forceIdle)
        assertEquals("0", FeatureReadback.value(Feature.FORCE_DOZE, 23, restored.lines(), null))
    }

    @Test
    fun everyDeepTokenIsExactInDumpAndGetReply() {
        for (state in DeepState.entries) {
            assertEquals(state, DozeStateParser.parse("  mState=${state.name} mLightState=ACTIVE").deep)
            assertEquals(state, DozeStateParser.parseDeep("  ${state.name}\n\n"))
        }
        assertEquals(DeepState.IDLE, DozeStateParser.parseDeep("IDLE"))
        assertEquals(DeepState.IDLE_MAINTENANCE, DozeStateParser.parseDeep("IDLE_MAINTENANCE"))
        assertEquals(DeepState.IDLE_PENDING, DozeStateParser.parseDeep("IDLE_PENDING"))
    }

    @Test
    fun everyLightTokenIsExactInDumpAndGetReply() {
        for (state in LightState.entries) {
            assertEquals(state, DozeStateParser.parse("mState=IDLE mLightState=${state.name}").light)
            assertEquals(state, DozeStateParser.parseLight("${state.name}\r\n\r\n"))
        }
    }

    @Test
    fun separateLinesCrLfAndStdoutListsAreAccepted() {
        val text = "  mState=IDLE_PENDING\r\n  mLightState=PRE_IDLE\r\n" +
            "  mForceIdle=false mQuickDozeActivated=true\r\n  mScreenOn=true mCharging=true\r\n"
        val reading = DozeStateParser.parse(text)
        assertEquals(DeepState.IDLE_PENDING, reading.deep)
        assertEquals(LightState.PRE_IDLE, reading.light)
        assertEquals(false, reading.forceIdle)
        assertEquals(true, reading.quickDozeActivated)
        assertEquals(true, reading.screenOn)
        assertEquals(true, reading.charging)
        assertEquals(reading, DozeStateParser.parse(text.lines()))
        assertEquals(DozeStateParser.parse(deviceIdleFixture()), DozeStateParser.parse(deviceIdleFixture().replace("\n", "\r\n")))
    }

    @Test
    fun unknownOemAndMalformedTokensNeverBecomeIdleOrActive() {
        for (token in listOf("OEM_IDLE", "IDLE_PENDING_OEM", "IDLE-MAINTENANCE", "idle")) {
            val reading = DozeStateParser.parse("mState=$token mLightState=$token")
            assertEquals(DeepState.UNKNOWN, reading.deep)
            assertEquals(LightState.UNKNOWN, reading.light)
            assertEquals(DeepState.UNKNOWN, DozeStateParser.parseDeep(token))
            assertEquals(LightState.UNKNOWN, DozeStateParser.parseLight(token))
        }
        assertEquals(DeepState.UNKNOWN, DozeStateParser.parseDeep("IDLE\nACTIVE"))
        assertEquals(LightState.UNKNOWN, DozeStateParser.parseLight("IDLE extra"))
    }

    @Test
    fun missingAndMalformedFieldsRemainUnverified() {
        val reading = DozeStateParser.parse("permission denied\nnot mState=IDLE\nmForceIdle=TRUE mCharging=maybe")
        assertNull(reading.deep)
        assertNull(reading.light)
        assertNull(reading.forceIdle)
        assertNull(reading.quickDozeActivated)
        assertNull(reading.screenOn)
        assertNull(reading.charging)
        assertTrue(reading.settings.isEmpty())
        assertNull(DozeStateParser.parseDeep("\r\n "))
        assertNull(DozeStateParser.parseLight(emptyList()))
        assertNull(DozeStateParser.parse("mState=IDLE").light)
    }

    @Test
    fun forceAndQuickGetRepliesRequireExactBoolean() {
        assertEquals(true, DozeStateParser.parseBoolean("  true\r\n\r\n"))
        assertEquals(false, DozeStateParser.parseBoolean(listOf("false", "")))
        for (reply in listOf("", "TRUE", "false extra", "true\nfalse", "1", "permission denied")) {
            assertNull(DozeStateParser.parseBoolean(reply))
        }
    }

    @Test
    fun settingsStopAtBlankOrSameIndentAndDoNotCaptureWhitelist() {
        val reading = DozeStateParser.parse("""
            Settings:
              inactive_to=+30m0s0ms
            whitelist_entry=value
              other=value
        """.trimIndent())
        assertEquals(mapOf("inactive_to" to "+30m0s0ms"), reading.settings)
        assertFalse(reading.settings.containsKey("whitelist_entry"))
        assertEquals(
            mapOf("inactive_to" to "+1s0ms"),
            DozeStateParser.parse("Settings:\n  inactive_to=+1s0ms\n\n  idle_to=+2s0ms").settings,
        )
    }
}
