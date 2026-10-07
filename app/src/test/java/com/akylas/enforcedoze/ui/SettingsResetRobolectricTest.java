package com.akylas.enforcedoze.ui;

import android.app.Application;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Looper;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;

import com.akylas.enforcedoze.R;
import com.akylas.enforcedoze.SettingsActivity;
import com.akylas.enforcedoze.TestAppState;
import com.akylas.enforcedoze.service.ResetRestoreOutcome;
import com.akylas.enforcedoze.service.SystemResetResult;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class SettingsResetRobolectricTest {
    private Application app;
    private ActivityController<SettingsActivity> controller;

    @Before
    public void hostSettings() throws Exception {
        app = RuntimeEnvironment.getApplication();
        TestAppState.reset();
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear().commit();
        TestAppState.selectNonRootMode(app);
        TestAppState.setAppContext(app);
        controller = Robolectric.buildActivity(SettingsActivity.class).setup();
        idle();
    }

    @After
    public void tearDown() throws Exception {
        controller.pause().stop().destroy();
        idle();
        TestAppState.reset();
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear().commit();
    }

    @Test
    public void runningReportAndFinishingReplaceOnlyTheOppositeDialog() {
        assertTrue(ResetReport.TRACKER.begin());
        render();
        Dialog progress = ShadowDialog.getLatestDialog();
        assertTrue(progress.isShowing());
        assertEquals(app.getString(R.string.reset_running_text), message(progress));
        render();
        assertSame("Running keeps the existing wait", progress, ShadowDialog.getLatestDialog());

        SystemResetResult result = new SystemResetResult(ResetRestoreOutcome.COMPLETE,
                Collections.emptyList(), Collections.emptyList());
        ResetReport.TRACKER.deliver(result, true);
        render();
        Dialog report = ShadowDialog.getLatestDialog();
        assertFalse("Report dismisses the wait", progress.isShowing());
        assertTrue(report.isShowing());
        assertEquals(ResetReport.message(app, result, true), message(report));
        render();
        assertSame("Reported keeps the existing report", report, ShadowDialog.getLatestDialog());

        assertEquals(Collections.emptyList(), ResetReport.TRACKER.confirm());
        render();
        Dialog finishing = ShadowDialog.getLatestDialog();
        assertFalse("Finishing dismisses the report", report.isShowing());
        assertTrue(finishing.isShowing());
        assertEquals(app.getString(R.string.reset_running_text), message(finishing));
        render();
        assertSame("Finishing keeps the existing wait", finishing, ShadowDialog.getLatestDialog());
    }

    @Test
    public void failedReportConfirmationReturnsToIdleAndDismissesTheReport() {
        assertTrue(ResetReport.TRACKER.begin());
        render();
        SystemResetResult result = new SystemResetResult(ResetRestoreOutcome.COMPLETE,
                Collections.emptyList(), Collections.emptyList(), true);
        ResetReport.TRACKER.deliver(result, false);
        render();
        AlertDialog report = (AlertDialog) ShadowDialog.getLatestDialog();
        assertEquals(ResetReport.message(app, result, false), message(report));
        report.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        idle();
        assertSame(ResetReport.Tracker.Phase.IDLE, ResetReport.TRACKER.phase());
        assertFalse("Idle dismisses the report", report.isShowing());
        assertNull("No reset worker service is started", shadowOf(app).getNextStartedService());
    }

    private void render() {
        ResetReport.TRACKER.notifyListener();
        idle();
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static String message(Dialog dialog) {
        TextView message = dialog.findViewById(android.R.id.message);
        if (message == null) message = dialog.findViewById(com.afollestad.materialdialogs.R.id.md_content);
        return message.getText().toString();
    }
}
