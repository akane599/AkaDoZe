package com.akylas.enforcedoze;

import android.app.Application;
import com.akylas.enforcedoze.access.AccessManager;
import com.akylas.enforcedoze.ui.NoticeSink;
import com.akylas.enforcedoze.ui.ResetReport;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import rikka.shizuku.Shizuku;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class TestAppStateRobolectricTest {
    @Before public void before() throws Exception { TestAppState.reset(); }
    @After public void after() throws Exception { TestAppState.reset(); }

    @Test public void resetRemovesOwnedShizukuListenersAcrossRepeatedManagers() throws Exception {
        Shizuku.OnBinderDeadListener foreign = () -> { };
        Shizuku.addBinderDeadListener(foreign);
        int received = count("RECEIVED_LISTENERS");
        int dead = count("DEAD_LISTENERS");
        int permission = count("PERMISSION_LISTENERS");
        try {
            for (int i = 0; i < 2; i++) {
                TestAppState.selectNonRootMode(RuntimeEnvironment.getApplication());
                AccessManager manager = TestAppState.accessWithoutRoot(RuntimeEnvironment.getApplication());
                assertEquals(received + 1, count("RECEIVED_LISTENERS"));
                assertEquals(dead + 1, count("DEAD_LISTENERS"));
                assertEquals(permission + 1, count("PERMISSION_LISTENERS"));
                TestAppState.reset();
                assertEquals("reset removes the previous manager's received listener", received, count("RECEIVED_LISTENERS"));
                assertEquals("reset removes only the owned death listener", dead, count("DEAD_LISTENERS"));
                assertEquals("reset removes the previous manager's permission listener", permission, count("PERMISSION_LISTENERS"));
                assertFalse("owned listener is absent, not just balanced by another removal",
                        Shizuku.removeBinderReceivedListener((Shizuku.OnBinderReceivedListener) get(AccessManager.class, manager, "binderReceived")));
                assertFalse(Shizuku.removeBinderDeadListener((Shizuku.OnBinderDeadListener) get(AccessManager.class, manager, "binderDead")));
                assertFalse(Shizuku.removeRequestPermissionResultListener((Shizuku.OnRequestPermissionResultListener) get(AccessManager.class, manager, "permissionListener")));
                TestAppState.assertNoRuntimeOrDiscovery();
            }
        } finally {
            assertTrue("reset must preserve unrelated Shizuku listeners", Shizuku.removeBinderDeadListener(foreign));
        }
    }

    @Test public void resetClearsApplicationJournalNoticeAndResetTrackerTogether() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        TestAppState.setAppContext(app);
        MyApplication.getJournal(app);
        AtomicInteger views = (AtomicInteger) get(NoticeSink.class, null, "debtViews");
        views.set(3);
        assertTrue(ResetReport.TRACKER.begin());
        ResetReport.TRACKER.setListener(() -> { });

        TestAppState.reset();
        TestAppState.reset(); // Shared teardown is also safe if a fixture already reset itself.

        TestAppState.assertNoRuntimeOrDiscovery();
        assertNull(MyApplication.getAppContext());
        assertNull(get(MyApplication.LazyJournal.class, get(MyApplication.class, null, "JOURNAL"), "journal"));
        assertNull(get(NoticeSink.class, null, "instance"));
        assertEquals(0, views.get());
        assertEquals(ResetReport.Tracker.Phase.IDLE, ResetReport.TRACKER.phase());
        assertNull(ResetReport.TRACKER.result());
        assertFalse(ResetReport.TRACKER.prefsCleared());
        assertNull(get(ResetReport.Tracker.class, ResetReport.TRACKER, "listener"));
    }

    private static int count(String name) throws Exception {
        return ((Collection<?>) get(Shizuku.class, null, name)).size();
    }

    private static Object get(Class<?> type, Object target, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
