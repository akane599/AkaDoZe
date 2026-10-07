package com.akylas.enforcedoze.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SettingsRulesTest {
    @Test
    public void resetDisplayMatchesEveryTrackerPhase() {
        assertEquals(SettingsRules.ResetDisplay.NONE,
                SettingsRules.resetDisplay(ResetReport.Tracker.Phase.IDLE));
        assertEquals(SettingsRules.ResetDisplay.PROGRESS,
                SettingsRules.resetDisplay(ResetReport.Tracker.Phase.RUNNING));
        assertEquals(SettingsRules.ResetDisplay.REPORT,
                SettingsRules.resetDisplay(ResetReport.Tracker.Phase.REPORTED));
        assertEquals(SettingsRules.ResetDisplay.PROGRESS,
                SettingsRules.resetDisplay(ResetReport.Tracker.Phase.FINISHING));
    }

    @Test
    public void longDozeDelayStartsAtFiveMinutes() {
        assertEquals(300, SettingsRules.LONG_DOZE_DELAY_SECONDS);
        assertFalse("No delay", SettingsRules.warnsLongDozeDelay(0));
        assertFalse("Just under five minutes", SettingsRules.warnsLongDozeDelay(299));
        assertTrue("Exactly five minutes", SettingsRules.warnsLongDozeDelay(300));
        assertTrue("Longer", SettingsRules.warnsLongDozeDelay(301));
        assertTrue("Largest picker value", SettingsRules.warnsLongDozeDelay(Integer.MAX_VALUE));
        assertFalse("Negative values never warn", SettingsRules.warnsLongDozeDelay(-1));
    }
}
