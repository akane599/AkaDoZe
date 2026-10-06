package com.akylas.enforcedoze.ui;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import com.akylas.enforcedoze.TestAppState;
import com.akylas.enforcedoze.access.Prefs;
import com.akylas.enforcedoze.service.ResetRestoreOutcome;
import com.akylas.enforcedoze.service.SystemResetResult;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class ResetReportRobolectricTest {
    private SharedPreferences prefs(String name) {
        return RuntimeEnvironment.getApplication().getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    @Before public void resetBeforeTest() throws Exception {
        TestAppState.reset();
    }

    @After
    public void clearTestPreferences() throws Exception {
        prefs("reset_test").edit().clear().commit();
        prefs("reset_helpers_test").edit().clear().commit();
        TestAppState.reset();
    }

    @Test
    public void incompleteRestoreKeepsTheServiceSensorWhitelistPreference() {
        SystemResetResult incomplete = new SystemResetResult(ResetRestoreOutcome.REMAINING_DEBT,
                Collections.emptyList(), Collections.emptyList(), false);

        assertTrue("Incomplete restore retains the service allow-token key",
                ResetReport.keysToKeep(incomplete).contains("sensorWhitelistPackage"));
    }

    @Test
    public void remainingDebtResetRetainsCustomServiceSensorWhitelistValue() {
        SharedPreferences prefs = prefs("reset_test");
        SharedPreferences helpers = prefs("reset_helpers_test");
        prefs.edit().putString("sensorWhitelistPackage", "com.example.sensor-owner")
                .putString(Prefs.RESTORE_LEDGER, "pending").commit();

        assertTrue("Reset with remaining debt commits",
                ResetReport.clearPreferences(prefs, helpers,
                        new SystemResetResult(ResetRestoreOutcome.REMAINING_DEBT, Collections.emptyList())));
        assertEquals("Reset retains the custom allow token the service reads",
                "com.example.sensor-owner", prefs.getString("sensorWhitelistPackage", null));
    }

    @Test
    public void failedJobOnlyCommitsServiceStoppedAndPreservesRetrySettingsAndHelpers() {
        SharedPreferences prefs = prefs("reset_test");
        SharedPreferences helpers = prefs("reset_helpers_test");
        prefs.edit().putBoolean(Prefs.SERVICE_ENABLED, true)
                .putBoolean(Prefs.SERVICE_USER_ENABLED, true)
                .putString(Prefs.RESTORE_LEDGER, "pending")
                .putString("custom", "keep").commit();
        helpers.edit().putString("helper", "keep").commit();
        Map<String, Object> expected = new LinkedHashMap<>(prefs.getAll());
        expected.put(Prefs.SERVICE_ENABLED, false);
        Map<String, ?> expectedHelpers = helpers.getAll();
        SystemResetResult failed = new SystemResetResult(ResetRestoreOutcome.REMAINING_DEBT,
                Collections.emptyList(), Collections.emptyList(), true);

        assertFalse("Failed reset cannot report preferences cleared", ResetReport.clearPreferences(prefs, helpers, failed));
        assertEquals("Only service enabled changes on failure", expected, prefs.getAll());
        assertEquals("Helper records survive a failed job", expectedHelpers, helpers.getAll());
    }

    @Test
    public void remainingDebtRetainsIntentWithOriginalTypesAndClearsHelpers() {
        SharedPreferences prefs = prefs("reset_test");
        SharedPreferences helpers = prefs("reset_helpers_test");
        prefs.edit().putString(Prefs.EXECUTION_MODE, "root")
                .putFloat(Prefs.RESTORE_LEDGER, 1.25f)
                .putLong(Prefs.RESTRICT_SENSORS_ALLOW_TOKEN, 42L)
                .putBoolean(Prefs.SERVICE_ENABLED, true).commit();
        helpers.edit().putString("helper", "remove").commit();
        Map<String, Object> expected = ResetReport.retained(prefs.getAll(), ResetReport.RESTORE_INTENT_KEYS);

        assertTrue("Both preference commits succeed", ResetReport.clearPreferences(prefs, helpers,
                new SystemResetResult(ResetRestoreOutcome.REMAINING_DEBT, Collections.emptyList())));
        assertEquals("Pending intent retains its original types and values", expected, prefs.getAll());
        assertEquals("Float value survives the reset", 1.25f, prefs.getFloat(Prefs.RESTORE_LEDGER, 0f), 0f);
        assertTrue("Helper records are cleared after success", helpers.getAll().isEmpty());
    }

    @Test
    public void completeRestoreClearsAllPreferencesAndHelpers() {
        SharedPreferences prefs = prefs("reset_test");
        SharedPreferences helpers = prefs("reset_helpers_test");
        prefs.edit().putString(Prefs.RESTORE_LEDGER, "restored").putInt("custom", 7).commit();
        helpers.edit().putString("helper", "remove").commit();

        assertTrue("Both clears commit", ResetReport.clearPreferences(prefs, helpers,
                new SystemResetResult(ResetRestoreOutcome.COMPLETE, Collections.emptyList())));
        assertTrue("No intent remains after a complete restore", prefs.getAll().isEmpty());
        assertTrue("No helpers remain", helpers.getAll().isEmpty());
    }

    @Test
    public void typedReputPreservesSupportedValuesAndSilentlySkipsUnknownTypes() {
        SharedPreferences prefs = prefs("reset_test");
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("string", "value");
        expected.put("boolean", true);
        expected.put("integer", 7);
        expected.put("long", 42L);
        expected.put("float", 1.25f);
        SharedPreferences.Editor editor = prefs.edit();
        for (Map.Entry<String, Object> entry : expected.entrySet()) {
            ResetReport.putTyped(editor, entry.getKey(), entry.getValue());
        }
        ResetReport.putTyped(editor, "set", Collections.singleton("unsupported"));
        ResetReport.putTyped(editor, "double", 1.25d);
        ResetReport.putTyped(editor, "null", null);
        assertTrue(editor.commit());

        assertEquals("Only the five supported types are written, without coercion", expected, prefs.getAll());
    }

    @Test
    public void unsupportedRetainedValueIsStillSilentlyDroppedDuringReset() {
        SharedPreferences prefs = prefs("reset_test");
        SharedPreferences helpers = prefs("reset_helpers_test");
        prefs.edit().putStringSet(Prefs.RESTORE_LEDGER, Collections.singleton("unsupported"))
                .putString(Prefs.EXECUTION_MODE, "root").commit();

        assertTrue(ResetReport.clearPreferences(prefs, helpers,
                new SystemResetResult(ResetRestoreOutcome.REMAINING_DEBT, Collections.emptyList())));
        assertFalse("Unknown retained types are skipped", prefs.contains(Prefs.RESTORE_LEDGER));
        assertEquals("Supported intent is retained", "root", prefs.getString(Prefs.EXECUTION_MODE, null));
    }
}
