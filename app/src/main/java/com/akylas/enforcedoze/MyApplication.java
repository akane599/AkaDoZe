package com.akylas.enforcedoze;

import android.content.Context;

import com.akylas.enforcedoze.service.AndroidClock;
import com.akylas.enforcedoze.service.DozeRuntime;
import com.akylas.enforcedoze.service.JournalSink;
import com.akylas.enforcedoze.ui.NoticeSink;

import java.util.function.Consumer;
import java.util.function.Supplier;

public class MyApplication extends android.app.Application {
    private static Context context;
    private static DozeRuntime dozeRuntime;
    private static final AndroidClock CLOCK = new AndroidClock();
    private static final LazyJournal<JournalSink> JOURNAL = new LazyJournal<>();

    /** Pure ownership seam: construction and notice registration happen once under the same lock. */
    public static final class LazyJournal<T> {
        private T journal;

        public synchronized T get(Supplier<T> factory, Consumer<T> registerNotice) {
            if (journal == null) {
                T created = factory.get();
                registerNotice.accept(created);
                journal = created;
            }
            return journal;
        }
    }

    /** Journal-only cold starts must not discover access or construct the runtime. */
    public static synchronized JournalSink getJournal(Context context) {
        Context app = context.getApplicationContext();
        return JOURNAL.get(() -> new JournalSink(app, CLOCK),
                journal -> journal.addSink(NoticeSink.get(app)));
    }

    /**
     * Built on first use only: constructing it starts AccessManager, which probes su in root mode, so a
     * cold start that never needs it (listener rebind, tile bind, alarm, package replaced) must not.
     */
    public static synchronized DozeRuntime getDozeRuntime(Context context) {
        if (dozeRuntime == null) {
            Context app = context.getApplicationContext();
            dozeRuntime = new DozeRuntime(app, CLOCK, getJournal(app));
        }
        return dozeRuntime;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        MyApplication.context = getApplicationContext();
    }

    public static Context getAppContext() {
        return MyApplication.context;
    }
}
