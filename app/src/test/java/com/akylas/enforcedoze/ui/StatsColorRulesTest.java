package com.akylas.enforcedoze.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.akylas.enforcedoze.R;

import org.junit.Test;

public class StatsColorRulesTest {

    @Test
    public void sessionsBelowThreeBatteryPointsAreNotDrains() {
        assertFalse(StatsColorRules.isDrain(-1));
        assertFalse(StatsColorRules.isDrain(0));
        assertFalse(StatsColorRules.isDrain(2));
    }

    @Test
    public void sessionsFromThreeBatteryPointsAreDrains() {
        assertTrue(StatsColorRules.isDrain(3));
        assertTrue(StatsColorRules.isDrain(40));
    }

    @Test
    public void drainShowsTheAlertBatteryInColorError() {
        assertEquals(R.drawable.ic_battery_alert_black_48dp, StatsColorRules.batteryIcon(true));
        assertEquals(androidx.appcompat.R.attr.colorError, StatsColorRules.batteryTint(true));
    }

    @Test
    public void goodSessionShowsTheFullBatteryInSage() {
        assertEquals(R.drawable.ic_battery_charging_full_black_48dp, StatsColorRules.batteryIcon(false));
        assertEquals(com.google.android.material.R.attr.colorSecondary, StatsColorRules.batteryTint(false));
    }
}
