package com.akylas.enforcedoze;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.view.View;

import java.lang.reflect.Field;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.fakes.RoboMenuItem;

/**
 * Menu routing of the log screen. The activity is attached but never created, so no logcat read is started;
 * the full-log branch (progress dialog + background read) is left to device QA.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class LogActivityRobolectricTest {

    // Utils' static init (reached through LogActivity.log) reads MyApplication's context, which
    // @Config(application = Application.class) never sets.
    @Before
    public void seedAppContext() throws Exception {
        Field context = MyApplication.class.getDeclaredField("context");
        context.setAccessible(true);
        context.set(null, RuntimeEnvironment.getApplication());
    }

    private LogActivity attached() {
        return Robolectric.buildActivity(LogActivity.class).get();
    }

    @Test
    public void homeGoesBack() {
        LogActivity activity = attached();

        assertTrue(activity.onOptionsItemSelected(new RoboMenuItem(android.R.id.home)));
        assertTrue(activity.isFinishing());
    }

    @Test
    public void sharingAnEmptyLogStartsNoChooser() {
        LogActivity activity = attached();

        assertFalse(activity.onOptionsItemSelected(new RoboMenuItem(R.id.action_share_log)));
        assertNull(shadowOf(activity).getNextStartedActivity());
        assertFalse(activity.isFinishing());
    }

    @Test
    public void unknownItemsFallThroughToTheFramework() {
        LogActivity activity = attached();

        assertFalse(activity.onOptionsItemSelected(new RoboMenuItem(View.NO_ID)));
        assertFalse(activity.isFinishing());
    }
}
