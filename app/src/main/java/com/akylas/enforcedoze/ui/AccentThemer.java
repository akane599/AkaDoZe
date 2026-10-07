package com.akylas.enforcedoze.ui;

import android.app.Activity;
import android.app.Application;
import android.os.Build;
import android.os.Bundle;

import com.akylas.enforcedoze.R;

import java.util.Calendar;
import java.util.EnumMap;
import java.util.Map;

/**
 * Merges the time-of-day accent overlay over each activity's own theme before its views inflate.
 * Holds no state beyond the hour source: safe to register during locked direct boot.
 */
public final class AccentThemer implements Application.ActivityLifecycleCallbacks {
    // Own SAM type: java.util.function is API 24+, minSdk is 23.
    public interface HourSource { int hour(); }

    private static final Map<AccentClock.Phase, Integer> OVERLAYS = new EnumMap<>(AccentClock.Phase.class);
    static {
        OVERLAYS.put(AccentClock.Phase.MORNING, R.style.ThemeOverlay_Amber_Accent_Morning);
        OVERLAYS.put(AccentClock.Phase.DAY, R.style.ThemeOverlay_Amber_Accent_Day);
        OVERLAYS.put(AccentClock.Phase.EVENING, R.style.ThemeOverlay_Amber_Accent_Evening);
        OVERLAYS.put(AccentClock.Phase.NIGHT, R.style.ThemeOverlay_Amber_Accent_Night);
    }

    private final HourSource hours;

    public AccentThemer() {
        this(() -> Calendar.getInstance().get(Calendar.HOUR_OF_DAY));
    }

    public AccentThemer(HourSource hours) {
        this.hours = hours;
    }

    public static int overlayFor(AccentClock.Phase phase) {
        return OVERLAYS.get(phase);
    }

    // API 29+: runs before Activity.onCreate, so even pre-super.onCreate inflation sees the accent.
    @Override
    public void onActivityPreCreated(Activity activity, Bundle savedInstanceState) {
        apply(activity);
    }

    // Below API 29 there is no pre-create hook; this fires inside super.onCreate, before setContentView.
    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            apply(activity);
        }
    }

    private void apply(Activity activity) {
        // applyStyle(force) merges over the manifest theme; setTheme would replace it.
        activity.getTheme().applyStyle(overlayFor(AccentClock.forHour(hours.hour())), true);
    }

    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityResumed(Activity activity) { }
    @Override public void onActivityPaused(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) { }
    @Override public void onActivityDestroyed(Activity activity) { }
}
