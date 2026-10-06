package com.akylas.enforcedoze;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import androidx.preference.PreferenceManager;
import com.akylas.enforcedoze.access.AccessManager;
import com.akylas.enforcedoze.access.Prefs;
import com.akylas.enforcedoze.service.AndroidClock;
import com.akylas.enforcedoze.service.DozeRuntime;
import com.akylas.enforcedoze.service.JournalSink;
import com.akylas.enforcedoze.ui.NoticeSink;
import com.akylas.enforcedoze.ui.ResetReport;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import rikka.shizuku.Shizuku;
import static org.junit.Assert.*;

/** Shared Robolectric state ownership; remove listeners before discarding their singleton owner. */
public final class TestAppState {
    public static void selectNonRootMode(Context context) {
        assertTrue("fixture mode must be persisted before runtime construction",
                PreferenceManager.getDefaultSharedPreferences(context).edit()
                        .putString(Prefs.EXECUTION_MODE, Prefs.MODE_SHIZUKU).commit());
    }

    private static void assertNonRootMode(Context context) {
        // Check before construction: a broken fixture must fail before AccessManager can start su.
        assertEquals("runtime fixture must select non-root mode before construction", Prefs.MODE_SHIZUKU,
                PreferenceManager.getDefaultSharedPreferences(context)
                        .getString(Prefs.EXECUTION_MODE, Prefs.DEFAULT_EXECUTION_MODE));
    }

    public static AccessManager accessWithoutRoot(Context context) {
        assertNonRootMode(context);
        AccessManager access = AccessManager.getInstance(context);
        assertNoRootProbe(access);
        return access;
    }

    public static DozeRuntime runtimeWithoutRoot(Context context, AndroidClock clock, JournalSink journal) {
        assertNonRootMode(context);
        DozeRuntime runtime = new DozeRuntime(context, clock, journal);
        assertNoRootProbe(runtime.getAccess());
        return runtime;
    }

    private static void assertNoRootProbe(AccessManager access) {
        try {
            assertEquals("runtime access must remain non-root", Prefs.MODE_SHIZUKU,
                    get(AccessManager.class, access, "mode"));
            assertFalse("runtime fixture must not queue a root probe",
                    ((AtomicBoolean) get(AccessManager.class, access, "probePending")).get());
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    public static void setAppContext(Context context) throws Exception {
        set(MyApplication.class, null, "context", context);
    }

    public static void reset() throws Exception {
        removeAccessListeners();
        set(AccessManager.class, null, "instance", null);
        setAppContext(null);
        set(MyApplication.class, null, "dozeRuntime", null);
        Object journal = get(MyApplication.class, null, "JOURNAL");
        set(MyApplication.LazyJournal.class, journal, "journal", null);
        set(NoticeSink.class, null, "instance", null);
        ((AtomicInteger) get(NoticeSink.class, null, "debtViews")).set(0);
        set(ResetReport.Tracker.class, ResetReport.TRACKER, "phase", ResetReport.Tracker.Phase.IDLE);
        set(ResetReport.Tracker.class, ResetReport.TRACKER, "result", null);
        set(ResetReport.Tracker.class, ResetReport.TRACKER, "prefsCleared", false);
        set(ResetReport.Tracker.class, ResetReport.TRACKER, "listener", null);
    }

    private static void removeAccessListeners() throws Exception {
        Object access = get(AccessManager.class, null, "instance");
        if (access == null) return;
        Shizuku.removeRequestPermissionResultListener((Shizuku.OnRequestPermissionResultListener)
                get(AccessManager.class, access, "permissionListener"));
        Shizuku.removeBinderReceivedListener((Shizuku.OnBinderReceivedListener)
                get(AccessManager.class, access, "binderReceived"));
        Shizuku.removeBinderDeadListener((Shizuku.OnBinderDeadListener)
                get(AccessManager.class, access, "binderDead"));
        ((SharedPreferences) get(AccessManager.class, access, "prefs"))
                .unregisterOnSharedPreferenceChangeListener((SharedPreferences.OnSharedPreferenceChangeListener)
                        get(AccessManager.class, access, "prefListener"));
        // Cancel discovery callbacks that would otherwise publish after their test has finished.
        ((Handler) get(AccessManager.class, access, "main")).removeCallbacksAndMessages(null);
    }

    public static void assertNoRuntimeOrDiscovery() throws Exception {
        assertNull("no discovery/runtime", get(AccessManager.class, null, "instance"));
        assertNull("no discovery/runtime", get(MyApplication.class, null, "dozeRuntime"));
    }

    private static Object get(Class<?> type, Object target, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Class<?> type, Object target, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
