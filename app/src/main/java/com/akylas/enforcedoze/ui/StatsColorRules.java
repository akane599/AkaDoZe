package com.akylas.enforcedoze.ui;

import androidx.annotation.AttrRes;
import androidx.annotation.DrawableRes;

import com.akylas.enforcedoze.R;

/**
 * Pure colour and icon choice for a Doze session card in the battery stats list. A session that cost
 * {@link #DRAIN_THRESHOLD} or more battery points reads as a drain (alert icon, colorError); anything less is
 * a good session (charging-full icon, sage colorSecondary).
 */
public final class StatsColorRules {

    /** Battery points lost over one Doze session at which the session counts as a drain. */
    public static final int DRAIN_THRESHOLD = 3;

    public static boolean isDrain(int batteryUsed) {
        return batteryUsed >= DRAIN_THRESHOLD;
    }

    @DrawableRes
    public static int batteryIcon(boolean drain) {
        return drain ? R.drawable.ic_battery_alert_black_48dp : R.drawable.ic_battery_charging_full_black_48dp;
    }

    @AttrRes
    public static int batteryTint(boolean drain) {
        return drain ? androidx.appcompat.R.attr.colorError : com.google.android.material.R.attr.colorSecondary;
    }
}
