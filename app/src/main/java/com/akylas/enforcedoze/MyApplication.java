package com.akylas.enforcedoze;

import android.content.Context;

import com.akylas.enforcedoze.service.AndroidClock;
import com.akylas.enforcedoze.service.DozeRuntime;
import com.akylas.enforcedoze.service.JournalSink;
import com.akylas.enforcedoze.ui.NoticeSink;

public class MyApplication extends android.app.Application {
    private static Context context;
    private static DozeRuntime dozeRuntime;
    private static final AndroidClock CLOCK = new AndroidClock();
    private static final LazyJournal<JournalSink> JOURNAL = new LazyJournal<>();

    // Own SAM types: java.util.function is API 24+, minSdk is 23.
    public interface Factory<T> { T get(); }
    public interface Callback<T> { void accept(T value); }

    /** Pure ownership seam: construction and notice registration are attempted once under the same lock. */
    public static final class LazyJournal<T> {
        private T journal;

        public synchronized T get(Factory<T> factory, Callback<T> registerNotice) {
            if (journal == null) {
                // Keep the process-owned sink even if notice registration throws.
                journal = factory.get();
                registerNotice.accept(journal);
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
