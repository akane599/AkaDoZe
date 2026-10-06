package com.akylas.enforcedoze;

import com.akylas.enforcedoze.access.AccessManager;
import com.akylas.enforcedoze.ui.NoticeSink;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.assertNull;

final class TestAppState {
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
