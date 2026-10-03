package com.akylas.enforcedoze.doze.parse;

import com.akylas.enforcedoze.doze.DeepState;
import com.akylas.enforcedoze.doze.LightState;
import com.akylas.enforcedoze.doze.SensorMode;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public class ParserJavaApiTest {
    @Test
    public void javaConsumersCanParseRunnerStdoutAndReadTypedResults() {
        DozeStateReading state = DozeStateParser.parse(Arrays.asList(
                "mState=IDLE mLightState=OVERRIDE", "mForceIdle=true"));
        assertEquals(DeepState.IDLE, state.getDeep());
        assertEquals(LightState.OVERRIDE, state.getLight());
        assertEquals(Boolean.TRUE, state.getForceIdle());
        assertEquals(DeepState.IDLE_PENDING, DozeStateParser.parseDeep("IDLE_PENDING"));
        assertEquals(LightState.PRE_IDLE, DozeStateParser.parseLight(Arrays.asList("PRE_IDLE", "")));
        assertEquals(Boolean.FALSE, DozeStateParser.parseBoolean("false"));
        assertEquals(SensorMode.RESTRICTED,
                SensorModeParser.parse(Arrays.asList("Mode:RESTRICTED:com.test")).getMode());
        assertEquals("com.test", SensorModeParser.parse("Mode:RESTRICTED:com.test").getAllowToken());
        assertEquals(ForceIdleResult.Forced.INSTANCE,
                ForceIdleResultParser.parse("Now forced in to deep idle mode"));
        IdlingHistory history = IdlingHistoryParser.parse(Arrays.asList(
                "Idling history:", " normal: -12ms"), 100L);
        assertEquals(Long.valueOf(88), history.getOldestElapsed());
        assertEquals(HistoryKind.NORMAL, history.getEvents().get(0).getKind());
    }
}
