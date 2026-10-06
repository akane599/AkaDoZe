package com.akylas.enforcedoze;

import android.app.Application;
import android.content.Context;
import android.os.UserManager;
import org.junit.After;
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
}
