package com.akylas.enforcedoze.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.akylas.enforcedoze.doze.DeepState;
import com.akylas.enforcedoze.doze.EventType;
import com.akylas.enforcedoze.doze.SensorMode;
import com.akylas.enforcedoze.monitor.JournalEvent;
import com.akylas.enforcedoze.monitor.Source;

import org.junit.Test;

/** SQ-42 NIT-1: a failed readback is a problem row even when it carries the Doze or sensor state it read. */
public class MonitorProblemTest {
    private static JournalEvent verify(String detail, DeepState deep, SensorMode sensor) {
        return new JournalEvent(1, 0L, 0L, 1L, Source.APP, EventType.VERIFY, deep, null, sensor, null, null, detail);
    }

    @Test
    public void unverifiedForceDozeWithDeepStateIsAProblem() {
        assertTrue(MonitorFormat.isProblem(verify("FORCE_DOZE: UNVERIFIED", DeepState.ACTIVE, null)));
    }

    @Test
    public void unverifiedSensorRestrictionWithSensorModeIsAProblem() {
        assertTrue(MonitorFormat.isProblem(verify("MOTION_SENSORS: UNVERIFIED", null, SensorMode.NORMAL)));
    }

    @Test
    public void verifiedReadbacksAreNotProblems() {
        assertFalse(MonitorFormat.isProblem(verify("FORCE_DOZE", DeepState.IDLE, null)));
        assertFalse(MonitorFormat.isProblem(verify("MOTION_SENSORS", null, SensorMode.RESTRICTED)));
        assertFalse(MonitorFormat.isProblem(verify("WIFI", null, null)));
    }
}
