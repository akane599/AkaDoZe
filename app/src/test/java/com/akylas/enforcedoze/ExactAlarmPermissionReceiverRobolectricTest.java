package com.akylas.enforcedoze;

import android.app.AlarmManager;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import com.akylas.enforcedoze.access.Prefs;
import java.util.Collections;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlarmManager;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class ExactAlarmPermissionReceiverRobolectricTest {
    private Application app;
    private SharedPreferences prefs;
    private ShadowAlarmManager alarms;
    private Context noServiceContext;

    @Before public void setup() throws Exception {
        TestAppState.reset();
        app = RuntimeEnvironment.getApplication();
        TestAppState.setAppContext(app);
        prefs = PreferenceManager.getDefaultSharedPreferences(app);
        prefs.edit().clear().putBoolean(Prefs.SERVICE_USER_ENABLED, true)
                .putBoolean("serviceEnabled", false)
                .putStringSet("customDozePeriods", Collections.singleton("00:00-23:59")).commit();
        alarms = shadowOf(app.getSystemService(AlarmManager.class));
        noServiceContext = new ContextWrapper(app) {
            @Override public ComponentName startService(Intent intent) {
                throw new AssertionError("permission broadcast must never start the service");
            }
            @Override public ComponentName startForegroundService(Intent intent) {
                throw new AssertionError("permission broadcast must never start the foreground service");
            }
            @Override public boolean stopService(Intent intent) {
                throw new AssertionError("permission broadcast must not apply the current window");
            }
        };
    }

    @After public void cleanup() throws Exception {
        TestAppState.reset();
        prefs.edit().clear().commit();
    }

    @Test public void grantRearmsThenRevocationRequeriesActualAccessWithoutStartingService() throws Exception {
        Map<String, ?> before = prefs.getAll();
        alarms.setCanScheduleExactAlarms(true);
        receiveGrant(false); // Misleading extras must not override actual granted capability.
        ShadowAlarmManager.ScheduledAlarm exact = onlyBoundary();
        assertEquals("granted capability re-arms an exact boundary", 0, exact.getWindowLengthMs());

        alarms.setCanScheduleExactAlarms(false);
        receiveGrant(true); // A stale grant broadcast can race a fresh revocation.
        ShadowAlarmManager.ScheduledAlarm inexact = onlyBoundary();
        assertNotSame("requery must replace the old boundary", exact, inexact);
        assertNotEquals("revoked capability must re-arm with the inexact fallback", 0, inexact.getWindowLengthMs());
        assertEquals("requery must not apply the current window or mutate preferences", before, prefs.getAll());
        assertNull("no service start is hidden through applicationContext", shadowOf(app).getNextStartedService());
        TestAppState.assertNoRuntimeOrDiscovery();
    }

    @Test public void nullAndUnrelatedActionsDoNotRearmOrStartService() throws Exception {
        ExactAlarmPermissionReceiver receiver = new ExactAlarmPermissionReceiver();
        receiver.onReceive(noServiceContext, null);
        receiver.onReceive(noServiceContext, new Intent());
        receiver.onReceive(noServiceContext, new Intent("unrelated").putExtra("granted", true));
        assertTrue("unrelated broadcasts must not arm a boundary", alarms.getScheduledAlarms().isEmpty());
        assertNull(shadowOf(app).getNextStartedService());
        TestAppState.assertNoRuntimeOrDiscovery();
    }

    @Test public void userDisabledAndMissingPeriodsDoNotArmOrStartService() throws Exception {
        prefs.edit().putBoolean(Prefs.SERVICE_USER_ENABLED, false).commit();
        receiveGrant(true);
        assertTrue("disabled user intent must not arm", alarms.getScheduledAlarms().isEmpty());
        prefs.edit().putBoolean(Prefs.SERVICE_USER_ENABLED, true).remove("customDozePeriods").commit();
        receiveGrant(true);
        assertTrue("missing periods must not arm", alarms.getScheduledAlarms().isEmpty());
        assertNull(shadowOf(app).getNextStartedService());
        TestAppState.assertNoRuntimeOrDiscovery();
    }

    private void receiveGrant(boolean claimedGrant) {
        new ExactAlarmPermissionReceiver().onReceive(noServiceContext,
                new Intent(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)
                        .putExtra("granted", claimedGrant));
    }

    private ShadowAlarmManager.ScheduledAlarm onlyBoundary() {
        assertEquals("exactly one next boundary is armed", 1, alarms.getScheduledAlarms().size());
        ShadowAlarmManager.ScheduledAlarm alarm = alarms.getScheduledAlarms().get(0);
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.getType());
        assertTrue("the receiver arms a future boundary, not the current window",
                alarm.getTriggerAtMs() > System.currentTimeMillis());
        Intent boundary = shadowOf(alarm.operation).getSavedIntent();
        assertEquals(CustomDozePeriodReceiver.class.getName(), boundary.getComponent().getClassName());
        assertEquals(Utils.ACTION_CUSTOM_DOZE_PERIOD_BOUNDARY, boundary.getAction());
        return alarm;
    }
}
