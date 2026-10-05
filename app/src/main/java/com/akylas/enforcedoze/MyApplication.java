package com.akylas.enforcedoze;

import android.content.Context;
import android.os.Build;
import android.os.UserManager;

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
        // Default preferences are credential-protected; don't read them during locked direct boot.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                && !((UserManager) getSystemService(Context.USER_SERVICE)).isUserUnlocked()) {
            return;
        }
        // Exact-alarm revocation kills the process without a revoke broadcast. Re-arm only the
        // next boundary on restart; this shared seam never constructs the runtime or starts service.
        Utils.requeryExactAlarmAccess(MyApplication.context);
    }

    public static Context getAppContext() {
        return MyApplication.context;
    }
}
