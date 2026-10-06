package com.akylas.enforcedoze;

import android.content.Context;
import androidx.preference.PreferenceManager;
import com.akylas.enforcedoze.access.AccessManager;
import com.akylas.enforcedoze.access.Prefs;
import com.akylas.enforcedoze.service.AndroidClock;
import com.akylas.enforcedoze.service.DozeRuntime;
import com.akylas.enforcedoze.service.JournalSink;
import com.akylas.enforcedoze.ui.NoticeSink;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public final class TestAppState {
    public static void selectNonRootMode(Context context) {
        assertTrue("fixture mode must be persisted before runtime construction",
                PreferenceManager.getDefaultSharedPreferences(context).edit()
                        .putString(Prefs.EXECUTION_MODE, Prefs.MODE_SHIZUKU).commit());
    }

    public static DozeRuntime runtimeWithoutRoot(Context context, AndroidClock clock, JournalSink journal) {
        // Check before construction: a broken fixture must fail before AccessManager can start su.
        assertEquals("runtime fixture must select non-root mode before construction", Prefs.MODE_SHIZUKU,
                PreferenceManager.getDefaultSharedPreferences(context)
                        .getString(Prefs.EXECUTION_MODE, Prefs.DEFAULT_EXECUTION_MODE));
        DozeRuntime runtime = new DozeRuntime(context, clock, journal);
        try {
            Field mode = AccessManager.class.getDeclaredField("mode");
            mode.setAccessible(true);
            assertEquals("runtime access must remain non-root", Prefs.MODE_SHIZUKU, mode.get(runtime.getAccess()));
            Field pending = AccessManager.class.getDeclaredField("probePending");
            pending.setAccessible(true);
            assertFalse("runtime fixture must not queue a root probe",
                    ((AtomicBoolean) pending.get(runtime.getAccess())).get());
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        return runtime;
    }
    static void reset() throws Exception {
        clear(AccessManager.class, "instance");
        clear(MyApplication.class, "context");
        clear(MyApplication.class, "dozeRuntime");
        Field journal = MyApplication.class.getDeclaredField("JOURNAL"); journal.setAccessible(true);
        Field sink = MyApplication.LazyJournal.class.getDeclaredField("journal"); sink.setAccessible(true); sink.set(journal.get(null), null);
        clear(NoticeSink.class, "instance");
        Field views = NoticeSink.class.getDeclaredField("debtViews"); views.setAccessible(true); ((AtomicInteger) views.get(null)).set(0);
    }
    static void assertNoRuntimeOrDiscovery() throws Exception {
        for (Class<?> type : new Class<?>[]{AccessManager.class, MyApplication.class}) {
            Field field = type.getDeclaredField(type == AccessManager.class ? "instance" : "dozeRuntime");
            field.setAccessible(true); assertNull("no discovery/runtime", field.get(null));
        }
    }
    private static void clear(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(null, null);
    }
}
