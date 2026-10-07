package com.akylas.enforcedoze.ui;

import androidx.annotation.AttrRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;

import com.akylas.enforcedoze.R;
import com.akylas.enforcedoze.doze.DeepState;
import com.akylas.enforcedoze.doze.parse.DozeStateReading;
import com.akylas.enforcedoze.doze.parse.SensorModeReading;
import com.akylas.enforcedoze.monitor.JournalEvent;

/** The Doze monitor's Amber accent and motion decisions, kept free of views so they run on the JVM. */
final class MonitorMotionRules {
    /** Scale the live icon springs up from when the live state changes. */
    static final float ENTRANCE_SCALE = 0.6f;

    /** What the live card shows of the system's Doze state; null until the first read. */
    @Nullable
    static String liveKey(@Nullable MonitorData.Live live) {
        if (live == null) return null;
        return idleKey(live.idle) + "|" + sensorKey(live.sensor);
    }

    private static String idleKey(@Nullable DozeStateReading idle) {
        if (idle == null) return "-";
        return idle.getDeep() + "|" + idle.getLight() + "|" + idle.getForceIdle() + "|" + idle.getQuickDozeActivated();
    }

    private static String sensorKey(@Nullable SensorModeReading sensor) {
        return sensor == null ? "-" : String.valueOf(sensor.getMode());
    }

    /** The entrance plays only for a real change: a rebind of the same state, or no reading yet, doesn't replay it. */
    static boolean changed(@Nullable String lastKey, @Nullable String key) {
        return key != null && !key.equals(lastKey);
    }

    /** Deep Doze is idle right now: the live card's icon turns amber. */
    static boolean dozing(@Nullable MonitorData.Live live) {
        return live != null && live.idle != null && live.idle.getDeep() == DeepState.IDLE;
    }

    /** Doze is forced right now: the live card glows. */
    static boolean forced(@Nullable MonitorData.Live live) {
        return live != null && live.idle != null && Boolean.TRUE.equals(live.idle.getForceIdle());
    }

    @DrawableRes
    static int liveIcon(@Nullable MonitorData.Live live) {
        return dozing(live) ? R.drawable.ic_monitor_idle_active : R.drawable.ic_monitor_idle;
    }

    /** Problems read as errors, verified readbacks in sage, everything else stays neutral. */
    @AttrRes
    static int eventTint(JournalEvent event) {
        if (MonitorFormat.isProblem(event)) return androidx.appcompat.R.attr.colorError;
        if (MonitorFormat.eventIcon(event) == R.drawable.ic_monitor_verified) {
            return com.google.android.material.R.attr.colorSecondary;
        }
        return com.google.android.material.R.attr.colorOnSurfaceVariant;
    }
}
