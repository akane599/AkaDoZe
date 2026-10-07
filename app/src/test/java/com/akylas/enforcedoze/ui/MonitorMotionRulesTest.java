package com.akylas.enforcedoze.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.akylas.enforcedoze.R;
import com.akylas.enforcedoze.doze.DeepState;
import com.akylas.enforcedoze.doze.EventType;
import com.akylas.enforcedoze.doze.LightState;
import com.akylas.enforcedoze.doze.SensorMode;
import com.akylas.enforcedoze.doze.parse.DozeStateReading;
import com.akylas.enforcedoze.doze.parse.SensorModeReading;
import com.akylas.enforcedoze.monitor.JournalEvent;
import com.akylas.enforcedoze.monitor.Source;

import org.junit.Test;

import java.util.Collections;

/** SQ-20: the monitor's live-card motion fires on real state changes only; event tints follow verification. */
public class MonitorMotionRulesTest {
    static DozeStateReading idle(DeepState deep, Boolean forced) {
        return new DozeStateReading(deep, LightState.IDLE, forced, false, false, false, Collections.emptyMap());
    }

    static MonitorData.Live live(DozeStateReading idle, SensorMode sensor) {
        MonitorData.Live live = new MonitorData.Live();
        live.idle = idle;
        live.sensor = sensor == null ? null : new SensorModeReading(sensor, null);
        return live;
    }

    private static JournalEvent event(EventType type, String detail, DeepState deep) {
        return new JournalEvent(1, 0L, 0L, 1L, Source.APP, type, deep, null, null, null, null, detail);
    }

    @Test
    public void noReadingHasNoKeyAndNeverAnimates() {
        assertNull(MonitorMotionRules.liveKey(null));
        assertFalse(MonitorMotionRules.changed(null, null));
        assertFalse(MonitorMotionRules.changed("IDLE", null));
    }

    @Test
    public void theSameStateReadAgainDoesNotReplayTheEntrance() {
        String first = MonitorMotionRules.liveKey(live(idle(DeepState.IDLE, true), SensorMode.RESTRICTED));
        String again = MonitorMotionRules.liveKey(live(idle(DeepState.IDLE, true), SensorMode.RESTRICTED));
        assertEquals(first, again);
        assertTrue("the first reading is a change", MonitorMotionRules.changed(null, first));
        assertFalse("a recycled bind of the same state", MonitorMotionRules.changed(first, again));
    }

    @Test
    public void deepForcedAndSensorChangesEachChangeTheKey() {
        String base = MonitorMotionRules.liveKey(live(idle(DeepState.IDLE, true), SensorMode.RESTRICTED));
        String deep = MonitorMotionRules.liveKey(live(idle(DeepState.ACTIVE, true), SensorMode.RESTRICTED));
        String forced = MonitorMotionRules.liveKey(live(idle(DeepState.IDLE, false), SensorMode.RESTRICTED));
        String sensor = MonitorMotionRules.liveKey(live(idle(DeepState.IDLE, true), SensorMode.NORMAL));
        String unread = MonitorMotionRules.liveKey(live(null, null));
        assertTrue(MonitorMotionRules.changed(base, deep));
        assertTrue(MonitorMotionRules.changed(base, forced));
        assertTrue(MonitorMotionRules.changed(base, sensor));
        assertTrue(MonitorMotionRules.changed(base, unread));
        assertNotEquals(deep, unread);
    }

    @Test
    public void onlyDeepIdleTurnsTheIconAmber() {
        assertEquals(R.drawable.ic_monitor_idle_active, MonitorMotionRules.liveIcon(live(idle(DeepState.IDLE, false), null)));
        assertEquals(R.drawable.ic_monitor_idle, MonitorMotionRules.liveIcon(live(idle(DeepState.ACTIVE, true), null)));
        assertEquals(R.drawable.ic_monitor_idle, MonitorMotionRules.liveIcon(live(null, null)));
        assertEquals(R.drawable.ic_monitor_idle, MonitorMotionRules.liveIcon(null));
    }

    @Test
    public void onlyForcedIdleGlows() {
        assertTrue(MonitorMotionRules.forced(live(idle(DeepState.IDLE, true), null)));
        assertFalse(MonitorMotionRules.forced(live(idle(DeepState.IDLE, false), null)));
        assertFalse("unknown is not forced", MonitorMotionRules.forced(live(idle(DeepState.IDLE, null), null)));
        assertFalse(MonitorMotionRules.forced(live(null, null)));
        assertFalse(MonitorMotionRules.forced(null));
    }

    @Test
    public void problemsAreErrorsVerifiedIsSageTheRestNeutral() {
        assertEquals(androidx.appcompat.R.attr.colorError,
                MonitorMotionRules.eventTint(event(EventType.VERIFY, "FORCE_DOZE: UNVERIFIED", DeepState.ACTIVE)));
        assertEquals(androidx.appcompat.R.attr.colorError, MonitorMotionRules.eventTint(event(EventType.ERROR, "x", null)));
        assertEquals(com.google.android.material.R.attr.colorSecondary,
                MonitorMotionRules.eventTint(event(EventType.VERIFY, "FORCE_DOZE", DeepState.IDLE)));
        assertEquals(com.google.android.material.R.attr.colorOnSurfaceVariant,
                MonitorMotionRules.eventTint(event(EventType.SCREEN_OFF, null, null)));
    }
}
