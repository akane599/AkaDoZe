package com.akylas.enforcedoze;

import android.app.Application;
import android.app.ActivityManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.ComponentName;
import android.content.Intent;
import android.os.UserManager;
import com.akylas.enforcedoze.monitor.EventCodes;
import com.akylas.enforcedoze.service.JournalSink;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class MyApplicationRobolectricTest {
    private static class RecordingApplication extends MyApplication {
        int requeries;
        void attach(Context context) { attachBaseContext(context); }
        @Override void requeryExactAlarmAccess() { requeries++; }
    }
    @Before public void resetBeforeTest() throws Exception {
        TestAppState.reset();
    }

    @After public void resetState() throws Exception {
        TestAppState.reset();
        shadowOf(RuntimeEnvironment.getApplication().getSystemService(UserManager.class)).setUserUnlocked(true);
    }
    private RecordingApplication app(boolean unlocked) {
        Context context = RuntimeEnvironment.getApplication();
        shadowOf(context.getSystemService(UserManager.class)).setUserUnlocked(unlocked);
        RecordingApplication app = new RecordingApplication(); app.attach(context); return app;
    }
    @Test public void lockedDirectBootSkipsRequeryButRegistersContextWithoutRuntime() throws Exception {
        RecordingApplication app = app(false); app.onCreate();
        assertEquals("locked direct boot must return before requery", 0, app.requeries);
        assertSame(RuntimeEnvironment.getApplication(), MyApplication.getAppContext());
        TestAppState.assertNoRuntimeOrDiscovery();
    }
    @Test public void unlockedBootRequeriesOnceWithoutRuntime() throws Exception {
        RecordingApplication app = app(true); app.onCreate();
        assertEquals(1, app.requeries); TestAppState.assertNoRuntimeOrDiscovery();
    }
    @Test public void preNBootRequeriesEvenIfUserIsMarkedLocked() throws Exception {
        RecordingApplication app = app(false);
        // Exercise the API guard without fetching a second Android SDK from the network.
        int sdk = android.os.Build.VERSION.SDK_INT;
        org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build.VERSION.class, "SDK_INT", 23);
        try {
            app.onCreate();
            assertEquals("pre-N does not consult direct boot", 1, app.requeries); TestAppState.assertNoRuntimeOrDiscovery();
        } finally {
            org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build.VERSION.class, "SDK_INT", sdk);
        }
    }

    @Test public void deniedServiceStartJournalsWithoutBuildingRuntime() throws Exception {
        Context appContext = RuntimeEnvironment.getApplication();
        app(true).onCreate();
        ActivityManager manager = (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.RunningServiceInfo running = new ActivityManager.RunningServiceInfo();
        running.service = new ComponentName(appContext, ForceDozeService.class);
        shadowOf(manager).setServices(java.util.Collections.singletonList(running));
        assertTrue(Utils.startForceDozeService(appContext));
        shadowOf(manager).setServices(java.util.Collections.emptyList());

        Context deniedContext = new ContextWrapper(appContext) {
            @Override public ComponentName startForegroundService(Intent intent) {
                throw new IllegalStateException("background start denied");
            }
            @Override public ComponentName startService(Intent intent) {
                throw new IllegalStateException("background start denied");
            }
        };

        assertFalse(Utils.startForceDozeService(deniedContext));
        JournalSink journal = MyApplication.getJournal(appContext);
        List<com.akylas.enforcedoze.monitor.JournalEvent> rows = journal.queryRecent(10).get(5, TimeUnit.SECONDS);
        assertTrue("denied start must be journaled with the stable event code",
                rows.stream().anyMatch(row -> EventCodes.FOREGROUND_START_DENIED.equals(row.getDetail())));
        TestAppState.assertNoRuntimeOrDiscovery();
    }
}
